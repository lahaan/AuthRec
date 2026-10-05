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

    defaultConfig {
        applicationId = "com.authrec"
        minSdk = 31
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.1"
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
