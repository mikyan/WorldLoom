import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    android {
        namespace = "io.worldloom.persistence"
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
            api(projects.shared.domainRules)
            api(projects.shared.agentRuntime)
            implementation(projects.shared.application)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.sqldelight.runtime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        named("desktopMain") {
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        named("desktopTest") {
            resources.srcDir("src/commonMain/sqldelight/databases")
        }
        named("androidMain") {
            dependencies {
                implementation(libs.sqldelight.android.driver)
            }
        }
        named("iosMain") {
            dependencies {
                implementation(libs.sqldelight.native.driver)
            }
        }
    }

    jvmToolchain(17)
}

sqldelight {
    databases {
        register("WorldloomDatabase") {
            packageName.set("io.worldloom.persistence.db")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}
