package com.tumemanque.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder

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

class App : Application(), ImageLoaderFactory {
    /** Coil sait aussi afficher les GIF animés. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }
        .crossfade(true)
        .build()

    override fun onCreate() {
        super.onCreate()
        Backend.init(this)
        createNotificationChannel(this)
        CrashLog.install(this)
    }
}

/** Garde la dernière erreur fatale pour l'afficher au prochain lancement (aide à comprendre un plantage). */
object CrashLog {
    private const val FILE = "crash"
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                app.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                    .putString("trace", android.util.Log.getStackTraceString(e).take(3500)).commit()
            } catch (_: Throwable) { }
            previous?.uncaughtException(t, e)
        }
    }
    fun take(context: Context): String? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val t = prefs.getString("trace", null)
        if (t != null) prefs.edit().remove("trace").apply()
        return t
    }
}
