package com.tumemanque.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Installation de la mise à jour par l'appli elle-même (PackageInstaller).
 * Android 12 et plus : si l'appli a déjà été installée/mise à jour de cette façon, aucune confirmation n'est demandée.
 * Sinon (ou si Android refuse), l'écran d'installation habituel s'ouvre.
 */
object Installer {
    const val ACTION = "com.tumemanque.app.INSTALL_RESULT"

    fun install(ctx: Context, f: File): Boolean = try {
        val pi = ctx.packageManager.packageInstaller
        val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        p.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(p)
        pi.openSession(id).use { s ->
            f.inputStream().use { i -> s.openWrite("app.apk", 0, f.length()).use { o -> i.copyTo(o); s.fsync(o) } }
            val intent = Intent(ACTION).setPackage(ctx.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
        true
    } catch (e: Exception) {
        false
    }

    /** L'ancienne méthode : ouvre l'installateur d'Android. */
    fun viewFallback(ctx: Context, f: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/** Résultat de l'installation envoyé par Android. */
class InstallResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_SUCCESS -> {}
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android demande une confirmation (appli pas encore « propriétaire » de ses mises à jour) : on l'affiche.
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                runCatching { context.startActivity(confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            else -> {
                val pr = context.getSharedPreferences("upd", Context.MODE_PRIVATE)
                val f = File(pr.getString("file", "") ?: "")
                if (f.exists()) Installer.viewFallback(context, f)
            }
        }
    }
}

/** La mise à jour vient d'être installée : on rouvre l'appli (ou, si Android l'interdit, une notification le propose). */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(launch) }
        val pi = PendingIntent.getActivity(context, 8, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("update", "Mises à jour", NotificationManager.IMPORTANCE_DEFAULT))
        val n = NotificationCompat.Builder(context, "update")
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle("Mise à jour installée")
            .setContentText("Touche pour rouvrir l'application.")
            .setContentIntent(pi).setAutoCancel(true).setTimeoutAfter(120_000).build()
        try { nm.notify(4243, n) } catch (e: SecurityException) { /* notifications refusées */ }
    }
}
