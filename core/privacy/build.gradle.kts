plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.sirrak.core.privacy"
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
        // The privacy policy is pure Kotlin over injected seams; android.jar stubs only need to
        // return defaults, so the whole module is covered by JVM tests.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // NetworkEndpoint lives in :core:runtime's AppConfig so the endpoint registry stays data.
    implementation(project(":core:runtime"))
    testImplementation(libs.junit)
}
