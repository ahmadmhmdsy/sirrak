plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.sirrak.engine.whisper"
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
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:speech"))
    // M10: the same pinned sherpa-onnx v2.0.1 release AAR the H3b spike measured (doc 10 §3.7),
    // resolved from the local flatDir declared in settings.gradle.kts so the build needs no network.
    implementation(mapOf("name" to "sherpa-onnx-2.0.1", "ext" to "aar"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
