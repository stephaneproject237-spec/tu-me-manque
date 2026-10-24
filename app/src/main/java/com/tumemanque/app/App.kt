package com.tumemanque.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

const val CHANNEL_ID = "miss"

fun createNotificationChannel(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    val channel = NotificationChannel(
        CHANNEL_ID,
        "Tu me manques",
        NotificationManager.IMPORTANCE_HIGH
    ).apply {
        description = "Les messages de ton partenaire"
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
