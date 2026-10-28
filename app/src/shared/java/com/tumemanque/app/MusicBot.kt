package com.tumemanque.app

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

// =============================================================================================
//  Le bot @music : on lui demande une chanson dans le chat, il la trouve et la télécharge.
// =============================================================================================

/** Une chanson proposée par le bot. */
data class MbSong(val id: String, val title: String, val channel: String, val durationSec: Int, val views: Long) {
    val url get() = "https://www.youtube.com/watch?v=$id"
    val thumb get() = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
}

object MbEngine {
    private val lock = Mutex()
    @Volatile private var ready = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 24

    suspend fun ensure(ctx: Context) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (ready) return@withLock
            YoutubeDL.getInstance().init(ctx.applicationContext)
            FFmpeg.getInstance().init(ctx.applicationContext)
            ready = true
            // YouTube change souvent : on met le moteur à jour une fois par jour.
            val p = ctx.getSharedPreferences("music_bot", Context.MODE_PRIVATE)
            if (System.currentTimeMillis() - p.getLong("upd", 0L) > 20 * 3600_000L) {
                scope.launch {
                    runCatching { YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE) }
                    p.edit().putLong("upd", System.currentTimeMillis()).apply()
                }
            }
        }
    }

    private fun words(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(" ").filter { it.length > 1 }.toSet()

    /** Classe les résultats selon ce qu'on a compris de la demande. */
    private fun score(q: String, s: MbSong): Double {
        val want = words(q)
        val t = (s.title + " " + s.channel).lowercase()
        val have = words(t)
        var sc = if (want.isEmpty()) 0.0 else want.count { it in have }.toDouble() / want.size * 10
        val ql = q.lowercase()
        if ("official audio" in t || "audio officiel" in t || s.channel.endsWith(" - Topic")) sc += 2.5
        if ("official" in t || "officiel" in t || "clip" in t) sc += 1.0
        for (bad in listOf("live", "cover", "remix", "karaoke", "instrumental", "reaction", "lyrics", "paroles", "8d", "slowed", "speed up")) {
            if (bad in t && bad !in ql) sc -= 2.0
        }
        if (s.durationSec in 90..480) sc += 1.5 else if (s.durationSec > 900) sc -= 3.0
        sc += kotlin.math.log10((s.views + 10).toDouble()) * 0.4
        return sc
    }

    suspend fun search(ctx: Context, q: String): List<MbSong> = withContext(Dispatchers.IO) {
        ensure(ctx)
        val req = YoutubeDLRequest("ytsearch10:$q")
            .addOption("--flat-playlist").addOption("--dump-single-json")
            .addOption("--no-warnings").addOption("--socket-timeout", "15")
        val o = JSONObject(YoutubeDL.getInstance().execute(req, null, null).out)
        val e = o.optJSONArray("entries") ?: return@withContext emptyList()
        (0 until e.length()).mapNotNull { i ->
            val x = e.optJSONObject(i) ?: return@mapNotNull null
            val id = x.optString("id")
            if (id.length != 11 || x.optString("live_status") == "is_live") return@mapNotNull null
            MbSong(
                id, x.optString("title"), x.optString("channel").ifBlank { x.optString("uploader") },
                x.optDouble("duration", 0.0).toInt(), x.optLong("view_count", 0L),
            )
        }.sortedByDescending { score(q, it) }
    }

    /** Télécharge la chanson en MP3. */
    suspend fun download(ctx: Context, s: MbSong, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        ensure(ctx)
        val dir = File(ctx.cacheDir, "cam/music/${s.id}").apply { deleteRecursively(); mkdirs() }
        val req = YoutubeDLRequest(s.url)
            .addOption("--no-playlist").addOption("--no-mtime").addOption("--newline").addOption("--no-warnings")
            .addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
            .addOption("-x").addOption("--audio-format", "mp3").addOption("--audio-quality", "192K")
            .addOption("--embed-metadata")
            .addOption("-o", File(dir, "song.%(ext)s").absolutePath)
        YoutubeDL.getInstance().execute(req, "bot${s.id}") { p, _, _ -> onProgress((p / 100f).coerceIn(0f, 1f)) }
        val f = dir.listFiles()?.filter { it.extension.equals("mp3", true) }?.maxByOrNull { it.length() }
            ?: throw Exception("Fichier introuvable")
        val nice = File(dir, safeName(s.title) + ".mp3")
        f.renameTo(nice)
        nice
    }

    /** Télécharge la vidéo en MP4 (480p max, sous 48 Mo pour pouvoir l'envoyer dans le chat). */
    suspend fun downloadVideo(ctx: Context, s: MbSong, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        ensure(ctx)
        val dir = File(ctx.cacheDir, "cam/video/${s.id}").apply { deleteRecursively(); mkdirs() }
        val req = YoutubeDLRequest(s.url)
            .addOption("--no-playlist").addOption("--no-mtime").addOption("--newline").addOption("--no-warnings")
            .addOption("-f", "bv*[height<=480]+ba/b[height<=480]/bv*+ba/b")
            .addOption("-S", "res:480,vcodec:h264,acodec:m4a,ext:mp4:m4a")
            .addOption("--merge-output-format", "mp4")
            .addOption("--max-filesize", "48M")
            .addOption("-o", File(dir, "video.%(ext)s").absolutePath)
        YoutubeDL.getInstance().execute(req, "botv${s.id}") { p, _, _ -> onProgress((p / 100f).coerceIn(0f, 1f)) }
        val f = dir.listFiles()?.filter { it.extension.equals("mp4", true) }?.maxByOrNull { it.length() }
            ?: throw Exception("Vidéo trop lourde ou introuvable")
        val nice = File(dir, safeName(s.title) + ".mp4")
        f.renameTo(nice)
        nice
    }

    /** Largeur, hauteur et durée (s) d'une vidéo. */
    fun videoInfo(f: File): Triple<Int, Int, Int> {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            var w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 16
            var h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 9
            val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val x = w; w = h; h = x }
            val d = ((r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000).toInt()
            Triple(w, h, d)
        } catch (_: Exception) { Triple(16, 9, 0) } finally { runCatching { r.release() } }
    }

    /** Enregistre une vidéo dans la mémoire du téléphone (dossier Films). */
    fun saveVideoToPhone(ctx: Context, f: File, title: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Tu me manques")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.MediaColumns.TITLE, title)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v)
                ?: throw Exception("Enregistrement impossible")
            ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return "Films › Tu me manques"
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
            f.copyTo(File(dir, f.name), overwrite = true)
            return dir.absolutePath
        }
    }

    /** Récupère le fichier d'un message du chat (déjà sur le téléphone, ou téléchargé). */
    fun localCopy(ctx: Context, m: ChatMsg, title: String, ext: String, onProgress: ((Float) -> Unit)? = null): File {
        val dir = File(ctx.cacheDir, "cam/share").apply { mkdirs() }
        val f = File(dir, safeName(title) + "." + ext)
        if (f.exists() && f.length() > 0) return f
        val local = m.localFile?.let { File(it) }
        if (local != null && local.exists()) local.copyTo(f, overwrite = true)
        else {
            val c = java.net.URL(Backend.mediaUrl(m.media ?: "")).openConnection()
            val total = c.contentLengthLong.coerceAtLeast(1)
            val tmp = File(dir, f.name + ".part")
            c.getInputStream().use { i ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; onProgress?.invoke((done.toFloat() / total).coerceIn(0f, 1f)) }
                }
            }
            tmp.renameTo(f)
        }
        return f
    }

    fun shareFile(ctx: Context, f: File, mime: String) {
        runCatching {
            val u = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, u)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            i.clipData = ClipData.newRawUri("", u)
            ctx.startActivity(Intent.createChooser(i, "Transférer").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun safeName(t: String) = t.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "chanson" }

    /** Enregistre la chanson dans la mémoire du téléphone (dossier Musique). */
    fun saveToPhone(ctx: Context, f: File, title: String, artist: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Tu me manques")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.MediaColumns.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v)
                ?: throw Exception("Enregistrement impossible")
            ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return "Musique › Tu me manques"
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: ctx.filesDir
            f.copyTo(File(dir, f.name), overwrite = true)
            return dir.absolutePath
        }
    }

    fun share(ctx: Context, f: File) {
        runCatching {
            val u = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            val i = Intent(Intent.ACTION_SEND).setType("audio/mpeg").putExtra(Intent.EXTRA_STREAM, u)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            i.clipData = ClipData.newRawUri("", u)
            ctx.startActivity(Intent.createChooser(i, "Envoyer la chanson").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

// ---------------------------------------------------------------------------------------------
//  La boîte de dialogue du bot (même esprit que les jeux)
// ---------------------------------------------------------------------------------------------

private sealed class MbMsg {
    data class Bot(val text: String) : MbMsg()
    data class Me(val text: String) : MbMsg()
    data class Results(val q: String, val list: List<MbSong>) : MbMsg()
    data class Song(val song: MbSong) : MbMsg()
}

private val BPink = Color(0xFFFF4F86)
private val BTxt = Color(0xFFF5EEF2)
private val BSoft = Color(0xFFC9A9BA)

private fun mbTime(s: Int) = if (s <= 0) "" else "%d:%02d".format(s / 60, s % 60)

@Composable
fun MusicBotOverlay(initial: String, partner: String = "", onSendToChat: ((File, MbSong) -> Unit)? = null, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val msgs = remember {
        mutableStateListOf<MbMsg>(MbMsg.Bot("Salut, je suis @music 🎵 Dis-moi l'artiste et le titre de la chanson que tu veux, je m'occupe du reste."))
    }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val progress = remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    val files = remember { mutableStateOf<Map<String, File>>(emptyMap()) }
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    var playingId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { runCatching { player.value?.release() } } }
    BackHandler { onClose() }

    fun say(m: MbMsg) { msgs.add(m); scope.launch { delay(60); list.animateScrollToItem(msgs.size) } }

    fun ask(q: String) {
        val t = q.trim()
        if (t.isEmpty() || busy) return
        query = ""
        say(MbMsg.Me(t))
        if (!MbEngine.supported) { say(MbMsg.Bot("Désolé, ton téléphone est trop ancien pour que je télécharge des chansons (Android 7 minimum).")); return }
        busy = true
        say(MbMsg.Bot("Je cherche « $t »…"))
        scope.launch {
            try {
                val r = MbEngine.search(ctx, t)
                msgs.removeAt(msgs.lastIndex)
                if (r.isEmpty()) say(MbMsg.Bot("Je n'ai rien trouvé pour « $t ». Essaie avec l'artiste et le titre exacts."))
                else {
                    say(MbMsg.Bot("Voilà ce que j'ai compris. Touche la bonne chanson pour la télécharger :"))
                    say(MbMsg.Results(t, r.take(5)))
                }
            } catch (e: Exception) {
                msgs.removeAt(msgs.lastIndex)
                say(MbMsg.Bot("Oups, la recherche n'a pas marché. Vérifie ta connexion et réessaie."))
            } finally { busy = false }
        }
    }

    fun download(s: MbSong) {
        if (progress.value.containsKey(s.id) || files.value.containsKey(s.id)) return
        say(MbMsg.Song(s))
        progress.value = progress.value + (s.id to 0f)
        scope.launch {
            try {
                val f = MbEngine.download(ctx, s) { p -> progress.value = progress.value + (s.id to p) }
                files.value = files.value + (s.id to f)
                say(MbMsg.Bot("C'est prêt ! Tu peux l'écouter, l'enregistrer dans ton téléphone ou l'envoyer à quelqu'un."))
            } catch (e: Exception) {
                say(MbMsg.Bot("Le téléchargement de « ${s.title} » a échoué. Réessaie dans un instant."))
            } finally {
                progress.value = progress.value - s.id
            }
        }
    }

    fun togglePlay(s: MbSong) {
        val f = files.value[s.id] ?: return
        if (playingId == s.id) { player.value?.pause(); playingId = null; return }
        runCatching { player.value?.release() }
        player.value = MediaPlayer().apply {
            setDataSource(f.absolutePath); prepare(); start()
            setOnCompletionListener { playingId = null }
        }
        playingId = s.id
    }

    LaunchedEffect(Unit) {
        if (initial.isNotBlank()) ask(initial) else { delay(250); runCatching { focus.requestFocus() } }
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF1A0D16), Color(0xFF26122A), Color(0xFF120F1C))))
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color(0xB3281C24)).border(1.dp, Color(0x1AFFFFFF), CircleShape).clickable { onClose() },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = BPink, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🎵 @music", color = BTxt, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Ton bot musique", color = BSoft, fontSize = 12.sp)
                }
                Spacer(Modifier.width(44.dp))
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                itemsIndexed(msgs) { _, m ->
                    val appear = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
                    AnimatedVisibility(appear, enter = fadeIn() + slideInVertically { it / 3 } + scaleIn(initialScale = 0.92f, animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow))) {
                        when (m) {
                            is MbMsg.Bot -> BotBubble(m.text)
                            is MbMsg.Me -> MeBubble(m.text)
                            is MbMsg.Results -> Column(Modifier.padding(start = 40.dp, top = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                m.list.forEachIndexed { i, s -> ResultCard(s, best = i == 0, done = files.value.containsKey(s.id), loading = progress.value.containsKey(s.id)) { download(s) } }
                            }
                            is MbMsg.Song -> SongCard(
                                m.song, progress.value[m.song.id], files.value[m.song.id], playingId == m.song.id,
                                onPlay = { togglePlay(m.song) },
                                onSave = {
                                    files.value[m.song.id]?.let { f ->
                                        val where = runCatching { MbEngine.saveToPhone(ctx, f, m.song.title, m.song.channel.removeSuffix(" - Topic")) }.getOrNull()
                                        say(MbMsg.Bot(if (where != null) "Enregistrée dans ton téléphone ($where) 💾" else "Je n'ai pas réussi à l'enregistrer."))
                                    }
                                },
                                onShare = { files.value[m.song.id]?.let { MbEngine.share(ctx, it) } },
                                chatLabel = if (onSendToChat != null) (if (partner.isNotEmpty()) "💬 Envoyer à @$partner dans le chat" else "💬 Envoyer dans le chat") else null,
                                onChat = {
                                    files.value[m.song.id]?.let { f ->
                                        onSendToChat?.invoke(f, m.song)
                                        say(MbMsg.Bot("Envoyée dans le chat 💬 Ton partenaire pourra l'écouter et la télécharger, même sur iPhone."))
                                    }
                                },
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
            // Barre de recherche du bot
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(Color(0x1FFFFFFF)).border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                ) {
                    BasicTextField(
                        query, { query = it }, singleLine = true,
                        textStyle = TextStyle(color = BTxt, fontSize = 16.sp), cursorBrush = SolidColor(BPink),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { ask(query) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                    if (query.isEmpty()) Text("Artiste et titre…", color = BSoft, fontSize = 16.sp)
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(Brush.linearGradient(listOf(BPink, Color(0xFFD81B60)))).clickable { ask(query) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text("🔍", fontSize = 20.sp)
                }
            }
        }
    }
}

@Composable
private fun BotBubble(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Brush.linearGradient(listOf(BPink, Color(0xFF8E24AA)))), contentAlignment = Alignment.Center) {
            Text("🎵", fontSize = 15.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text, color = BTxt, fontSize = 15.sp,
            modifier = Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)).background(Color(0x1FFFFFFF))
                .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun MeBubble(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.CenterEnd) {
        Text(
            text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                .background(Brush.linearGradient(listOf(BPink, Color(0xFFD81B60)))).padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun ResultCard(s: MbSong, best: Boolean, done: Boolean, loading: Boolean, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (best) Brush.linearGradient(listOf(Color(0x40FF4886), Color(0x33D6185C))) else Brush.linearGradient(listOf(Color(0x12FFFFFF), Color(0x12FFFFFF))))
            .border(1.dp, if (best) BPink else Color(0x1FFFFFFF), RoundedCornerShape(16.dp))
            .clickable { onPick() }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 84.dp, height = 48.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x22FFFFFF))) {
            AsyncImage(model = s.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (best) Text("MEILLEURE CORRESPONDANCE", color = BPink, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Text(s.title, color = BTxt, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(s.channel.removeSuffix(" - Topic"), mbTime(s.durationSec)).filter { it.isNotEmpty() }.joinToString(" · "), color = BSoft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(6.dp))
        Text(if (done) "✓" else if (loading) "…" else "⬇", color = if (done) Color(0xFF66BB6A) else BPink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SongCard(
    s: MbSong, progress: Float?, file: File?, playing: Boolean, onPlay: () -> Unit, onSave: () -> Unit, onShare: () -> Unit,
    chatLabel: String? = null, onChat: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth().padding(start = 40.dp, top = 4.dp, bottom = 6.dp).clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0x33FF4886), Color(0x1F8E24AA)))).border(1.dp, Color(0x40FF4F86), RoundedCornerShape(20.dp)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x22FFFFFF))) {
                AsyncImage(model = s.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, color = BTxt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    when { file != null -> "MP3 · ${file.length() / 1_048_576} Mo"; progress != null -> "Téléchargement… ${(progress * 100).toInt()} %"; else -> "Échec" },
                    color = BSoft, fontSize = 12.sp,
                )
            }
        }
        if (progress != null && file == null) {
            Spacer(Modifier.height(10.dp))
            if (progress > 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)), color = BPink, trackColor = Color(0x1AFFFFFF))
            else LinearProgressIndicator(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)), color = BPink, trackColor = Color(0x1AFFFFFF))
        }
        if (file != null) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BotBtn(if (playing) "⏸ Pause" else "▶ Écouter", Modifier.weight(1f), main = true, onClick = onPlay)
                BotBtn("💾 Enregistrer", Modifier.weight(1f), onClick = onSave)
                BotBtn("📤 Envoyer", Modifier.weight(1f), onClick = onShare)
            }
            if (chatLabel != null) {
                Spacer(Modifier.height(8.dp))
                BotBtn(chatLabel, Modifier.fillMaxWidth(), main = true, onClick = onChat)
            }
        }
    }
}

@Composable
private fun BotBtn(label: String, modifier: Modifier, main: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(50))
            .background(if (main) Brush.linearGradient(listOf(BPink, Color(0xFFD81B60))) else Brush.linearGradient(listOf(Color(0x1FFFFFFF), Color(0x1FFFFFFF))))
            .clickable { onClick() }.padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

// =============================================================================================
//  Le bot dans la conversation : questions, propositions (QCM) et choix visibles des deux côtés
// =============================================================================================

object ChatBot {
    /** Texte à mettre dans la barre de saisie (bouton « Répondre » du bot). */
    var prefill by mutableStateOf<String?>(null)
    /** Envoi d'un message du bot (branché par l'écran du chat). */
    var send: ((String, JSONObject) -> Unit)? = null
    var sendSong: ((File, MbSong, Long) -> Unit)? = null
    var sendVideo: ((File, MbSong, Long) -> Unit)? = null
    private val busy = HashSet<Long>()

    fun bot(m: ChatMsg): JSONObject? = if (m.kind == "text") m.meta?.optJSONObject("bot") else null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("music_bot", Context.MODE_PRIVATE)
    private fun done(ctx: Context, id: Long) = prefs(ctx).getBoolean("h$id", false)
    private fun markDone(ctx: Context, id: Long) { prefs(ctx).edit().putBoolean("h$id", true).apply() }

    fun ask() = send?.invoke("🎵 @music : quelle chanson voulez-vous ? Répondez « @music artiste titre ».", JSONObject().put("t", "ask"))
    fun query(q: String) = send?.invoke("@music $q", JSONObject().put("t", "q").put("q", q).put("dev", "a"))

    /** f = "a" (audio) ou "v" (vidéo). */
    fun pick(res: ChatMsg, k: Int, f: String = "a") {
        val o = bot(res)?.optJSONArray("o")?.optJSONObject(k) ?: return
        send?.invoke("🎵 Choix : ${o.optString("ti")}" + if (f == "v") " (vidéo)" else " (audio)", JSONObject().put("t", "pick").put("r", res.id).put("k", k).put("f", f).put("dev", "a"))
    }

    /** Ce téléphone Android fait le travail (recherche, téléchargement) quand c'est son tour. */
    fun process(ctx: Context, msgs: List<ChatMsg>, scope: CoroutineScope) {
        if (!MbEngine.supported) return
        val now = System.currentTimeMillis()
        for (m in msgs) {
            if (m.id == 0L || m.pending || m.deleted) continue
            val b = bot(m) ?: continue
            if (now - m.createdAt > if (b.optString("t") == "dlreq") 3 * 86_400_000L else 15 * 60_000L) continue
            if (done(ctx, m.id) || m.id in busy) continue
            val dev = b.optString("dev")
            when (b.optString("t")) {
                "q" -> {
                    val mineJob = (dev == "a" && m.mine) || (dev == "w" && !m.mine)
                    if (!mineJob) continue
                    if (msgs.any { bot(it)?.let { x -> x.optString("t") == "res" && x.optLong("r") == m.id } == true }) { markDone(ctx, m.id); continue }
                    busy.add(m.id)
                    val q = b.optString("q")
                    scope.launch {
                        try {
                            val r = MbEngine.search(ctx, q).take(5)
                            if (r.isEmpty()) send?.invoke("🎵 Je n'ai rien trouvé pour « $q ».", JSONObject().put("t", "none").put("r", m.id))
                            else {
                                val arr = org.json.JSONArray()
                                r.forEach { s -> arr.put(JSONObject().put("id", s.id).put("ti", s.title.take(70)).put("ch", s.channel.removeSuffix(" - Topic").take(30)).put("d", s.durationSec)) }
                                send?.invoke("🎵 Quelle chanson pour « $q » ?", JSONObject().put("t", "res").put("r", m.id).put("q", q.take(60)).put("src", "yt").put("o", arr))
                            }
                            markDone(ctx, m.id)
                        } catch (_: Exception) {
                        } finally { busy.remove(m.id) }
                    }
                }
                "pick" -> {
                    val res = msgs.firstOrNull { it.id == b.optLong("r") } ?: continue
                    val rb = bot(res) ?: continue
                    val src = rb.optString("src")
                    val mineJob = if (src == "au") (dev == "a" && m.mine) else ((dev == "a" && m.mine) || (dev == "w" && !m.mine))
                    if (!mineJob) continue
                    if (msgs.any { it.meta?.optJSONObject("song")?.optLong("r") == m.id }) { markDone(ctx, m.id); continue }
                    val o = rb.optJSONArray("o")?.optJSONObject(b.optInt("k")) ?: continue
                    val song = MbSong(o.optString("id"), o.optString("ti"), o.optString("ch"), o.optInt("d"), 0)
                    val video = b.optString("f") == "v" && src != "au"
                    fetchAndSend(ctx, scope, m.id, song, video, if (src == "au") o.optString("u") else null, o.optString("art"))
                }
                // Demande venue d'un iPhone (qui ne peut pas télécharger) : le téléphone Android de l'ami s'en charge.
                "dlreq" -> {
                    if (m.mine) continue
                    if (msgs.any { it.meta?.optJSONObject("song")?.optLong("r") == m.id } ||
                        msgs.any { bot(it)?.let { x -> x.optString("t") == "fail" && x.optLong("r") == m.id } == true }) { markDone(ctx, m.id); continue }
                    val id = b.optString("id")
                    val q = b.optString("q")
                    if (id.isEmpty() && q.isEmpty() && b.optString("u").isEmpty()) { markDone(ctx, m.id); continue }
                    val song = MbSong(id, b.optString("ti").ifEmpty { q.ifEmpty { "Chanson" } }, b.optString("ch"), b.optInt("d"), 0)
                    val video = b.optString("f") == "v" && b.optString("u").isEmpty()
                    fetchAndSend(ctx, scope, m.id, song, video, b.optString("u").ifEmpty { null }, b.optString("art"), if (id.isEmpty()) q.ifEmpty { null } else null)
                }
            }
        }
    }

    private fun fetchAndSend(ctx: Context, scope: CoroutineScope, ref: Long, song0: MbSong, video: Boolean, url: String?, art: String, search: String? = null) {
        busy.add(ref)
        send?.invoke("⏳ Téléchargement ${if (video) "de la vidéo" else "de la chanson"} « ${song0.title} »…", JSONObject().put("t", "wait").put("r", ref))
        scope.launch {
            var song = song0
            try {
                // Demande sans identifiant YouTube (recherche faite sur iPhone ailleurs) : on prend le meilleur résultat.
                if (search != null) song = MbEngine.search(ctx, search).firstOrNull() ?: throw Exception("rien")
                if (video) {
                    val f = MbEngine.downloadVideo(ctx, song) { }
                    val keep = File(ctx.cacheDir, "cam/send_${System.currentTimeMillis()}.mp4").also { f.copyTo(it, overwrite = true) }
                    sendVideo?.invoke(keep, song, ref)
                } else {
                    val f = if (url != null) httpSong(ctx, url, song) else MbEngine.download(ctx, song) { }
                    val keep = File(ctx.cacheDir, "cam/send_${System.currentTimeMillis()}.mp3").also { f.copyTo(it, overwrite = true) }
                    sendSong?.invoke(keep, if (url != null && art.isNotEmpty()) song.copy(id = art) else song, ref)
                }
                markDone(ctx, ref)
            } catch (_: Exception) {
                send?.invoke("⚠️ Le téléchargement de « ${song.title} » a échoué.", JSONObject().put("t", "fail").put("r", ref))
                markDone(ctx, ref)
            } finally { busy.remove(ref) }
        }
    }

    private suspend fun httpSong(ctx: Context, url: String, s: MbSong): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "cam/music").apply { mkdirs() }
        val f = File(dir, MbEngine.safeName(s.title) + ".mp3")
        java.net.URL(url).openStream().use { i -> f.outputStream().use { i.copyTo(it) } }
        f
    }
}

/** Une bulle du bot dans la conversation (au centre, comme les jeux). */
@Composable
fun BotChatRow(m: ChatMsg, partner: String, msgs: List<ChatMsg>) {
    val b = ChatBot.bot(m) ?: return
    val t = b.optString("t")
    val who = if (m.mine) "Toi" else "@$partner"
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.Center) {
        Column(
            Modifier.fillMaxWidth(0.94f).clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(Color(0xDB3A2834), Color(0xD1241822))))
                .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(22.dp))
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(Brush.linearGradient(listOf(BPink, Color(0xFF8E24AA)))), contentAlignment = Alignment.Center) { Text("🎵", fontSize = 13.sp) }
                Spacer(Modifier.width(8.dp))
                Text("@music", color = BSoft, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            when (t) {
                "ask" -> {
                    Text("Quelle chanson voulez-vous ? Tapez l'artiste et le titre.", color = BTxt, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    BotBtn("✍️ Répondre", Modifier.fillMaxWidth(), main = true) { ChatBot.prefill = "@music " }
                }
                "res" -> {
                    Text("Quelle chanson pour « ${b.optString("q")} » ?", color = BTxt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("${who} a demandé · n'importe qui peut choisir", color = BSoft, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    val pick = msgs.firstOrNull { ChatBot.bot(it)?.let { x -> x.optString("t") == "pick" && x.optLong("r") == m.id } == true }
                    val chosen = pick?.let { ChatBot.bot(it)?.optInt("k") }
                    val pickId = pick?.id ?: -1L
                    val song = if (pick != null) msgs.firstOrNull { it.meta?.optJSONObject("song")?.optLong("r") == pickId } else null
                    val failed = pick != null && msgs.any { ChatBot.bot(it)?.let { x -> x.optString("t") == "fail" && x.optLong("r") == pickId } == true }
                    val arr = b.optJSONArray("o")
                    val audioOnly = b.optString("src") == "au"
                    var choosing by remember(m.id) { mutableStateOf(-1) }
                    val chosenFmt = pick?.let { ChatBot.bot(it)?.optString("f") } ?: ""
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (k in 0 until (arr?.length() ?: 0)) {
                            val o = arr!!.getJSONObject(k)
                            val on = chosen == k
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                    .background(if (on) Brush.linearGradient(listOf(Color(0x59FF4886), Color(0x4DD6185C))) else Brush.linearGradient(listOf(Color(0x0FFFFFFF), Color(0x0FFFFFFF))))
                                    .border(1.dp, if (on) BPink else Color(0x1FFFFFFF), RoundedCornerShape(14.dp))
                                    .clickable(enabled = pick == null && m.id != 0L) { if (audioOnly) ChatBot.pick(m, k, "a") else choosing = if (choosing == k) -1 else k }
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(26.dp).clip(CircleShape).background(if (on) BPink else Color(0x1AFFFFFF)), Alignment.Center) {
                                    Text("ABCDE"[k % 5].toString(), color = if (on) Color.White else BSoft, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                                Spacer(Modifier.width(8.dp))
                                val thumb = o.optString("art").ifEmpty { "https://i.ytimg.com/vi/${o.optString("id")}/default.jpg" }
                                AsyncImage(model = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(width = 52.dp, height = 38.dp).clip(RoundedCornerShape(8.dp)))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f).then(if (chosen != null && !on) Modifier.padding(0.dp) else Modifier)) {
                                    Text(o.optString("ti"), color = if (chosen != null && !on) BSoft else BTxt, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(listOf(o.optString("ch"), mbTime(o.optInt("d"))).filter { it.isNotEmpty() }.joinToString(" · "), color = BSoft, fontSize = 11.sp, maxLines = 1)
                                }
                            }
                            // Audio ou vidéo ?
                            if (choosing == k && pick == null) {
                                Row(Modifier.fillMaxWidth().padding(start = 34.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    BotBtn("🎵 Audio", Modifier.weight(1f), main = true) { choosing = -1; ChatBot.pick(m, k, "a") }
                                    BotBtn("🎬 Vidéo", Modifier.weight(1f), main = true) { choosing = -1; ChatBot.pick(m, k, "v") }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when {
                            pick == null -> if (audioOnly) "Touchez une proposition pour la télécharger." else "Touchez une proposition, puis choisissez audio ou vidéo."
                            song != null -> if (chosenFmt == "v") "✓ Vidéo envoyée dans la conversation, juste en dessous." else "✓ Chanson envoyée dans la conversation, juste en dessous."
                            failed -> "⚠️ Le téléchargement a échoué. Redemandez avec @music."
                            else -> "⏳ Téléchargement en cours…"
                        },
                        color = if (song != null) Color(0xFF8BE0A4) else BSoft, fontSize = 12.sp,
                    )
                }
                "dlreq" -> {
                    val got = msgs.any { it.meta?.optJSONObject("song")?.optLong("r") == m.id }
                    val failed = msgs.any { ChatBot.bot(it)?.let { x -> x.optString("t") == "fail" && x.optLong("r") == m.id } == true }
                    Text("${who} demande ${if (b.optString("f") == "v") "la vidéo" else "la chanson"} « ${b.optString("ti")} »", color = BTxt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            got -> "✓ Envoyée dans la conversation, elle est ajoutée au Casque."
                            failed -> "⚠️ Le téléchargement a échoué."
                            m.mine -> "En attente d'un ami sur Android pour la télécharger…"
                            else -> "⏳ Ton téléphone la télécharge et l'envoie…"
                        },
                        color = if (got) Color(0xFF8BE0A4) else BSoft, fontSize = 12.sp,
                    )
                }
                "pick" -> Text("${who} a choisi : ${m.body?.removePrefix("🎵 Choix : ") ?: ""}", color = BTxt, fontSize = 14.sp)
                else -> Text(m.body ?: "", color = BTxt, fontSize = 14.sp)
            }
        }
    }
}
