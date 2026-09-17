plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.duzgun.eksiengelplus.database"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    // MigrationTestHelper reads the exported schemas off the test APK's assets,
    // so the directory KSP writes them to has to be packaged into it.
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

// Exported schemas are committed and CI fails when they are dirty. An
// uncommitted schema change means a migration was never authored, which only
// surfaces as a crash on a user's device.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    api(project(":core:model"))
    // api, not implementation: EksiDatabase extends RoomDatabase and consumers
    // need withTransaction, so Room is part of this module's surface.
    api(libs.room.runtime)
    api(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlin.coroutines.android)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlin.coroutines.test)
    androidTestImplementation(libs.room.testing)
}
