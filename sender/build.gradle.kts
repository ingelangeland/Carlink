plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "no.carlink.sender"
    compileSdk = 35
    defaultConfig {
        applicationId = "no.carlink.sender"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    // Fast signeringsnøkkel, så nye versjoner kan installeres oppå de gamle.
    // (Privat bruk. Del ikke nøkkelen hvis appen en gang skal legges ut offentlig.)
    signingConfigs {
        create("carlink") {
            storeFile = rootProject.file("carlink.keystore")
            storePassword = "carlink123"
            keyAlias = "carlink"
            keyPassword = "carlink123"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("carlink") }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("carlink") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")

}
