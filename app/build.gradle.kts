import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val singleAbiProp = (project.findProperty("singleAbi") as? String)?.trim()?.takeIf { it.isNotEmpty() }
    ?: System.getenv("MIRRLY_SINGLE_ABI")?.trim()?.takeIf { it.isNotEmpty() }
val activeAbis = if (singleAbiProp != null) listOf(singleAbiProp) else listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

android {
    namespace = "com.mirrly.tgproxy"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mirrly.tgproxy"
        minSdk = 26
        targetSdk = 36
        versionCode = 33
        versionName = "2.1.3"


        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        manifestPlaceholders["appLabel"] = "Mirrly TG Proxy"
        ndk {
            abiFilters += activeAbis
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
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
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
                val isReleaseBuildRequested = gradle.startParameter.taskNames.any {
                    it.contains("Release", ignoreCase = true) || it.equals("assemble", ignoreCase = true)
                }
                if (isReleaseBuildRequested) {
                    throw GradleException("FATAL: Release keystore is NOT configured! Release APKs MUST be signed with the official release keystore. Falling back to debug signing for releases is strictly forbidden.")
                }
                logger.warn("WARNING: Release keystore is not configured. Falling back to debug signing for non-release build task.")
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
            if (isReleaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
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
            include(*activeAbis.toTypedArray())
            isUniversalApk = singleAbiProp == null
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
            keepDebugSymbols += "**/libmirrlyengine.so"
            keepDebugSymbols += "**/libmirrlyvpn.so"
        }
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    applicationVariants.all {
        val vName = versionName
        val vCode = versionCode
        outputs.all {
            val output = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val abi = output?.getFilter("ABI") ?: "universal"
            output?.outputFileName = "MTGP [$vName+$vCode] [$abi].apk"
        }
    }
}

val rootDirFile = rootProject.projectDir
val buildCargoNdk = tasks.register<Exec>("buildCargoNdk") {
    group = "build"
    description = "Builds mirrlyvpn native Rust library via cargo-ndk for active ABIs into app/src/main/jniLibs (libmirrlyengine.so is prebuilt from canonical mirrlyengine repo)"
    workingDir = rootDirFile
    inputs.dir(rootDirFile.resolve("mirrlyvpn/src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        rootDirFile.resolve("mirrlyvpn/Cargo.toml"),
        rootDirFile.resolve("mirrlyvpn/Cargo.lock"),
        rootDirFile.resolve("mirrlyvpn/build.rs"),
        rootDirFile.resolve("mirrlyvpn/.cargo/config.toml")
    ).withPathSensitivity(PathSensitivity.RELATIVE)
    if (singleAbiProp != null) {
        inputs.property("singleAbi", singleAbiProp)
        environment("MIRRLY_SINGLE_ABI", singleAbiProp)
    }
    outputs.dir(project.layout.projectDirectory.dir("src/main/jniLibs"))
    outputs.cacheIf { true }
    if (org.apache.tools.ant.taskdefs.condition.Os.isFamily(org.apache.tools.ant.taskdefs.condition.Os.FAMILY_WINDOWS)) {
        commandLine("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "${rootDirFile}/build_native.ps1")
    } else {
        commandLine("bash", "${rootDirFile}/build_native.sh")
    }
}

val isAnyJniLibMissing = activeAbis.any { abi ->
    !project.file("src/main/jniLibs/$abi/libmirrlyengine.so").exists() ||
        !project.file("src/main/jniLibs/$abi/libmirrlyvpn.so").exists()
}

if (isAnyJniLibMissing || project.hasProperty("autoBuildNative") || System.getenv("AUTO_BUILD_NATIVE") == "true") {
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
        dependsOn(buildCargoNdk)
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
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




