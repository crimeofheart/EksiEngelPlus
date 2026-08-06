plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure JVM. The pacer, retry policy and state machine are the pieces most worth
// testing exhaustively, and none of them need Android. WorkManager and the
// foreground service live in a separate Android module.
kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:model"))
    api(project(":eksi:client"))
    implementation(libs.kotlin.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
