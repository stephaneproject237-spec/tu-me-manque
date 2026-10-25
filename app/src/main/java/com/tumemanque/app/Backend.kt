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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class Snapshot(
    val username: String?,
    val partnerName: String?,
    val miss: Miss?,
    val unread: Int,
    val incoming: List<Req>,
    val outgoing: List<Req>,
)

data class Gif(val id: String, val url: String, val preview: String, val w: Int, val h: Int)

data class GifPage(val items: List<Gif>, val next: Int)

/** Un message du chat. `kind` : text, gif, sticker, image ou voice. */
data class ChatMsg(
    val id: Long,
    val tmp: Long = 0,
    val mine: Boolean,
    val kind: String,
    val body: String?,
    val media: String?,
    val w: Int = 0,
    val h: Int = 0,
    val dur: Int = 0,
    val createdAt: Long,
    val read: Boolean = false,
    val pending: Boolean = false,
    val localBytes: ByteArray? = null,
    val localFile: String? = null,
) {
    val key: String get() = if (pending) "t$tmp" else "m$id"
}

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

    suspend fun removePartner(): String = rpc("remove_partner")

    suspend fun sendMiss(reply: Boolean): String =
        rpc("send_miss", JSONObject().put("p_reply", reply))

    suspend fun loadSnapshot(): Snapshot {
        val uid = ensureSession()
        val profile = JSONArray(
            authed(
                "GET",
                "/rest/v1/profiles?select=username,partner_name,last_miss_from,last_miss_reply,last_miss_at&id=eq.$uid"
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
        var unread = 0
        if (profile?.str("partner_name") != null) {
            // Le chat n'est peut-être pas encore installé côté serveur : on ignore l'erreur.
            unread = runCatching {
                JSONArray(
                    authed("GET", "/rest/v1/messages?select=id&recipient_id=eq.$uid&read_at=is.null&limit=100")
                ).length()
            }.getOrDefault(0)
        }
        return Snapshot(
            username = profile?.str("username"),
            partnerName = profile?.str("partner_name"),
            miss = if (missFrom == null) null
            else Miss(missFrom, profile.optBoolean("last_miss_reply", false), profile.str("last_miss_at") ?: ""),
            unread = unread,
            incoming = incoming,
            outgoing = outgoing,
        )
    }

    // ====================== Chat ======================

    private const val COLS = "id,sender_id,kind,body,media,meta,created_at,read_at"

    fun mediaUrl(path: String): String = "${Config.SUPABASE_URL}/storage/v1/object/public/chat/$path"

    private fun parseTime(s: String?): Long {
        val now = System.currentTimeMillis()
        if (s == null || s.length < 19) return now
        return try {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            f.parse(s.substring(0, 19))?.time ?: now
        } catch (e: Exception) {
            now
        }
    }

    private fun parseMsg(o: JSONObject, uid: String): ChatMsg {
        val meta = o.optJSONObject("meta")
        return ChatMsg(
            id = o.getLong("id"),
            mine = o.str("sender_id") == uid,
            kind = o.getString("kind"),
            body = o.str("body"),
            media = o.str("media"),
            w = meta?.optInt("w", 0) ?: 0,
            h = meta?.optInt("h", 0) ?: 0,
            dur = meta?.optInt("d", 0) ?: 0,
            createdAt = parseTime(o.str("created_at")),
            read = o.str("read_at") != null,
        )
    }

    private suspend fun messages(query: String): List<ChatMsg> {
        val uid = ensureSession()
        val arr = JSONArray(authed("GET", "/rest/v1/messages?select=$COLS&$query"))
        return List(arr.length()) { parseMsg(arr.getJSONObject(it), uid) }
    }

    /** Les derniers messages, du plus ancien au plus récent. */
    suspend fun latestMessages(): List<ChatMsg> = messages("order=id.desc&limit=40").reversed()

    suspend fun olderMessages(beforeId: Long): List<ChatMsg> =
        messages("id=lt.$beforeId&order=id.desc&limit=40").reversed()

    suspend fun newMessages(afterId: Long): List<ChatMsg> = messages("id=gt.$afterId&order=id.asc&limit=100")

    /** Parmi ces messages envoyés par moi, lesquels ont été lus ? */
    suspend fun readAmong(ids: List<Long>): List<Long> {
        if (ids.isEmpty()) return emptyList()
        val arr = JSONArray(
            authed("GET", "/rest/v1/messages?select=id&read_at=not.is.null&id=in.(${ids.joinToString(",")})")
        )
        return List(arr.length()) { arr.getJSONObject(it).getLong("id") }
    }

    data class SendResult(val status: String, val id: Long)

    suspend fun sendMessage(kind: String, body: String?, media: String?, meta: JSONObject?): SendResult {
        val args = JSONObject()
            .put("p_kind", kind)
            .put("p_body", body ?: JSONObject.NULL)
            .put("p_media", media ?: JSONObject.NULL)
            .put("p_meta", meta ?: JSONObject.NULL)
        val j = JSONObject(authed("POST", "/rest/v1/rpc/send_message", args.toString()))
        return SendResult(j.optString("status", "error"), j.optLong("id", 0))
    }

    suspend fun markRead() { rpc("mark_read") }

    suspend fun clearMiss() { rpc("clear_miss") }

    /** Envoie un fichier (photo ou vocal) dans le dossier de l'utilisateur et renvoie son chemin. */
    suspend fun upload(bytes: ByteArray, ext: String, mime: String): String {
        val uid = ensureSession()
        val path = "$uid/${UUID.randomUUID().toString().replace("-", "")}.$ext"
        suspend fun attempt(token: String): Int = withContext(Dispatchers.IO) {
            val conn = URL("${Config.SUPABASE_URL}/storage/v1/object/chat/$path").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.setRequestProperty("apikey", Config.SUPABASE_ANON_KEY)
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", mime)
                conn.setRequestProperty("x-upsert", "false")
                conn.outputStream.use { it.write(bytes) }
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        }
        var code = attempt(session().first)
        if (code == 401) code = attempt(session(force = true).first)
        if (code !in 200..299) throw IOException("Envoi du fichier refusé ($code)")
        return path
    }

    suspend fun searchGifs(query: String, offset: Int): GifPage {
        val text = authed("GET", "/functions/v1/gifs?q=${URLEncoder.encode(query, "UTF-8")}&offset=$offset")
        val j = JSONObject(text)
        val arr = j.optJSONArray("results") ?: JSONArray()
        val items = List(arr.length()) {
            val o = arr.getJSONObject(it)
            Gif(o.optString("id"), o.getString("url"), o.optString("preview", o.getString("url")), o.optInt("w", 200), o.optInt("h", 200))
        }
        val next = if (j.has("next") && !j.isNull("next")) j.optInt("next", offset + items.size) else offset + items.size
        return GifPage(items, next)
    }
}
