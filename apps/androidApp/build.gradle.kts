import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipFile

abstract class VerifyApkAssets : DefaultTask() {
    @get:InputDirectory
    abstract val apkDirectory: DirectoryProperty

    @get:Input
    abstract val requiredAssets: ListProperty<String>

    @TaskAction
    fun verify() {
        val apks = apkDirectory.get().asFile
            .listFiles { file -> file.extension == "apk" }
            .orEmpty()
            .toList()
        check(apks.size == 1) { "Expected one Android APK, found ${apks.size}" }
        ZipFile(apks.single()).use { apk ->
            val missing = requiredAssets.get().filter { path -> apk.getEntry(path) == null }
            check(missing.isEmpty()) {
                "Android APK is missing gameplay Compose resources: ${missing.joinToString()}"
            }
        }
    }
}

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

val androidReleaseSigningVariables = listOf(
    "WORLDLOOM_ANDROID_KEYSTORE_FILE",
    "WORLDLOOM_ANDROID_KEYSTORE_PASSWORD",
    "WORLDLOOM_ANDROID_KEY_ALIAS",
    "WORLDLOOM_ANDROID_KEY_PASSWORD",
)
val androidReleaseSigningValues = androidReleaseSigningVariables.associateWith { name ->
    providers.environmentVariable(name).orNull
}
val configuredAndroidReleaseSigningValues = androidReleaseSigningValues.values.count { !it.isNullOrBlank() }
check(configuredAndroidReleaseSigningValues == 0 || configuredAndroidReleaseSigningValues == androidReleaseSigningVariables.size) {
    "Android release signing is only partially configured. Provide all of: " +
        androidReleaseSigningVariables.joinToString()
}
val androidReleaseSigningConfigured = configuredAndroidReleaseSigningValues == androidReleaseSigningVariables.size
val androidReleaseSigningRequired = providers.environmentVariable("WORLDLOOM_REQUIRE_ANDROID_RELEASE_SIGNING")
    .orNull
    .toBoolean()
check(!androidReleaseSigningRequired || androidReleaseSigningConfigured) {
    "A signed Android release was requested, but the release signing environment is missing"
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
        versionCode = 3
        versionName = "0.0.3"
    }

    signingConfigs {
        if (androidReleaseSigningConfigured) {
            create("release") {
                storeFile = file(checkNotNull(androidReleaseSigningValues["WORLDLOOM_ANDROID_KEYSTORE_FILE"]))
                storePassword = androidReleaseSigningValues["WORLDLOOM_ANDROID_KEYSTORE_PASSWORD"]
                keyAlias = androidReleaseSigningValues["WORLDLOOM_ANDROID_KEY_ALIAS"]
                keyPassword = androidReleaseSigningValues["WORLDLOOM_ANDROID_KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (androidReleaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
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

tasks.register<VerifyApkAssets>("verifyDebugUiAssets") {
    group = "verification"
    description = "Build the Android debug APK and verify that gameplay Compose resources are packaged."
    dependsOn("assembleDebug")
    apkDirectory.set(layout.buildDirectory.dir("outputs/apk/debug"))
    requiredAssets.set(
        listOf(
            "home_dreamweaver_cave.png",
            "gameplay_station_core.png",
            "gameplay_war_ruins.png",
            "npc_station_lyra.png",
            "npc_station_soren.png",
            "npc_war_mara.png",
            "npc_war_tomas.png",
        ).map { fileName ->
            "assets/composeResources/$uiGameResourcePackage/drawable/$fileName"
        },
    )
}

tasks.register<VerifyApkAssets>("verifyReleaseUiAssets") {
    group = "verification"
    description = "Build the Android release APK and verify that gameplay Compose resources are packaged."
    dependsOn("assembleRelease")
    apkDirectory.set(layout.buildDirectory.dir("outputs/apk/release"))
    requiredAssets.set(
        listOf(
            "home_dreamweaver_cave.png",
            "gameplay_station_core.png",
            "gameplay_war_ruins.png",
            "npc_station_lyra.png",
            "npc_station_soren.png",
            "npc_war_mara.png",
            "npc_war_tomas.png",
        ).map { fileName ->
            "assets/composeResources/$uiGameResourcePackage/drawable/$fileName"
        },
    )
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
        jvmTarget = JvmTarget.JVM_17
    }
}
