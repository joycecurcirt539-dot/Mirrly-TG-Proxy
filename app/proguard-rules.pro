# Keep OkHttp & Coroutines rules if minified
-keepclassmembers class * {
    @okhttp3.internal.annotations.EverythingIsNonNull *;
}
-dontwarn okhttp3.internal.platform.**

# Keep JNA interfaces and NativeProxy library calls
-keep interface com.sun.jna.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**
-keep interface com.mirrly.tgproxy.core.ProxyLibrary { *; }
-keep class com.mirrly.tgproxy.core.NativeProxy { *; }
