package com.tumemanque.app

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mode p2p : le fichier audio (m4a) est découpé en morceaux ; chaque Android en ligne télécharge directement
 * ses morceaux auprès de la source (téléchargement partiel) puis les envoie ; l'iPhone les recolle.
 */
object P2p {
    class Stream(val fid: String, val url: String, val headers: Map<String, String>, val size: Long)

    private val FID_OK = Regex("^[A-Za-z0-9_.-]{1,24}$")

    /** Cherche le flux audio m4a direct. [fid] impose un format précis (celui choisi par le premier téléphone). Null si on ne peut pas découper ce titre. */
    suspend fun resolve(ctx: Context, yid: String, light: Boolean, fid: String?): Stream? = withContext(Dispatchers.IO) {
        MbEngine.ensure(ctx)
        val sel = fid ?: if (light) "bestaudio[ext=m4a][abr<=70]/bestaudio[ext=m4a]" else "bestaudio[ext=m4a]"
        val req = YoutubeDLRequest("https://www.youtube.com/watch?v=$yid")
            .addOption("--no-playlist").addOption("--no-warnings").addOption("--socket-timeout", "15")
            .addOption("-f", sel).addOption("--dump-json")
        val o = JSONObject(EngineHeal.exec(req, null, null).out)
        val url = o.optString("url")
        if (!url.startsWith("https://")) return@withContext null
        if (!o.optString("ext").equals("m4a", true) || !o.optString("protocol").startsWith("http")) return@withContext null
        val id = o.optString("format_id")
        if (!FID_OK.matches(id)) return@withContext null
        // Taille EXACTE seulement (paramètre clen de l'adresse ou taille annoncée) : sinon on ne découpe pas.
        val size = Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
            ?: o.optLong("filesize", 0L).takeIf { it > 0 }
            ?: return@withContext null
        val headers = HashMap<String, String>()
        o.optJSONObject("http_headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.optString(k) } }
        Stream(id, url, headers, size)
    }

    /** Télécharge les octets [a]..[b] (inclus) dans [out]. Refuse si la source renvoie autre chose que le morceau demandé. */
    suspend fun fetchRange(st: Stream, a: Long, b: Long, out: File) = withContext(Dispatchers.IO) {
        val conn = URL(st.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            st.headers.forEach { (k, v) -> if (!k.equals("Range", true) && !k.equals("Host", true)) conn.setRequestProperty(k, v) }
            conn.setRequestProperty("Range", "bytes=$a-$b")
            if (conn.responseCode != 206) throw IOException("HTTP ${conn.responseCode}")
            val want = b - a + 1
            out.outputStream().use { o -> conn.inputStream.use { it.copyTo(o) } }
            if (out.length() != want) throw IOException("morceau incomplet (${out.length()}/$want)")
        } finally {
            conn.disconnect()
        }
    }
}
