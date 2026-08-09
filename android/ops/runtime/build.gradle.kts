plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
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
         * The default is the value already committed in
         * frontend/app/assets/js/config.js and shipped inside every extension zip
         * on two public stores. Withholding it here protected nothing that is not
         * already public, and cost everything: with EKSIENGEL_API_KEY unset --
         * which is every local build -- the app reported nothing at all, silently.
         *
         * Override for a fork or a staging server:
         *   EKSIENGEL_API_KEY=... ./gradlew ...
         *   ./gradlew -PtelemetryUrl=http://10.0.2.2:8000/api/action/
         */
        val telemetryKey = System.getenv("EKSIENGEL_API_KEY")
            ?: providers.gradleProperty("telemetryKey").orNull
            ?: "cbjhsabj=iuhfnkenkfjnbekvbkjhdsbkjucbviujsdvnk./.d876fwuj*/8*f"
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
