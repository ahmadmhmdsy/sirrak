plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.sirrak.engine.llamacpp"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 28
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
            }
        }
    }

    // Debug builds target the emulator only; release builds compile both the emulator ABI and the
    // phone ABI so a real device install is already validated before phone day.
    buildTypes {
        debug {
            ndk { abiFilters.clear(); abiFilters.add("x86_64") }
        }
        release {
            ndk { abiFilters.clear(); abiFilters.addAll(listOf("x86_64", "arm64-v8a")) }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
    implementation(project(":core:runtime"))
    implementation(libs.kotlinx.coroutines.android)
}
