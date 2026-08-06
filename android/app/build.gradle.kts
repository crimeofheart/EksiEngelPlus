import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * The product version lives in android/version.json, which frontend/app/scripts/ext.mjs
 * keeps in lockstep with package.json, package-lock.json (x2), and both manifest
 * variants. Never edit it by hand -- use `cd frontend/app && npm run version:patch`.
 *
 * The file is JSON with a top-level "version" field specifically so ext.mjs's existing
 * versionsIn() and rewriteVersion() consume it with no changes.
 */
val versionFile = rootProject.file("version.json")

val productVersion: String = run {
    if (!versionFile.exists()) {
        throw GradleException("${versionFile.path}: missing. Run `cd frontend/app && npm run check`.")
    }
    val parsed = try {
        JsonSlurper().parse(versionFile)
    } catch (e: Exception) {
        throw GradleException("${versionFile.path}: not valid JSON (${e.message})")
    }
    val value = (parsed as? Map<*, *>)?.get("version")
        ?: throw GradleException("${versionFile.path}: no top-level \"version\" field")
    value.toString()
}

/**
 * Google Play requires a strictly increasing integer per upload. Deriving it from the
 * semver means the version bump is the only action and monotonicity is structural --
 * there is no second thing to forget. 0.1.7 -> 107, 1.0.0 -> 10000.
 */
val productVersionCode: Int = run {
    val match = Regex("""^(\d+)\.(\d+)\.(\d+)$""").matchEntire(productVersion)
        ?: throw GradleException("${versionFile.path}: version \"$productVersion\" is not x.y.z")
    val (major, minor, patch) = match.destructured.toList().map(String::toInt)
    // A collision here would surface only at Play upload, long after a release was cut.
    if (minor > 99 || patch > 99) {
        throw GradleException(
            "${versionFile.path}: version \"$productVersion\" would collide -- " +
                "minor and patch must each stay below 100 for the versionCode derivation"
        )
    }
    major * 10000 + minor * 100 + patch
}

android {
    namespace = "org.duzgun.eksiengelplus"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.duzgun.eksiengelplus"
        minSdk = 26
        targetSdk = 36
        versionName = productVersion
        versionCode = productVersionCode
    }

    /**
     * Release signing is configured only when CI has decoded a keystore. Locally,
     * and on a release run with no secrets set, the config is absent and Gradle
     * produces an unsigned artifact -- which is why release.yml falls back to a
     * debug build rather than shipping something Play would reject anyway.
     */
    val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
    val hasKeystore = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

    if (hasKeystore) {
        signingConfigs {
            create("upload") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("ANDROID_STORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildFeatures { viewBinding = false }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("upload")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    implementation(project(":core:network"))
    implementation(project(":webview"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":eksi:client"))
    implementation(project(":eksi:parser"))
    implementation(project(":core:model"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.work.runtime)
    implementation(libs.kotlin.serialization.json)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}

/** Lets CI assert the derivation without parsing build output. */
tasks.register("printVersion") {
    val name = productVersion
    val code = productVersionCode
    doLast { println("versionName=$name versionCode=$code") }
}
