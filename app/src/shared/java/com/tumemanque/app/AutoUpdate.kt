package com.tumemanque.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Mise à jour automatique : dès qu'une nouvelle version est publiée, son téléchargement démarre tout seul
 * (appli ouverte ou fermée). Quand il est terminé, une fenêtre « Installer la mise à jour » s'affiche à l'ouverture.
 */
object AutoUpdate {
    private fun prefs(c: Context) = c.getSharedPreferences("upd", Context.MODE_PRIVATE)
    private fun dlDir(c: Context): File = (c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(c.filesDir, "dl")).apply { mkdirs() }

    /** Lance le téléchargement du système (continue en arrière-plan, sans notification). */
    fun enqueue(app: Context, info: UpdateInfo): Pair<Long, File> {
        val dm = app.getSystemService(DownloadManager::class.java)
        val old = prefs(app).getLong("id", -1)
        if (old >= 0) runCatching { dm.remove(old) }
        val dir = dlDir(app)
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "${BuildConfig.APK_PREFIX}-${info.code}.apk")
        val req = DownloadManager.Request(Uri.parse(info.apk))
            .setTitle("Mise à jour ${info.name}")
            .setDescription("Téléchargement de la mise à jour")
            .setDestinationUri(Uri.fromFile(f))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            .setAllowedOverMetered(true).setAllowedOverRoaming(true)
        val id = dm.enqueue(req)
        prefs(app).edit().putLong("id", id).putString("file", f.path).putInt("code", info.code).apply()
        return id to f
    }

    /** Y a-t-il déjà un fichier complet ou un téléchargement en cours pour cette version ? */
    private fun alreadyHandled(app: Context, info: UpdateInfo): Boolean {
        val pr = prefs(app)
        if (pr.getInt("code", 0) != info.code) return false
        val id = pr.getLong("id", -1)
        if (id < 0) return false
        val dm = app.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) {
                val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                return st != DownloadManager.STATUS_FAILED
            }
        }
        return false
    }

    /** Vérifie et, s'il y a une nouvelle version, démarre son téléchargement. */
    suspend fun kick(ctx: Context) {
        val app = ctx.applicationContext
        val pr = prefs(app)
        val now = System.currentTimeMillis()
        if (now - pr.getLong("kick_at", 0) < 90_000) return
        pr.edit().putLong("kick_at", now).apply()
        val info = runCatching { UpdateSource.fetch() }.getOrNull() ?: return
        if (info.code <= BuildConfig.VERSION_CODE) return
        if (alreadyHandled(app, info)) return
        runCatching { enqueue(app, info) }
    }

    /** Pour les notifications silencieuses (ne bloque jamais l'appelant). */
    fun kickAsync(ctx: Context) {
        val app = ctx.applicationContext
        CoroutineScope(Dispatchers.IO).launch { kick(app) }
    }

    /** Vérification toutes les 15 minutes, même appli fermée (dès qu'il y a internet). */
    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork("auto-update", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class UpdateWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        AutoUpdate.kick(applicationContext)
        runCatching { engineMaintenance(applicationContext) }
        return Result.success()
    }
}
