plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "pl.pixelagent"
    compileSdk = 35
    defaultConfig {
        applicationId = "pl.pixelagent.private"
        minSdk = 30
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
