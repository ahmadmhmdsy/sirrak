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
    // G11: a physical-phone smoke needs the arm64 engine in a debug APK. Keeping it behind an
    // explicit property leaves CI and the emulator loop on the fast x86_64-only build while making
    // the phone build reproducible: -PsirrakArm64Debug=true
    val arm64Debug = (project.findProperty("sirrakArm64Debug") as? String)?.toBoolean() == true
    buildTypes {
        debug {
            ndk {
                abiFilters.clear()
                abiFilters.add("x86_64")
                if (arm64Debug) abiFilters.add("arm64-v8a")
            }
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
