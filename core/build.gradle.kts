// Felles kode: tilkobling (Bluetooth + Wi-Fi), protokoll, musikk- og navigasjonslesing.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "no.carlink.core"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    api("androidx.core:core-ktx:1.13.1")
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
