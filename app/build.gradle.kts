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
        versionCode = 12
        versionName = "1.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}