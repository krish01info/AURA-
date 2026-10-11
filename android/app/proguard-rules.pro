# AURA release (minified) build rules.

# Vosk (live-task keyword spotting) calls native code through JNA, which uses reflection.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# Compile-time-only annotations / optional dependencies referenced by libraries
# (Tink via security-crypto, Ktor via Supabase, OkHttp). Not needed at run time.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn io.ktor.utils.io.jvm.javaio.**
-dontwarn java.lang.management.**

# Keep line numbers so crash reports from release builds are readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
