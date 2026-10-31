package com.freetune.server

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

class HttpRes(val code: Int, val text: String)

object Http {
    fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?, readTimeout: Int = 60000): HttpRes {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20000
            c.readTimeout = readTimeout
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val st = if (code in 200..299) c.inputStream else c.errorStream
            val text = st?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            return HttpRes(code, text)
        } finally {
            c.disconnect()
        }
    }

    fun hex(n: Int): String {
        val b = ByteArray(n); SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /** Lit une réponse JSON quelconque (objet, tableau ou rien). */
    fun parse(t: String): Any? {
        val s = t.trim()
        return try {
            if (s.startsWith("{")) JSONObject(s) else if (s.startsWith("[")) JSONArray(s) else null
        } catch (e: Exception) { null }
    }
}

/** Un compte serveur (pseudo + code) : session, appels de fonctions, envoi de fichiers. */
class Client(val username: String, private val code: String) {
    private var access = ""
    private var refresh = ""
    private var expires = 0L
    var uid = ""
        private set

    private fun hdr(token: String?, ct: String = "application/json"): Map<String, String> {
        val m = HashMap<String, String>()
        m["apikey"] = Cfg.KEY
        m["Content-Type"] = ct
        m["Accept"] = "application/json"
        if (token != null) m["Authorization"] = "Bearer $token"
        return m
    }

    private fun save(j: JSONObject) {
        access = j.getString("access_token")
        refresh = j.optString("refresh_token")
        expires = System.currentTimeMillis() / 1000 + j.optLong("expires_in", 3600)
        val u = j.optJSONObject("user")
        if (u != null && u.optString("id").isNotEmpty()) uid = u.optString("id")
    }

    @Synchronized
    fun ensure(force: Boolean = false) {
        val now = System.currentTimeMillis() / 1000
        if (!force && access.isNotEmpty() && expires > now + 60) return
        if (refresh.isNotEmpty()) {
            val r = Http.request("POST", Cfg.URL + "/auth/v1/token?grant_type=refresh_token", hdr(null), JSONObject().put("refresh_token", refresh).toString().toByteArray())
            if (r.code in 200..299) { save(JSONObject(r.text)); return }
        }
        login()
    }

    fun login() {
        val body = JSONObject().put("email", "$username@${Cfg.SRV_DOMAIN}").put("password", code).toString().toByteArray()
        val r = Http.request("POST", Cfg.URL + "/auth/v1/token?grant_type=password", hdr(null), body)
        if (r.code !in 200..299) {
            var m = r.text
            try { val j = JSONObject(r.text); m = j.optString("error_description").ifEmpty { j.optString("msg", m) } } catch (e: Exception) { /* texte brut */ }
            throw Exception("HTTP ${r.code} : $m")
        }
        save(JSONObject(r.text))
    }

    private fun authed(method: String, path: String, body: ByteArray?, ct: String = "application/json", extra: Map<String, String> = emptyMap()): HttpRes {
        ensure(false)
        fun go(): HttpRes = Http.request(method, Cfg.URL + path, hdr(access, ct) + extra, body)
        var r = go()
        if (r.code == 401) { ensure(true); r = go() }
        if (r.code !in 200..299) throw Exception("HTTP ${r.code} : ${r.text.take(200)}")
        return r
    }

    /** Les fonctions de Free tune portent le préfixe c_ dans la base. */
    fun rpc(name: String, args: JSONObject = JSONObject()): Any? =
        Http.parse(authed("POST", "/rest/v1/rpc/c_$name", args.toString().toByteArray()).text)

    fun rpcObj(name: String, args: JSONObject = JSONObject()): JSONObject? = rpc(name, args) as? JSONObject
    fun rpcArr(name: String, args: JSONObject = JSONObject()): JSONArray = (rpc(name, args) as? JSONArray) ?: JSONArray()

    /** Envoie un fichier dans le stockage public « chat », dans le dossier du compte. Renvoie le chemin. */
    fun upload(data: ByteArray, ext: String, mime: String): String {
        ensure(false)
        val path = uid + "/" + Http.hex(16) + "." + ext
        authed("POST", "/storage/v1/object/chat/$path", data, mime, mapOf("x-upsert" to "false"))
        return path
    }
}
