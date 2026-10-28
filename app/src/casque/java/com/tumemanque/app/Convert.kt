package com.tumemanque.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.InputStream

/**
 * Fichiers « chanson.mp3.download » (ou .crdownload) laissés par les navigateurs :
 * on vérifie que c'est bien de l'audio, on en fait un vrai .mp3 dans Musique/Casque, et il apparaît dans les Audios.
 */
object Convert {
    private val SUFFIXES = listOf(".download", ".crdownload", ".part", ".tmp")
    private val AUDIO = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")

    /** « titre.mp3.download » -> « mp3 » ; null si ce n'est pas un fichier audio en attente. */
    fun pendingExt(name: String): String? {
        val low = name.lowercase()
        val suf = SUFFIXES.firstOrNull { low.endsWith(it) } ?: return null
        val inner = low.removeSuffix(suf).substringAfterLast('.', "")
        return inner.takeIf { it in AUDIO }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("casque_convert", Context.MODE_PRIVATE)

    private fun done(ctx: Context): MutableSet<String> = runCatching {
        val a = JSONArray(prefs(ctx).getString("done", "[]")); (0 until a.length()).map { a.getString(it) }.toMutableSet()
    }.getOrDefault(mutableSetOf())

    private fun markDone(ctx: Context, key: String) {
        val s = done(ctx); s.add(key)
        prefs(ctx).edit().putString("done", JSONArray(s.toList().takeLast(2000)).toString()).apply()
    }

    fun alreadyDone(ctx: Context, key: String) = key in done(ctx)

    /** Détecte le vrai format d'après les premiers octets (le nom du fichier peut mentir). */
    private fun sniff(head: ByteArray): String? {
        if (head.size < 12) return null
        fun s(a: Int, n: Int) = String(head, a, n, Charsets.ISO_8859_1)
        return when {
            s(0, 3) == "ID3" -> "mp3"
            (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0 -> "mp3"
            s(4, 4) == "ftyp" -> "m4a"
            s(0, 4) == "OggS" -> "ogg"
            s(0, 4) == "RIFF" && s(8, 4) == "WAVE" -> "wav"
            s(0, 4) == "fLaC" -> "flac"
            else -> null
        }
    }

    /**
     * Convertit un fichier et l'ajoute aux Audios.
     * @return le morceau créé, ou null si ce n'est pas de l'audio lisible.
     */
    suspend fun import(ctx: Context, name: String, key: String, open: () -> InputStream?): Track? = withContext(Dispatchers.IO) {
        val tmpDir = File(ctx.cacheDir, "convert").apply { mkdirs() }
        val tmp = File(tmpDir, "in_" + System.nanoTime())
        try {
            val input = open() ?: return@withContext null
            input.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            if (tmp.length() < 30_000) return@withContext null
            val head = tmp.inputStream().use { s -> ByteArray(16).also { b -> s.read(b) } }
            val ext = sniff(head) ?: pendingExt(name) ?: name.substringAfterLast('.', "").lowercase().takeIf { it in AUDIO } ?: return@withContext null
            var base = name
            SUFFIXES.forEach { if (base.lowercase().endsWith(it)) base = base.dropLast(it.length) }
            base = base.substringBeforeLast('.')
            val file = File(tmpDir, "x_" + System.nanoTime() + "." + ext)
            tmp.renameTo(file)
            // Titre et artiste : d'abord les étiquettes du fichier, sinon le nom « Artiste - Titre ».
            var title = ""; var artist = ""; var dur = 0L
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.absolutePath)
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim().orEmpty()
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim().orEmpty()
                dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (_: Exception) {
            } finally { runCatching { r.release() } }
            if (title.isEmpty() || artist.isEmpty()) {
                val (t2, a2) = cleanTitle(base.replace('_', ' '), "")
                if (title.isEmpty()) title = t2
                if (artist.isEmpty()) artist = a2
            }
            val saved = Store.save(ctx, file, title, artist, video = false)
            val size = file.length()
            file.delete()
            val track = Track(saved.first, title, artist, "Free tune", dur, saved.second, 0L, System.currentTimeMillis(), size)
            Lib.addTrack(track)
            markDone(ctx, key)
            track
        } catch (_: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }

    /** Fichiers choisis à la main (Importer des fichiers). */
    suspend fun fromUris(ctx: Context, uris: List<Uri>): Pair<Int, Int> {
        var ok = 0; var bad = 0
        for (u in uris) {
            val name = runCatching {
                ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: u.lastPathSegment?.substringAfterLast('/') ?: "Morceau"
            val t = import(ctx, name, "u:$u") { ctx.contentResolver.openInputStream(u) }
            if (t != null) ok++ else bad++
        }
        return ok to bad
    }
}
