package com.tumemanque.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Base64
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
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class Snapshot(
    val username: String?,
    val partnerName: String?,
    val partnerPhone: String?,
    val wall: String?,
    val supportUnread: Int,
    val supportHas: Boolean,
    val miss: Miss?,
    val unread: Int,
    val incoming: List<Req>,
    val outgoing: List<Req>,
    /** Compte sans date de naissance ou sans numéro : on les demande. */
    val needContact: Boolean = false,
)

data class SupportMsg(val id: Long, val fromAdmin: Boolean, val body: String, val createdAt: Long)

data class Gif(val id: String, val url: String, val preview: String, val w: Int, val h: Int)

data class GifPage(val items: List<Gif>, val next: Int)

/** Message auquel on répond (`fromMe` : écrit par moi). */
data class Reply(val id: Long, val fromMe: Boolean, val kind: String, val text: String)

/** Un message du chat. `kind` : text, gif, sticker, image ou voice. */
/** Progression des envois en cours (clé = message en attente), pour la barre de chargement. */
object UploadProgress {
    val map = androidx.compose.runtime.mutableStateMapOf<Long, Float>()
    private var last = HashMap<Long, Long>()
    fun set(tmp: Long, p: Float) {
        val now = System.currentTimeMillis()
        if (p < 1f && now - (last[tmp] ?: 0L) < 120) return
        last[tmp] = now
        android.os.Handler(android.os.Looper.getMainLooper()).post { map[tmp] = p }
    }
    fun done(tmp: Long) { android.os.Handler(android.os.Looper.getMainLooper()).post { map.remove(tmp) } }
}

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
    val reply: Reply? = null,
    val deleted: Boolean = false,
    val meta: JSONObject? = null,
    val myUid: String = "",
) {
    val key: String get() = if (pending) "t$tmp" else "m$id"

    /** Message éphémère (vue unique) : le contenu n'est pas dans le message, on ne l'ouvre qu'une fois. */
    val once: Boolean get() = meta?.optBoolean("once", false) == true
    val opened: Boolean get() = meta?.optBoolean("opened", false) == true
}

/** Comment envoyer : en vue unique, et/ou à une heure précise (at = millisecondes, 0 = tout de suite). */
data class SendOpts(val once: Boolean = false, val at: Long = 0)

/** Contenu d'un message éphémère, remis une seule fois au destinataire. */
class OnceContent(val kind: String, val body: String?, val media: String?)

class NowSession(val id: Long, val state: String, val byMe: Boolean)

/** Un message programmé, pas encore parti. */
data class Sched(val id: Long, val kind: String, val body: String?, val once: Boolean, val sendAt: Long)

private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key)

/** Tout ce qui parle à Supabase (identité anonyme, base de données, fonctions) et à Firebase (jeton de notification). */
object Backend {
    private var prefs: SharedPreferences? = null
    private var pending: SharedPreferences? = null
    private val mutex = Mutex()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("session", Context.MODE_PRIVATE)
        pending = context.applicationContext.getSharedPreferences("pending", Context.MODE_PRIVATE)
    }

    private fun store(): SharedPreferences = prefs ?: error("Backend non initialisé")

    const val NO_SESSION = "NO_SESSION"

    fun hasSession(): Boolean = store().getString("refresh", null) != null

    /** « Casque » a ses propres tables et fonctions (préfixe c_), dans la même base que « Tu me manques ». */
    private val CASQUE_TABLES = setOf(
        "profiles", "requests", "notifications", "messages", "typing", "once_secrets", "scheduled_messages",
        "game_rounds", "game_answers", "game_guesses", "call_signals", "nowpic_sessions", "reminders", "support_messages",
    )

    private fun route(path: String): String {
        if (!BuildConfig.CASQUE) return path
        val rpc = "/rest/v1/rpc/"
        if (path.startsWith(rpc)) return rpc + "c_" + path.removePrefix(rpc)
        val rest = "/rest/v1/"
        if (path.startsWith(rest)) {
            val tail = path.removePrefix(rest)
            if (tail.substringBefore("?") in CASQUE_TABLES) return rest + "c_" + tail
        }
        return path
    }

    private suspend fun http(method: String, path: String, body: String?, token: String?): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val conn = URL(Config.SUPABASE_URL + route(path)).openConnection() as HttpURLConnection
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
            .putString("email", json.getJSONObject("user").optString("email", ""))
            .putString("phone", json.getJSONObject("user").optString("phone", ""))
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
            p.edit().clear().apply() // session perdue : il faudra se reconnecter
        }
        throw IOException(NO_SESSION)
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

    /** Appel d'une fonction du jeu : renvoie la réponse JSON, ou null si le réseau a échoué. */
    suspend fun gameCall(name: String, args: JSONObject = JSONObject()): JSONObject? =
        runCatching { JSONObject(authed("POST", "/rest/v1/rpc/$name", args.toString())) }.getOrNull()

    /** Appel d'une fonction : renvoie le texte JSON brut (objet ou tableau), ou null si ça a échoué. */
    suspend fun rpcText(name: String, args: JSONObject = JSONObject()): String? =
        runCatching { authed("POST", "/rest/v1/rpc/$name", args.toString()) }.getOrNull()

    suspend fun saveToken() {
        saveToken(FirebaseMessaging.getInstance().token.await())
    }

    suspend fun saveToken(token: String) {
        rpc("save_token", JSONObject().put("p_token", token))
    }

    /** Renvoie (état, pseudo trouvé). On peut ajouter par pseudo ou par numéro de téléphone. */
    suspend fun sendRequest(query: String): Pair<String, String?> {
        val text = authed("POST", "/rest/v1/rpc/send_request", JSONObject().put("p_username", query).toString())
        val j = runCatching { JSONObject(text) }.getOrNull()
        return (j?.optString("status", "error") ?: "error") to j?.str("name")
    }

    suspend fun respondRequest(requestId: String, accept: Boolean): String =
        rpc("respond_request", JSONObject().put("p_request_id", requestId.toLong()).put("p_accept", accept))

    suspend fun removePartner(): String = rpc("remove_partner")

    // ---------- Relais « le premier ami Android disponible télécharge » (Free tune) ----------
    suspend fun relayOpen(): JSONArray =
        runCatching { JSONArray(authed("POST", "/rest/v1/rpc/relay_open", "{}")) }.getOrDefault(JSONArray())

    suspend fun relayTake(id: Long): Boolean = runCatching {
        JSONObject(authed("POST", "/rest/v1/rpc/relay_take", JSONObject().put("p_id", id).toString())).optString("status") == "ok"
    }.getOrDefault(false)

    suspend fun relayDone(id: Long, media: String, size: Long): Boolean = runCatching {
        JSONObject(authed("POST", "/rest/v1/rpc/relay_done", JSONObject().put("p_id", id).put("p_media", media).put("p_size", size).toString())).optString("status") == "ok"
    }.getOrDefault(false)

    // ---------- Mode p2p : le fichier est découpé, chaque Android envoie des morceaux ----------
    suspend fun relayP2pInit(id: Long, yid: String, fid: String, size: Long): JSONObject? = runCatching {
        JSONObject(authed("POST", "/rest/v1/rpc/relay_p2p_init", JSONObject().put("p_id", id).put("p_yid", yid).put("p_fid", fid).put("p_size", size).toString()))
            .takeIf { it.optString("status") == "ok" }
    }.getOrNull()

    suspend fun relayP2pJobs(): JSONArray =
        runCatching { JSONArray(authed("POST", "/rest/v1/rpc/relay_p2p_jobs", "{}")) }.getOrDefault(JSONArray())

    suspend fun relayChunkTake(id: Long): JSONObject =
        runCatching { JSONObject(authed("POST", "/rest/v1/rpc/relay_chunk_take", JSONObject().put("p_id", id).toString())) }
            .getOrDefault(JSONObject().put("status", "error"))

    suspend fun relayChunkBeat(id: Long, idx: Int) {
        runCatching { authed("POST", "/rest/v1/rpc/relay_chunk_beat", JSONObject().put("p_id", id).put("p_idx", idx).toString()) }
    }

    suspend fun relayChunkFail(id: Long, idx: Int) {
        runCatching { authed("POST", "/rest/v1/rpc/relay_chunk_fail", JSONObject().put("p_id", id).put("p_idx", idx).toString()) }
    }

    suspend fun relayChunkDone(id: Long, idx: Int, media: String, size: Long): Boolean = runCatching {
        JSONObject(authed("POST", "/rest/v1/rpc/relay_chunk_done", JSONObject().put("p_id", id).put("p_idx", idx).put("p_media", media).put("p_size", size).toString())).optString("status") == "ok"
    }.getOrDefault(false)

    /** Free tune : « je suis en ligne » (Android), avec l'état du partage de chansons aux iPhone. */
    @Volatile private var versionSent = false
    suspend fun heartbeat(share: Boolean, inApp: Boolean) {
        // L'administrateur voit la version de Free tune de chaque compte : envoyée une fois par lancement.
        if (!versionSent) {
            versionSent = true
            runCatching { authed("POST", "/rest/v1/rpc/set_version", JSONObject().put("p_ver", BuildConfig.VERSION_NAME).toString()) }.onFailure { versionSent = false }
        }
        runCatching { authed("POST", "/rest/v1/rpc/heartbeat", JSONObject().put("p_platform", "android").put("p_share", share).put("p_in_app", inApp).toString()) }
    }

    /** Le téléphone qui télécharge pour un ami iPhone se signale (si on se tait plus de 6 secondes, la demande passe à un autre téléphone). */
    suspend fun relayBeat(id: Long) {
        runCatching { authed("POST", "/rest/v1/rpc/relay_beat", JSONObject().put("p_id", id).toString()) }
    }

    suspend fun relayProgress(id: Long, pct: Int) {
        runCatching { authed("POST", "/rest/v1/rpc/relay_progress", JSONObject().put("p_id", id).put("p_prog", pct).toString()) }
    }

    suspend fun relayFail(id: Long) {
        runCatching { authed("POST", "/rest/v1/rpc/relay_fail", JSONObject().put("p_id", id).toString()) }
    }

    suspend fun sendMiss(reply: Boolean): String =
        rpc("send_miss", JSONObject().put("p_reply", reply))

    suspend fun loadSnapshot(): Snapshot {
        val uid = ensureSession()
        val profile = JSONArray(
            authed(
                "GET",
                "/rest/v1/profiles?select=username,partner_name,partner_phone,last_miss_from,last_miss_reply,last_miss_at&id=eq.$uid"
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
        // contact.sql n'est peut-être pas encore installé côté serveur : dans ce cas on ne demande rien.
        val needContact = runCatching {
            val c = JSONArray(authed("GET", "/rest/v1/profiles?select=birthdate,phone&id=eq.$uid")).optJSONObject(0)
            c != null && profile?.str("username") != null && (c.str("birthdate") == null || c.str("phone") == null)
        }.getOrDefault(false)
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
        // Fond d'écran commun : on ignore l'erreur si la mise à jour du serveur n'est pas faite.
        val wall = runCatching {
            JSONArray(authed("GET", "/rest/v1/profiles?select=wall&id=eq.$uid")).optJSONObject(0)?.str("wall")
        }.getOrNull()
        // (nombre de messages, non lus) de la messagerie @admin ; (0, 0) si elle n'existe pas encore.
        val support = if (BuildConfig.CASQUE) Pair(0, 0) else runCatching {
            val arr = JSONArray(authed("GET", "/rest/v1/support_messages?select=from_admin,read_at&limit=200"))
            var unread = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optBoolean("from_admin", false) && o.isNull("read_at")) unread++
            }
            Pair(arr.length(), unread)
        }.getOrDefault(Pair(0, 0))
        return Snapshot(
            wall = wall,
            supportUnread = support.second,
            supportHas = support.first > 0,
            username = profile?.str("username"),
            partnerName = profile?.str("partner_name"),
            partnerPhone = profile?.str("partner_phone"),
            miss = if (missFrom == null) null
            else Miss(missFrom, profile.optBoolean("last_miss_reply", false), profile.str("last_miss_at") ?: ""),
            unread = unread,
            incoming = incoming,
            outgoing = outgoing,
            needContact = needContact,
        )
    }


    // ====================== Comptes (Google / Apple / SMS) ======================

    private fun pend(): SharedPreferences = pending ?: error("Backend non initialisé")

    fun e164(input: String): String {
        var d = input.filter { it.isDigit() }
        if (input.trim().startsWith("00")) d = d.drop(2)
        if (d.length == 9 && d[0] == '6') d = "237$d"
        return "+$d"
    }

    suspend fun usernameAvailable(username: String): String {
        val (code, text) = http("POST", "/rest/v1/rpc/username_available", JSONObject().put("p_username", username).toString(), null)
        if (code !in 200..299) return "error"
        return runCatching { JSONObject(text).optString("status", "error") }.getOrDefault("error")
    }

    /** « ok », « disabled » (SMS pas encore branché), « rate » (trop d'essais) ou « error ». */
    suspend fun sendOtp(phone: String): String {
        val (code, text) = http("POST", "/auth/v1/otp", JSONObject().put("phone", phone).put("create_user", true).toString(), null)
        if (code in 200..299) return "ok"
        val t = text.lowercase()
        return when {
            code == 429 || "rate limit" in t || "too many" in t || "only request this after" in t -> "rate"
            Regex("provider|unsupported|sms_send_failed|not enabled|disabled|twilio").containsMatchIn(t) -> "disabled"
            else -> "error"
        }
    }

    suspend fun verifyOtp(phone: String, token: String): Boolean {
        val body = JSONObject().put("type", "sms").put("phone", phone).put("token", token).toString()
        val (code, text) = http("POST", "/auth/v1/verify", body, null)
        if (code !in 200..299) return false
        saveSession(JSONObject(text))
        return true
    }

    /** E-mail et numéro du compte Google / Apple / SMS connecté (vides s'ils ne sont pas connus). */
    fun sessionEmail(): String = store().getString("email", "") ?: ""
    fun sessionPhone(): String = (store().getString("phone", "") ?: "").filter { it.isDigit() }

    /** Est-ce que ce fournisseur de connexion (apple, google…) est activé sur le serveur ? En cas de doute : oui. */
    suspend fun providerEnabled(provider: String): Boolean = try {
        val (code, text) = http("GET", "/auth/v1/settings", null, null)
        if (code != 200) true else JSONObject(text).optJSONObject("external")?.let { it.optBoolean(provider, true) } ?: true
    } catch (e: Exception) { true }

    private fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** Adresse à ouvrir dans le navigateur pour « Continuer avec Google / Apple » (PKCE). */
    fun oauthUri(provider: String, pseudo: String?, age: Int?, birth: String?, phone: String?): Uri {
        val raw = ByteArray(48).also { SecureRandom().nextBytes(it) }
        val verifier = b64url(raw)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val editor = pend().edit()
        editor.clear()
        editor.putString("verifier", verifier)
        if (!pseudo.isNullOrEmpty() && age != null) {
            editor.putString("pseudo", pseudo)
            editor.putInt("age", age)
            if (birth != null) editor.putString("birth", birth)
            if (phone != null) editor.putString("phone", phone)
        }
        editor.apply()
        val redirect = URLEncoder.encode("${BuildConfig.OAUTH_SCHEME}://auth", "UTF-8")
        return Uri.parse(
            "${Config.SUPABASE_URL}/auth/v1/authorize?provider=$provider&redirect_to=$redirect" +
                "&code_challenge=$challenge&code_challenge_method=s256"
        )
    }

    /** Termine la connexion Google / Apple avec le code reçu au retour dans l'appli. */
    suspend fun exchangeCode(authCode: String): Boolean {
        val verifier = pend().getString("verifier", null) ?: return false
        pend().edit().remove("verifier").apply()
        val body = JSONObject().put("auth_code", authCode).put("code_verifier", verifier).toString()
        val (code, text) = http("POST", "/auth/v1/token?grant_type=pkce", body, null)
        if (code !in 200..299) return false
        saveSession(JSONObject(text))
        return true
    }

    suspend fun createAccount(username: String, age: Int): String {
        val device = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · app ${BuildConfig.VERSION_NAME}".take(200)
        val base = JSONObject().put("p_username", username).put("p_age", age)
        // Si le serveur n'est pas encore à jour, on réessaie sans l'appareil.
        val first = runCatching { rpc("create_account", JSONObject(base.toString()).put("p_device", device)) }.getOrDefault("error")
        return if (first == "error") rpc("create_account", base) else first
    }

    /** Date de naissance (AAAA-MM-JJ) et numéro de téléphone. Renvoie ok, phone_taken, invalid_birth, invalid_phone… */
    suspend fun saveContact(birthIso: String, phone: String): String =
        rpc("save_contact", JSONObject().put("p_birth", birthIso).put("p_phone", phone))

    // ====================== Rappels ======================

    /** Mes rappels en cours (pas encore validés avec « OK »). null si le serveur ne répond pas ou n'est pas à jour. */
    suspend fun reminderList(): List<Reminder>? {
        val arr = runCatching { JSONArray(authed("POST", "/rest/v1/rpc/reminder_list", "{}")) }.getOrNull() ?: return null
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            Reminder(o.optLong("id"), o.optString("title", ""), parseTime(o.str("due_at")), o.optBoolean("by_me", false), o.str("by_name") ?: "", o.optString("target", "me"))
        }
    }

    suspend fun reminderCreate(title: String, who: String, dueMillis: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return runCatching {
            rpc("reminder_create", JSONObject().put("p_title", title).put("p_who", who).put("p_due", f.format(java.util.Date(dueMillis))))
        }.getOrDefault("error")
    }

    suspend fun reminderAck(id: Long) { runCatching { rpc("reminder_ack", JSONObject().put("p_id", id)) } }

    suspend fun reminderCancel(id: Long): String =
        runCatching { rpc("reminder_cancel", JSONObject().put("p_id", id)) }.getOrDefault("error")

    // ====================== Messagerie avec @admin ======================

    suspend fun supportList(): List<SupportMsg> {
        val arr = JSONArray(authed("GET", "/rest/v1/support_messages?select=id,from_admin,body,created_at&order=id.asc&limit=300"))
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            SupportMsg(o.optLong("id"), o.optBoolean("from_admin", false), o.optString("body", ""), parseTime(o.str("created_at")))
        }
    }

    /** Free tune : le pseudo de mon parrain (ou null). */
    suspend fun sponsorGet(): String? = runCatching {
        JSONObject(authed("POST", "/rest/v1/rpc/sponsor_get", "{}")).let { if (it.isNull("name")) null else it.optString("name").ifBlank { null } }
    }.getOrNull()

    /** Free tune : je renseigne mon parrain. Renvoie (statut, pseudo). */
    suspend fun sponsorSet(name: String): Pair<String, String?> = try {
        val o = JSONObject(authed("POST", "/rest/v1/rpc/sponsor_set", JSONObject().put("p_name", name).toString()))
        o.optString("status", "error") to (if (o.isNull("name")) null else o.optString("name"))
    } catch (e: Exception) { "error" to null }

    /** Free tune : ma cagnotte de parrainage (solde, filleuls, demande de retrait). */
    suspend fun refGet(): JSONObject? = runCatching { JSONObject(authed("POST", "/rest/v1/rpc/ref_get", "{}")).takeIf { it.optString("status") == "ok" } }.getOrNull()

    /** Free tune : demande de retrait de la cagnotte. Renvoie le statut du serveur. */
    suspend fun payoutRequest(phone: String, name: String): String = try {
        JSONObject(authed("POST", "/rest/v1/rpc/payout_request", JSONObject().put("p_phone", phone).put("p_name", name).toString())).optString("status", "error")
    } catch (e: Exception) { "error" }

    suspend fun supportSend(body: String): String = rpc("support_send", JSONObject().put("p_body", body))

    suspend fun supportMarkRead() { rpc("support_mark_read") }

    /**
     * Après une connexion réussie. Renvoie null si le compte est prêt, sinon un message
     * (éventuellement vide) : on demandera alors le pseudo et l'âge.
     */
    suspend fun finishAuth(fallbackPseudo: String?, fallbackAge: Int?, fallbackBirth: String?, fallbackPhone: String?): String? {
        val snap = loadSnapshot()
        val p = pend()
        if (snap.username != null) {
            p.edit().clear().apply()
            return null
        }
        val pseudo = p.getString("pseudo", null) ?: fallbackPseudo
        val age = if (p.contains("age")) p.getInt("age", 0) else (fallbackAge ?: 0)
        val birth = p.getString("birth", null) ?: fallbackBirth
        val phone = p.getString("phone", null) ?: fallbackPhone
        p.edit().clear().apply()
        if (!pseudo.isNullOrEmpty() && age > 0) {
            return when (createAccount(pseudo, age)) {
                "ok", "has_username" -> {
                    if (birth != null && phone != null) runCatching { saveContact(birth, phone) }
                    null
                }
                "taken" -> "Ce pseudo vient d'être pris. Choisis-en un autre."
                else -> "Impossible de créer le compte."
            }
        }
        return ""
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
        val isMine = o.str("sender_id") == uid
        val rj = meta?.optJSONObject("reply")
        val reply = if (rj == null) null else Reply(
            id = rj.optLong("id", 0),
            fromMe = if (isMine) rj.optBoolean("by_me", false) else !rj.optBoolean("by_me", false),
            kind = rj.optString("kind", "text"),
            text = rj.optString("text", ""),
        )
        return ChatMsg(
            id = o.getLong("id"),
            reply = reply,
            meta = meta,
            myUid = uid,
            deleted = o.has("deleted_at") && !o.isNull("deleted_at"),
            mine = isMine,
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

    // La colonne deleted_at n'existe qu'après supprimer.sql : sans elle, on lit les messages comme avant.
    @Volatile private var colsFull = true

    private suspend fun messages(query: String): List<ChatMsg> {
        val uid = ensureSession()
        val text = try {
            authed("GET", "/rest/v1/messages?select=$COLS${if (colsFull) ",deleted_at" else ""}&$query")
        } catch (e: IOException) {
            if (colsFull && (e.message ?: "").contains("HTTP 400")) {
                colsFull = false
                authed("GET", "/rest/v1/messages?select=$COLS&$query")
            } else throw e
        }
        val arr = JSONArray(text)
        return List(arr.length()) { parseMsg(arr.getJSONObject(it), uid) }
    }

    /** Supprime un message pour moi, ou pour tout le monde (si c'est le mien). */
    suspend fun deleteMessage(id: Long, all: Boolean): Boolean =
        rpc("delete_message", JSONObject().put("p_id", id).put("p_all", all)) == "ok"

    /** Pour la page d'appel (WebView) : appel brut à Supabase avec ma session. */
    suspend fun callRaw(method: String, path: String, body: String?): String = authed(method, path, body)

    class CallSig(val id: Long, val kind: String, val cid: String, val video: Boolean)

    /** Signaux d'appel reçus depuis [after] (sonnerie, acceptation, raccroché…). */
    suspend fun callPoll(after: Long): List<CallSig> {
        val arr = JSONArray(authed("POST", "/rest/v1/rpc/call_poll", JSONObject().put("p_after", after).toString()))
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            val p = o.optJSONObject("payload")
            CallSig(o.getLong("id"), o.optString("kind"), p?.optString("cid") ?: "", p?.optBoolean("video", false) ?: false)
        }
    }

    /** Modifie le texte d'un de mes messages déjà envoyés. */
    suspend fun editMessage(id: Long, body: String): Boolean =
        rpc("edit_message", JSONObject().put("p_id", id).put("p_body", body)) == "ok"

    /** Messages modifiés (à partir de [minId]) : identifiant et nouveau texte. */
    suspend fun editedSince(minId: Long): List<Pair<Long, String>> = try {
        val arr = JSONArray(authed("GET", "/rest/v1/messages?select=id,body&meta->>edited=eq.true&id=gte.$minId"))
        List(arr.length()) { arr.getJSONObject(it).getLong("id") to arr.getJSONObject(it).optString("body", "") }
    } catch (e: Exception) {
        emptyList()
    }

    /** Parmi les messages chargés (à partir de [minId]), lesquels l'autre personne a-t-elle supprimés pour tous ? */
    suspend fun deletedSince(minId: Long): List<Long> {
        if (!colsFull) return emptyList()
        return try {
            val arr = JSONArray(authed("GET", "/rest/v1/messages?select=id&deleted_at=not.is.null&id=gte.$minId"))
            List(arr.length()) { arr.getJSONObject(it).getLong("id") }
        } catch (e: Exception) {
            emptyList()
        }
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

    // ---------- Éphémères et programmés ----------

    suspend fun sendOnce(kind: String, body: String?, media: String?, meta: JSONObject?): SendResult {
        val args = JSONObject().put("p_kind", kind).put("p_body", body ?: JSONObject.NULL)
            .put("p_media", media ?: JSONObject.NULL).put("p_meta", meta ?: JSONObject.NULL)
        val j = JSONObject(authed("POST", "/rest/v1/rpc/send_once", args.toString()))
        return SendResult(j.optString("status", "error"), j.optLong("id", 0))
    }

    /** Ouvre un message éphémère reçu : le contenu sort une seule fois. null = déjà disparu. */
    suspend fun onceOpen(id: Long): OnceContent? {
        val j = JSONObject(authed("POST", "/rest/v1/rpc/once_open", JSONObject().put("p_id", id).toString()))
        if (j.optString("status") != "ok") return null
        return OnceContent(j.optString("kind"), j.str("body"), j.str("media"))
    }

    suspend fun onceDone(id: Long) { runCatching { rpc("once_done", JSONObject().put("p_id", id)) } }

    /** Parmi ces messages éphémères envoyés par moi, lesquels ont été ouverts ? */
    suspend fun openedAmong(ids: List<Long>): List<Long> {
        if (ids.isEmpty()) return emptyList()
        val arr = JSONArray(authed("GET", "/rest/v1/messages?select=id&meta->>opened=eq.true&id=in.(${ids.joinToString(",")})"))
        return List(arr.length()) { arr.getJSONObject(it).getLong("id") }
    }

    suspend fun scheduleMessage(kind: String, body: String?, media: String?, meta: JSONObject?, once: Boolean, atMillis: Long): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date(atMillis))
        val args = JSONObject().put("p_kind", kind).put("p_body", body ?: JSONObject.NULL).put("p_media", media ?: JSONObject.NULL)
            .put("p_meta", meta ?: JSONObject.NULL).put("p_once", once).put("p_at", iso)
        return JSONObject(authed("POST", "/rest/v1/rpc/schedule_message", args.toString())).optString("status", "error")
    }

    suspend fun scheduledList(): List<Sched> {
        val arr = JSONArray(authed("POST", "/rest/v1/rpc/scheduled_list", "{}"))
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            Sched(o.getLong("id"), o.optString("kind"), o.str("body"), o.optBoolean("once"), parseTime(o.str("send_at")))
        }
    }

    suspend fun scheduledCancel(id: Long) { rpc("scheduled_cancel", JSONObject().put("p_id", id)) }

    suspend fun deliverScheduled() { runCatching { rpc("deliver_scheduled") } }

    // ---------- Now pic ----------

    suspend fun nowpicRequest(): String = JSONObject(authed("POST", "/rest/v1/rpc/nowpic_request", "{}")).optString("status", "error")

    /** La demande de Now pic en cours (la mienne ou celle de l'autre), ou null. */
    suspend fun nowpicState(): NowSession? {
        val j = JSONObject(authed("POST", "/rest/v1/rpc/nowpic_state", "{}"))
        val o = j.optJSONObject("session") ?: return null
        return NowSession(o.optLong("id"), o.optString("state"), o.optBoolean("by_me"))
    }

    suspend fun nowpicRespond(id: Long, accept: Boolean) { rpc("nowpic_respond", JSONObject().put("p_id", id).put("p_accept", accept)) }

    suspend fun nowpicCancel(id: Long) { rpc("nowpic_cancel", JSONObject().put("p_id", id)) }

    /** « Est en train d'écrire » : tout est silencieux si la fonction n'existe pas encore côté serveur. */
    suspend fun partnerTyping(): Boolean = runCatching {
        JSONObject(authed("POST", "/rest/v1/rpc/partner_typing", "{}")).optBoolean("typing", false)
    }.getOrDefault(false)

    suspend fun setTyping() { runCatching { rpc("set_typing") } }

    suspend fun stopTyping() { runCatching { rpc("stop_typing") } }

    /** Change le fond d'écran de la conversation pour les deux partenaires (null = fond d'origine). */
    suspend fun setWallpaper(value: String?): Boolean =
        rpc("set_wallpaper", JSONObject().put("p_value", value ?: JSONObject.NULL)) == "ok"

    suspend fun clearMiss() { rpc("clear_miss") }

    /** Envoie un fichier (photo ou vocal) dans le dossier de l'utilisateur et renvoie son chemin. */
    suspend fun upload(bytes: ByteArray, ext: String, mime: String, onProgress: ((Float) -> Unit)? = null): String {
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
                conn.outputStream.use { out ->
                    var off = 0
                    while (off < bytes.size) {
                        val n = minOf(32 * 1024, bytes.size - off)
                        out.write(bytes, off, n); off += n
                        onProgress?.invoke(off.toFloat() / bytes.size)
                    }
                }
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

    /** Comme [upload], mais lit le fichier par morceaux (vidéos lourdes : rien n'est chargé en mémoire). */
    suspend fun uploadFile(file: java.io.File, ext: String, mime: String, onProgress: ((Float) -> Unit)? = null): String {
        val uid = ensureSession()
        val path = "$uid/${UUID.randomUUID().toString().replace("-", "")}.$ext"
        suspend fun attempt(token: String): Int = withContext(Dispatchers.IO) {
            val conn = URL("${Config.SUPABASE_URL}/storage/v1/object/chat/$path").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 180000
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(file.length())
                conn.setRequestProperty("apikey", Config.SUPABASE_ANON_KEY)
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", mime)
                conn.setRequestProperty("x-upsert", "false")
                val total = file.length().coerceAtLeast(1)
                file.inputStream().use { input ->
                    conn.outputStream.use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); done += n
                            onProgress?.invoke(done.toFloat() / total)
                        }
                    }
                }
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        }
        var code = attempt(session().first)
        if (code == 401) code = attempt(session(force = true).first)
        if (code !in 200..299) throw IOException("Envoi de la vidéo refusé ($code)")
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
