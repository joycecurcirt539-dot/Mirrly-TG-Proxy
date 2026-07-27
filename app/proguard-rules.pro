# Keep OkHttp & Coroutines rules if minified
-keepclassmembers class * {
    @okhttp3.internal.annotations.EverythingIsNonNull *;
}
-dontwarn okhttp3.internal.platform.**
