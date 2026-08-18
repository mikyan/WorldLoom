import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.kotlinx.serialization)
}

kotlin {
    jvm("desktop") { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
    android {
        namespace = "io.worldloom.content.generation"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTestBuilder {}
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    }
    iosArm64()
    iosSimulatorArm64()
    applyDefaultHierarchyTemplate()

    compilerOptions { allWarningsAsErrors = true }

    sourceSets {
        commonMain.dependencies {
            api(projects.shared.contentSchema)
            api(projects.shared.behaviorRuntime)
            api(projects.shared.worldPackage)
            implementation(projects.shared.domainWorld)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
    jvmToolchain(17)
}
