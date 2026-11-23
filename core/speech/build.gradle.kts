plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.sirrak.core.speech"
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
}

dependencies {
    // The speech seams depend only on coroutines: no Android types, so the state machine, WAV codec
    // and model-layout rules stay plain-JVM unit-testable.
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
