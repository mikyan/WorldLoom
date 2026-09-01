import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

compose.resources {
    packageOfResClass = "io.worldloom.ui.game.generated.resources"
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    android {
        namespace = "io.worldloom.ui.game"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTestBuilder {}
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "WorldloomShared"
            isStatic = true
        }
    }
    applyDefaultHierarchyTemplate()

    compilerOptions {
        allWarningsAsErrors = true
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.shared.application)
            implementation(projects.shared.agentRuntime)
            implementation(projects.shared.contentGeneration)
            implementation(projects.shared.providerApi)
            implementation(projects.shared.providerOpenai)
            implementation(projects.platform.secureVault)
            implementation(libs.compose.animation)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material)
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        named("iosMain") {
            dependencies {
                implementation(projects.shared.persistence)
                implementation(projects.shared.providerOpenai)
                implementation(libs.ktor.client.core)
            }
        }
    }

    jvmToolchain(17)
}
