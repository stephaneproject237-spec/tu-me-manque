package com.freetune.server

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class Item(val id: Int, var yid: String, val title: String, val artist: String, val dur: Int, val tag: Int, val kind: String, val height: Int) {
    @Volatile var state = "attente"
    @Volatile var pct = 0.0
    @Volatile var file = ""
    @Volatile var err = ""
    @Volatile var stop = ""
    var rid = ""
}

/** Téléchargements faits par la personne elle-même (page Recherche) : pause, reprise, annulation. */
class Dl(private val ctx: Context, private val dir: () -> File, private val changed: () -> Unit) {
    private val items = ArrayList<Item>()
    private var seq = 0
    private var running = 0
    private val pool = Executors.newCachedThreadPool()

    @Synchronized fun list(): JSONArray {
        val a = JSONArray()
        items.takeLast(80).forEach {
            a.put(JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist).put("kind", it.kind).put("state", it.state)
                .put("pct", it.pct).put("file", it.file).put("err", it.err).put("yid", it.rid.ifEmpty { it.yid }).put("tag", it.tag))
        }
        return a
    }

    @Synchronized fun busy(): Boolean = items.any { it.state == "en cours" || it.state == "attente" }

    @Synchronized fun start(o: JSONObject): Int {
        val it = Item(++seq, o.optString("yid"), o.optString("title", "titre").take(120), o.optString("artist").take(80), o.optInt("dur"), o.optInt("tag"),
            if (o.optString("kind") == "video") "video" else "audio", o.optInt("height", 720))
        items.add(it); changed(); pump()
        return it.id
    }

    /** Ce titre est-il déjà dans le dossier de téléchargement ? (historique du compte) */
    fun has(artist: String, title: String, kind: String): Boolean {
        val name = Tools.safeName(if (artist.isNotEmpty()) "$artist - $title" else title)
        return (if (kind == "video") listOf(".mp4") else listOf(".m4a", ".mp3")).any { File(dir(), name + it).exists() }
    }

    @Synchronized private fun find(id: Int) = items.find { it.id == id }

    @Synchronized fun pause(id: Int): Boolean {
        val it = find(id) ?: return false
        when (it.state) {
            "en cours" -> { it.stop = "en pause"; Tools.cancel("dl${it.id}") }
            "attente" -> it.state = "en pause"
            else -> return false
        }
        changed(); return true
    }

    @Synchronized fun resume(id: Int): Boolean {
        val it = find(id) ?: return false
        if (it.state != "en pause" && it.state != "échec" && it.state != "annulé") return false
        it.state = "attente"; it.err = ""; it.stop = ""; changed(); pump(); return true
    }

    @Synchronized fun cancel(id: Int): Boolean {
        val it = find(id) ?: return false
        when (it.state) {
            "en cours" -> { it.stop = "annulé"; Tools.cancel("dl${it.id}") }
            "attente", "en pause", "échec" -> it.state = "annulé"
            else -> return false
        }
        changed(); return true
    }

    @Synchronized fun clear() { items.removeAll { it.state != "attente" && it.state != "en cours" && it.state != "en pause" }; changed() }

    @Synchronized private fun pump() {
        for (it in items) {
            if (running >= 2) return
            if (it.state != "attente") continue
            running++; it.state = "en cours"; it.stop = ""
            changed()
            pool.execute { work(it) }
        }
    }

    private fun work(it: Item) {
        try {
            run(it)
            it.state = "terminé"; it.pct = 1.0
        } catch (e: Throwable) {
            if (it.stop.isNotEmpty()) it.state = it.stop
            else { it.state = "échec"; it.err = (e.message ?: "erreur").lines().last().take(140) }
        } finally {
            synchronized(this) { running-- }
            changed()
            synchronized(this) { pump() }
        }
    }

    private fun run(it: Item) {
        var yid = it.rid.ifEmpty { it.yid }
        if (yid.isEmpty()) yid = Tools.bestMatch(it.artist, it.title, it.dur) ?: throw Exception("introuvable sur YouTube")
        it.rid = yid
        val name = if (it.artist.isNotEmpty()) "${it.artist} - ${it.title}" else it.title
        val pid = "dl${it.id}"
        val onP: (Float) -> Unit = { p -> if (p > it.pct) { it.pct = p.toDouble(); changed() } }
        val stopped = { it.stop.isNotEmpty() }
        val f = if (it.kind == "video") Tools.downloadVideo(yid, dir(), name, it.height, 0, pid, stopped, onP)
                else Tools.downloadAudio(yid, dir(), name, false, 192, pid, stopped, onP)
        it.file = f.path
    }
}
