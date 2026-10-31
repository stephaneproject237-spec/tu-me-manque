package com.freetune.server

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Flux audio m4a direct avec sa taille exacte (mode p2p). */
class Stream(val fid: String, val url: String, val headers: Map<String, String>, val size: Long)
/** Adresse directe pour lire sans télécharger. */
class MediaSrc(val url: String, val headers: Map<String, String>, val duration: Int)

/** yt-dlp et FFmpeg embarqués : recherche, téléchargement, lecture directe. */
object Tools {
    @Volatile var ready = false
    @Volatile var status = ""
    private lateinit var app: Context
    private var lastFix = 0L

    fun init(ctx: Context) { app = ctx.applicationContext }

    @Synchronized
    fun ensure() {
        if (ready) return
        status = "Préparation du moteur de téléchargement…"
        try {
            YoutubeDL.init(app)
            FFmpeg.init(app)
            ready = true
        } finally { status = "" }
    }

    private fun looksBroken(e: Throwable): Boolean {
        if (e is InterruptedException || e.javaClass.simpleName.contains("Cancel", true)) return false
        val m = (e.message ?: "") + " " + (e.cause?.message ?: "")
        if (Regex("unknownhost|unable to resolve host|network is unreachable|timed out|no space left|connection reset|failed to connect", RegexOption.IGNORE_CASE).containsMatchIn(m)) return false
        return Regex("sign in to confirm|http error 4\\d\\d|unable to extract|nsig|signature|player response|unable to download|failed to extract|video unavailable|ERROR:|exit code", RegexOption.IGNORE_CASE).containsMatchIn(m)
    }

    /** Met le moteur à jour (au plus une fois tous les 15 minutes). Renvoie true si on peut réessayer. */
    @Synchronized
    private fun fix(): Boolean {
        val since = System.currentTimeMillis() - lastFix
        if (since < 2 * 60_000L) return true
        if (since < 15 * 60_000L) return false
        lastFix = System.currentTimeMillis()
        val r = runCatching { YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE) }.getOrNull()
        if (r == null || r != YoutubeDL.UpdateStatus.DONE) runCatching { YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.NIGHTLY) }
        return true
    }

    private fun exec(req: YoutubeDLRequest, pid: String?, cb: ((Float, Long, String) -> Unit)?): String {
        ensure()
        try {
            return YoutubeDL.getInstance().execute(req, pid, cb).out
        } catch (e: Exception) {
            if (!looksBroken(e) || !fix()) throw e
            return YoutubeDL.getInstance().execute(req, pid, cb).out
        }
    }

    private fun base(url: String): YoutubeDLRequest =
        YoutubeDLRequest(url).addOption("--no-warnings").addOption("--socket-timeout", "15").addOption("-R", "2")

    fun cancel(pid: String) { runCatching { YoutubeDL.destroyProcessById(pid) } }

    fun safeName(s: String): String {
        val t = s.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), " ").replace(Regex("\\s+"), " ").trim().take(100)
        return t.ifEmpty { "titre" }
    }

    @Volatile private var searchSeq = 0

    /** Recherche YouTube : titres, chaînes, durées. */
    fun search(q: String, n: Int = 15): JSONArray {
        val clean = q.replace(Regex("[\\u0000-\\u001f]"), " ").trim().take(120)
        val out = JSONArray()
        if (clean.isEmpty()) return out
        val seq = ++searchSeq
        if (seq > 1) cancel("search${seq - 1}")
        val req = base("ytsearch$n:$clean").addOption("--flat-playlist").addOption("--dump-single-json")
        val entries = JSONObject(exec(req, "search$seq", null)).optJSONArray("entries") ?: JSONArray()
        for (i in 0 until entries.length()) {
            val o = entries.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.length != 11 || o.optString("live_status") == "is_live") continue
            out.put(JSONObject()
                .put("id", id)
                .put("title", o.optString("title"))
                .put("channel", o.optString("channel").ifBlank { o.optString("uploader") }.removeSuffix(" - Topic"))
                .put("duration", Math.round(o.optDouble("duration", 0.0)).toInt())
                .put("views", o.optLong("view_count", 0L))
                .put("thumb", "https://i.ytimg.com/vi/$id/mqdefault.jpg"))
        }
        return out
    }

    /** Premier résultat de recherche dont la durée est proche (titres d'albums). */
    fun bestMatch(artist: String, title: String, dur: Int): String? {
        val r = search((artist + " " + title).trim(), 8)
        if (r.length() == 0) return null
        if (dur > 0) for (i in 0 until r.length()) { val o = r.getJSONObject(i); if (Math.abs(o.getInt("duration") - dur) <= 12) return o.getString("id") }
        return r.getJSONObject(0).getString("id")
    }

    /** Audio : on garde le m4a de YouTube tel quel ; sinon repli en MP3 (ffmpeg). */
    fun downloadAudio(yid: String, dir: File, name: String, light: Boolean, kbps: Int, pid: String, stopped: () -> Boolean, onProgress: (Float) -> Unit): File {
        dir.mkdirs()
        val base = File(dir, safeName(name)).path
        val cb: (Float, Long, String) -> Unit = { p, _, _ -> onProgress((p / 100f).coerceIn(0f, 1f)) }
        val url = "https://www.youtube.com/watch?v=$yid"
        val fmt = if (light) "bestaudio[ext=m4a][abr<=70]/bestaudio[ext=m4a]" else "bestaudio[ext=m4a]"
        try {
            exec(base(url).addOption("--no-playlist").addOption("--no-mtime").addOption("--newline").addOption("-f", fmt).addOption("-o", "$base.%(ext)s"), pid, cb)
            val f = File("$base.m4a")
            if (f.exists() && f.length() > 1000) return f
        } catch (e: Exception) { if (stopped()) throw e }
        exec(base(url).addOption("--no-playlist").addOption("--no-mtime").addOption("--newline").addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
            .addOption("-x").addOption("--audio-format", "mp3").addOption("--audio-quality", "${kbps}K").addOption("-o", "$base.%(ext)s"), pid, cb)
        val f = File("$base.mp3")
        if (!f.exists()) throw IOException("fichier introuvable")
        return f
    }

    /** Vidéo MP4 (hauteur maximale choisie). maxBytes > 0 : on retente en plus petit si le fichier est trop lourd. */
    fun downloadVideo(yid: String, dir: File, name: String, height: Int, maxBytes: Long, pid: String, stopped: () -> Boolean, onProgress: (Float) -> Unit): File {
        dir.mkdirs()
        val base = File(dir, safeName(name)).path
        val cb: (Float, Long, String) -> Unit = { p, _, _ -> onProgress((p / 100f).coerceIn(0f, 1f)) }
        val hs = listOf(720, 480, 360).filter { it <= height }.ifEmpty { listOf(360) }
        for (h in hs) {
            if (maxBytes > 0) File("$base.mp4").delete()
            var req = base("https://www.youtube.com/watch?v=$yid").addOption("--no-playlist").addOption("--no-mtime").addOption("--newline")
                .addOption("-f", "bv*[height<=$h]+ba/b[height<=$h]/bv*+ba/b")
                .addOption("-S", "res:$h,vcodec:h264,acodec:m4a,ext:mp4:m4a")
                .addOption("--merge-output-format", "mp4")
            if (maxBytes > 0) req = req.addOption("--max-filesize", maxBytes.toString())
            req = req.addOption("-o", "$base.%(ext)s")
            try { exec(req, pid, cb) } catch (e: Exception) { if (maxBytes <= 0 || stopped()) throw e }
            val f = File("$base.mp4")
            if (f.exists()) return f
        }
        throw IOException("vidéo trop lourde ou introuvable")
    }

    private fun headersOf(o: JSONObject): Map<String, String> {
        val m = HashMap<String, String>()
        o.optJSONObject("http_headers")?.let { h -> h.keys().forEach { k -> m[k] = h.optString(k) } }
        return m
    }

    /** Mode p2p : flux m4a direct + taille exacte (mêmes règles que les autres applications). */
    fun resolveStream(yid: String, light: Boolean, fid: String?): Stream? {
        val sel = fid ?: if (light) "bestaudio[ext=m4a][abr<=70]/bestaudio[ext=m4a]" else "bestaudio[ext=m4a]"
        val o = JSONObject(exec(base("https://www.youtube.com/watch?v=$yid").addOption("--no-playlist").addOption("-f", sel).addOption("--dump-json"), null, null))
        val url = o.optString("url")
        if (!url.startsWith("https://")) return null
        if (!o.optString("ext").equals("m4a", true) || !o.optString("protocol").startsWith("http")) return null
        val id = o.optString("format_id")
        if (!Regex("^[A-Za-z0-9_.-]{1,24}$").matches(id)) return null
        val size = Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: o.optLong("filesize", 0L).takeIf { it > 0 } ?: return null
        return Stream(id, url, headersOf(o), size)
    }

    /** Télécharge les octets a..b (inclus) ; refuse toute réponse qui n'est pas exactement le morceau demandé. */
    fun fetchRange(st: Stream, a: Long, b: Long): ByteArray {
        val conn = URL(st.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            st.headers.forEach { (k, v) -> if (!k.equals("Range", true) && !k.equals("Host", true)) conn.setRequestProperty(k, v) }
            conn.setRequestProperty("Range", "bytes=$a-$b")
            if (conn.responseCode != 206) throw IOException("HTTP ${conn.responseCode}")
            val buf = conn.inputStream.use { it.readBytes() }
            if (buf.size.toLong() != b - a + 1) throw IOException("morceau incomplet (${buf.size}/${b - a + 1})")
            return buf
        } finally { conn.disconnect() }
    }

    /** Lecture sans télécharger : vidéo MP4 avec son, ou audio. */
    fun resolveMedia(yid: String, video: Boolean): MediaSrc {
        val sel = if (video) "b[ext=mp4][height<=720][vcodec!=none][acodec!=none][protocol=https]/b[ext=mp4][vcodec!=none][acodec!=none][protocol=https]/18"
                  else "bestaudio[ext=m4a][protocol=https]/bestaudio[protocol=https]"
        val o = JSONObject(exec(base("https://www.youtube.com/watch?v=$yid").addOption("--no-playlist").addOption("-f", sel).addOption("--dump-json"), null, null))
        val url = o.optString("url")
        if (!url.startsWith("https://")) throw IOException("flux introuvable")
        return MediaSrc(url, headersOf(o), o.optDouble("duration", 0.0).toInt())
    }
}
