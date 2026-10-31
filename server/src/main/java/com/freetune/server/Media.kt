package com.freetune.server

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

class Entry(val file: File?, val url: String?, val headers: Map<String, String>)

/** Lecteur intégré : sert à la page un fichier du téléphone ou un flux YouTube, avec « Range » pour avancer / reculer. */
class Media {
    private val map: MutableMap<String, Entry> = Collections.synchronizedMap(object : LinkedHashMap<String, Entry>() {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Entry>?) = size > 60
    })

    fun add(e: Entry): String { val t = Http.hex(12); map[t] = e; return t }

    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"; "m4a" -> "audio/mp4"; "mp3" -> "audio/mpeg"; "webm" -> "video/webm"
        "ogg" -> "audio/ogg"; "wav" -> "audio/wav"; "flac" -> "audio/flac"; else -> ""
    }

    private fun err(code: Int, msg: String) = WebResourceResponse("text/plain", "utf-8", code, msg, mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(ByteArray(0)))

    fun handle(token: String, range: String?): WebResourceResponse {
        val e = map[token] ?: return err(404, "Not Found")
        return try { if (e.file != null) serveFile(e.file, range) else serveUrl(e, range) } catch (ex: Exception) { err(502, "Bad Gateway") }
    }

    private fun serveFile(f: File, range: String?): WebResourceResponse {
        val len = f.length()
        var a = 0L; var b = len - 1
        val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
        if (m != null) {
            val s = m.groupValues[1]; val t = m.groupValues[2]
            if (s.isEmpty() && t.isNotEmpty()) { a = maxOf(0, len - t.toLong()) } else if (s.isNotEmpty()) { a = s.toLong(); if (t.isNotEmpty()) b = minOf(b, t.toLong()) }
        }
        if (a >= len) return err(416, "Range Not Satisfiable")
        val ins: InputStream = FileInputStream(f).also { it.skip(a) }
        val n = b - a + 1
        val lim = object : InputStream() {
            var left = n
            override fun read(): Int { if (left <= 0) return -1; val c = ins.read(); if (c >= 0) left--; return c }
            override fun read(buf: ByteArray, off: Int, l: Int): Int { if (left <= 0) return -1; val r = ins.read(buf, off, minOf(l.toLong(), left).toInt()); if (r > 0) left -= r; return r }
            override fun close() { ins.close() }
        }
        val h = hashMapOf("Accept-Ranges" to "bytes", "Content-Length" to n.toString(), "Access-Control-Allow-Origin" to "*")
        if (m != null) h["Content-Range"] = "bytes $a-$b/$len"
        return WebResourceResponse(mime(f.name).ifEmpty { "application/octet-stream" }, null, if (m != null) 206 else 200, if (m != null) "Partial Content" else "OK", h, lim)
    }

    private fun serveUrl(e: Entry, range: String?): WebResourceResponse {
        val c = URL(e.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        e.headers.forEach { (k, v) -> if (!k.equals("Range", true) && !k.equals("Host", true)) c.setRequestProperty(k, v) }
        if (range != null) c.setRequestProperty("Range", range)
        val code = c.responseCode
        if (code !in 200..299) { c.disconnect(); return err(502, "Bad Gateway") }
        val h = HashMap<String, String>()
        h["Accept-Ranges"] = "bytes"; h["Access-Control-Allow-Origin"] = "*"
        c.getHeaderField("Content-Length")?.let { h["Content-Length"] = it }
        c.getHeaderField("Content-Range")?.let { h["Content-Range"] = it }
        val ct = c.contentType?.substringBefore(';') ?: "video/mp4"
        return WebResourceResponse(ct, null, code, if (code == 206) "Partial Content" else "OK", h, c.inputStream)
    }
}
