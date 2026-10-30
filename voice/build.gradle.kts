plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.takemyvoice.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.takemyvoice.app"
        minSdk = 24
        targetSdk = 35
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = build
        versionName = "1.$build"
        // yt-dlp + Python + FFmpeg embarqués : téléphones ARM uniquement.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Les bibliothèques natives du moteur YouTube doivent être extraites à l'installation.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    buildTypes {
        getByName("debug") { isDebuggable = false }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Recherche et extraction de l'audio YouTube (mêmes versions que Free tune).
    implementation("io.github.junkfood02.youtubedl-android:library:0.17.3")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.3")
}
