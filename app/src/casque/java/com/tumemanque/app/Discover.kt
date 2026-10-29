package com.tumemanque.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

data class DiscoAlbum(val id: Long, val name: String, val art: String, val year: String)
data class DiscoTrack(val n: Int, val title: String, val ms: Long)
/** Une suggestion pendant la frappe : le texte cherché, ce qu'on affiche, et si c'est un artiste. */
data class Sug(val text: String, val label: String, val sub: String = "", val artist: Boolean = false)

data class DiscoGenre(val emoji: String, val name: String, val query: String)

/** Ce que la personne cherche : un artiste (→ ses albums) ou des films (→ des genres). */
sealed class DiscoKind {
    data class Artist(val id: Long, val name: String) : DiscoKind()
    object Film : DiscoKind()
}

object Disco {
    val genres = listOf(
        DiscoGenre("💥", "Action", "film action complet en français"),
        DiscoGenre("🧭", "Aventure", "film aventure complet en français"),
        DiscoGenre("🎨", "Animé", "film animation complet en français"),
        DiscoGenre("😂", "Comédie", "film comédie complet en français"),
        DiscoGenre("🎭", "Drame", "film drame complet en français"),
        DiscoGenre("👻", "Horreur", "film horreur complet en français"),
        DiscoGenre("🚀", "Science-fiction", "film science fiction complet en français"),
        DiscoGenre("🕵️", "Thriller", "film thriller complet en français"),
        DiscoGenre("💕", "Romance", "film romance complet en français"),
        DiscoGenre("🔍", "Policier", "film policier complet en français"),
        DiscoGenre("🧙", "Fantastique", "film fantastique complet en français"),
        DiscoGenre("🎥", "Documentaire", "documentaire complet en français"),
        DiscoGenre("🌍", "Africain", "film africain nollywood complet"),
    )

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]"), "")

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    private fun get(url: String): JSONObject? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 10000 }
        if (c.responseCode != 200) null else JSONObject(c.inputStream.use { it.readBytes().decodeToString() })
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Un nom d'artiste exact (sans autres mots) donne ses albums ; « film », « movie »… donne les genres de films. */
    suspend fun detect(q: String): DiscoKind? = withContext(Dispatchers.IO) {
        val t = q.trim()
        val nq = norm(t)
        if (nq.length < 3) return@withContext null
        if (Regex("\\b(films?|movies?|cin[eé]ma|s[eé]ries?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)) return@withContext DiscoKind.Film
        val a = get("https://itunes.apple.com/search?term=${enc(t)}&entity=musicArtist&limit=5")?.optJSONArray("results")
        if (a != null) {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val name = o.optString("artistName")
                val nn = norm(name)
                val ok = nn == nq || (nq.length >= 5 && lev(nn, nq) <= 1) || (nq.length >= 4 && nn.startsWith(nq) && nn.length - nq.length <= 3)
                if (ok && o.optLong("artistId") > 0) return@withContext DiscoKind.Artist(o.optLong("artistId"), name)
            }
        }
        val m = get("https://itunes.apple.com/search?term=${enc(t)}&entity=movie&limit=3")?.optJSONArray("results")
        if (m != null) for (i in 0 until m.length()) if (norm(m.getJSONObject(i).optString("trackName")) == nq) return@withContext DiscoKind.Film
        null
    }

    /** Pendant la frappe : l'artiste le plus proche d'abord, puis les titres qui y ressemblent, puis les suggestions YouTube. */
    suspend fun suggest(q: String): List<Sug> = coroutineScope {
        val t = q.trim()
        val nq = norm(t)
        val art = async(Dispatchers.IO) { get("https://itunes.apple.com/search?term=${enc(t)}&entity=musicArtist&limit=6") }
        val songs = async(Dispatchers.IO) { get("https://itunes.apple.com/search?term=${enc(t)}&entity=song&limit=12") }
        val yt = async { runCatching { Yt.suggest(t) }.getOrDefault(emptyList()) }
        val out = ArrayList<Sug>()
        val seen = HashSet<String>()
        // L'artiste dont le nom ressemble le plus à ce qui est tapé.
        var best: String? = null
        var bestScore = Int.MAX_VALUE
        art.await()?.optJSONArray("results")?.let { a ->
            for (i in 0 until a.length()) {
                val name = a.getJSONObject(i).optString("artistName")
                val nn = norm(name)
                if (nn.isEmpty() || nq.isEmpty()) continue
                val score = when {
                    nn == nq -> 0
                    nn.startsWith(nq) -> 1 + (nn.length - nq.length)
                    nn.contains(nq) && nq.length >= 3 -> 40 + (nn.length - nq.length)
                    lev(nn, nq) <= maxOf(2, nq.length / 3) -> 100 + lev(nn, nq)
                    else -> Int.MAX_VALUE
                }
                if (score < bestScore) { bestScore = score; best = name }
            }
        }
        best?.let { if (seen.add(norm(it))) out.add(Sug(it, "🎤  $it", "Artiste", true)) }
        songs.await()?.optJSONArray("results")?.let { a ->
            var n = 0
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.optString("wrapperType") != "track") continue
                val title = o.optString("trackName"); val artist = o.optString("artistName")
                if (title.isBlank() || !seen.add(norm("$artist $title"))) continue
                out.add(Sug("$artist $title", title, artist))
                if (++n >= 6) break
            }
        }
        for (y in yt.await()) { if (out.size >= 10) break; if (seen.add(norm(y))) out.add(Sug(y, y)) }
        out
    }

    suspend fun albums(artistId: Long): List<DiscoAlbum> = withContext(Dispatchers.IO) {
        val r = get("https://itunes.apple.com/lookup?id=$artistId&entity=album&limit=80")?.optJSONArray("results") ?: return@withContext emptyList()
        val out = ArrayList<Triple<String, String, DiscoAlbum>>()
        val seen = HashSet<String>()
        for (i in 0 until r.length()) {
            val o = r.getJSONObject(i)
            if (o.optString("wrapperType") != "collection") continue
            val name = o.optString("collectionName")
            if (name.isBlank() || name.endsWith(" - Single")) continue
            val key = norm(name.replace(Regex("\\(.*?\\)|\\[.*?]"), ""))
            if (!seen.add(key)) continue
            out.add(Triple(o.optString("releaseDate"), name, DiscoAlbum(o.optLong("collectionId"), name, o.optString("artworkUrl100").replace("100x100", "400x400"), o.optString("releaseDate").take(4))))
        }
        out.sortedByDescending { it.first }.map { it.third }
    }

    suspend fun tracks(albumId: Long): List<DiscoTrack> = withContext(Dispatchers.IO) {
        val r = get("https://itunes.apple.com/lookup?id=$albumId&entity=song&limit=200")?.optJSONArray("results") ?: return@withContext emptyList()
        val l = ArrayList<Triple<Int, Int, DiscoTrack>>()
        for (i in 0 until r.length()) {
            val o = r.getJSONObject(i)
            if (o.optString("wrapperType") != "track") continue
            l.add(Triple(o.optInt("discNumber", 1), o.optInt("trackNumber", i), DiscoTrack(o.optInt("trackNumber", i), o.optString("trackName"), o.optLong("trackTimeMillis"))))
        }
        l.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
    }
}

/** Le bandeau en verre au-dessus de la vidéo : question, puis albums (ou genres de films) à faire défiler. */
@Composable
fun DiscoverCard(
    kind: DiscoKind, stage: Int, albums: List<DiscoAlbum>?, selAlbum: Long?, selGenre: String?,
    onYes: () -> Unit, onNo: () -> Unit, onAlbum: (DiscoAlbum) -> Unit, onGenre: (DiscoGenre) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).glass(RoundedCornerShape(24.dp), 0.10f).padding(vertical = 14.dp),
    ) {
        val isArtist = kind is DiscoKind.Artist
        if (stage == 0) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    if (kind is DiscoKind.Artist) "🎵  ${kind.name}" else "🎬  Tu cherches un film ?",
                    color = P.text, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    if (isArtist) "Veux-tu écouter ses albums ?" else "Veux-tu voir des films par genre ?",
                    color = P.sub, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassChip("Oui", Ic.Play, P.blueSoft, Modifier.weight(1f)) { onYes() }
                    GlassChip("Non merci", Ic.Close, P.sub, Modifier.weight(1f)) { onNo() }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (kind is DiscoKind.Artist) "Albums · ${kind.name}" else "Films · choisis un genre",
                    color = P.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                RoundBtn(Ic.Close, { onNo() }, size = 32.dp, iconSize = 16.dp, fill = Color(0x22FFFFFF))
            }
            Spacer(Modifier.height(10.dp))
            if (kind is DiscoKind.Artist) {
                when {
                    albums == null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = P.blueSoft)
                    }
                    albums.isEmpty() -> Text("Aucun album trouvé pour cet artiste.", color = P.sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    else -> LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(albums, key = { it.id }) { a ->
                            val on = selAlbum == a.id
                            Column(Modifier.width(112.dp).pressable { onAlbum(a) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier.size(112.dp).clip(RoundedCornerShape(18.dp)).background(P.card2)
                                        .then(if (on) Modifier.glass(RoundedCornerShape(18.dp), 0.0f, P.blueSoft) else Modifier),
                                ) {
                                    AsyncImage(model = a.art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)))
                                    if (on) Box(Modifier.fillMaxSize().background(Color(0x442F7BFF)))
                                }
                                Text(a.name, color = if (on) P.blueSoft else P.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                                if (a.year.isNotBlank()) Text(a.year, color = P.sub, fontSize = 11.sp)
                            }
                        }
                    }
                }
            } else {
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(Disco.genres, key = { it.name }) { g ->
                        val on = selGenre == g.name
                        Column(Modifier.width(78.dp).pressable { onGenre(g) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(68.dp).glass(CircleShape, if (on) 0.22f else 0.08f, if (on) P.blueSoft else Color.White),
                                contentAlignment = Alignment.Center,
                            ) { Text(g.emoji, fontSize = 30.sp) }
                            Text(g.name, color = if (on) P.blueSoft else P.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Une ligne de titre d'un album. */
@Composable
fun TrackRow(t: DiscoTrack, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(onClick).padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${t.n}", color = P.sub, fontSize = 14.sp, modifier = Modifier.width(30.dp))
        Text(t.title, color = P.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (t.ms > 0) Text(fmt(t.ms), color = P.sub, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
        Spacer(Modifier.width(10.dp))
        CIcon(Ic.Play, P.blueSoft, 20.dp)
    }
}
