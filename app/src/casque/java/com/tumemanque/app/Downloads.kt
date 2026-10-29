package com.tumemanque.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// =============================================================================================
//  Formats proposés
// =============================================================================================

enum class DlKind(val label: String, val sub: String, val video: Boolean, val ext: String, val res: Int = 0) {
    MP3_HQ("MP3", "Haute qualité · 320 kbit/s", false, "mp3"),
    MP3_LITE("MP3 léger", "128 kbit/s · prend peu de place", false, "mp3"),
    M4A("M4A", "Qualité d'origine, sans conversion", false, "m4a"),
    MP4_360("MP4 360p", "Petit fichier, idéal hors connexion", true, "mp4", 360),
    MP4_720("MP4 720p", "HD", true, "mp4", 720),
    MP4_1080("MP4 1080p", "Full HD", true, "mp4", 1080),
}

enum class DlStatus { QUEUED, RUNNING, PROCESSING, DONE, FAILED, CANCELED }

data class DlTask(
    val id: String,
    val video: YtVideo,
    val kind: DlKind,
    val status: DlStatus = DlStatus.QUEUED,
    val progress: Float = 0f,
    val eta: Long = -1,
    val phase: String = "Connexion au serveur…",
    val resultId: String? = null,
    val error: String? = null,
    val size: Long = 0,
    val created: Long = System.currentTimeMillis(),
) {
    val active: Boolean get() = status == DlStatus.QUEUED || status == DlStatus.RUNNING || status == DlStatus.PROCESSING

    fun toJson(): JSONObject = JSONObject().put("id", id).put("v", video.toJson()).put("k", kind.name).put("s", status.name)
        .put("r", resultId ?: "").put("e", error ?: "").put("z", size).put("c", created)

    companion object {
        fun fromJson(o: JSONObject): DlTask? = runCatching {
            var st = DlStatus.valueOf(o.getString("s"))
            var err = o.optString("e").ifEmpty { null }
            if (st == DlStatus.RUNNING || st == DlStatus.PROCESSING || st == DlStatus.QUEUED) { st = DlStatus.FAILED; err = "Interrompu. Touche « Réessayer »." }
            DlTask(
                o.getString("id"), YtVideo.fromJson(o.getJSONObject("v")), DlKind.valueOf(o.getString("k")), st,
                if (st == DlStatus.DONE) 1f else 0f, -1, "", o.optString("r").ifEmpty { null }, err, o.optLong("z"), o.optLong("c"),
            )
        }.getOrNull()
    }
}

// =============================================================================================
//  La file des téléchargements
// =============================================================================================

object Dl {
    private lateinit var app: Context
    private val _tasks = MutableStateFlow<List<DlTask>>(emptyList())
    val tasks: StateFlow<List<DlTask>> = _tasks.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runLock = Mutex()
    private val canceled = HashSet<String>()

    /** La notification suit la progression. */
    var listener: ((DlTask?) -> Unit)? = null

    private fun file() = File(app.filesDir, "downloads.json")

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        runCatching {
            val f = file()
            if (f.exists()) {
                val a = JSONArray(f.readText())
                _tasks.value = (0 until a.length()).mapNotNull { DlTask.fromJson(a.getJSONObject(it)) }
            }
        }
    }

    private fun save() {
        runCatching { file().writeText(JSONArray(_tasks.value.take(300).map { it.toJson() }).toString()) }
    }

    private fun edit(id: String, f: (DlTask) -> DlTask) {
        _tasks.update { l -> l.map { if (it.id == id) f(it) else it } }
    }

    fun find(id: String): DlTask? = _tasks.value.firstOrNull { it.id == id }

    /** Le téléchargement de cette vidéo dans ce format (le plus récent). */
    fun of(ytId: String, kind: DlKind): DlTask? = _tasks.value.firstOrNull { it.video.id == ytId && it.kind == kind && it.status != DlStatus.CANCELED }

    fun enqueue(ctx: Context, v: YtVideo, kind: DlKind) {
        init(ctx)
        val existing = of(v.id, kind)
        if (existing != null && (existing.active || existing.status == DlStatus.DONE && stillThere(existing))) return
        val t = DlTask("d" + System.currentTimeMillis() + (0..999).random(), v, kind)
        _tasks.update { listOf(t) + it.filterNot { o -> o.video.id == v.id && o.kind == kind && !o.active } }
        save()
        kick(ctx)
    }

    /** Le fichier existe-t-il encore dans la bibliothèque ? */
    fun stillThere(t: DlTask): Boolean {
        val r = t.resultId ?: return false
        return if (t.kind.video) Vids.items.value.any { it.id == r } else Lib.byId.value.containsKey(r)
    }

    fun retry(ctx: Context, id: String) {
        val t = find(id) ?: return
        synchronized(canceled) { canceled.remove(id) }
        edit(id) { it.copy(status = DlStatus.QUEUED, progress = 0f, error = null, phase = "Connexion au serveur…") }
        save()
        kick(ctx)
    }

    fun cancel(id: String) {
        synchronized(canceled) { canceled.add(id) }
        val t = find(id) ?: return
        if (t.status == DlStatus.QUEUED) edit(id) { it.copy(status = DlStatus.CANCELED, phase = "Annulé") }
        else runCatching { YoutubeDL.destroyProcessById(id) }
        save()
    }

    fun remove(id: String) {
        cancel(id)
        _tasks.update { l -> l.filterNot { it.id == id } }
        save()
    }

    fun clearFinished() {
        _tasks.update { l -> l.filter { it.active } }
        save()
    }

    private fun kick(ctx: Context) {
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        } catch (_: Exception) {
            // Démarrage du service refusé : on télécharge quand même tant que l'appli est ouverte.
            scope.launch { runQueue() }
        }
    }

    /** Traite la file jusqu'à ce qu'elle soit vide. */
    suspend fun runQueue() {
        while (true) {
            if (!runLock.tryLock()) return
            try {
                while (true) {
                    val next = _tasks.value.lastOrNull { it.status == DlStatus.QUEUED } ?: break
                    process(next)
                }
            } finally {
                runLock.unlock()
            }
            if (_tasks.value.none { it.status == DlStatus.QUEUED }) return
        }
    }

    private fun isCanceled(id: String) = synchronized(canceled) { id in canceled }

    private fun request(t: DlTask, dir: File, full: Boolean): YoutubeDLRequest {
        val r = YoutubeDLRequest(t.video.url)
            .addOption("--no-playlist")
            .addOption("--no-mtime")
            .addOption("--newline")
            .addOption("--socket-timeout", "20")
            .addOption("-R", "3")
            .addOption("--no-warnings")
            .addOption("-o", File(dir, "media.%(ext)s").absolutePath)
        if (!t.kind.video) {
            r.addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
            r.addOption("-x")
            r.addOption("--audio-format", t.kind.ext)
            when (t.kind) {
                DlKind.MP3_HQ -> r.addOption("--audio-quality", "320K")
                DlKind.MP3_LITE -> r.addOption("--audio-quality", "128K")
                else -> {}
            }
            if (full) {
                // Titre et artiste propres dans le fichier, et la miniature en pochette carrée.
                val (title, artist) = cleanTitle(t.video.title, t.video.channel)
                r.addOption("--parse-metadata", "%(release_year,upload_date)s:%(meta_date)s")
                r.addCommands(listOf("--replace-in-metadata", "track,title", ".+", title.replace("\\", "\\\\")))
                r.addCommands(listOf("--replace-in-metadata", "artist,creator,uploader", ".+", artist.replace("\\", "\\\\")))
                r.addOption("--embed-metadata")
                r.addOption("--embed-thumbnail")
                r.addOption("--convert-thumbnails", "jpg")
                val cfg = File(dir.parentFile, "crop.conf")
                if (!cfg.exists()) cfg.writeText("--ppa \"ffmpeg: -c:v mjpeg -vf crop=\\\"'if(gt(ih,iw),iw,ih)':'if(gt(iw,ih),ih,iw)'\\\"\"")
                r.addOption("--config", cfg.absolutePath)
            }
        } else {
            r.addOption("-f", "bv*+ba/b")
            r.addOption("-S", "res:${t.kind.res},vcodec:h264,acodec:m4a,ext:mp4:m4a")
            r.addOption("--merge-output-format", "mp4")
            if (full) r.addOption("--embed-metadata")
        }
        return r
    }

    private suspend fun process(t0: DlTask) {
        val id = t0.id
        edit(id) { it.copy(status = DlStatus.RUNNING, progress = 0f, phase = "Connexion au serveur…", error = null) }
        listener?.invoke(find(id))
        val dir = File(app.cacheDir, "dl/$id")
        try {
            Yt.ensure()
            var ok = false
            var lastErr: Throwable? = null
            for (full in listOf(true, false)) {
                if (isCanceled(id)) throw YoutubeDL.CanceledException()
                dir.deleteRecursively(); dir.mkdirs()
                var streams = 0
                var lastPush = 0L
                try {
                    EngineHeal.exec(request(t0, dir, full), id) { p, eta, line ->
                        var phase: String? = null
                        var processing = false
                        when {
                            line.startsWith("[download] Destination:") -> streams++
                            line.startsWith("[ExtractAudio]") -> { phase = "Conversion en ${t0.kind.ext.uppercase()}…"; processing = true }
                            line.startsWith("[Merger]") -> { phase = "Assemblage de l'image et du son…"; processing = true }
                            line.startsWith("[EmbedThumbnail]") -> { phase = "Ajout de la pochette…"; processing = true }
                            line.startsWith("[Metadata]") -> { phase = "Ajout du titre et de l'artiste…"; processing = true }
                        }
                        val frac = (p / 100f).coerceIn(0f, 1f)
                        val overall = if (t0.kind.video) { if (streams >= 2) 0.85f + 0.13f * frac else 0.85f * frac } else 0.9f * frac
                        val now = System.currentTimeMillis()
                        if (processing || now - lastPush > 350) {
                            lastPush = now
                            edit(id) { c ->
                                if (c.status == DlStatus.PROCESSING && !processing) c
                                else c.copy(
                                    status = if (processing) DlStatus.PROCESSING else DlStatus.RUNNING,
                                    progress = if (processing) maxOf(c.progress, if (t0.kind.video) 0.98f else 0.93f) else maxOf(c.progress, overall),
                                    eta = eta,
                                    phase = phase ?: if (p > 0) "Téléchargement…" else c.phase,
                                )
                            }
                            listener?.invoke(find(id))
                        }
                    }
                    ok = true
                    break
                } catch (e: YoutubeDL.CanceledException) {
                    throw e
                } catch (e: Exception) {
                    lastErr = e
                    if (isCanceled(id)) throw YoutubeDL.CanceledException()
                    edit(id) { it.copy(phase = "Nouvel essai…", progress = 0f) }
                }
            }
            if (!ok) throw lastErr ?: Exception("Échec")

            val media = dir.listFiles()?.filter {
                it.isFile && it.extension.lowercase() in setOf("mp3", "m4a", "mp4", "webm", "mkv", "opus", "ogg", "aac")
            }?.maxByOrNull { it.length() } ?: throw Exception("Fichier introuvable après le téléchargement")
            edit(id) { it.copy(status = DlStatus.PROCESSING, phase = "Enregistrement…", progress = 0.99f) }
            listener?.invoke(find(id))

            val dur = durationOf(media)
            val size = media.length()
            val resultId: String
            if (!t0.kind.video) {
                val (title, artist) = cleanTitle(t0.video.title, t0.video.channel)
                val saved = Store.save(app, media, title, artist, video = false)
                resultId = saved.first
                Covers.fetch(app, t0.video, resultId, square = true)
                Lib.addTrack(Track(resultId, title, artist, "Free tune", dur, saved.second, 0L, System.currentTimeMillis(), size))
            } else {
                val saved = Store.save(app, media, t0.video.title, t0.video.channel, video = true)
                resultId = saved.first
                Covers.fetch(app, t0.video, resultId, square = false)
                Vids.add(VideoItem(resultId, t0.video, dur, size, System.currentTimeMillis(), t0.kind.label))
            }
            edit(id) { it.copy(status = DlStatus.DONE, progress = 1f, phase = "Terminé", resultId = resultId, size = size) }
            runCatching { MediaSync.onDone(app, t0.video, t0.kind, resultId, size) }
        } catch (e: YoutubeDL.CanceledException) {
            edit(id) { it.copy(status = DlStatus.CANCELED, phase = "Annulé") }
        } catch (e: Throwable) {
            edit(id) { it.copy(status = DlStatus.FAILED, error = friendly(e), phase = "Échec") }
        } finally {
            runCatching { dir.deleteRecursively() }
            synchronized(canceled) { canceled.remove(id) }
            save()
            listener?.invoke(find(id))
        }
    }

    private fun durationOf(f: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L } finally { runCatching { r.release() } }
    }

    private fun friendly(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("Sign in to confirm", true) || m.contains("bot", true) ->
                "Le serveur demande une vérification. Réessaie dans quelques minutes, ou mets à jour le moteur dans Paramètres."
            m.contains("unavailable", true) || m.contains("private", true) -> "Cette vidéo n'est pas disponible."
            m.contains("403") -> "Le serveur a refusé le téléchargement. Mets à jour le moteur dans Paramètres puis réessaie."
            m.contains("No space", true) -> "Plus de place sur le téléphone."
            m.contains("resolve", true) || m.contains("timed out", true) || m.contains("Network", true) || m.contains("connection", true) ->
                "Pas de connexion internet ?"
            else -> m.lineSequence().map { it.removePrefix("ERROR: ").trim() }.firstOrNull { it.isNotEmpty() }?.take(160) ?: "Le téléchargement a échoué."
        }
    }
}

// =============================================================================================
//  Enregistrement dans Musique/Casque et Films/Casque
// =============================================================================================

object Store {
    private fun safe(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(90).ifEmpty { "Casque" }

    fun mime(ext: String) = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        else -> "video/mp4"
    }

    /** Copie le fichier là où il restera ; renvoie (identifiant, dossier). */
    fun save(ctx: Context, src: File, title: String, artist: String, video: Boolean): Pair<String, String> {
        val name = safe(if (video) title else "$artist - $title") + "." + src.extension.lowercase()
        if (video) {
            // Les vidéos restent dans l'application : jamais dans la galerie du téléphone.
            val base = File(ctx.filesDir, "videos").apply { mkdirs() }
            var dest = File(base, name)
            var i = 1
            while (dest.exists()) { dest = File(base, name.substringBeforeLast('.') + " ($i)." + name.substringAfterLast('.')); i++ }
            src.copyTo(dest)
            return Uri.fromFile(dest).toString() to base.absolutePath
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val rel = if (video) Environment.DIRECTORY_MOVIES + "/Casque" else Environment.DIRECTORY_MUSIC + "/Casque"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime(src.extension))
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.MediaColumns.TITLE, title)
                if (!video) {
                    put(MediaStore.Audio.Media.ARTIST, artist)
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                }
            }
            val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = ctx.contentResolver.insert(collection, values) ?: throw Exception("Impossible d'enregistrer le fichier")
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                    ?: throw Exception("Impossible d'écrire le fichier")
                ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { ctx.contentResolver.delete(uri, null, null) }
                throw e
            }
            val num = uri.lastPathSegment ?: ""
            val id = if (video) "content://media/external/video/media/$num" else "content://media/external/audio/media/$num"
            val folder = Environment.getExternalStorageDirectory().absolutePath + "/" + rel
            return id to folder
        } else {
            val base = ctx.getExternalFilesDir(if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC) ?: File(ctx.filesDir, if (video) "videos" else "music")
            base.mkdirs()
            var dest = File(base, name)
            var i = 1
            while (dest.exists()) { dest = File(base, name.substringBeforeLast('.') + " ($i)." + name.substringAfterLast('.')); i++ }
            src.copyTo(dest)
            return Uri.fromFile(dest).toString() to base.absolutePath
        }
    }

    fun delete(ctx: Context, id: String) {
        runCatching {
            val u = Uri.parse(id)
            if (u.scheme == "file") File(u.path ?: return).delete() else ctx.contentResolver.delete(u, null, null)
        }
    }
}

// =============================================================================================
//  Pochettes et miniatures (prises sur YouTube)
// =============================================================================================

object Covers {
    fun file(ctx: Context, key: String): File = File(ctx.filesDir, "covers/" + Integer.toHexString(key.hashCode()) + "_" + key.length + ".jpg")

    fun has(ctx: Context, key: String) = file(ctx, key).exists()

    fun load(ctx: Context, key: String, px: Int): Bitmap? {
        val f = file(ctx, key)
        if (!f.exists()) return null
        return runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, o)
            var s = 1
            while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
        }.getOrNull()
    }

    private fun fetchBytes(url: String): ByteArray? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
        if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }
    }.getOrNull()

    suspend fun fetch(ctx: Context, v: YtVideo, key: String, square: Boolean) = withContext(Dispatchers.IO) {
        runCatching {
            val data = fetchBytes(v.thumbHd) ?: fetchBytes(v.thumb) ?: return@runCatching
            var bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return@runCatching
            // hqdefault a des bandes noires : on recadre en 16:9, puis en carré pour la musique.
            val w = bmp.width; val h = bmp.height
            val h169 = (w * 9 / 16).coerceAtMost(h)
            bmp = Bitmap.createBitmap(bmp, 0, (h - h169) / 2, w, h169)
            if (square) {
                val s = minOf(bmp.width, bmp.height)
                bmp = Bitmap.createBitmap(bmp, (bmp.width - s) / 2, (bmp.height - s) / 2, s, s)
            }
            val f = file(ctx, key)
            f.parentFile?.mkdirs()
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
    }
}

// =============================================================================================
//  Les vidéos téléchargées
// =============================================================================================

data class VideoItem(
    val id: String,
    val video: YtVideo,
    val durationMs: Long,
    val size: Long,
    val added: Long,
    val format: String,
)

object Vids {
    private lateinit var app: Context
    private val _items = MutableStateFlow<List<VideoItem>>(emptyList())
    val items: StateFlow<List<VideoItem>> = _items.asStateFlow()

    private fun file() = File(app.filesDir, "videos.json")
    private fun prefs() = app.getSharedPreferences("casque_videos", Context.MODE_PRIVATE)

    /** Les anciennes vidéos rangées dans la galerie (Films/Casque) sont rapatriées dans l'application. */
    private fun migrate() {
        val old = _items.value.filter { it.id.startsWith("content://") }
        if (old.isEmpty()) return
        Thread {
            for (v in old) runCatching {
                val u = Uri.parse(v.id)
                val base = File(app.filesDir, "videos").apply { mkdirs() }
                val ext = "mp4"
                val dest = File(base, v.video.id + "_" + System.currentTimeMillis() + "." + ext)
                app.contentResolver.openInputStream(u)?.use { i -> dest.outputStream().use { o -> i.copyTo(o) } } ?: return@runCatching
                if (dest.length() <= 0L) { dest.delete(); return@runCatching }
                // On ne bascule que si la copie de la galerie a bien pu être supprimée.
                val removed = runCatching { app.contentResolver.delete(u, null, null) }.getOrDefault(0)
                if (removed <= 0) { dest.delete(); return@runCatching }
                val newId = Uri.fromFile(dest).toString()
                runCatching { val c = Covers.file(app, v.id); if (c.exists()) c.renameTo(Covers.file(app, newId)) }
                prefs().edit().putLong("pos_$newId", prefs().getLong("pos_${v.id}", 0L)).remove("pos_${v.id}").apply()
                _items.update { l -> l.map { o -> if (o.id == v.id) o.copy(id = newId) else o } }
                save()
            }
        }.start()
    }

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        runCatching {
            val f = file()
            if (f.exists()) {
                val a = JSONArray(f.readText())
                _items.value = (0 until a.length()).mapNotNull { i ->
                    runCatching {
                        val o = a.getJSONObject(i)
                        VideoItem(o.getString("id"), YtVideo.fromJson(o.getJSONObject("v")), o.optLong("d"), o.optLong("s"), o.optLong("a"), o.optString("f"))
                    }.getOrNull()
                }
            }
        }
        migrate()
    }

    private fun save() {
        runCatching {
            file().writeText(
                JSONArray(_items.value.map {
                    JSONObject().put("id", it.id).put("v", it.video.toJson()).put("d", it.durationMs).put("s", it.size).put("a", it.added).put("f", it.format)
                }).toString()
            )
        }
    }

    fun add(v: VideoItem) {
        _items.update { listOf(v) + it.filterNot { o -> o.id == v.id } }
        save()
    }

    fun delete(ctx: Context, id: String) {
        Store.delete(ctx, id)
        runCatching { Covers.file(ctx, id).delete() }
        _items.update { it.filterNot { o -> o.id == id } }
        prefs().edit().remove("pos_$id").apply()
        save()
    }

    fun position(id: String): Long = prefs().getLong("pos_$id", 0L)

    fun savePosition(id: String, ms: Long, durationMs: Long) {
        // Vidéo presque finie : la prochaine fois, on repart du début.
        val v = if (durationMs > 0 && ms > durationMs - 8000) 0L else ms
        prefs().edit().putLong("pos_$id", v).apply()
    }
}

// =============================================================================================
//  Le service : les téléchargements continuent écran éteint, avec une notification
// =============================================================================================

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var lastNotif = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    private fun channel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(NotificationChannel(CH, "Téléchargements", NotificationManager.IMPORTANCE_LOW))
            }
            if (nm.getNotificationChannel(CH_DONE) == null) {
                nm.createNotificationChannel(NotificationChannel(CH_DONE, "Téléchargements terminés", NotificationManager.IMPORTANCE_DEFAULT))
            }
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 7, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("casque_tab", "downloads"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun build(t: DlTask?): android.app.Notification {
        val left = Dl.tasks.value.count { it.active }
        val b = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
        if (t == null) {
            b.setContentTitle("Free tune").setContentText("Préparation du téléchargement…").setProgress(0, 0, true)
        } else {
            val pct = (t.progress * 100).toInt()
            b.setContentTitle(t.video.title)
                .setContentText("${t.kind.label} · $pct %" + if (left > 1) " · ${left - 1} en attente" else "")
                .setProgress(100, pct, t.status == DlStatus.PROCESSING)
        }
        return b.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channel()
        Dl.init(this)
        ServiceCompat.startForeground(
            this, NOTIF_ID, build(null),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        Dl.listener = { t ->
            val now = System.currentTimeMillis()
            if (t != null && (now - lastNotif > 900 || !t.active)) {
                lastNotif = now
                if (t.active) runCatching { NotificationManagerCompat.from(this).notify(NOTIF_ID, build(t)) }
                else if (t.status == DlStatus.DONE) done(t)
            }
        }
        if (job?.isActive != true) {
            job = scope.launch {
                Dl.runQueue()
                Dl.listener = null
                ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun done(t: DlTask) {
        runCatching {
            val n = NotificationCompat.Builder(this, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Téléchargé : ${t.video.title}")
                .setContentText(if (t.kind.video) "Disponible dans Vidéos" else "Disponible dans Audios")
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build()
            NotificationManagerCompat.from(this).notify(t.id.hashCode(), n)
        }
    }

    override fun onDestroy() {
        Dl.listener = null
        super.onDestroy()
    }

    companion object {
        const val CH = "casque_dl"
        const val CH_DONE = "casque_dl_done"
        const val NOTIF_ID = 4242
    }
}
