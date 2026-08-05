plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.duzgun.eksiengelplus.devharness"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.duzgun.eksiengelplus.devharness"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "harness"
    }
    buildFeatures { viewBinding = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}

dependencies {
    // The point of this module: everything below is production code.
    implementation(project(":core:network"))
    implementation(project(":eksi:client"))
    implementation(project(":eksi:parser"))
    implementation(project(":core:model"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlin.coroutines.android)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
