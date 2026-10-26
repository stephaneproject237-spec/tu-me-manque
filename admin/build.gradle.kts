plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tumemanque.admin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tumemanque.admin"
        minSdk = 21
        targetSdk = 35
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = build
        versionName = "1.$build"
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

    // La page admin du site est embarquée dans l'application (elle marche même si le site est injoignable).
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/adminAssets"))
}

val copyAdminAssets by tasks.registering(Copy::class) {
    from("../web/admin") { into("admin") }
    from("../web/config.js")
    into(layout.buildDirectory.dir("generated/adminAssets"))
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(copyAdminAssets) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.11.0")
}
