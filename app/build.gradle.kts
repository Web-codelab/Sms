plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.webcodelab.smsvault"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.webcodelab.smsvault"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    val ciDebugKeystore = rootProject.file("debug.keystore")
    signingConfigs {
        getByName("debug") {
            if (ciDebugKeystore.exists()) {
                storeFile = ciDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.gson)
}
