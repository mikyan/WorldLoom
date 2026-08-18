import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    android {
        namespace = "io.worldloom.provider.openai"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTestBuilder {}
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    iosArm64()
    iosSimulatorArm64()
    applyDefaultHierarchyTemplate()

    compilerOptions {
        allWarningsAsErrors = true
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.shared.providerApi)
            implementation(projects.platform.secureVault)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        named("desktopMain") {
            dependencies {
                implementation(libs.ktor.client.cio)
            }
        }
        named("desktopTest") {
            dependencies {
                implementation(projects.shared.agentRuntime)
                implementation(projects.shared.application)
            }
            resources.srcDir(rootProject.layout.projectDirectory.dir("contract-worlds"))
        }
        named("androidMain") {
            dependencies {
                implementation(libs.ktor.client.okhttp)
            }
        }
        named("iosMain") {
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
    }

    jvmToolchain(17)
}
