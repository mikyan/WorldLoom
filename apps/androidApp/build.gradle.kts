import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.Sync
import java.util.zip.ZipFile

val uiGameResourcePackage = "io.worldloom.ui.game.generated.resources"

val uiGameComposeResources = project(":shared:ui-game")
    .layout.projectDirectory.dir("src/commonMain/composeResources")
val generatedUiGameComposeAssets = layout.buildDirectory.dir("generated/uiGameComposeAssets")
val prepareUiGameComposeAssets = tasks.register<Sync>("prepareUiGameComposeAssets") {
    from(uiGameComposeResources)
    into(generatedUiGameComposeAssets.map { directory ->
        directory.dir("composeResources/$uiGameResourcePackage")
    })
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

dependencies {
    implementation(projects.shared.uiGame)
    implementation(projects.shared.persistence)
    implementation(projects.shared.agentRuntime)
    implementation(projects.shared.providerOpenai)
    implementation(projects.platform.secureVault)
    implementation(libs.ktor.client.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
}

android {
    namespace = "io.worldloom.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.worldloom.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].assets.directories.add(
        rootProject.layout.projectDirectory.dir("contract-worlds").asFile.absolutePath,
    )
    // Compose's Android KMP library target currently generates resource accessors but
    // does not publish the common resource payload into the consuming APK. Mirror the
    // files at the exact asset paths encoded in those generated accessors.
    sourceSets["main"].assets.directories.add(
        generatedUiGameComposeAssets.get().asFile.absolutePath,
    )
}

tasks.named("preBuild").configure {
    dependsOn(prepareUiGameComposeAssets)
}

tasks.register("verifyDebugUiAssets") {
    group = "verification"
    description = "Build the Android debug APK and verify that gameplay Compose resources are packaged."
    dependsOn("assembleDebug")
    doLast {
        val apkDirectory = layout.buildDirectory.dir("outputs/apk/debug").get().asFile
        val apks = apkDirectory.listFiles { file -> file.extension == "apk" }.orEmpty().toList()
        check(apks.size == 1) { "Expected one Android debug APK, found ${apks.size}" }
        val requiredAssets = listOf(
            "gameplay_station_core.png",
            "gameplay_war_ruins.png",
            "npc_station_lyra.png",
            "npc_station_soren.png",
            "npc_war_mara.png",
            "npc_war_tomas.png",
        ).map { fileName ->
            "assets/composeResources/$uiGameResourcePackage/drawable/$fileName"
        }
        ZipFile(apks.single()).use { apk ->
            val missing = requiredAssets.filter { path -> apk.getEntry(path) == null }
            check(missing.isEmpty()) {
                "Android APK is missing gameplay Compose resources: ${missing.joinToString()}"
            }
        }
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
        jvmTarget = JvmTarget.JVM_17
    }
}
