plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.hazbu.xcam"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hazbu.xcam"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
       