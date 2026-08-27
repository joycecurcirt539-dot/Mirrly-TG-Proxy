import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mirrly.tgproxy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mirrly.tgproxy"
        minSdk = 26
        targetSdk = 35
        versionCode = 23
        versionName = "1.1.8.1"


        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        manifestPlaceholders["appLabel"] = "Mirrly TG Proxy"
        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?: project.file("keystore.properties").takeIf { it.exists() }
    val keystoreProperties = Properties()
    if (keystorePropertiesFile != null) {
        keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
    }

    val envStoreFile = System.getenv("RELEASE_KEYSTORE_PATH") ?: System.getenv("KEYSTORE_PATH")
    val propStoreFile = keystoreProperties.getProperty("storeFile")
    val releaseStoreFile = when {
        !propStoreFile.isNullOrBlank() -> {
            val f = file(propStoreFile)
            if (f.exists()) f else rootProject.file(propStoreFile).takeIf { it.exists() }
        }
        !envStoreFile.isNullOrBlank() -> file(envStoreFile).takeIf { it.exists() }
        else -> null
    }

    val releaseStorePassword = keystoreProperties.getProperty("storePassword")
        ?: System.getenv("RELEASE_STORE_PASSWORD")
        ?: System.getenv("KEYSTORE_PASSWORD")

    val releaseKeyAlias = keystoreProperties.getProperty("keyAlias")
        ?: System.getenv("RELEASE_KEY_ALIAS")
        ?: System.getenv("KEY_ALIAS")

    val releaseKeyPassword = keystoreProperties.getProperty("keyPassword")
        ?: System.getenv("RELEASE_KEY_PASSWORD")
        ?: System.getenv("KEY_PASSWORD")

    val isReleaseSigningConfigured = releaseStoreFile != null &&
            !releaseStorePassword.isNullOrBlank() &&
            !releaseKeyAlias.isNullOrBlank() &&
            !releaseKeyPassword.isNullOrBlank()

    signingConfigs {
        if (isReleaseSigningConfigured) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (isReleaseSigningConfigured) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("WARNING: Release keystore is not configured. Falling back to debug signing for assembleRelease. This APK will NOT match official release signatures.")
                signingConfigs.getByName("debug")
            }
            manifestPlaceholders["appLabel"] = "Mirrly TG Proxy"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("beta") {
            initWith(getByName("release"))
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            matchingFallbacks += listOf("release")
            manifestPlaceholders["appLabel"] = "Mirrly (Beta)"
            signingConfig = if (isReleaseSigningConfigured) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            manifestPlaceholders["appLabel"] = "Mirrly (Debug)"
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    // CameraX for QR Scanner
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Google Play Services ML Kit Barcode Scanning (Uses OS/Google Play model, zero APK size bloat)
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")

    // ZXing Core for standalone fallback
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}




