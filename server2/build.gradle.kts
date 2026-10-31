plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.freetune.server"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.freetune.server2"
        minSdk = 24
        targetSdk = 35
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = build
        versionName = "1.$build"
        buildConfigField("int", "AUTO_SERVERS", "4")
        buildConfigField("String", "UPD_PREFIX", "\"server2\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        getByName("debug") { isDebuggable = false }
    }
    lint { checkReleaseBuilds = false; abortOnError = false }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // Les bibliothèques natives du moteur (Python, yt-dlp, FFmpeg) doivent être extraites à l'installation.
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*", "/META-INF/DEPENDENCIES", "/META-INF/*.kotlin_module", "/kotlin/**", "/DebugProbesKt.bin")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }

    // Le reste du code (service, démarrage, état) est celui de « Free tune server » : on partage ses sources.
    sourceSets.getByName("main").java.srcDir("../server/src/main/java")
}

dependencies {
    implementation(project(":srvcore"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
