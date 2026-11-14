# xCam Bypass - ProGuard rules
-keep class com.hazbu.xcam.** { *; }
-keep class io.github.libxposed.** { *; }

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-keep class androidx.media3.common.** { *; }
-dontwarn androidx.media3.**

# Keep Xposed module metadata
-keepclassmembers class * {
    @android.os.Bundle <init>(...);
    @android.os.Bundle <clinit>(...);
}

# Keep hook classes
-keep class com.hazbu.xcam.hooks.** { *; }
-keep class com.hazbu.xcam.core.** { *; }
