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
fun MusicBotOverlay(initial: String, onClose: () -> Unit) {
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
private fun SongCard(s: MbSong, progress: Float?, file: File?, playing: Boolean, onPlay: () -> Unit, onSave: () -> Unit, onShare: () -> Unit) {
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
