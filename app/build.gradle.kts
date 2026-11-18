import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Personal release key: key.properties + keystore/alo-answer-release.jks (both git-ignored).
// Without them, release builds fall back to the debug key.
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
val hasReleaseKey = keystorePropertiesFile.exists()
if (hasReleaseKey) {
    FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) }
}

android {
    namespace = "com.aloanswer.alo_answer"
    compileSdk = 36
    // Only used to strip the prebuilt Vosk / JNA native libraries.
    ndkVersion = "28.2.13676358"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.aloanswer.alo_answer"
        minSdk = 26 // ANSWER_PHONE_CALLS / acceptRingingCall need API 26
        targetSdk = 36
        // The Flutter builds used 2000 + build number (the last one, 1.4.0, was 2006).
        // Staying above that lets this version install over them with the same key.
        versionCode = 2008
        versionName = "2.0.1"
        // 64-bit ARM phones only (every current Redmi). Add "armeabi-v7a" for old 32-bit
        // phones or "x86_64" for an Intel-Mac emulator; each adds its own Vosk library.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            // Keep JNA/Vosk classes from being stripped by R8 (see proguard-rules.pro).
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // Vosk offline speech recognition (Apache 2.0): no account, no key, no internet.
    // Brings in net.java.dev.jna:jna (aar) transitively. Model: assets/model-uz.
    implementation("com.alphacephei:vosk-android:0.3.75")
}
