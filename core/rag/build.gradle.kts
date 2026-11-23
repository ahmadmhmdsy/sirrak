plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.sirrak.core.rag"
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
        // PDFBox-Android links against android.* for rendering only; the text-extraction path used
        // here must stay runnable as a plain JVM unit test, so stub calls return defaults.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:runtime"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // PDFBox-Android lives in :app (it needs an Android Context for font resources and an
    // instrumentation test), so this module stays pure JVM and its algorithms stay unit-testable.
    testImplementation(libs.junit)
}
