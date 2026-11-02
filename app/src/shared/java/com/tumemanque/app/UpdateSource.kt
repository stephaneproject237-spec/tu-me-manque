package com.tumemanque.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue

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
     * L'attente est limitée : une connexion bloquée par l'opérateur ne doit jamais laisser la vérification tourner sans fin.
     */
    suspend fun fetch(): UpdateInfo? {
        val t = System.currentTimeMillis()
        val sources = listOf(
            Config.WEB_URL + "update.json?t=$t",
            "https://raw.githubusercontent.com/stephaneproject237-spec/tu-me-manque/main/${BuildConfig.UPDATE_JSON}?t=$t",
        )
        val answers = ConcurrentLinkedQueue<Pair<String, Result<UpdateInfo?>>>()
        // Portée séparée : un appel réseau bloqué n'est pas interruptible et ne doit pas retenir la réponse.
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val jobs = sources.map { url -> scope.launch { answers.add(url to runCatching { fetchOne(url) }) } }
        withTimeoutOrNull(MAX_WAIT_MS) { jobs.joinAll() }
        val got = answers.toList()
        if (got.none { it.second.isSuccess }) {
            val detail = got.joinToString("\n") { (url, r) ->
                val e = r.exceptionOrNull()
                val host = url.substringAfter("://").substringBefore("/")
                "$host : ${e?.javaClass?.simpleName}${e?.message?.let { m -> " ($m)".take(60) } ?: ""}"
            }
            throw IOException(detail.ifEmpty { "Aucune réponse du serveur de mises à jour." })
        }
        return got.mapNotNull { it.second.getOrNull() }.maxByOrNull { it.code }
    }

    private const val MAX_WAIT_MS = 12_000L
}
