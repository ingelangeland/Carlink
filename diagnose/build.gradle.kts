// CarLink Diagnose: egen app på bilskjermen med tester og rapport. Ingen bakgrunnstjenester.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "no.carlink.diagnose"
    compileSdk = 35
    defaultConfig {
        applicationId = "no.carlink.diagnose"
        minSdk = 24
        targetSdk = 35
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = run
        versionName = "1.0.$run"
    }
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
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("carlink")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        textReport = true
        textOutput = file("build/lint.txt")
    }
}
dependencies {
}
