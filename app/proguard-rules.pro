# Sirrak R8 rules. Compose, Kotlin and AndroidX ship their own consumer rules; these cover the
# kotlinx-serialization models that validate assets/config/app.json and assets/content/*.json.

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# llama.cpp JNI: native methods and the streaming sink are resolved by exact name from C++.
-keepclasseswithmembernames,includedescriptorclasses class app.sirrak.engine.llamacpp.** {
    native <methods>;
}
-keep interface app.sirrak.engine.llamacpp.TokenSink { *; }

# sherpa-onnx (M10 dictation): the native JNI layer reads the recognizer config fields by name
# (OfflineRecognizerConfig.decodingMethod, model config fields, ...) and calls constructors from C++.
# Without this the R8 release build fails at load time with
# NoSuchFieldError: no "Ljava/lang/String;" field "decodingMethod" — caught by the M10 release pass.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# PDFBox-Android (M8 document RAG): text extraction never decodes images, and the JPEG2000 (JPX)
# decoder is an optional dependency PDFBox probes reflectively. It is intentionally absent from the
# APK, so tell R8 that the reference is a capability probe, not a missing required class.
-dontwarn com.gemalto.jp2.**

-keep,includedescriptorclasses class app.sirrak.**$$serializer { *; }
-keepclassmembers class app.sirrak.** {
    *** Companion;
}
-keepclasseswithmembers class app.sirrak.** {
    kotlinx.serialization.KSerializer serializer(...);
}
