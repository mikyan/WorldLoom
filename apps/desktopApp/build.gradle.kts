import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
        jvmTarget = JvmTarget.JVM_17
    }
    jvmToolchain(17)
}

dependencies {
    implementation(projects.shared.uiGame)
    implementation(projects.shared.persistence)
    implementation(projects.shared.agentRuntime)
    implementation(projects.shared.providerOpenai)
    implementation(projects.platform.secureVault)
    implementation(libs.ktor.client.core)
    implementation(compose.desktop.currentOs)
}

sourceSets.main {
    resources.srcDir(rootProject.layout.projectDirectory.dir("contract-worlds"))
}

compose.desktop {
    application {
        mainClass = "io.worldloom.app.desktop.MainKt"

        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Dmg)
            // SQLDelight's desktop driver uses java.sql at application startup. jpackage's
            // module detection does not discover that transitive runtime requirement.
            modules("java.sql")
            packageName = "Worldloom"
            packageVersion = "0.0.5"
            description = "AI-hosted single-player digital tabletop RPG"
            vendor = "Worldloom"
            windows {
                shortcut = true
                menu = true
                menuGroup = "Worldloom"
                upgradeUuid = "276d1f09-b6e7-4409-983b-63ad63a810dd"
            }
        }
    }
}
