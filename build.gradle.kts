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
    version = "0.1.0-SNAPSHOT"
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
        ":platform:secure-vault:check",
        ":shared:application:check",
        ":shared:ui-game:check",
        ":apps:androidApp:check",
        ":apps:desktopApp:check",
    )
}
