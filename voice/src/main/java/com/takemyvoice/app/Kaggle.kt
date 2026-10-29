package com.takemyvoice.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class KaggleException(message: String, val http: Int = 0) : IOException(message)

/**
 * Petit client de l'API officielle Kaggle (https://api.kaggle.com/v1/<service>/<méthode>).
 * Accepte l'ancienne clé (kaggle.json : nom + clé) ou le nouveau jeton « KGAT_… ».
 */
class Kaggle(val user: String, private val key: String) {

    private val auth: String =
        if (key.startsWith("KGAT_")) "Bearer $key"
        else "Basic " + Base64.encodeToString("$user:$key".toByteArray(), Base64.NO_WRAP)

    fun call(service: String, method: String, body: JSONObject = JSONObject()): JSONObject {
        val conn = URL("https://api.kaggle.com/v1/$service/$method").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", auth)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "kaggle-api/v1.7.0 take-my-voice")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try { JSONObject(text).optString("message", text) } catch (_: Exception) { text }
                throw KaggleException(msg.ifBlank { "HTTP $code" }.take(400), code)
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    // ---------- Compte ----------

    /** Vrai nom d'utilisateur Kaggle lié au jeton « KGAT_… » (celui qu'il faut dans toutes les adresses), ou null. */
    fun vraiNom(): String? {
        if (!key.startsWith("KGAT_")) return null
        val r = call("security.OAuthService", "IntrospectToken", JSONObject().put("token", key))
        return r.optString("username").trim().takeIf { r.optBoolean("active", true) && it.isNotBlank() }
    }

    /** Heures de GPU utilisées / autorisées cette semaine (sert aussi à tester la connexion). */
    fun quota(): JSONObject {
        val r = call("kernels.KernelsApiService", "GetAcceleratorQuotaStatistics")
        val gpu = r.optJSONObject("gpuQuota") ?: JSONObject()
        return JSONObject()
            .put("utilise_h", duree(gpu.optString("timeUsed")) / 3600.0)
            .put("total_h", duree(gpu.optString("totalTimeAllowed")) / 3600.0)
            .put("recharge", r.optString("quotaRefreshTime"))
    }

    private fun duree(s: String): Double = s.removeSuffix("s").toDoubleOrNull() ?: 0.0

    // ---------- Fichiers et jeux de données ----------

    /** Envoie un fichier vers le stockage Kaggle et renvoie le jeton à joindre au jeu de données. */
    fun envoyerFichier(f: File, type: String, progression: (Long) -> Unit): String {
        val start = call("blobs.BlobApiService", "StartBlobUpload", JSONObject()
            .put("type", "DATASET")
            .put("name", f.name)
            .put("contentType", type)
            .put("contentLength", f.length())
            .put("lastModifiedEpochSeconds", f.lastModified() / 1000))
        val url = start.getString("createUrl")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.connectTimeout = 20000
            conn.readTimeout = 120000
            conn.setFixedLengthStreamingMode(f.length())
            conn.setRequestProperty("Content-Type", type)
            if (f.length() > 0) conn.setRequestProperty("Content-Range", "bytes 0-${f.length() - 1}/${f.length()}")
            conn.outputStream.use { out ->
                f.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var envoye = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        envoye += n
                        progression(envoye)
                    }
                }
            }
            if (conn.responseCode !in 200..299) throw KaggleException("Envoi refusé (HTTP ${conn.responseCode})", conn.responseCode)
        } finally {
            conn.disconnect()
        }
        return start.getString("token")
    }

    /** Crée le jeu de données privé, ou en publie une nouvelle version s'il existe déjà. */
    fun publierJeu(slug: String, titre: String, jetons: List<String>) {
        val files = JSONArray().apply { jetons.forEach { put(JSONObject().put("token", it)) } }
        var r: JSONObject? = null
        var existe = false
        try {
            r = call("datasets.DatasetApiService", "CreateDataset", JSONObject()
                .put("ownerSlug", user).put("slug", slug).put("title", titre)
                .put("licenseName", "CC0-1.0").put("isPrivate", true).put("files", files))
            val err = r.optString("error")
            if (err.isNotBlank()) {
                if (err.contains("already", true) || err.contains("exist", true) || err.contains("in use", true)) existe = true
                else throw KaggleException(err)
            }
        } catch (e: KaggleException) {
            if (e.http == 409 || e.message?.contains("exist", true) == true || e.message?.contains("already", true) == true) existe = true
            else throw e
        }
        if (existe) {
            val v = call("datasets.DatasetApiService", "CreateDatasetVersion", JSONObject()
                .put("ownerSlug", user).put("datasetSlug", slug)
                .put("body", JSONObject().put("versionNotes", "Nouveaux enregistrements")
                    .put("deleteOldVersions", true).put("files", files)))
            val err = v.optString("error")
            if (err.isNotBlank()) throw KaggleException(err)
        }
    }

    fun etatJeu(slug: String): String =
        call("datasets.DatasetApiService", "GetDatasetStatus", JSONObject()
            .put("ownerSlug", user).put("datasetSlug", slug)).optString("status", "NOT_YET_PERSISTED")

    fun supprimerJeu(slug: String) {
        call("datasets.DatasetApiService", "DeleteDataset", JSONObject().put("ownerSlug", user).put("datasetSlug", slug))
    }

    // ---------- Scripts (« kernels ») sur GPU ----------

    fun lancerScript(slug: String, titre: String, code: String, jeux: List<String>, scripts: List<String>, delaiMax: Int) {
        val r = call("kernels.KernelsApiService", "SaveKernel", JSONObject()
            .put("slug", "$user/$slug")
            .put("newTitle", titre)
            .put("text", code)
            .put("language", "python")
            .put("kernelType", "script")
            .put("isPrivate", true)
            .put("enableGpu", true)
            .put("enableInternet", true)
            .put("machineShape", "NvidiaTeslaT4")
            .put("sessionTimeoutSeconds", delaiMax)
            .put("datasetDataSources", JSONArray(jeux.map { "$user/$it" }))
            .put("kernelDataSources", JSONArray(scripts.map { "$user/$it" })))
        val err = r.optString("error")
        if (err.isNotBlank()) throw KaggleException(err)
        val invalides = (r.optJSONArray("invalidDatasetSources")?.length() ?: 0) + (r.optJSONArray("invalidKernelSources")?.length() ?: 0)
        if (invalides > 0) throw KaggleException("Source introuvable sur Kaggle (voix pas encore prête ?)")
    }

    /** QUEUED, RUNNING, COMPLETE, ERROR, CANCEL_REQUESTED, CANCEL_ACKNOWLEDGED… */
    fun etatScript(slug: String): Pair<String, String> {
        val r = call("kernels.KernelsApiService", "GetKernelSessionStatus", JSONObject()
            .put("userName", user).put("kernelSlug", slug))
        return r.optString("status", "QUEUED") to r.optString("failureMessage", "")
    }

    /** Fichiers produits par la dernière exécution : nom -> adresse de téléchargement. */
    fun sorties(slug: String): Map<String, String> {
        val r = call("kernels.KernelsApiService", "ListKernelSessionOutput", JSONObject()
            .put("userName", user).put("kernelSlug", slug).put("pageSize", 50))
        val out = LinkedHashMap<String, String>()
        val arr = r.optJSONArray("files") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val nom = o.optString("fileName").substringAfterLast('/')
            if (nom.isNotBlank() && o.optString("url").isNotBlank()) out[nom] = o.optString("url")
        }
        return out
    }

    fun supprimerScript(slug: String) {
        call("kernels.KernelsApiService", "DeleteKernel", JSONObject().put("userName", user).put("kernelSlug", slug))
    }

    /** Télécharge une sortie (adresse signée, sinon avec identification). */
    fun telecharger(url: String, dest: File) {
        for (avecAuth in listOf(false, true)) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 20000
                conn.readTimeout = 120000
                conn.instanceFollowRedirects = true
                if (avecAuth) conn.setRequestProperty("Authorization", auth)
                val code = conn.responseCode
                if (code in 200..299) {
                    val tmp = File(dest.path + ".part")
                    conn.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    tmp.renameTo(dest)
                    return
                }
                if (avecAuth || (code != 401 && code != 403)) throw KaggleException("Téléchargement impossible (HTTP $code)", code)
            } finally {
                conn.disconnect()
            }
        }
    }

    fun texte(url: String): String {
        val f = File.createTempFile("tmv", ".txt")
        try { telecharger(url, f); return f.readText() } finally { f.delete() }
    }
}
