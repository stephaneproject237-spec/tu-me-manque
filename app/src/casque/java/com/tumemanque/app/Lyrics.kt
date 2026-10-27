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
data class LyricsData(val synced: List<LyricLine>, val plain: String?)

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
        return LyricsData(s?.let { parse(it) } ?: emptyList(), p)
    }

    private fun words(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(" ").filter { it.length > 1 }.toSet()

    suspend fun get(ctx: Context, t: Track, force: Boolean = false): LyricsData? = withContext(Dispatchers.IO) {
        val f = cache(ctx, t.id)
        if (!force && f.exists()) {
            runCatching {
                val o = JSONObject(f.readText())
                if (o.optBoolean("none")) {
                    if (System.currentTimeMillis() - o.optLong("at") < 3 * 86400_000L) return@withContext null
                } else return@withContext toData(o)
            }
        }
        val (title, artist0) = cleanTitle(t.title, if (t.artist == UNKNOWN_ARTIST) "" else t.artist)
        val artist = if (artist0 == UNKNOWN_ARTIST) "" else artist0.substringBefore(",").substringBefore(" feat").substringBefore(" ft.").trim()
        val dur = t.durationMs / 1000
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        var found: JSONObject? = null
        if (artist.isNotEmpty()) {
            http("https://lrclib.net/api/get?artist_name=${enc(artist)}&track_name=${enc(title)}" + if (dur > 0) "&duration=$dur" else "")
                ?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { if (toData(it) != null) found = it }
        }
        if (found == null) {
            val arr = http("https://lrclib.net/api/search?q=${enc("$artist $title".trim())}")?.let { runCatching { JSONArray(it) }.getOrNull() }
            if (arr != null) {
                val want = words(title)
                val cands = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { o ->
                    val w = words(o.optString("trackName"))
                    want.isEmpty() || w.intersect(want).size * 2 >= want.size
                }
                found = cands.firstOrNull { it.optString("syncedLyrics").isNotBlank() && (dur <= 0 || kotlin.math.abs(it.optDouble("duration", 0.0) - dur) <= 4) }
                    ?: cands.firstOrNull { it.optString("syncedLyrics").isNotBlank() }
                    ?: cands.firstOrNull { it.optString("plainLyrics").isNotBlank() }
            }
        }
        f.parentFile?.mkdirs()
        val o = found
        if (o == null) {
            runCatching { f.writeText(JSONObject().put("none", true).put("at", System.currentTimeMillis()).toString()) }
            null
        } else {
            runCatching { f.writeText(JSONObject().put("syncedLyrics", o.optString("syncedLyrics")).put("plainLyrics", o.optString("plainLyrics")).toString()) }
            toData(o)
        }
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
                    d.synced.isNotEmpty() -> Karaoke(d.synced, pos)
                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                        Text("Paroles non synchronisées", color = P.sub, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))
                        Text(d.plain ?: "", color = P.text, fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
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
    val idx = lines.indexOfLast { it.time <= pos + 150 }
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
