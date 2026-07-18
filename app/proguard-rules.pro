# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK tools proguard-defaults.txt.

# Keep Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-keep class com.google.android.exoplayer2.** { *; }

# Keep NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# Keep Shizuku
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# Keep Coil
-keep class coil.** { *; }

# Keep commons-compress
-keep class org.apache.commons.compress.** { *; }
# Suppress warnings for optional dependencies not included in APK
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**

# Keep Miuix
-keep class top.yukonga.miuix.** { *; }

# Keep JNI native classes
-keep class com.example.fold.audio.UsbAudioStream { *; }
-keep class com.example.fold.audio.DspEngine { *; }

# Keep SQLite entities
-keep class com.example.fold.data.db.** { *; }
