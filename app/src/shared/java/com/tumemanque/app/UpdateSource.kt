package com.tumemanque.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    /**
     * Interroge plusieurs adresses en même temps (au cas où l'une serait bloquée sur ce réseau) et garde la version
     * la plus récente : une adresse en retard ne doit jamais masquer une mise à jour annoncée par une autre.
     */
    suspend fun fetch(): UpdateInfo? = coroutineScope {
        val t = System.currentTimeMillis()
        val sources = listOf(
            Config.WEB_URL + "update.json?t=$t",
            "https://raw.githubusercontent.com/stephaneproject237-spec/tu-me-manque/main/${BuildConfig.UPDATE_JSON}?t=$t",
        )
        val answers = sources.map { url -> async(Dispatchers.IO) { url to runCatching { fetchOne(url) } } }.awaitAll()
        if (answers.all { it.second.isFailure }) {
            throw IOException(answers.joinToString("\n") { (url, r) ->
                val e = r.exceptionOrNull()
                val host = url.substringAfter("://").substringBefore("/")
                "$host : ${e?.javaClass?.simpleName}${e?.message?.let { m -> " ($m)".take(60) } ?: ""}"
            })
        }
        answers.mapNotNull { it.second.getOrNull() }.maxByOrNull { it.code }
    }

}
