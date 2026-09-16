plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.astro.pdfprice"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.astro.pdfprice"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "2.0-ai"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.rmtheis:tess-two:9.1.0")
}
