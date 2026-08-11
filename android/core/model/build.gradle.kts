plugins {
    alias(libs.plugins.kotlin.jvm)
    // DateFilterRule travels inside an OperationRequest, which is persisted as
    // JSON, so the rule has to be serialisable where it is declared.
    alias(libs.plugins.kotlin.serialization)
}

// Deliberately Android-free: TurkishDateParser and the date-filter predicates are
// pure functions, and their characterisation suite must be trivial to run.
kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.kotlin.coroutines.core)
    api(libs.kotlin.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
