// Standalone AI module — pure Kotlin/JVM, NO Android dependencies.
// All libraries here (coroutines, serialization, Ktor) are multiplatform-capable,
// so this module can be converted to Kotlin Multiplatform for the iOS port
// without rewriting AI logic.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock) // never hit the real Anthropic API in tests
}
