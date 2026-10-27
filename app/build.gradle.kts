import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.tumemanque.app"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        targetSdk = 35
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = build
        versionName = "1.$build"
        buildConfigField("String", "APK_URL", "\"${System.getenv("APK_URL") ?: ""}\"")
    }

    flavorDimensions += "brand"
    productFlavors {
        // « Tu me manques » : l'application d'origine (code partagé tel quel).
        create("tmm") {
            dimension = "brand"
            applicationId = "com.tumemanque.app"
            buildConfigField("boolean", "CASQUE", "false")
            buildConfigField("String", "UPDATE_JSON", "\"web/update.json\"")
            buildConfigField("String", "APK_PREFIX", "\"tu-me-manque\"")
            buildConfigField("String", "WEB_URL", "\"https://stephaneproject237-spec.github.io/tu-me-manque/\"")
            buildConfigField("String", "OAUTH_SCHEME", "\"tumemanque\"")
            manifestPlaceholders["scheme"] = "tumemanque"
        }
        // « Casque » : application parallèle (lecteur audio + messagerie en noir / gris / bleu).
        create("casque") {
            dimension = "brand"
            applicationId = "com.casque.app"
            buildConfigField("boolean", "CASQUE", "true")
            buildConfigField("String", "UPDATE_JSON", "\"web/casque/update.json\"")
            buildConfigField("String", "APK_PREFIX", "\"casque\"")
            buildConfigField("String", "WEB_URL", "\"https://stephaneproject237-spec.github.io/tu-me-manque/casque/\"")
            buildConfigField("String", "OAUTH_SCHEME", "\"casque\"")
            manifestPlaceholders["scheme"] = "casque"
        }
    }

    // Le code partagé sert tel quel à « Tu me manques » ; « Casque » reçoit la même copie habillée en bleu/gris (tools/skin.py).
    sourceSets {
        getByName("tmm").java.srcDir("src/shared/java")
        getByName("casque").java.srcDir(layout.buildDirectory.dir("skin/java"))
        getByName("casque").assets.srcDir(layout.buildDirectory.dir("skin/assets"))
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// La page d'appel de l'appli réutilise exactement le code web des appels.
val syncCallAssets by tasks.registering(Copy::class) {
    from("../web/appel.js", "../web/appel.css", "../web/photoedit.js", "../web/ios.js", "../web/photoedit.css", "../web/emoji.js")
    into("src/main/assets/call")
}
val skinCasque by tasks.registering(Exec::class) {
    workingDir = rootDir
    commandLine("python3", "tools/skin.py")
    inputs.dir("src/shared/java")
    inputs.dir("../web")
    inputs.file("../tools/skin.py")
    outputs.dir(layout.buildDirectory.dir("skin"))
}
tasks.named("preBuild") { dependsOn(syncCallAssets, skinCasque) }

// Media3 marque une partie de son API « instable » : on l'accepte pour « Casque ».
tasks.withType<KotlinCompile>().configureEach {
    if (name.contains("Casque")) compilerOptions.freeCompilerArgs.add("-opt-in=androidx.media3.common.util.UnstableApi")
}

dependencies {
    // Firebase sert uniquement aux notifications (gratuit, sans carte bancaire).
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Lecteur audio de « Casque » (lecture en arrière-plan, notification, écran verrouillé, Bluetooth).
    "casqueImplementation"("androidx.media3:media3-exoplayer:1.4.1")
    "casqueImplementation"("androidx.media3:media3-session:1.4.1")
    "casqueImplementation"("androidx.documentfile:documentfile:1.0.1")
}
