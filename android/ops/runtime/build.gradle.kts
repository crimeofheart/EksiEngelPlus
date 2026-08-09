plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * The shared API key, from the one file that declares it.
 *
 * Fails the build rather than falling back to an empty string. Empty is exactly
 * what the sender reads as "do not send", so a silent fallback would reinstate
 * the failure this replaced: an app that reports nothing and says nothing about
 * why. If config.js is genuinely unavailable -- a sparse checkout of android/
 * only -- pass the key explicitly.
 */
fun sharedApiKeyFromExtension(): String {
    val configJs = rootProject.file("../frontend/app/assets/js/config.js")
    val hint = "set EKSIENGEL_API_KEY or pass -PtelemetryKey="
    require(configJs.isFile) { "Cannot read $configJs to resolve the shared API key; $hint" }
    return Regex("""SHARED_API_KEY\s*=\s*"([^"]+)"""")
        .find(configJs.readText())
        ?.groupValues
        ?.get(1)
        ?: error("No SHARED_API_KEY literal in $configJs; $hint")
}

android {
    namespace = "org.duzgun.eksiengelplus.ops.runtime"
    compileSdk = 36
    buildFeatures { buildConfig = true }
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        /*
         * The reporting endpoint and its shared key, defined once for the whole
         * app -- :app used to carry a second copy of both, and two definitions of
         * one key is one too many.
         *
         * The key is read out of the extension's config.js rather than repeated
         * here. Both clients authenticate to one endpoint with one shared key, so
         * two literals could only ever drift apart, and the drift would show up
         * as one client silently 401ing months later. Reading the source of truth
         * makes that unrepresentable.
         *
         * This does not make the key secret and is not trying to: it ships inside
         * every extension zip on two public stores, and it would ship inside the
         * APK wherever it came from. It gates spam, not trust.
         *
         * Override for a fork or a staging server:
         *   EKSIENGEL_API_KEY=... ./gradlew ...
         *   ./gradlew -PtelemetryUrl=http://10.0.2.2:8000/api/action/
         */
        val telemetryKey = System.getenv("EKSIENGEL_API_KEY")
            ?: providers.gradleProperty("telemetryKey").orNull
            ?: sharedApiKeyFromExtension()
        val telemetryUrl = providers.gradleProperty("telemetryUrl")
            .getOrElse("https://eksiengelplus.duzgun.org/api/action/")

        buildConfigField("String", "TELEMETRY_KEY", "\"$telemetryKey\"")
        buildConfigField("String", "TELEMETRY_URL", "\"$telemetryUrl\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}

dependencies {
    api(project(":ops:engine"))
    api(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:network"))

    implementation(libs.work.runtime)
    implementation(libs.hilt.android)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.work.compiler)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.truth)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.work.testing)
    androidTestImplementation(libs.kotlin.coroutines.test)
}
