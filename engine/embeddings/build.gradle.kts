plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.sirrak.engine.embeddings"
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
        // LiteRT-LM 0.19.1 is published with Kotlin 2.4.0 metadata while this build stays on
        // 2.0.21. The AAR is consumed only through its Java-facing API (EmbeddingEngine,
        // InputData, EmbeddingOptions), so the newer metadata is a compile-time version check,
        // not a bytecode incompatibility. Keeping one Kotlin/stdlib version in the build is
        // safer than upgrading the whole app for one dependency, and the skip is confined to
        // this wrapper module. Revisit when the build's Kotlin version moves past 2.4.
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }
}

dependencies {
    implementation(project(":core:rag"))
    // Spike-proven LiteRT-LM AAR (doc 10 §3.6): the EmbeddingEngine that loads the EmbeddingGemma-2
    // 270M .litertlm bundle (768 dims). Same native library the H2/H3 spikes measured.
    implementation(libs.litertlm.android) {
        // Its POM pins kotlin-reflect 2.4.0 and coroutines 1.11.0. Pulling those transitively would
        // put two Kotlin stdlib metadata versions on every consumer's classpath, so the wrapper owns
        // the versions: the build's coroutines and a compatible reflect runtime below.
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
    }
    implementation(libs.kotlinx.coroutines.android)
    // Some LiteRT-LM API paths reflect over their own types; the artifact declares reflect, so the
    // runtime copy must match this build's Kotlin rather than disappear with the footprint above.
    runtimeOnly("org.jetbrains.kotlin:kotlin-reflect:2.0.21")

    testImplementation(libs.junit)
}
