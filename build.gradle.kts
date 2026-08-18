plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.multiplatform.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlinx.serialization) apply false
    alias(libs.plugins.sqldelight) apply false
}

allprojects {
    group = "io.worldloom"
    version = "0.1.0-alpha.1"
}

tasks.named("check") {
    dependsOn(
        ":shared:definition-runtime:check",
        ":shared:domain-world:check",
        ":shared:domain-rules:check",
        ":shared:rule-module-api:check",
        ":shared:rule-module-registry:check",
        ":shared:persistence:check",
        ":shared:provider-api:check",
        ":shared:provider-openai:check",
        ":shared:agent-runtime:check",
        ":platform:secure-vault:check",
        ":shared:application:check",
        ":shared:ui-game:check",
        ":apps:androidApp:check",
        ":apps:desktopApp:check",
    )
}

val alphaRepositoryPath = layout.projectDirectory.asFile.absolutePath
val alphaArtifactRootPaths = listOf(
    layout.projectDirectory.dir("apps/androidApp/build/outputs").asFile.absolutePath,
    layout.projectDirectory.dir("apps/desktopApp/build/compose/binaries").asFile.absolutePath,
    layout.projectDirectory.dir("apps/desktopApp/build/compose/jars").asFile.absolutePath,
)
val alphaHashFilePath = layout.buildDirectory.file("alpha/artifact-hashes.sha256").get().asFile.absolutePath

tasks.register("alphaRelease") {
    group = "distribution"
    description = "Build closed-Alpha Android and current-OS Desktop artifacts and write SHA-256 hashes."
    notCompatibleWithConfigurationCache("Artifact hashing executes after platform packaging")
    dependsOn(
        ":apps:androidApp:assembleRelease",
        ":apps:desktopApp:packageReleaseUberJarForCurrentOS",
    )
    doLast {
        val repositoryRoot = java.io.File(alphaRepositoryPath)
        val artifacts = alphaArtifactRootPaths.map { java.io.File(it) }
            .flatMap { root ->
                if (!root.exists()) emptyList() else root.walkTopDown()
                    .filter { file ->
                        if (!file.isFile) return@filter false
                        val path = file.invariantSeparatorsPath
                        when (file.extension.lowercase()) {
                            "apk" -> "/apk/release/" in path
                            "jar" -> file.name.endsWith("-release.jar")
                            "msi", "deb", "dmg" -> "/main-release/" in path
                            else -> false
                        }
                    }
                    .toList()
            }
            .sortedBy { it.relativeTo(repositoryRoot).invariantSeparatorsPath }
        check(artifacts.any { "/androidApp/" in it.invariantSeparatorsPath }) {
            "Android Alpha artifact was not produced"
        }
        check(artifacts.any { "/desktopApp/" in it.invariantSeparatorsPath }) {
            "Desktop Alpha artifact was not produced"
        }
        val hashFile = java.io.File(alphaHashFilePath)
        hashFile.parentFile.mkdirs()
        val lines = artifacts.map { artifact ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            artifact.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            val relativePath = artifact.relativeTo(repositoryRoot).invariantSeparatorsPath
            "$hash  $relativePath"
        }
        hashFile.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
        logger.lifecycle("Alpha artifact hashes: ${hashFile.absolutePath}")
    }
}

tasks.register("alphaGate") {
    group = "verification"
    description = "Run the closed-Alpha repository gates and produce release artifacts."
    dependsOn(
        "check",
        ":shared:persistence:verifyCommonMainWorldloomDatabaseMigration",
        ":shared:ui-game:compileKotlinIosSimulatorArm64",
        "alphaRelease",
    )
}
