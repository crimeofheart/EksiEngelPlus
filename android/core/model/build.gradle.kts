plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Deliberately Android-free: TurkishDateParser and the date-filter predicates are
// pure functions, and their characterisation suite must be trivial to run.
kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.kotlin.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
