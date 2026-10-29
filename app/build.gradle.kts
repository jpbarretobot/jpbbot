plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.jpb.meteowidget"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.jpb.meteowidget"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        // Clé fixe versionnée : chaque nouvelle version s'installe par-dessus la précédente.
        getByName("debug") {
            storeFile = rootProject.file("keystore/meteo.keystore")
            storePassword = "meteopaysage"
            keyAlias = "meteo"
            keyPassword = "meteopaysage"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
