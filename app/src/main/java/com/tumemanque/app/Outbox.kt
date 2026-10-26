package com.tumemanque.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Message écrit sans réseau : il attend ici d'être envoyé, même si l'appli est fermée. */
data class OutMsg(
    val id: String,
    val kind: String,
    val body: String?,
    val media: String?,      // lien déjà envoyé (GIF, ou fichier déjà téléversé)
    val file: String?,       // fichier local à téléverser (photo, vocal, vidéo)
    val ext: String,
    val mime: String,
    val w: Int,
    val h: Int,
    val dur: Int,
    val reply: String?,      // JSON de la réponse citée
    val createdAt: Long,
    val sms: String? = null, // identifiant du SMS de secours déjà envoyé
)

object Outbox {
    private val lock = Any()
    private var ctx: Context? = null

    fun init(context: Context) { ctx = context.applicationContext }
    private fun c(): Context = ctx ?: error("Outbox non initialisée")
    fun context(): Context = c()
    private fun listFile() = File(c().filesDir, "outbox.json")
    private fun dir() = File(c().filesDir, "outbox").apply { mkdirs() }

    private fun read(): MutableList<OutMsg> {
        val f = listFile()
        if (!f.exists()) return mutableListOf()
        return try {
            val a = JSONArray(f.readText())
            MutableList(a.length()) {
                val o = a.getJSONObject(it)
                OutMsg(
                    o.getString("id"), o.getString("kind"),
                    if (o.isNull("body")) null else o.getString("body"),
                    if (o.isNull("media")) null else o.getString("media"),
                    if (o.isNull("file")) null else o.getString("file"),
                    o.optString("ext"), o.optString("mime"),
                    o.optInt("w"), o.optInt("h"), o.optInt("dur"),
                    if (o.isNull("reply")) null else o.getString("reply"),
                    o.optLong("createdAt"),
                    if (o.isNull("sms") || !o.has("sms")) null else o.getString("sms"),
                )
            }
        } catch (e: Exception) { mutableListOf() }
    }

    private fun write(list: List<OutMsg>) {
        val a = JSONArray()
        for (m in list) a.put(
            JSONObject().put("id", m.id).put("kind", m.kind).put("body", m.body ?: JSONObject.NULL)
                .put("media", m.media ?: JSONObject.NULL).put("file", m.file ?: JSONObject.NULL)
                .put("ext", m.ext).put("mime", m.mime).put("w", m.w).put("h", m.h).put("dur", m.dur)
                .put("reply", m.reply ?: JSONObject.NULL).put("createdAt", m.createdAt).put("sms", m.sms ?: JSONObject.NULL)
        )
        listFile().writeText(a.toString())
    }

    fun all(): List<OutMsg> = synchronized(lock) { read() }

    fun add(m: OutMsg) = synchronized(lock) { val l = read(); l.add(m); write(l) }

    fun remove(id: String) = synchronized(lock) {
        val l = read()
        l.firstOrNull { it.id == id }?.file?.let { runCatching { File(it).delete() } }
        l.removeAll { it.id == id }
        write(l)
    }

    fun stash(bytes: ByteArray, ext: String): String {
        val f = File(dir(), UUID.randomUUID().toString() + "." + ext)
        f.writeBytes(bytes)
        return f.absolutePath
    }

    fun stashCopy(src: File, ext: String): String {
        val f = File(dir(), UUID.randomUUID().toString() + "." + ext)
        src.copyTo(f, overwrite = true)
        return f.absolutePath
    }

    /** Programme l'envoi dès qu'une connexion est disponible (fonctionne appli fermée). */
    fun schedule() {
        val req = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(c()).enqueueUniqueWork("outbox", ExistingWorkPolicy.KEEP, req)
    }
}

class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private enum class R { OK, DROP, RETRY }

    override suspend fun doWork(): Result {
        Backend.init(applicationContext)
        Outbox.init(applicationContext)
        if (!Backend.hasSession()) return Result.success()
        while (true) {
            val m = Outbox.all().firstOrNull() ?: break
            when (sendOne(m)) {
                R.OK, R.DROP -> Outbox.remove(m.id)
                R.RETRY -> {
                    if (runAttemptCount >= 60) { Outbox.remove(m.id); continue }
                    return Result.retry()
                }
            }
        }
        return Result.success()
    }

    private suspend fun sendOne(m: OutMsg): R {
        return try {
            var media = m.media
            if (media == null && m.file != null) {
                val f = File(m.file)
                if (!f.exists()) return R.DROP
                media = Backend.uploadFile(f, m.ext, m.mime)
            }
            val meta = JSONObject()
            if (m.w > 0) meta.put("w", m.w)
            if (m.h > 0) meta.put("h", m.h)
            if (m.dur > 0) meta.put("d", m.dur)
            m.reply?.let { runCatching { meta.put("reply", JSONObject(it)) } }
            m.sms?.let { meta.put("sms", it) }
            val r = Backend.sendMessage(m.kind, m.body, media, if (meta.length() == 0) null else meta)
            if (r.status == "ok") R.OK else R.DROP
        } catch (e: Exception) {
            val t = e.message ?: ""
            val permanent = t.startsWith("HTTP 4") && !t.startsWith("HTTP 401") && !t.startsWith("HTTP 408") && !t.startsWith("HTTP 429")
            if (permanent) R.DROP else R.RETRY
        }
    }
}
