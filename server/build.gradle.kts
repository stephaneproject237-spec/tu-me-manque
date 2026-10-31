plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.freetune.server"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.freetune.server"
        minSdk = 24
        targetSdk = 35
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = build
        versionName = "1.$build"
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

    // L'interface est EXACTEMENT celle des logiciels Mac et Windows (windows/renderer) : on la copie dans l'application.
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/uiAssets"))
}

val copyUiAssets by tasks.registering(Copy::class) {
    from("../windows/renderer") { into("ui") }
    into(layout.buildDirectory.dir("generated/uiAssets"))
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(copyUiAssets) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Recherche et téléchargements YouTube (yt-dlp + FFmpeg embarqués), comme dans Free tune.
    implementation("io.github.junkfood02.youtubedl-android:library:0.17.3")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.3")
}
