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
    version = "0.0.3"
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
        ":shared:world-package:check",
        ":shared:behavior-runtime:check",
        ":shared:content-schema:check",
        ":shared:content-generation:check",
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

tasks.register("alphaAudit") {
    group = "verification"
    description = "Audit committed secrets and topic-specific Runtime branches."
    notCompatibleWithConfigurationCache("The audit invokes git against the working tree")
    doLast {
        fun requireNoGitGrepMatches(label: String, pattern: String, pathspecs: List<String>) {
            val command = listOf("git", "grep", "-n", "-I", "-E", pattern, "--") + pathspecs
            val process = ProcessBuilder(command)
                .directory(layout.projectDirectory.asFile)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            check(exitCode == 1) {
                if (exitCode == 0) "$label found:\n$output" else "$label could not complete (exit $exitCode):\n$output"
            }
        }

        requireNoGitGrepMatches(
            "Potential committed secret material",
            "sk-[A-Za-z0-9_-]{20,}|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY",
            listOf(".", ":(exclude)**/build/**"),
        )
        val topicPattern = Regex("contract\\.(war-survival|station-ai)|DefinitionId\\(\"(war|station)\\.")
        val topicMatches = layout.projectDirectory.dir("shared").asFile.walkTopDown()
            .filter { file ->
                file.isFile && file.extension == "kt" &&
                    "/src/commonMain/" in file.invariantSeparatorsPath
            }
            .flatMap { file ->
                file.readLines().asSequence().mapIndexedNotNull { index, line ->
                    if (topicPattern.containsMatchIn(line)) {
                        "${file.relativeTo(layout.projectDirectory.asFile).invariantSeparatorsPath}:${index + 1}:$line"
                    } else {
                        null
                    }
                }
            }
            .toList()
        check(topicMatches.isEmpty()) {
            "Topic-specific branch material in shared production Runtime found:\n${topicMatches.joinToString("\n")}"
        }
        logger.lifecycle("Alpha audit passed: secrets and topic boundaries.")
    }
}

tasks.register("desktopReleaseSmoke") {
    group = "verification"
    description = "Launch the Desktop release JAR and require it to remain alive for five seconds."
    notCompatibleWithConfigurationCache("The smoke gate launches a release process")
    dependsOn(":apps:desktopApp:packageReleaseUberJarForCurrentOS")
    doLast {
        val jarRoot = layout.projectDirectory.dir("apps/desktopApp/build/compose/jars").asFile
        val releaseJars = jarRoot.walkTopDown()
            .filter {
                it.isFile && it.name.endsWith("-${project.version}-release.jar")
            }
            .toList()
        check(releaseJars.size == 1) { "Expected one Desktop release JAR, found ${releaseJars.size}" }
        val smokeLog = layout.buildDirectory.file("alpha/desktop-smoke.log").get().asFile
        smokeLog.parentFile.mkdirs()
        val javaExecutable = java.io.File(
            System.getProperty("java.home"),
            if (System.getProperty("os.name").startsWith("Windows")) "bin/java.exe" else "bin/java",
        )
        val process = ProcessBuilder(javaExecutable.absolutePath, "-jar", releaseJars.single().absolutePath)
            .directory(layout.projectDirectory.asFile)
            .redirectErrorStream(true)
            .redirectOutput(smokeLog)
            .start()
        val exitedEarly = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        if (!exitedEarly) {
            process.destroy()
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
        }
        check(!exitedEarly) {
            "Desktop release exited during smoke window with code ${process.exitValue()}:\n${smokeLog.readText()}"
        }
        logger.lifecycle("Desktop release remained alive for the five-second smoke window.")
    }
}

tasks.register("round35CandidateGate") {
    group = "verification"
    description = "Run the round 35 playable-draft candidate gates and release smoke."
    dependsOn(
        "alphaGate",
        "alphaAudit",
        "desktopReleaseSmoke",
        ":shared:content-generation:compileKotlinIosSimulatorArm64",
    )
}
