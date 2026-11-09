# ProGuard / R8 Rules for IPTV Android TV (Full Mode Optimization)

# 1. ExoPlayer 2.x & Media Codecs
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**
-keepclassmembers class * extends com.google.android.exoplayer2.video.MediaCodecVideoRenderer {
    protected boolean codecNeedsSetOutputSurfaceWorkaround(java.lang.String);
}

# 2. Data Models (Serialized / Deserialized via JSON)
-keep class com.iptv.tvapp.model.** { *; }
-keepclassmembers class com.iptv.tvapp.model.** { *; }

# 3. ViewBinding (Activity / Dialog / Item bindings)
-keep class com.iptv.tvapp.databinding.** { *; }

# 4. OkHttp & Okio (Network M3U / HTTP Data Source)
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# 5. Glide Image Loader (Logos & Thumbnails)
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class com.bumptech.glide.** { *; }
-dontwarn com.bumptech.glide.**

# 6. Kotlin Coroutines & Reflection
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# 7. Leanback & AndroidX
-keep class androidx.leanback.** { *; }
-dontwarn androidx.leanback.**
