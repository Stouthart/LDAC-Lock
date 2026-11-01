plugins {
    id("com.android.application")
}

layout.buildDirectory = layout.projectDirectory.dir("build.nosync") // iCloud Drive skips .nosync names.

android {
    namespace = "nl.stouthart.ldaclock"
    compileSdk = 37

    defaultConfig {
        applicationId = "nl.stouthart.ldaclock"
        minSdk = 36 // RedMagic Astra (Android 16).
        targetSdk = 37
        versionCode = 6 // Keeps counting up: the phones carried internal builds up to 5.
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug") // Sideloaded only, so the debug key is enough.
        }
    }

    buildFeatures {
        buildConfig = true // VERSION_NAME for the copyright line, as a compile-time constant.
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}
