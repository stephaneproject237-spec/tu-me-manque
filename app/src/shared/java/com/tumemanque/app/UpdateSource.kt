package com.tumemanque.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Lit le fichier update.json (utilisé par l'appli ouverte, par la vérification automatique et par les notifications silencieuses). */
object UpdateSource {
    private fun fetchOne(url: String): UpdateInfo? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.useCaches = false
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val code = j.optInt("versionCode", 0)
            val apk = j.optString("apk", "")
            if (code <= 0 || !apk.startsWith("https://github.com/stephaneproject237-spec/tu-me-manque/")) return null
            return UpdateInfo(code, j.optString("versionName", "1.$code"), j.optString("notes", ""), apk)
        } finally {
            conn.disconnect()
        }
    }

    /** Interroge plusieurs adresses en même temps (au cas où l'une serait bloquée sur ce réseau) : la première qui répond gagne. */
    suspend fun fetch(): UpdateInfo? = coroutineScope {
        val t = System.currentTimeMillis()
        val sources = listOf(
            Config.WEB_URL + "update.json?t=$t",
            "https://raw.githubusercontent.com/stephaneproject237-spec/tu-me-manque/main/${BuildConfig.UPDATE_JSON}?t=$t",
        )
        val result = CompletableDeferred<UpdateInfo?>()
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val jobs = sources.map { url ->
            launch(Dispatchers.IO) {
                try {
                    result.complete(fetchOne(url))
                } catch (e: Exception) {
                    val host = url.substringAfter("://").substringBefore("/")
                    errors.add("$host : ${e.javaClass.simpleName}${e.message?.let { m -> " ($m)".take(60) } ?: ""}")
                    if (errors.size == sources.size) result.completeExceptionally(IOException(errors.joinToString("\n")))
                }
            }
        }
        try { result.await() } finally { jobs.forEach { it.cancel() } }
    }

}
