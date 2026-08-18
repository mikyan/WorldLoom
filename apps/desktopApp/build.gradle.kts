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

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Dmg)
            packageName = "Worldloom"
            packageVersion = "0.1.0"
        }
    }
}
