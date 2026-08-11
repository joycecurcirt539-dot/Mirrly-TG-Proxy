# ====================================================================
# Mirrly TG Proxy - R8 & Proguard Optimization Rules
# ====================================================================

# --- Stacktraces & Debugging Attributes ---
-keepattributes Signature,InnerClasses,EnclosingMethod,AnnotationDefault,*Annotation*,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- OkHttp & Networking ---
-keepclassmembers class * {
    @okhttp3.internal.annotations.EverythingIsNonNull *;
}
-dontwarn okhttp3.internal.platform.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# --- JNA (Java Native Access) & Go/C Native Proxy ---
-keep interface com.sun.jna.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**
-keep interface com.mirrly.tgproxy.core.ProxyLibrary { *; }
-keep class com.mirrly.tgproxy.core.ProxyLibrary** { *; }
-keep class com.mirrly.tgproxy.core.NativeProxy { *; }
-keep class com.mirrly.tgproxy.core.NativeProxy** { *; }

# --- JNI & Native Security Library (mirrly_sec) ---
-keep class com.mirrly.tgproxy.util.SignatureVerifier { *; }
-keep class com.mirrly.tgproxy.util.SignatureStatus { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- WorkManager Background Workers ---
-keep class com.mirrly.tgproxy.service.UpdateCheckWorker { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(...);
}

# --- Enums (SessionStatus, ProxyMode, etc.) ---
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Jetpack Compose ---
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}


