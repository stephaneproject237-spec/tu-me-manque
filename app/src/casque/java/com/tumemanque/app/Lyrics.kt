package com.tumemanque.app

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LyricLine(val time: Long, val text: String)
data class LyricsData(val synced: List<LyricLine>, val plain: String?, val lrcDuration: Double = 0.0)

/** Paroles synchronisées, prises sur LRCLIB (base libre et gratuite), puis gardées sur le téléphone. */
object Lyrics {
    private fun cache(ctx: Context, id: String) = File(ctx.filesDir, "lyrics/" + Integer.toHexString(id.hashCode()) + "_" + id.length + ".json")

    private fun http(url: String): String? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 10000
            setRequestProperty("User-Agent", "Casque (Android)")
        }
        if (c.responseCode != 200) null else c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    fun parse(lrc: String): List<LyricLine> {
        val tag = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        val out = ArrayList<LyricLine>()
        lrc.lineSequence().forEach { raw ->
            val stamps = tag.findAll(raw).toList()
            if (stamps.isEmpty()) return@forEach
            val text = raw.substring(stamps.last().range.last + 1).trim()
            stamps.forEach { m ->
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0; 1 -> frac.toInt() * 100; 2 -> frac.toInt() * 10; else -> frac.take(3).toInt() }
                out.add(LyricLine(m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + ms, text))
            }
        }
        return out.sortedBy { it.time }
    }

    private fun toData(o: JSONObject): LyricsData? {
        val s = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        val p = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (s == null && p == null) return null
        return LyricsData(s?.let { parse(it) } ?: emptyList(), p, o.optDouble("duration", 0.0))
    }

    private fun words(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(" ").filter { it.length > 1 }.toSet()

    /** Nom du fichier sur le téléphone (souvent « Artiste - Titre.mp3 » quand les étiquettes manquent). */
    private fun fileName(ctx: Context, id: String): String? = runCatching {
        val u = android.net.Uri.parse(id)
        if (u.scheme == "file") return@runCatching File(u.path ?: return@runCatching null).name
        ctx.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Nettoie un nom de fichier : extension, sites (y2mate, tubidy…), numéros de piste, « _ ». */
    fun cleanFileName(n: String): String {
        var x = n
        repeat(3) { x = x.replace(Regex("\\.(mp3|m4a|aac|ogg|opus|wav|flac|download|crdownload|part|tmp)$", RegexOption.IGNORE_CASE), "") }
        x = x.replace('_', ' ')
            .replace(Regex("(?i)\\b(www\\.)?[a-z0-9-]+\\.(com|net|org|io|cc|me|to|mobi|co|xyz|info|site|top)\\b"), " ")
            .replace(Regex("(?i)\\b(tubidy|y2mate|ytmp3|mp3juices|savefrom|mp3paw|naijaloaded|320kbps|128kbps|mp3|audio|hq)\\b"), " ")
            .replace(Regex("^\\s*\\d{1,3}\\s*[-.)]\\s*"), "")
            .replace(Regex("\\s+"), " ").trim(' ', '-', '.')
        return x
    }

    private fun candidates(ctx: Context, t: Track): List<Pair<String, String>> {
        val out = LinkedHashSet<Pair<String, String>>()
        fun add(title: String, artist0: String) {
            val a = if (artist0 == UNKNOWN_ARTIST) "" else artist0.substringBefore(",").substringBefore(" feat").substringBefore(" ft.").substringBefore(" x ").trim()
            val tt = title.replace(Regex("(?i)\\s*[\\(\\[](feat|ft)\\.?[^\\)\\]]*[\\)\\]]"), "").trim()
            if (tt.isNotEmpty()) out.add(tt to a)
        }
        val known = if (t.artist == UNKNOWN_ARTIST) "" else t.artist
        cleanTitle(t.title, known).let { add(it.first, it.second) }
        fileName(ctx, t.id)?.let { n -> cleanTitle(cleanFileName(n), "").let { add(it.first, if (it.second == UNKNOWN_ARTIST) known else it.second) } }
        cleanTitle(cleanFileName(t.title), "").let { add(it.first, it.second) }
        return out.toList()
    }

    private fun lookup(title: String, artist: String, dur: Long): JSONObject? {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        if (artist.isNotEmpty()) {
            http("https://lrclib.net/api/get?artist_name=${enc(artist)}&track_name=${enc(title)}" + if (dur > 0) "&duration=$dur" else "")
                ?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { if (toData(it) != null) return it }
        }
        val arr = http("https://lrclib.net/api/search?q=${enc("$artist $title".trim())}")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return null
        val want = words(title)
        val wantA = words(artist)
        val cands = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { o ->
            val w = words(o.optString("trackName"))
            (want.isEmpty() || w.intersect(want).size * 2 >= want.size) &&
                (wantA.isEmpty() || words(o.optString("artistName")).intersect(wantA).isNotEmpty())
        }
        fun dd(o: JSONObject) = kotlin.math.abs(o.optDouble("duration", 0.0) - dur)
        val synced = cands.filter { it.optString("syncedLyrics").isNotBlank() }
        return (if (dur > 0) synced.minByOrNull { dd(it) } else synced.firstOrNull())
            ?: cands.firstOrNull { it.optString("plainLyrics").isNotBlank() }
    }

    suspend fun get(ctx: Context, t: Track, force: Boolean = false): LyricsData? = withContext(Dispatchers.IO) {
        val f = cache(ctx, t.id)
        if (!force && f.exists()) {
            runCatching {
                val o = JSONObject(f.readText())
                if (o.optBoolean("none")) {
                    if (o.optInt("v") >= 2 && System.currentTimeMillis() - o.optLong("at") < 3 * 86400_000L) return@withContext null
                } else if (o.optInt("v") >= 2) return@withContext toData(o)
            }
        }
        val dur = t.durationMs / 1000
        var found: JSONObject? = null
        for ((title, artist) in candidates(ctx, t)) {
            found = lookup(title, artist, dur)
            if (found != null) break
        }
        f.parentFile?.mkdirs()
        val o = found
        if (o == null) {
            runCatching { f.writeText(JSONObject().put("v", 2).put("none", true).put("at", System.currentTimeMillis()).toString()) }
            null
        } else {
            runCatching {
                f.writeText(
                    JSONObject().put("v", 2).put("syncedLyrics", o.optString("syncedLyrics")).put("plainLyrics", o.optString("plainLyrics"))
                        .put("duration", o.optDouble("duration", 0.0)).toString()
                )
            }
            toData(o)
        }
    }

    // ------------------------------------------------------------------ synchronisation
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("casque_lyrics", Context.MODE_PRIVATE)

    /** Décalage choisi à la main (ms, positif = paroles plus tard). */
    fun manualOffset(ctx: Context, id: String): Long? = prefs(ctx).let { if (it.contains(id)) it.getLong(id, 0L) else null }
    fun setOffset(ctx: Context, id: String, ms: Long) { prefs(ctx).edit().putLong(id, ms).apply() }
    fun resetOffset(ctx: Context, id: String) { prefs(ctx).edit().remove(id).apply() }

    /**
     * Décalage automatique : la version téléchargée (clip YouTube) a souvent une intro plus longue que la version des paroles.
     * Si notre fichier est plus long de quelques secondes, on retarde les paroles d'autant.
     */
    fun autoOffset(t: Track, d: LyricsData): Long {
        if (d.lrcDuration <= 0 || t.durationMs <= 0) return 0L
        val diff = t.durationMs - (d.lrcDuration * 1000).toLong()
        return if (diff in 1500L..45_000L) diff else 0L
    }
}

/** Mode karaoké : la ligne chantée s'illumine au rythme de la chanson. */
@Composable
fun LyricsScreen(t: Track, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember(t.id) { mutableStateOf(true) }
    val data by produceState<LyricsData?>(null, t.id, reload) {
        loading = true
        value = Lyrics.get(ctx, t, force = reload > 0)
        loading = false
    }
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(t.id) { while (true) { withFrameMillis { }; pos = Hub.player.currentPosition } }
    val tint by produceState(P.card2, t.id) { value = Art.load(ctx, t, 96)?.let { avgColor(it) } ?: P.card2 }
    // Décalage des paroles : automatique (intro du clip), ou réglé à la main.
    var manual by remember(t.id) { mutableStateOf(Lyrics.manualOffset(ctx, t.id)) }
    val auto = data?.let { Lyrics.autoOffset(t, it) } ?: 0L
    val offset = manual ?: auto
    fun shift(ms: Long) { val v = offset + ms; manual = v; Lyrics.setOffset(ctx, t.id, v) }
    val bg by animateColorAsState(tint.copy(alpha = 0.75f), tween(600), label = "lbg")

    Box(
        Modifier.fillMaxSize().background(P.bg).background(Brush.verticalGradient(listOf(bg, P.bg)))
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(t, 48.dp, 10.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = P.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, color = P.sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                RoundBtn(Ic.ChevronDown, onClose, fill = Color(0x26FFFFFF))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val d = data
                when {
                    loading && d == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                    d == null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CIcon(Ic.Mic, P.sub, 48.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("Paroles introuvables pour ce morceau", color = P.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("Vérifie que le titre et l'artiste sont corrects, ou réessaie plus tard.", color = P.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                        Spacer(Modifier.height(14.dp))
                        GlassChip("Réessayer", Ic.Refresh, P.blueSoft) { reload++ }
                    }
                    d.synced.isNotEmpty() -> Karaoke(d.synced, pos - offset)
                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                        Text("Paroles non synchronisées", color = P.sub, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))
                        Text(d.plain ?: "", color = P.text, fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (data?.synced?.isNotEmpty() == true) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    GlassChip("Paroles en avance", null, P.text) { shift(500) }
                    Text(
                        (if (offset >= 0) "+" else "−") + "%.1f s".format(kotlin.math.abs(offset) / 1000.0).replace('.', ','),
                        color = P.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp)
                            .clickable(remember { MutableInteractionSource() }, indication = null) { manual = null; Lyrics.resetOffset(ctx, t.id) },
                    )
                    GlassChip("En retard", null, P.text) { shift(-500) }
                }
                Text(
                    if (manual == null) "Synchronisation automatique · touche un bouton si les paroles ne suivent pas" else "Réglage enregistré pour cette chanson · touche le chiffre pour revenir en automatique",
                    color = P.sub, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 16.dp, end = 16.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                RoundBtn(Ic.Prev, { Hub.previous() }, size = 50.dp, iconSize = 28.dp, fill = Color.Transparent)
                Spacer(Modifier.width(18.dp))
                RoundBtn(if (Hub.playing) Ic.Pause else Ic.Play, { Hub.toggle() }, size = 64.dp, iconSize = 34.dp, fill = Color.White, tint = Color.Black)
                Spacer(Modifier.width(18.dp))
                RoundBtn(Ic.Next, { Hub.next() }, size = 50.dp, iconSize = 28.dp, fill = Color.Transparent)
            }
        }
    }
}

@Composable
private fun Karaoke(lines: List<LyricLine>, pos: Long) {
    val state = rememberLazyListState()
    val idx = lines.indexOfLast { it.time <= pos }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val half = with(LocalDensity.current) { (maxHeight * 0.38f).roundToPx() }
        LaunchedEffect(idx) { if (idx >= 0) state.animateScrollToItem(idx + 1, -half) }
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(horizontal = 24.dp, vertical = maxHeight * 0.4f)) {
            item { Spacer(Modifier.height(1.dp)) }
            itemsIndexed(lines) { i, l ->
                val end = lines.getOrNull(i + 1)?.time ?: (l.time + 5000)
                val p = when {
                    i < idx -> 1f
                    i > idx -> 0f
                    else -> ((pos - l.time).toFloat() / (end - l.time).coerceAtLeast(1)).coerceIn(0f, 1f)
                }
                val current = i == idx
                val text = l.text.ifBlank { "♪" }
                val style = if (current) {
                    TextStyle(
                        brush = Brush.horizontalGradient(0f to Color.White, p to Color.White, (p + 0.06f).coerceAtMost(1f) to Color(0x66FFFFFF), 1f to Color(0x66FFFFFF)),
                        fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold,
                    )
                } else {
                    TextStyle(color = if (i < idx) Color(0x99FFFFFF) else Color(0x4DFFFFFF), fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    text, style = style,
                    modifier = Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null) { Hub.seekTo(l.time) }
                        .padding(vertical = 10.dp),
                )
            }
        }
    }
}
