pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // M10: the pinned sherpa-onnx release AAR (Apache-2.0) is a local file, like the spike
        // pinned it. Local-file dependencies keep the offline build reproducible without a
        // network repository and without a version range that could drift.
        flatDir { dirs(rootDir.resolve("engine/whisper/libs")) }
    }
}

rootProject.name = "sirrak"

include(":app")
include(":core:runtime")
include(":core:design")
include(":feature:chat")
include(":feature:settings")
include(":engine:llamacpp")
include(":core:rag")
include(":engine:embeddings")
include(":core:speech")
include(":engine:whisper")
include(":feature:voice")
include(":core:models")
include(":feature:models")
include(":feature:privacy")
include(":core:privacy")
