import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing comes from keystore.properties (git-ignored); without it, release builds are unsigned.
val signingProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.authrec"
    compileSdk = 36
    // The software encoders behind "Compress" (src/main/cpp: x265).
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.authrec"
        minSdk = 31
        targetSdk = 36
        versionCode = 7
        versionName = "0.5.0"
        // Android 12+ phones are 64-bit Arm; other ABIs would only grow the APK.
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                // x265's CMakeLists still declares compatibility with CMake 2.8, which CMake 4 refuses.
                arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_POLICY_VERSION_MINIMUM=3.5")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    signingConfigs {
        if (signingProps.isNotEmpty()) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
}
