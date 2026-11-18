# Release builds shrink/obfuscate with R8. Vosk talks to its native library
# through JNA, which looks classes, fields and methods up by name at runtime,
# and neither vosk-android nor jna ships consumer rules. Keep them intact.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
