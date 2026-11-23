plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.sirrak.core.models"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // The downloader writes real files to a temp directory and validates the GGUF header through
        // :core:runtime; neither path touches the Android framework, so plain JVM tests cover it.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // GgufFile validation lives in :core:runtime so the downloader can reject a non-GGUF payload
    // with the same rule every other import path uses.
    implementation(project(":core:runtime"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
