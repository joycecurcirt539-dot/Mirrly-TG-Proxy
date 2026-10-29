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

# --- DPI Bypass (libsni) ---
-keep class com.21Modz.** { *; }
-keepclasseswithmembernames class com.21Modz.** { native <methods>; }
-keep class com.p00021Modz.** { *; }
-keepclasseswithmembernames class com.p00021Modz.** { native <methods>; }

# --- Compose UI sealed/data classes referenced by R8 ---
-keep class com.mirrly.tgproxy.ui.MarkdownBlock { *; }
-keep class com.mirrly.tgproxy.ui.MarkdownBlock* { *; }
-keep class com.mirrly.tgproxy.ui.ProxyUiState { *; }
-keep class com.mirrly.tgproxy.ui.TechSpecItem { *; }
-keep class com.mirrly.tgproxy.ui.WallpaperImage { *; }
-keep class com.mirrly.tgproxy.util.ContextExtensionsKt { *; }
-dontwarn com.mirrly.tgproxy.ui.MarkdownBlock
-dontwarn com.mirrly.tgproxy.ui.MarkdownBlock*
-dontwarn com.mirrly.tgproxy.ui.ProxyUiState
-dontwarn com.mirrly.tgproxy.ui.TechSpecItem
-dontwarn com.mirrly.tgproxy.ui.WallpaperImage
-dontwarn com.mirrly.tgproxy.util.ContextExtensionsKt

# --- R8 fullMode: Kotlin top-level и Compose-generated классы ---
# AGP 8.x включает R8 fullMode по умолчанию, что удаляет Kotlin *Kt top-level классы
# и ComposableSingletons$*Kt compose-generated синглтоны. Сохраняем весь пакет UI.
-keep class com.mirrly.tgproxy.ui.** { *; }
-keep class com.mirrly.tgproxy.util.** { *; }
-keep class com.mirrly.tgproxy.service.** { *; }
-keepclassmembers class com.mirrly.tgproxy.** {
    public *;
    protected *;
}
-dontwarn com.mirrly.tgproxy.ui.**
-dontwarn com.mirrly.tgproxy.util.**

# --- R8 auto-generated missing rules (from missing_rules.txt) ---
-dontwarn com.21Modz.SNI$Companion
-dontwarn com.21Modz.SNI
-keep class com.mirrly.tgproxy.service.PreferencesManager { *; }
-dontwarn com.mirrly.tgproxy.service.PreferencesManager
-keep class com.mirrly.tgproxy.service.** { *; }