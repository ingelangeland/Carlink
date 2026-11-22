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
        // Nytt versjonsnummer for hvert bygg (vises under App-info → versjon)
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = run
        versionName = "1.0.$run"
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
        // Optimalisert versjon: R8 fjerner ubrukt kode og ressurser → mindre og raskere app
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
    // Kodekontroll (Android Lint): rapport i stedet for å stoppe bygget
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        textReport = true
        textOutput = file("build/lint.txt")
    }
}
dependencies {
    implementation(project(":core"))
    // Forhåndskompilerer appens kode ved installasjon (baseline-prof.txt) → raskere oppstart og jevnere menyer
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")

}
