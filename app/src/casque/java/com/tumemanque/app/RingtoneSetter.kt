package com.tumemanque.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import kotlin.concurrent.thread

/** Copie un morceau de Casque dans le dossier « Sonneries » du téléphone et le définit comme sonnerie par défaut. */
object RingtoneSetter {
    private val main = Handler(Looper.getMainLooper())
    private fun toast(ctx: Context, msg: String) = main.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }

    fun set(ctx: Context, t: Track) {
        // Modifier la sonnerie par défaut du système exige une permission spéciale, accordée depuis un écran
        // système (pas une simple boîte de dialogue). On y envoie l'utilisateur s'il ne l'a pas encore donnée.
        if (!Settings.System.canWrite(ctx)) {
            toast(ctx, "Autorise Free tune à modifier les réglages, puis réessaie.")
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        thread {
            runCatching {
                val resolver = ctx.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.TITLE, t.title)
                    put(MediaStore.Audio.Media.DISPLAY_NAME, t.title)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/*")
                    put(MediaStore.Audio.Media.IS_RINGTONE, true)
                    put(MediaStore.Audio.Media.IS_MUSIC, false)
                    put(MediaStore.Audio.Media.IS_NOTIFICATION, false)
                    put(MediaStore.Audio.Media.IS_ALARM, false)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_RINGTONES)
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }
                }
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val newUri = resolver.insert(collection, values)
                    ?: error("insert")
                resolver.openOutputStream(newUri)?.use { out ->
                    resolver.openInputStream(t.uri)?.use { it.copyTo(out) } ?: error("source")
                } ?: error("dest")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    resolver.update(newUri, values, null, null)
                }
                RingtoneManager.setActiveDefaultRingtoneUri(ctx, RingtoneManager.TYPE_RINGTONE, newUri)
                toast(ctx, "« ${t.title} » est maintenant ta sonnerie.")
            }.onFailure {
                toast(ctx, "Impossible de définir cette sonnerie.")
            }
        }
    }
}
