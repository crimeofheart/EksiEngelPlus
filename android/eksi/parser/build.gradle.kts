plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Pure JVM, no Android framework. That is what lets the selector suite run against
// the committed fixture corpus as an ordinary unit test in CI, in seconds.
kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:model"))
    implementation(libs.jsoup)
    implementation(libs.kotlin.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.withType<Test>().configureEach {
    // The corpus is shared with the extension, so it lives at the repo root rather
    // than under android/. See docs/fixtures/eksisozluk/MANIFEST.md.
    systemProperty("eksi.fixtures", rootProject.file("../docs/fixtures/eksisozluk").absolutePath)
}
