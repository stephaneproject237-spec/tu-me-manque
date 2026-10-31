package com.freetune.server

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private fun audioKbps(f: String) = when (f) { "al" -> 96; "ah" -> 320; else -> 192 }
private fun videoHeight(f: String) = when (f) { "vl" -> 360; "vh" -> 720; else -> 480 }

/** Un « travailleur » par compte serveur : reste en ligne, prend les demandes des iPhone, télécharge, envoie, aide au p2p. */
class Worker(private val eng: Engine, acc: Acc) {
    val username = acc.username
    @Volatile var share = acc.share
    private val client = Client(acc.username, acc.code)
    @Volatile var state = "connexion"
    @Volatile var error = ""
    private val jobs = ConcurrentHashMap.newKeySet<Long>()
    private val p2p = ConcurrentHashMap.newKeySet<Long>()
    @Volatile var helped = 0
    @Volatile private var stopped = false
    @Volatile private var lastBeat = 0L
    private var thread: Thread? = null

    fun info(): JSONObject = JSONObject().put("username", username).put("state", state).put("error", error).put("share", share)
        .put("jobs", jobs.size + p2p.size).put("helped", helped)

    fun start() {
        stopped = false
        thread = Thread { loop() }.also { it.isDaemon = true; it.start() }
    }
    fun stop() { stopped = true; thread?.interrupt() }
    fun applyShare(on: Boolean) { share = on; lastBeat = 0; eng.touch() }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (e: InterruptedException) { /* arrêt demandé */ } }

    private fun loop() {
        while (!stopped) {
            try {
                if (state != "en ligne") {
                    client.ensure(false); state = "en ligne"; error = ""; eng.touch()
                    runCatching { client.rpc("set_version", JSONObject().put("p_ver", "srvdroid " + eng.version)) }
                }
                if (System.currentTimeMillis() - lastBeat > 20000) {
                    lastBeat = System.currentTimeMillis()
                    runCatching { client.rpc("heartbeat", JSONObject().put("p_platform", "srvdroid").put("p_share", share).put("p_in_app", true)) }
                }
                if (share) { step(); p2pStep() }
            } catch (e: Exception) {
                if (stopped) return
                val bad = Regex("invalid|credentials|HTTP 40[013]", RegexOption.IGNORE_CASE).containsMatchIn(e.message ?: "")
                state = "erreur"; error = if (bad) "connexion refusée (code incorrect ?)" else (e.message ?: "erreur").take(120); eng.touch()
                sleep(if (bad) 30000 else 8000)
                continue
            }
            sleep(if (System.currentTimeMillis() - eng.hot < 90000) 1000 else 3000)
        }
    }

    // ---------- demandes d'un iPhone ----------
    private fun step() {
        val jl = client.rpcArr("relay_open")
        for (i in 0 until jl.length()) {
            val j = jl.optJSONObject(i) ?: continue
            if (eng.running.get() >= eng.maxJobs()) break
            val id = j.optLong("id")
            val fmt = j.optString("fmt", "a")
            val yid0 = j.optString("yid")
            if (j.optBoolean("p2p") && yid0.isNotEmpty() && (fmt == "a" || fmt == "al")) {
                eng.hot = System.currentTimeMillis()
                Thread { runCatching { eng.stream(yid0, fmt == "al", null) } }.start()
            }
            if (jobs.contains(id)) continue
            val t = runCatching { client.rpcObj("relay_take", JSONObject().put("p_id", id)) }.getOrNull()
            if (t == null || t.optString("status") != "ok") continue
            jobs.add(id); eng.running.incrementAndGet(); eng.touch()
            Thread {
                try { handle(id, j); helped++ }
                catch (e: Throwable) { eng.log("[$username] échec : ${e.message}"); runCatching { client.rpc("relay_fail", JSONObject().put("p_id", id)) } }
                finally { jobs.remove(id); eng.running.decrementAndGet(); eng.touch() }
            }.start()
        }
    }

    private fun handle(id: Long, j: JSONObject) {
        val fmt = j.optString("fmt", "a")
        val video = fmt.startsWith("v")
        var yid = j.optString("yid")
        var title = j.optString("ti", "titre")
        if (yid.isEmpty()) {
            val r = Tools.search(j.optString("q").ifEmpty { j.optString("ti") }, 5)
            if (r.length() == 0) throw Exception("introuvable")
            yid = r.getJSONObject(0).getString("id"); title = r.getJSONObject(0).getString("title")
        }
        if (j.optBoolean("p2p") && (fmt == "a" || fmt == "al")) { if (p2pLead(id, yid, fmt == "al")) return }
        val beat = Thread { while (!Thread.currentThread().isInterrupted) { runCatching { client.rpc("relay_beat", JSONObject().put("p_id", id)) }; try { Thread.sleep(1500) } catch (e: InterruptedException) { return@Thread } } }
        beat.isDaemon = true; beat.start()
        val dir = File(eng.tmp, "r$id")
        try {
            var last = -10; var lastAt = 0L
            val push = { pct: Int ->
                val n = System.currentTimeMillis()
                if (pct - last >= 3 && n - lastAt > 1500) { last = pct; lastAt = n; runCatching { client.rpc("relay_progress", JSONObject().put("p_id", id).put("p_prog", pct)) } }
            }
            val pid = "relay$id"
            val f = if (video) Tools.downloadVideo(yid, dir, title, videoHeight(fmt), 48L * 1024 * 1024, pid, { false }) { push((it * 33).toInt()) }
                    else Tools.downloadAudio(yid, dir, title, fmt == "al", audioKbps(fmt), pid, { false }) { push((it * 33).toInt()) }
            runCatching { client.rpc("relay_progress", JSONObject().put("p_id", id).put("p_prog", 33)) }
            val buf = f.readBytes()
            val ext = f.extension.lowercase()
            val media = client.upload(buf, ext, if (video) "video/mp4" else if (ext == "m4a") "audio/mp4" else "audio/mpeg")
            val d = client.rpcObj("relay_done", JSONObject().put("p_id", id).put("p_media", media).put("p_size", buf.size))
            if (d == null || d.optString("status") != "ok") throw Exception("déjà traitée")
        } finally {
            beat.interrupt()
            dir.deleteRecursively()
        }
    }

    // ---------- mode p2p ----------
    private fun p2pLead(id: Long, yid: String, light: Boolean): Boolean {
        val beat = Thread { while (!Thread.currentThread().isInterrupted) { runCatching { client.rpc("relay_beat", JSONObject().put("p_id", id)) }; try { Thread.sleep(1500) } catch (e: InterruptedException) { return@Thread } } }
        beat.isDaemon = true; beat.start()
        try {
            val st = runCatching { eng.stream(yid, light, null) }.getOrNull()
            if (st == null || st.size < Cfg.P2P_MIN) return false
            val init = runCatching { client.rpcObj("relay_p2p_init", JSONObject().put("p_id", id).put("p_yid", yid).put("p_fid", st.fid).put("p_size", st.size)) }.getOrNull()
            if (init == null || init.optString("status") != "ok") return false
            p2p.add(id); eng.touch()
            sleep(7000)   // laisse aux autres le temps de se réveiller
            try {
                val fid = init.optString("fid"); val size = init.optLong("size")
                p2pWork(id, yid, light, fid, size, if (st.fid == fid && st.size == size) st else null)
            } catch (e: Throwable) { /* la demande est reprise par les autres */ }
            finally { p2p.remove(id); eng.touch() }
            return true
        } finally { beat.interrupt() }
    }

    private fun p2pStep() {
        val jl = runCatching { client.rpcArr("relay_p2p_jobs") }.getOrDefault(JSONArray())
        for (i in 0 until jl.length()) {
            val j = jl.optJSONObject(i) ?: continue
            val id = j.optLong("id")
            if (jobs.contains(id) || p2p.contains(id) || p2p.size >= 3) continue
            p2p.add(id); eng.hot = System.currentTimeMillis(); eng.touch()
            eng.log("[$username] p2p : j'aide la demande $id")
            Thread {
                try { p2pWork(id, j.optString("yid"), j.optString("fmt") == "al", j.optString("p2p_fid"), j.optLong("p2p_size"), null) }
                catch (e: Throwable) { eng.log("[$username] p2p : ${e.message}") }
                finally { p2p.remove(id); eng.touch() }
            }.start()
        }
    }

    private fun p2pWork(id: Long, yid: String, light: Boolean, fid: String, size: Long, known: Stream?) {
        if (yid.length != 11 || fid.isEmpty() || size <= 0) return
        val st = known ?: eng.stream(yid, light, fid)
        if (st == null || st.size != size) { eng.log("[$username] p2p : flux indisponible ou différent, je n'aide pas"); return }
        var idle = 0; var fails = 0
        while (idle < 40 && fails < 3 && share && !stopped) {
            val c = runCatching { client.rpcObj("relay_chunk_take", JSONObject().put("p_id", id)) }.getOrNull() ?: JSONObject().put("status", "error")
            when (c.optString("status")) {
                "ok" -> {
                    idle = 0
                    val idx = c.optInt("idx")
                    val beat = Thread { while (!Thread.currentThread().isInterrupted) { runCatching { client.rpc("relay_chunk_beat", JSONObject().put("p_id", id).put("p_idx", idx)) }; try { Thread.sleep(1500) } catch (e: InterruptedException) { return@Thread } } }
                    beat.isDaemon = true; beat.start()
                    try {
                        val buf = Tools.fetchRange(st, c.optLong("a"), c.optLong("b"))
                        val media = client.upload(buf, "m4a", "audio/mp4")
                        client.rpc("relay_chunk_done", JSONObject().put("p_id", id).put("p_idx", idx).put("p_media", media).put("p_size", buf.size))
                        fails = 0; helped++; eng.touch()
                    } catch (e: Exception) {
                        fails++
                        eng.log("[$username] p2p : morceau $idx échoué (${e.message})")
                        runCatching { client.rpc("relay_chunk_fail", JSONObject().put("p_id", id).put("p_idx", idx)) }
                    } finally { beat.interrupt() }
                }
                "wait", "error" -> { idle++; sleep(1500) }
                else -> return
            }
        }
    }
}

class Engine(ctx: Context, val store: Store, private val changed: () -> Unit) {
    val version: String = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0" } catch (e: Exception) { "1.0" }
    val tmp = File(ctx.cacheDir, "relay").also { it.mkdirs() }
    val running = AtomicInteger(0)
    @Volatile var hot = 0L
    private val workers = LinkedHashMap<String, Worker>()
    private val logs = ArrayList<String>()
    private class Entry(val at: Long, val st: Stream?)
    private val streams = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Any>()

    fun touch() = changed()
    fun maxJobs() = store.int("maxJobs", 3).coerceIn(1, 12)

    @Synchronized fun log(m: String) {
        logs.add(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.FRANCE).format(java.util.Date()) + " " + m)
        if (logs.size > 200) logs.removeAt(0)
        changed()
    }

    /** Résout le flux m4a d'une vidéo (une seule fois pour tous les comptes, gardé 10 minutes). */
    fun stream(yid: String, light: Boolean, fid: String?): Stream? {
        val k = yid + "|" + (if (light) 1 else 0)
        val lock = locks.getOrPut(k) { Any() }
        synchronized(lock) {
            val now = System.currentTimeMillis()
            streams.entries.removeIf { now - it.value.at > 600000 }
            var e = streams[k]
            if (e == null) {
                val st = Tools.resolveStream(yid, light, null)
                e = Entry(now, st); if (st != null) streams[k] = e
            }
            val st = e.st
            if (st != null && (fid == null || st.fid == fid)) return st
        }
        return Tools.resolveStream(yid, light, fid)
    }

    @Synchronized fun startAll() { Thread { runCatching { Tools.ensure() } }.start(); store.accounts().forEach { add(it) } }
    @Synchronized fun add(a: Acc) {
        if (workers.containsKey(a.username)) return
        val w = Worker(this, a); workers[a.username] = w; w.start(); changed()
    }
    @Synchronized fun remove(u: String) { workers.remove(u)?.stop(); store.removeAccount(u); changed() }
    @Synchronized fun setShare(u: String, on: Boolean) { store.setShare(u, on); workers[u]?.applyShare(on) }
    @Synchronized fun stopAll() { workers.values.forEach { it.stop() } }

    @Synchronized fun snapshot(): JSONObject {
        val a = JSONArray(); workers.values.forEach { a.put(it.info()) }
        return JSONObject().put("accounts", a).put("running", running.get()).put("maxJobs", maxJobs())
            .put("tool", Tools.status).put("logs", JSONArray(logs.takeLast(40)))
    }

    // ---------- création des comptes serveur ----------
    private fun rndCode(): String = Http.hex(9).take(12)
    private fun rndName(): String = "fts" + Http.hex(6).take(8)

    /** srv_auto : crée le compte côté base sans clé ; l'installation est identifiée par son numéro (30 serveurs maximum). */
    private fun registerAuto(username: String, code: String): String {
        val body = JSONObject().put("p_device", store.deviceId).put("p_username", username).put("p_code", code).put("p_platform", "srvdroid").toString().toByteArray()
        val r = Http.request("POST", Cfg.URL + "/rest/v1/rpc/srv_auto", mapOf("apikey" to Cfg.KEY, "Content-Type" to "application/json"), body, 30000)
        if (r.code !in 200..299) throw Exception("HTTP ${r.code} : ${r.text.take(160)}")
        return JSONObject(r.text).optString("status")
    }

    private val MSG = mapOf(
        "rate_limited" to "Trop de créations d'un coup : attends quelques minutes.",
        "invalid" to "Pseudo invalide : 3 à 20 caractères (a-z, 0-9, _).",
        "bad_code" to "Code trop court (8 caractères minimum).",
        "taken" to "Ce pseudo existe déjà.",
        "limit" to "Limite de serveurs atteinte sur cet appareil (30).")

    fun createAccount(username: String, code: String): JSONObject {
        val u = username.lowercase().trim()
        val st = try { registerAuto(u, code) } catch (e: Exception) { return JSONObject().put("ok", false).put("msg", "Impossible de joindre le serveur : " + e.message) }
        if (st == "taken") {
            try { Client(u, code).login() } catch (e: Exception) { return JSONObject().put("ok", false).put("msg", MSG["taken"] + " Si c'est un de tes comptes, vérifie son code.") }
        } else if (st != "ok") return JSONObject().put("ok", false).put("msg", MSG[st] ?: "Création refusée ($st).")
        store.addAccount(u, code)
        add(Acc(u, code, true))
        return JSONObject().put("ok", true).put("username", u).put("code", code)
    }

    @Volatile private var autoBusy = false
    /** Chaque installation crée toute seule ses 6 premiers serveurs (réessaie tant que le réseau manque). */
    fun autoServers() {
        if (autoBusy || store.bool("autoDone", false)) return
        autoBusy = true
        Thread {
            try {
                while (!store.bool("autoDone", false)) {
                    if (store.accounts().size >= BuildConfig.AUTO_SERVERS) { store.putBool("autoDone", true); break }
                    val r = createAccount(rndName(), rndCode())
                    if (!r.optBoolean("ok")) {
                        val m = r.optString("msg")
                        if (m.contains("Limite")) { store.putBool("autoDone", true); break }
                        if (m.contains("Impossible") || m.contains("Trop")) try { Thread.sleep(60000) } catch (e: InterruptedException) { return@Thread }
                    }
                }
            } finally { autoBusy = false }
        }.also { it.isDaemon = true }.start()
    }
}
