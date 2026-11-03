plugins {
    // AGP 9 compiles Kotlin with its built-in Kotlin support; no separate kotlin-android plugin.
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.eagertolearn.bookscanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.eagertolearn.bookscanner"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // AndroidX & Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Coroutines & WorkManager Background Processing
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.work.runtime.ktx)

    // Google ML Kit Text Recognition (On-device OCR)
    implementation(libs.mlkit.text.recognition)

    // PDFBox Android (Searchable PDF Assembly)
    implementation(libs.pdfbox.android)

    // Network / Sync Fallback
    implementation(libs.okhttp)

    // OpenCV Android SDK (Maven Central 4.9.0+ AAR)
    implementation(libs.opencv)

    testImplementation(libs.junit)
}
