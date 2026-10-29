package com.tumemanque.app

import android.content.Context
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

    private fun keyOf(j: org.json.JSONObject): String =
        (j.optString("yid").ifEmpty { j.optString("q") }) + "|" + (if (j.optString("fmt", "a").startsWith("v")) "v" else "a")

    /** Réveillé par une notification silencieuse : traite les demandes en attente (en parallèle) jusqu'à ce que tout soit terminé. */
    suspend fun drain(ctx: Context) {
        if (Backend.hasSession()) Backend.heartbeat(enabled(ctx), Presence.foreground)
        var guard = 0
        while (guard++ < 600 && enabled(ctx) && Backend.hasSession()) {
            val launched = runCatching { step(ctx) }.getOrDefault(false)
            if (!launched && active.isEmpty()) break
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
                if (now - lastBeat > 40_000) { lastBeat = now; scope.launch { Backend.heartbeat(enabled(ctx), Presence.foreground) } }
                if (enabled(ctx)) runCatching { step(ctx) }
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

    private suspend fun handle(ctx: Context, id: Long, j: org.json.JSONObject) {
        val fmt = j.optString("fmt", "a").ifEmpty { "a" }
        val video = fmt.startsWith("v")
        var song = MbSong(j.optString("yid"), j.optString("ti"), j.optString("ch"), j.optInt("d"), 0)
        if (song.id.isEmpty()) {
            val q = j.optString("q").ifEmpty { song.title }
            song = MbEngine.search(ctx, q).firstOrNull() ?: throw Exception("introuvable")
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
