plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.gooseandroid"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.gooseandroid"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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

    kotlinOptions {
        jvmTarget = "17"
    }

    // Don't compress the native binary
    androidResources {
        noCompress += "so"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Google AI Edge LiteRT - on-device LLM inference
    // Uses Snapdragon 888's Hexagon DSP + Adreno 660 GPU for acceleration
    // TODO: Uncomment once LiteRT inference integration is implemented
    // implementation("com.google.ai.edge.litert:litert:1.0.1")
    // implementation("com.google.ai.edge.litert:litert-gpu:1.0.1")
    // implementation("com.google.ai.edge.litert:litert-support:1.0.1")
}
