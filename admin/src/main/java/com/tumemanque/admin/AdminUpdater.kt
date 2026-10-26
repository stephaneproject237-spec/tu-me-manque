package com.tumemanque.admin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

data class AdminUpdate(val code: Int, val name: String, val apk: String)

/** Même principe que l'application principale : un petit fichier JSON indique la dernière version disponible. */
object AdminUpdater {
    private const val BASE = "stephaneproject237-spec/tu-me-manque"

    @Suppress("DEPRECATION")
    fun currentCode(c: Context): Int = c.packageManager.getPackageInfo(c.packageName, 0).versionCode

    fun currentName(c: Context): String = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""

    private fun fetchOne(url: String): AdminUpdate? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.useCaches = false
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val code = j.optInt("versionCode", 0)
            val apk = j.optString("apk", "")
            if (code <= 0 || !apk.startsWith("https://github.com/$BASE/")) return null
            return AdminUpdate(code, j.optString("versionName", "1.$code"), apk)
        } finally {
            conn.disconnect()
        }
    }

    /** Interroge deux adresses en parallèle ; la première qui répond gagne. Renvoie (info, erreur). */
    fun check(onDone: (AdminUpdate?, String?) -> Unit) {
        thread {
            val t = System.currentTimeMillis()
            val urls = listOf(
                "https://stephaneproject237-spec.github.io/tu-me-manque/admin-update.json?t=$t",
                "https://raw.githubusercontent.com/$BASE/main/web/admin-update.json?t=$t",
            )
            val res = AtomicReference<AdminUpdate?>(null)
            val errs = java.util.Collections.synchronizedList(mutableListOf<String>())
            val latch = CountDownLatch(1)
            val got = java.util.concurrent.atomic.AtomicBoolean(false)
            urls.forEach { u ->
                thread {
                    try {
                        val r = fetchOne(u)
                        if (got.compareAndSet(false, true)) { res.set(r); latch.countDown() }
                    } catch (e: Exception) {
                        errs.add(u.substringAfter("://").substringBefore("/") + " : " + e.javaClass.simpleName)
                        if (errs.size == urls.size && got.compareAndSet(false, true)) latch.countDown()
                    }
                }
            }
            latch.await()
            val r = res.get()
            if (r == null && errs.size == urls.size) onDone(null, errs.joinToString("\n")) else onDone(r, null)
        }
    }

    fun download(c: Context, info: AdminUpdate, onProgress: (Int) -> Unit, onDone: (File?, String?) -> Unit) {
        val app = c.applicationContext
        thread {
            try {
                val dir = File(app.cacheDir, "update").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "tu-me-manque-admin.apk")
                val conn = URL(info.apk).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.instanceFollowRedirects = true
                    if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                    val total = conn.contentLengthLong
                    var done = 0L
                    var last = -1
                    conn.inputStream.use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val p = (done * 100 / total).toInt()
                                    if (p != last) { last = p; onProgress(p) }
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                if (out.length() < 100_000) throw IOException("fichier incomplet")
                onDone(out, null)
            } catch (e: Exception) {
                onDone(null, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Lance l'installateur d'Android (ou le réglage d'autorisation si nécessaire). Renvoie false si l'autorisation manque. */
    fun install(c: Context, f: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !c.packageManager.canRequestPackageInstalls()) {
            c.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)
        c.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
