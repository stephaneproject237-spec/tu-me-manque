package com.tumemanque.app

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Snapshot(
    val username: String?,
    val partnerName: String?,
    val miss: Miss?,
    val incoming: List<Req>,
    val outgoing: List<Req>,
)

private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key)

/** Tout ce qui parle à Supabase (identité anonyme, base de données, fonctions) et à Firebase (jeton de notification). */
object Backend {
    private var prefs: SharedPreferences? = null
    private val mutex = Mutex()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("session", Context.MODE_PRIVATE)
    }

    private fun store(): SharedPreferences = prefs ?: error("Backend non initialisé")

    fun hasSession(): Boolean = store().getString("refresh", null) != null

    private suspend fun http(method: String, path: String, body: String?, token: String?): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val conn = URL(Config.SUPABASE_URL + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("apikey", Config.SUPABASE_ANON_KEY)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = conn.responseCode
                val stream = if (code < 400) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                code to text
            } finally {
                conn.disconnect()
            }
        }

    private fun saveSession(json: JSONObject) {
        val now = System.currentTimeMillis() / 1000
        val expires = if (json.has("expires_at") && !json.isNull("expires_at")) json.getLong("expires_at")
        else now + json.optLong("expires_in", 3600)
        store().edit()
            .putString("access", json.getString("access_token"))
            .putString("refresh", json.getString("refresh_token"))
            .putString("uid", json.getJSONObject("user").getString("id"))
            .putLong("expires", expires)
            .apply()
    }

    /** Renvoie (jeton d'accès, identifiant interne), en créant ou en renouvelant la session si besoin. */
    private suspend fun session(force: Boolean = false): Pair<String, String> = mutex.withLock {
        val p = store()
        val now = System.currentTimeMillis() / 1000
        val access = p.getString("access", null)
        val uid = p.getString("uid", null)
        if (!force && access != null && uid != null && p.getLong("expires", 0) > now + 60) {
            return@withLock access to uid
        }
        val refresh = p.getString("refresh", null)
        if (refresh != null) {
            val (code, text) = http(
                "POST", "/auth/v1/token?grant_type=refresh_token",
                JSONObject().put("refresh_token", refresh).toString(), null
            )
            if (code in 200..299) {
                saveSession(JSONObject(text))
                return@withLock store().getString("access", "")!! to store().getString("uid", "")!!
            }
            if (code !in 400..403) throw IOException("Renouvellement impossible ($code)")
            p.edit().clear().apply() // session perdue : on repart avec une nouvelle identité
        }
        val (code, text) = http("POST", "/auth/v1/signup", "{}", null)
        if (code !in 200..299) throw IOException("Connexion impossible ($code) : $text")
        saveSession(JSONObject(text))
        store().getString("access", "")!! to store().getString("uid", "")!!
    }

    suspend fun ensureSession(): String = session().second

    private suspend fun authed(method: String, path: String, body: String? = null): String {
        var res = http(method, path, body, session().first)
        if (res.first == 401) res = http(method, path, body, session(force = true).first)
        if (res.first !in 200..299) throw IOException("HTTP ${res.first} : ${res.second}")
        return res.second
    }

    private suspend fun rpc(name: String, args: JSONObject = JSONObject()): String {
        val text = authed("POST", "/rest/v1/rpc/$name", args.toString())
        return runCatching { JSONObject(text).optString("status", "error") }.getOrDefault("error")
    }

    suspend fun saveToken() {
        saveToken(FirebaseMessaging.getInstance().token.await())
    }

    suspend fun saveToken(token: String) {
        rpc("save_token", JSONObject().put("p_token", token))
    }

    suspend fun claimUsername(username: String): String =
        rpc("claim_username", JSONObject().put("p_username", username))

    suspend fun sendRequest(username: String): String =
        rpc("send_request", JSONObject().put("p_username", username))

    suspend fun respondRequest(requestId: String, accept: Boolean): String =
        rpc("respond_request", JSONObject().put("p_request_id", requestId.toLong()).put("p_accept", accept))

    suspend fun sendMiss(reply: Boolean): String =
        rpc("send_miss", JSONObject().put("p_reply", reply))

    suspend fun loadSnapshot(): Snapshot {
        val uid = ensureSession()
        val profile = JSONArray(
            authed(
                "GET",
                "/rest/v1/profiles?select=username,partner_name,last_miss_from,last_miss_reply&id=eq.$uid"
            )
        ).optJSONObject(0)
        val requests = JSONArray(
            authed(
                "GET",
                "/rest/v1/requests?select=id,from_id,from_name,to_name&or=(from_id.eq.$uid,to_id.eq.$uid)"
            )
        )
        val incoming = mutableListOf<Req>()
        val outgoing = mutableListOf<Req>()
        for (i in 0 until requests.length()) {
            val o = requests.getJSONObject(i)
            val req = Req(o.optLong("id").toString(), o.str("from_name") ?: "", o.str("to_name") ?: "")
            if (o.str("from_id") == uid) outgoing.add(req) else incoming.add(req)
        }
        val missFrom = profile?.str("last_miss_from")
        return Snapshot(
            username = profile?.str("username"),
            partnerName = profile?.str("partner_name"),
            miss = if (missFrom == null) null else Miss(missFrom, profile.optBoolean("last_miss_reply", false)),
            incoming = incoming,
            outgoing = outgoing,
        )
    }
}
