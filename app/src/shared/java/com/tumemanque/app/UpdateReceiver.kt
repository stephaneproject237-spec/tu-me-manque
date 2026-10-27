package com.tumemanque.app

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File

/** Le téléchargement de la mise à jour est terminé (même appli fermée) : une notification propose d'installer. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val pr = context.getSharedPreferences("upd", Context.MODE_PRIVATE)
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id < 0 || id != pr.getLong("id", -2)) return
        val f = File(pr.getString("file", "") ?: "")
        if (!f.exists() || f.length() < 1_000_000) return
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, 7, view, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("update", "Mises à jour", NotificationManager.IMPORTANCE_DEFAULT))
        val n = NotificationCompat.Builder(context, "update")
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle("Mise à jour prête")
            .setContentText("Touche pour installer la nouvelle version de Tu me manques.")
            .setContentIntent(pi).setAutoCancel(true).build()
        try { nm.notify(4242, n) } catch (e: SecurityException) { /* notifications refusées */ }
    }
}
