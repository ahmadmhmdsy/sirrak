import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// P3.5 release signing: the keystore and its passwords are never committed. A checkout without
// keystore.properties still builds (release stays unsigned), which keeps debug/CI builds honest.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "app.sirrak"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.sirrak"
        minSdk = 28
        targetSdk = 36
        versionCode = 13
        versionName = "1.0.0-rc2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // The engine ships x86_64 (emulator) and arm64-v8a (phone). Nothing else is built or
            // packaged, so the badging never advertises an ABI the app cannot actually run on.
            abiFilters += listOf("x86_64", "arm64-v8a")
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // P3.5 distribution flavours. Both are built from the same source and have the same app
    // identity; the split exists so a Play-only dependency (services, billing, crash reporting) can
    // never silently enter the F-Droid build: it goes into the play source set and the libre build
    // is the one reviewed for F-Droid. BuildConfig.STORE is shown in About for transparency.
    flavorDimensions += "store"
    productFlavors {
        create("libre") {
            dimension = "store"
            buildConfigField("String", "STORE", "\"libre\"")
        }
        create("play") {
            dimension = "store"
            buildConfigField("String", "STORE", "\"play\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // P3.6: the in-app language switch recreates the activity instead of using Play Core language
    // splits, so both locale resources must stay in the base APK of the Play build. Without this the
    // Play variant could install only Arabic resources and the sw (values-en) resources would be
    // missing on a device whose system locale is Arabic.
    bundle {
        language {
            enableSplit = false
        }
    }

    testOptions {
        // JVM composition tests drive the real resolver; Android stub calls such as Log.w must no-op.
        unitTests.isReturnDefaultValues = true
    }
}

// With two flavours, AGP names every concrete task after the variant (assembleLibreDebug,
// installLibreDebug, testLibreDebugUnitTest, connectedLibreDebugAndroidTest) and keeps the old
// build-type anchor tasks (assembleDebug, ...) as aggregates over all flavours. Scripts and docs
// should name the variant they mean; the libre variant is the default F-Droid distribution.

dependencies {
    implementation(project(":core:runtime"))
    implementation(project(":core:design"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:settings"))
    implementation(project(":engine:llamacpp"))
    implementation(project(":core:rag"))
    implementation(project(":engine:embeddings"))
    // M10: the speech seam, the sherpa-onnx Whisper engine and the voice feature (mic, status strip,
    // settings card). The app module is the only place that names the concrete engine.
    implementation(project(":core:speech"))
    implementation(project(":engine:whisper"))
    implementation(project(":feature:voice"))
    // M11: the pure-JVM model-manager core (catalogue, device check, resumable downloader, consent,
    // onboarding) and the feature module that renders onboarding + the Settings model manager.
    implementation(project(":core:models"))
    implementation(project(":feature:models"))
    // P3: privacy core (network audit, offline switch, AI-output report queue) and the feature that
    // renders the privacy settings, the network log, the reports list and the per-answer report action.
    implementation(project(":core:privacy"))
    implementation(project(":feature:privacy"))

    implementation(libs.androidx.core.ktx)
    // P3.6: AndroidX ExifInterface (lint: the platform one has known security bugs). Same API as the
    // framework class, used only to read the camera orientation of an attached image.
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // PDF text extraction (Apache-2.0). Fonts need an Android Context, which is why this lives in
    // :app rather than in the pure-JVM :core:rag module.
    implementation(libs.pdfbox.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)

    testImplementation(libs.junit)

    // Device tests carry the real EmbeddingGemma retrieval evaluation and PDF extraction smoke; both
    // need Android runtime pieces a JVM test cannot host.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}