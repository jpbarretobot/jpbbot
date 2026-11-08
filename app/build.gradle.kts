plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.studiocamera"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.studiocamera.app"
        // Camera2 (contrôles manuels, RAW/DNG, zoom par CONTROL_ZOOM_RATIO) et ExifInterface framework : API 26+.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables.useSupportLibrary = false
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = false
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }

    // Les modèles TFLite (.tflite) ne doivent pas être compressés pour un chargement mmap.
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // IA embarquée (modèles inclus dans l'APK, traitement 100 % local).
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta4")

    // Moteur d'inférence pour les modèles de segmentation / profondeur fournis par l'utilisateur.
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    // Tests unitaires des algorithmes purs (JVM, sans appareil).
    testImplementation("junit:junit:4.13.2")
}
