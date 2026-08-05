plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure JVM on purpose. The Android-specific cookie source lives in core:network
// and is injected as an interface, which keeps every client behaviour -- response
// codes, pagination, 429 handling, session expiry -- testable under MockWebServer
// with no emulator.
kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:model"))
    api(project(":eksi:parser"))
    api(libs.okhttp)
    implementation(libs.kotlin.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlin.coroutines.test)
}
