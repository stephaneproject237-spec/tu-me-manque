package com.tumemanque.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/** Une vidéo trouvée sur YouTube. */
data class YtVideo(
    val id: String,
    val title: String,
    val channel: String,
    val durationSec: Int,
    val views: Long = 0,
) {
    val url: String get() = "https://www.youtube.com/watch?v=$id"
    val thumb: String get() = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
    val thumbHd: String get() = "https://i.ytimg.com/vi/$id/maxresdefault.jpg"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("t", title).put("c", channel).put("d", durationSec).put("v", views)

    companion object {
        fun fromJson(o: JSONObject) = YtVideo(o.getString("id"), o.optString("t"), o.optString("c"), o.optInt("d"), o.optLong("v"))
    }
}

/** Ce qu'il faut pour lire la vidéo et estimer la taille de chaque format. */
data class YtInfo(
    val id: String,
    val playUrl: String?,
    val videoUrl: String?,
    val audioUrl: String?,
    val headers: Map<String, String>,
    val durationSec: Int,
    val audioBytes: Long,
    /** hauteur (côté le plus court) -> taille estimée de la vidéo complète */
    val videoSizes: Map<Int, Long>,
    val maxRes: Int,
)

/** Le moteur : yt-dlp (avec Python et FFmpeg) embarqué dans l'appli. */
object Yt {
    enum class State { IDLE, PREPARING, READY, FAILED }

    var state by mutableStateOf(State.IDLE)
        private set
    var error by mutableStateOf("")
        private set
    var engineVersion by mutableStateOf("")
        private set
    var updating by mutableStateOf(false)
        private set
    var updateMessage by mutableStateOf("")
        private set

    private lateinit var app: Context
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val infoCache = HashMap<String, Pair<Long, YtInfo>>()
    private val searchSeq = AtomicInteger(0)

    private fun prefs() = app.getSharedPreferences("casque_yt", Context.MODE_PRIVATE)

    /** Prépare le moteur en arrière-plan dès l'ouverture de l'appli, et le met à jour une fois par jour. */
    fun warmUp(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        scope.launch {
            runCatching { ensure() }
            val last = prefs().getLong("updated_at", 0L)
            if (state == State.READY && System.currentTimeMillis() - last > 20 * 3600_000L) update(silent = true)
        }
    }

    suspend fun ensure() {
        if (state == State.READY) return
        mutex.withLock {
            if (state == State.READY) return
            withContext(Dispatchers.IO) {
                state = State.PREPARING
                try {
                    YoutubeDL.init(app)
                    FFmpeg.init(app)
                    engineVersion = runCatching { YoutubeDL.versionName(app) ?: YoutubeDL.version(app) }.getOrNull().orEmpty()
                    state = State.READY
                } catch (e: Throwable) {
                    error = e.message ?: e.javaClass.simpleName
                    state = State.FAILED
                    throw e
                }
            }
        }
    }

    /** Met yt-dlp à jour : YouTube change souvent, c'est ce qui garde la recherche et les téléchargements fonctionnels. */
    fun update(silent: Boolean = false) {
        if (updating) return
        scope.launch {
            updating = true
            if (!silent) updateMessage = "Mise à jour du moteur…"
            try {
                ensure()
                val r = YoutubeDL.updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
                engineVersion = runCatching { YoutubeDL.versionName(app) ?: YoutubeDL.version(app) }.getOrNull().orEmpty()
                prefs().edit().putLong("updated_at", System.currentTimeMillis()).apply()
                updateMessage = if (r == YoutubeDL.UpdateStatus.DONE) "Moteur mis à jour." else "Le moteur est déjà à jour."
            } catch (e: Throwable) {
                if (!silent) updateMessage = "Mise à jour impossible : vérifie ta connexion."
            } finally {
                updating = false
            }
        }
    }

    private fun base(req: YoutubeDLRequest): YoutubeDLRequest = req
        .addOption("--no-warnings")
        .addOption("--socket-timeout", "15")
        .addOption("-R", "2")

    /** Cherche des vidéos (sans les ouvrir une à une : c'est rapide). */
    suspend fun search(query: String, count: Int = 18): List<YtVideo> = withContext(Dispatchers.IO) {
        ensure()
        val seq = searchSeq.incrementAndGet()
        val pid = "search$seq"
        // Une nouvelle recherche remplace la précédente.
        runCatching { YoutubeDL.destroyProcessById("search${seq - 1}") }
        val req = base(YoutubeDLRequest("ytsearch$count:$query"))
            .addOption("--flat-playlist")
            .addOption("--dump-single-json")
        val out = YoutubeDL.execute(req, pid, null).out
        val entries = JSONObject(out).optJSONArray("entries") ?: JSONArray()
        (0 until entries.length()).mapNotNull { i ->
            val o = entries.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.length != 11) return@mapNotNull null
            if (o.optString("live_status") == "is_live") return@mapNotNull null
            YtVideo(
                id = id,
                title = o.optString("title").ifBlank { "Sans titre" },
                channel = o.optString("channel").ifBlank { o.optString("uploader") }.removeSuffix(" - Topic"),
                durationSec = o.optDouble("duration", 0.0).toInt(),
                views = o.optLong("view_count", 0L),
            )
        }
    }

    fun cachedInfo(id: String): YtInfo? = synchronized(infoCache) {
        infoCache[id]?.takeIf { System.currentTimeMillis() - it.first < 3 * 3600_000L }?.second
    }

    /** Lien de lecture et tailles des formats d'une vidéo. */
    suspend fun info(id: String): YtInfo = withContext(Dispatchers.IO) {
        cachedInfo(id)?.let { return@withContext it }
        ensure()
        val req = base(YoutubeDLRequest("https://www.youtube.com/watch?v=$id"))
            .addOption("--no-playlist")
            .addOption("-f", "18/b[ext=mp4][vcodec!=none][acodec!=none]/bv*[vcodec^=avc1][height<=720]+ba[ext=m4a]/b/bv*+ba")
            .addOption("--dump-json")
        val o = JSONObject(YoutubeDL.execute(req, "info$id", null).out)
        val dur = o.optDouble("duration", 0.0).toInt()
        val headers = HashMap<String, String>()
        o.optJSONObject("http_headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.optString(k) } }
        var videoUrl: String? = null
        var audioUrl: String? = null
        o.optJSONArray("requested_formats")?.let { rf ->
            for (i in 0 until rf.length()) {
                val f = rf.getJSONObject(i)
                if (f.optString("vcodec", "none") != "none") videoUrl = f.optString("url") else audioUrl = f.optString("url")
            }
        }
        val playUrl = o.optString("url").takeIf { it.startsWith("http") }

        fun size(f: JSONObject): Long {
            val s = f.optLong("filesize", 0L).takeIf { it > 0 } ?: f.optLong("filesize_approx", 0L)
            if (s > 0) return s
            val tbr = f.optDouble("tbr", 0.0)
            return if (tbr > 0 && dur > 0) (tbr * 125 * dur).toLong() else 0L
        }

        val formats = o.optJSONArray("formats") ?: JSONArray()
        var bestAudio = 0L
        var bestAudioAbr = -1.0
        val videoOnly = ArrayList<Pair<Int, Long>>()
        var maxRes = 0
        for (i in 0 until formats.length()) {
            val f = formats.getJSONObject(i)
            val v = f.optString("vcodec", "none")
            val a = f.optString("acodec", "none")
            if (v == "none" && a != "none") {
                val abr = f.optDouble("abr", 0.0)
                if (f.optString("ext") == "m4a" && abr > bestAudioAbr) { bestAudioAbr = abr; bestAudio = size(f) }
            } else if (v != "none" && !f.optString("protocol").startsWith("m3u8")) {
                val w = f.optInt("width", 0); val h = f.optInt("height", 0)
                val res = if (w > 0 && h > 0) minOf(w, h) else h
                if (res <= 0) continue
                maxRes = maxOf(maxRes, res)
                if (v.startsWith("avc1")) videoOnly.add(res to size(f))
            }
        }
        val sizes = HashMap<Int, Long>()
        for (target in listOf(360, 480, 720, 1080)) {
            if (maxRes < target * 0.9) continue
            val best = videoOnly.filter { it.first <= target }.maxByOrNull { it.first * 1_000_000_000L + it.second }
            sizes[target] = (best?.second ?: 0L) + bestAudio
        }
        val info = YtInfo(id, playUrl, videoUrl, audioUrl, headers, dur, bestAudio, sizes, maxRes)
        synchronized(infoCache) { infoCache[id] = System.currentTimeMillis() to info }
        info
    }

    /** Suggestions pendant la frappe (rapide, sans passer par yt-dlp). */
    suspend fun suggest(q: String): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val u = URL(
                "https://suggestqueries-clients6.youtube.com/complete/search?client=firefox&ds=yt&hl=fr&ie=utf-8&oe=utf-8&q=" +
                    URLEncoder.encode(q, "UTF-8")
            )
            val c = (u.openConnection() as HttpURLConnection).apply { connectTimeout = 4000; readTimeout = 4000 }
            val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val arr = JSONArray(text).getJSONArray(1)
            (0 until arr.length()).map { arr.getString(it) }.distinct().take(8)
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------ historique des recherches
    fun history(): List<String> = runCatching {
        val a = JSONArray(prefs().getString("history", "[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    fun remember(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        val l = (listOf(t) + history().filterNot { it.equals(t, true) }).take(12)
        prefs().edit().putString("history", JSONArray(l).toString()).apply()
    }

    fun forget(q: String) {
        prefs().edit().putString("history", JSONArray(history().filterNot { it == q }).toString()).apply()
    }

    fun clearHistory() { prefs().edit().putString("history", "[]").apply() }
}

/** « Artiste - Titre (Clip officiel) » -> titre propre et artiste. */
fun cleanTitle(raw: String, channel: String): Pair<String, String> {
    val noise = Regex(
        "\\s*[\\(\\[][^\\)\\]]*(official|officiel|officielle|lyrics?|paroles|audio|video|vidéo|clip|visuali[sz]er|hd|4k)[^\\)\\]]*[\\)\\]]",
        RegexOption.IGNORE_CASE,
    )
    var t = raw.replace(noise, "").trim()
    var artist = channel.removeSuffix(" - Topic").removeSuffix("VEVO").trim()
    val i = t.indexOf(" - ")
    if (i > 0) {
        artist = t.substring(0, i).trim()
        t = t.substring(i + 3).trim()
    }
    return t.ifEmpty { raw } to artist.ifEmpty { UNKNOWN_ARTIST }
}

fun fmtSec(s: Int): String = if (s <= 0) "" else fmt(s * 1000L)

fun fmtViews(v: Long): String = when {
    v >= 1_000_000_000 -> "%.1f Md de vues".format(v / 1e9)
    v >= 1_000_000 -> "%.1f M de vues".format(v / 1e6)
    v >= 1_000 -> "${v / 1000} k vues"
    v > 0 -> "$v vues"
    else -> ""
}
