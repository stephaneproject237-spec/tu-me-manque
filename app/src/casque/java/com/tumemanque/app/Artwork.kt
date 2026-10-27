package com.tumemanque.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Trouve une vraie pochette (iTunes, puis Deezer) pour les morceaux qui n'en ont pas. */
object Artwork {
    /** Change quand de nouvelles pochettes arrivent : les images se rechargent. */
    var version by mutableIntStateOf(0)
        private set
    @Volatile private var running = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("casque_art", Context.MODE_PRIVATE)

    private fun get(url: String): ByteArray? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 10000
            setRequestProperty("User-Agent", "Casque (Android)")
        }
        if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }
    }.getOrNull()

    private fun words(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(" ").filter { it.length > 1 }.toSet()

    private fun close(a: String, b: String): Boolean {
        val wa = words(a); val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return false
        return wa.intersect(wb).size * 2 >= minOf(wa.size, wb.size)
    }

    private fun find(title: String, artist: String): String? {
        val term = URLEncoder.encode("$artist $title".trim(), "UTF-8")
        get("https://itunes.apple.com/search?term=$term&entity=song&limit=8")?.let { b ->
            runCatching {
                val r = JSONObject(String(b)).getJSONArray("results")
                for (i in 0 until r.length()) {
                    val o = r.getJSONObject(i)
                    if (close(o.optString("trackName"), title) && (artist.isEmpty() || close(o.optString("artistName"), artist))) {
                        return o.optString("artworkUrl100").replace("100x100bb", "600x600bb").ifEmpty { null }
                    }
                }
            }
        }
        get("https://api.deezer.com/search?q=$term&limit=8")?.let { b ->
            runCatching {
                val r = JSONObject(String(b)).getJSONArray("data")
                for (i in 0 until r.length()) {
                    val o = r.getJSONObject(i)
                    if (close(o.optString("title"), title) && (artist.isEmpty() || close(o.optJSONObject("artist")?.optString("name") ?: "", artist))) {
                        return o.optJSONObject("album")?.optString("cover_xl")?.ifEmpty { null }
                    }
                }
            }
        }
        return null
    }

    /** Parcourt la bibliothèque en arrière-plan. */
    suspend fun fill(ctx: Context, tracks: List<Track>) {
        if (running) return
        running = true
        try {
            withContext(Dispatchers.IO) {
                val p = prefs(ctx)
                val misses = runCatching { JSONObject(p.getString("miss", "{}")) }.getOrDefault(JSONObject())
                var found = 0
                for (t in tracks) {
                    if (Covers.has(ctx, t.id)) continue
                    val at = misses.optLong(t.id, 0L)
                    if (at > 0 && System.currentTimeMillis() - at < 10 * 86400_000L) continue
                    if (Art.loadSync(ctx, t, 96) != null) continue
                    val (title, artist0) = cleanTitle(t.title, if (t.artist == UNKNOWN_ARTIST) "" else t.artist)
                    val artist = if (artist0 == UNKNOWN_ARTIST) "" else artist0.substringBefore(",").substringBefore(" feat").trim()
                    val url = find(title, artist)
                    val bytes = url?.let { get(it) }
                    val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    if (bmp != null) {
                        val s = minOf(bmp.width, bmp.height)
                        val sq = Bitmap.createBitmap(bmp, (bmp.width - s) / 2, (bmp.height - s) / 2, s, s)
                        val f = Covers.file(ctx, t.id)
                        f.parentFile?.mkdirs()
                        f.outputStream().use { sq.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                        Art.forget(t.id)
                        found++
                        if (found % 4 == 0) version++
                    } else {
                        misses.put(t.id, System.currentTimeMillis())
                        p.edit().putString("miss", misses.toString()).apply()
                    }
                    delay(120)
                }
                if (found > 0) version++
            }
        } finally {
            running = false
        }
    }
}

