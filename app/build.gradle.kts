plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.floatingchargering"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.floatingchargering.v2"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "2.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
