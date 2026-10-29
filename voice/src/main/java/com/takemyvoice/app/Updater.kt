package com.takemyvoice.app

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
import kotlin.concurrent.thread

data class Maj(val code: Int, val nom: String, val apk: String)

/** Mises à jour intégrées : un petit fichier JSON sur GitHub indique la dernière version. */
object Updater {
    private const val BASE = "stephaneproject237-spec/tu-me-manque"

    @Suppress("DEPRECATION")
    fun codeActuel(c: Context): Int = c.packageManager.getPackageInfo(c.packageName, 0).versionCode
    fun nomActuel(c: Context): String = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""

    fun verifier(fini: (Maj?, String?) -> Unit) {
        thread {
            try {
                val conn = URL("https://raw.githubusercontent.com/$BASE/main/tools/voice-android.json?t=${System.currentTimeMillis()}")
                    .openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 10000; conn.readTimeout = 10000; conn.useCaches = false
                    if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                    val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val code = j.optInt("versionCode", 0)
                    val apk = j.optString("apk", "")
                    if (code <= 0 || !apk.startsWith("https://github.com/$BASE/")) fini(null, null)
                    else fini(Maj(code, j.optString("versionName", "1.$code"), apk), null)
                } finally { conn.disconnect() }
            } catch (e: Exception) {
                fini(null, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun telecharger(c: Context, m: Maj, progression: (Int) -> Unit, fini: (File?, String?) -> Unit) {
        val app = c.applicationContext
        thread {
            try {
                val dir = File(app.cacheDir, "update").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "take-my-voice.apk")
                val conn = URL(m.apk).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 15000; conn.readTimeout = 30000; conn.instanceFollowRedirects = true
                    if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                    val total = conn.contentLengthLong
                    var fait = 0L; var dernier = -1
                    conn.inputStream.use { i ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = i.read(buf); if (n < 0) break
                                o.write(buf, 0, n); fait += n
                                if (total > 0) { val p = (fait * 100 / total).toInt(); if (p != dernier) { dernier = p; progression(p) } }
                            }
                        }
                    }
                } finally { conn.disconnect() }
                if (out.length() < 100_000) throw IOException("fichier incomplet")
                fini(out, null)
            } catch (e: Exception) {
                fini(null, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun installer(c: Context, f: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !c.packageManager.canRequestPackageInstalls()) {
            c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)
        c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
