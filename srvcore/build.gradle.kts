plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.freetune.srvcore"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Moteur de serveurs commun : « Free tune server », « Free tune server 2 » et les 2 serveurs cachés de Free tune.
    api("io.github.junkfood02.youtubedl-android:library:0.17.3")
    api("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.3")
}
