plugins {
    id("com.android.application")
}

android {
    namespace = "com.shakedj.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shakedj.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.7.1"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    // One fixed key for every build (CI, local, the no-Gradle script), so each new APK installs
    // over the previous one. A fresh CI machine would otherwise sign with a new random debug key.
    // It is a sideload key committed on purpose; it is not meant for a store release.
    signingConfigs {
        create("sideload") {
            storeFile = rootProject.file("keystore/shakedj-sideload.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sideload")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        // Plain Java 8 without lambdas, so scripts/build-apk-without-gradle.sh can build it with dx too.
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
