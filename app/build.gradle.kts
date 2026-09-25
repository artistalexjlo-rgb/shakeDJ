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
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideload-friendly: signed with the debug key until a real release key is set up.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        // Plain Java 8 without lambdas, so scripts/build-apk-without-gradle.sh can build it with dx too.
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
