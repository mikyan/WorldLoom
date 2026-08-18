rootProject.name = "WorldLoom"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":shared:definition-runtime")
include(":shared:domain-world")
include(":shared:domain-rules")
include(":shared:rule-module-api")
include(":shared:rule-module-registry")
include(":shared:persistence")
include(":shared:provider-api")
include(":shared:provider-openai")
include(":shared:agent-runtime")
include(":platform:secure-vault")
include(":shared:application")
include(":shared:ui-game")
include(":apps:androidApp")
include(":apps:desktopApp")
