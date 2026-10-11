# AURA release (minified) build rules.

# Vosk (live-task keyword spotting) calls native code through JNA, which uses reflection.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
