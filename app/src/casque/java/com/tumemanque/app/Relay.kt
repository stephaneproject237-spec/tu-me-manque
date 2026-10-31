package com.tumemanque.app

import android.content.Context
import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Relais pour les amis iPhone : un iPhone ne peut pas télécharger depuis YouTube. Quand Free tune est ouvert,
 * ce téléphone regarde ce que ses amis attendent, prend la demande si personne d'autre ne l'a prise,
 * télécharge le titre et le renvoie.
 */
object Relay {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("casque_relay", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("help", true)
    fun setEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("help", on).apply(); scope.launch { Backend.heartbeat(on, Presence.foreground) } }

    /** Le serveur demande un signe de vie (notification silencieuse) : on répond « je suis en ligne ». */
    fun ping(ctx: Context) { if (Backend.hasSession()) scope.launch { Backend.heartbeat(enabled(ctx), Presence.foreground) } }

    /** Un téléchargement pour un ami est en cours. */
    var busy by mutableStateOf(false)
        private set
    /** Nombre de titres envoyés à des amis depuis le lancement de l'appli. */
    var helped by mutableIntStateOf(0)
        private set

    private val lock = Mutex()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    // Plusieurs demandes en même temps : chaque demande prise démarre tout de suite son propre téléchargement et son propre envoi.
    private val active = java.util.concurrent.ConcurrentHashMap<Long, String>()
    private const val MAX_PARALLEL = 8
    // Mode p2p : demandes pour lesquelles ce téléphone télécharge des morceaux.
    private val p2pActive = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()

    private fun keyOf(j: org.json.JSONObject): String =
        (j.optString("yid").ifEmpty { j.optString("q") }) + "|" + (if (j.optString("fmt", "a").startsWith("v")) "v" else "a")

    /** Réveillé par une notification silencieuse : traite les demandes en attente (en parallèle) jusqu'à ce que tout soit terminé. */
    suspend fun drain(ctx: Context) {
        if (Backend.hasSession()) Backend.heartbeat(enabled(ctx), Presence.foreground)
        var guard = 0
        while (guard++ < 600 && enabled(ctx) && Backend.hasSession()) {
            val launched = runCatching { step(ctx) }.getOrDefault(false)
            runCatching { p2pStep(ctx) }
            if (!launched && active.isEmpty() && p2pActive.isEmpty()) break
            delay(if (launched) 600 else 2_000)
        }
    }

    suspend fun loop(ctx: Context) {
        if (!MbEngine.supported) return
        var lastBeat = 0L
        while (true) {
            if (Backend.hasSession()) {
                // Présence en ligne (vue dans l'appli Admin) : toutes les 40 secondes, avec l'état du partage.
                val now = System.currentTimeMillis()
                if (now - lastBeat > 20_000) { lastBeat = now; scope.launch { Backend.heartbeat(enabled(ctx), Presence.foreground) } }
                if (enabled(ctx)) { runCatching { step(ctx) }; runCatching { p2pStep(ctx) } }
            }
            delay(4_000)
        }
    }

    /** Prend toutes les demandes ouvertes (dans la limite du possible) et lance chacune sans attendre les autres. */
    private suspend fun step(ctx: Context): Boolean {
        var launched = false
        lock.withLock {
            if (active.size >= MAX_PARALLEL) return false
            val jobs = Backend.relayOpen()
            for (i in 0 until jobs.length()) {
                if (active.size >= MAX_PARALLEL) break
                val j = jobs.getJSONObject(i)
                val id = j.getLong("id")
                val key = keyOf(j)
                // Déjà en cours chez nous (même demande, ou même titre demandé deux fois) : on ne la prend pas deux fois.
                if (active.containsKey(id) || active.containsValue(key)) continue
                if (!Backend.relayTake(id)) continue
                active[id] = key
                busy = true
                launched = true
                scope.launch {
                    try {
                        handle(ctx, id, j)
                        helped++
                    } catch (_: Exception) {
                        Backend.relayFail(id)
                    } finally {
                        active.remove(id)
                        busy = active.isNotEmpty()
                    }
                }
            }
        }
        return launched
    }

    /**
     * Premier téléphone à avoir pris la demande : il choisit le format, annonce la taille exacte (le serveur découpe en morceaux),
     * puis télécharge des morceaux comme les autres. Renvoie false si le découpage n'est pas possible (méthode habituelle ensuite).
     */
    private suspend fun p2pLead(ctx: Context, id: Long, song: MbSong, light: Boolean): Boolean {
        val beat = scope.launch { while (true) { Backend.relayBeat(id); delay(1_500) } }
        try {
            val st = runCatching { P2p.resolve(ctx, song.id, light, null) }.getOrNull() ?: return false
            val init = Backend.relayP2pInit(id, song.id, st.fid, st.size) ?: return false
            val fid = init.optString("fid"); val size = init.optLong("size")
            val yid = init.optString("yid").ifEmpty { song.id }
            p2pActive[id] = true
            try { p2pWork(ctx, id, yid, light, fid, size, st.takeIf { it.fid == fid && it.size == size }) } catch (_: Exception) { } finally { p2pActive.remove(id) }
            return true
        } finally { beat.cancel() }
    }

    /** Aide les téléchargements p2p des autres : toutes les 4 s, regarde s'il y a des morceaux à télécharger. */
    private suspend fun p2pStep(ctx: Context) {
        if (!MbEngine.supported) return
        val jobs = Backend.relayP2pJobs()
        for (i in 0 until jobs.length()) {
            val j = jobs.getJSONObject(i)
            val id = j.getLong("id")
            if (active.containsKey(id) || p2pActive.size >= 4) continue
            if (p2pActive.putIfAbsent(id, true) != null) continue
            scope.launch {
                try {
                    p2pWork(ctx, id, j.optString("yid"), j.optString("fmt") == "al", j.optString("p2p_fid"), j.optLong("p2p_size"), null)
                } catch (_: Exception) {
                } finally { p2pActive.remove(id) }
            }
        }
    }

    /** Boucle de travail : prend un morceau libre, le télécharge, l'envoie, recommence ; s'arrête quand tout est fait. */
    private suspend fun p2pWork(ctx: Context, id: Long, yid: String, light: Boolean, fid: String, size: Long, known: P2p.Stream?) {
        if (yid.length != 11 || fid.isEmpty() || size <= 0) return
        val st = known ?: P2p.resolve(ctx, yid, light, fid) ?: return
        if (st.size != size) return // ce téléphone voit une autre version du fichier : il n'aide pas
        val dir = File(ctx.cacheDir, "cam/p2p").apply { mkdirs() }
        var idle = 0
        var fails = 0
        while (idle < 40 && fails < 3 && enabled(ctx) && Backend.hasSession()) {
            val c = Backend.relayChunkTake(id)
            when (c.optString("status")) {
                "ok" -> {
                    idle = 0
                    val idx = c.getInt("idx")
                    val beat = scope.launch { while (true) { Backend.relayChunkBeat(id, idx); delay(1_500) } }
                    val f = File(dir, "p${id}_$idx.part")
                    try {
                        P2p.fetchRange(st, c.getLong("a"), c.getLong("b"), f)
                        val media = Backend.uploadFile(f, "m4a", "audio/mp4")
                        Backend.relayChunkDone(id, idx, media, f.length())
                        fails = 0
                    } catch (_: Exception) {
                        fails++
                        Backend.relayChunkFail(id, idx)
                    } finally {
                        beat.cancel()
                        runCatching { f.delete() }
                    }
                }
                "wait", "error" -> { idle++; delay(1_500) }
                else -> return // terminé (ou la demande n'existe plus)
            }
        }
    }

    private suspend fun handle(ctx: Context, id: Long, j: org.json.JSONObject) {
        val fmt = j.optString("fmt", "a").ifEmpty { "a" }
        val video = fmt.startsWith("v")
        var song = MbSong(j.optString("yid"), j.optString("ti"), j.optString("ch"), j.optInt("d"), 0)
        if (song.id.isEmpty()) {
            val q = j.optString("q").ifEmpty { song.title }
            song = MbEngine.search(ctx, q).firstOrNull() ?: throw Exception("introuvable")
        }
        // Mode p2p (réglage de l'administrateur) : le fichier est partagé entre tous les Android en ligne. Si ce n'est pas possible, méthode habituelle.
        if (j.optBoolean("p2p") && (fmt == "a" || fmt == "al")) {
            if (p2pLead(ctx, id, song, fmt == "al")) return
        }
        // La barre de l'iPhone est coupée en trois tiers égaux : 1) ce téléchargement, 2) l'envoi du fichier, 3) la réception sur l'iPhone.
        // Signe de vie toutes les 1,5 s : si ce téléphone perd internet plus de ~6 s, la demande passe à un autre téléphone.
        val beat = scope.launch { while (true) { Backend.relayBeat(id); delay(1_500) } }
        try {
        var lastPct = -10
        var lastAt = 0L
        fun push(pct: Int) {
            val now = System.currentTimeMillis()
            if (pct - lastPct >= 3 && now - lastAt > 1500) { lastPct = pct; lastAt = now; scope.launch { Backend.relayProgress(id, pct) } }
        }
        val report: (Float) -> Unit = { p -> push((p * 33f).toInt().coerceIn(0, 33)) }
        val f = if (video) MbEngine.downloadVideo(ctx, song, MbEngine.videoHeight(fmt), report)
        else MbEngine.downloadFast(ctx, song, fmt == "al", report) ?: MbEngine.download(ctx, song, MbEngine.audioKbps(fmt), report)
        Backend.relayProgress(id, 33)
        try {
            val media = Backend.uploadFile(f, if (video) "mp4" else if (f.extension.equals("m4a", true)) "m4a" else "mp3", if (video) "video/mp4" else "audio/mpeg") { p -> push(33 + (p * 34f).toInt().coerceIn(0, 34)) }
            if (!Backend.relayDone(id, media, f.length())) throw Exception("déjà traitée")
        } finally {
            runCatching { f.delete() }
        }
        } finally { beat.cancel() }
    }
}

/** Appelée par la notification silencieuse (voir MissMessagingService). */
fun onRelayPush(ctx: Context) = RelayService.start(ctx)

/** Notification silencieuse « signale-toi ». */
fun onPingPush(ctx: Context) = Relay.ping(ctx)

/** Free tune est-il au premier plan ? (« dans Free tune » ou « sur internet » dans l'appli Admin) */
object Presence { @Volatile var foreground = false }
