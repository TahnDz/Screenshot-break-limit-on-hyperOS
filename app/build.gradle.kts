plugins {
    id("com.android.application")
}

android {
    namespace = "com.tahndz.miuilongshot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tahndz.miuilongshot"
        minSdk = 27
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "miuilongshot"
            keyAlias = "miuilongshot"
            keyPassword = "miuilongshot"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Xposed API 82 (compile-only, runtime do LSPosed cung cap)
    compileOnly(files("libs/api-82.jar"))
}
