# Keep Gson model classes
-keep class com.arponzxc.novaai.data.model.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Keep JavascriptInterfaces for WebViews (Cyberpunk Orb & Settings Activity)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Vosk and JNA native libraries & models
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keep class net.java.dev.jna.** { *; }
# JNA ships optional desktop AWT bridges that are not present on Android; app code does not use them.
-dontwarn java.awt.**

# Keep Room database models
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase


# ── Arpon Gang / NOVA AI Security Module — keep rules ──────────────────────────
-keep class com.arponzxc.novaai.security.SecurityManager { *; }
-keep class com.arponzxc.novaai.security.DeveloperInfo { *; }
-keep class com.arponzxc.novaai.security.AppIdentityVerifier { *; }
-keep class com.arponzxc.novaai.config.SocialLinks { *; }

# Obfuscate everything else aggressively
-optimizationpasses 5
-allowaccessmodification
-repackageclasses 'novaai'

# Remove Log calls in release builds (security: don't leak internals)
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
