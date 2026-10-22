plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pvm.zombievectorizer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pvm.zombievectorizer"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Clave de firma fija: las versiones nuevas se instalan encima de las anteriores sin desinstalar.
    signingConfigs {
        create("fixed") {
            storeFile = file("zv-debug.keystore")
            storePassword = "zombie123"
            keyAlias = "zv"
            keyPassword = "zombie123"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("fixed") }
        release {
            signingConfig = signingConfigs.getByName("fixed")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
