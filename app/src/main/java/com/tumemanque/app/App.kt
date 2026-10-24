package com.tumemanque.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build

/** Nouveau canal : la sonnerie et les vibrations d'un canal existant ne peuvent plus être modifiées. */
const val CHANNEL_ID = "miss_v2"
const val OLD_CHANNEL_ID = "miss"

/** Pause, vibration, pause, vibration, pause, longue vibration : un rythme de « battement de cœur ». */
val VIBRATION_PATTERN = longArrayOf(0, 300, 120, 300, 120, 600)

fun soundUri(context: Context): Uri =
    Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.tu_me_manques}")

fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.deleteNotificationChannel(OLD_CHANNEL_ID)
    val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    val channel = NotificationChannel(
        CHANNEL_ID,
        "Tu me manques",
        NotificationManager.IMPORTANCE_HIGH
    ).apply {
        description = "Les messages de ton partenaire"
        setSound(soundUri(context), attributes)
        enableVibration(true)
        vibrationPattern = VIBRATION_PATTERN
    }
    manager.createNotificationChannel(channel)
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Backend.init(this)
        createNotificationChannel(this)
    }
}
