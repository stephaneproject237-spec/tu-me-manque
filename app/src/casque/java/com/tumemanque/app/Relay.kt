package com.tumemanque.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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
    fun setEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("help", on).apply() }

    /** Un téléchargement pour un ami est en cours. */
    var busy by mutableStateOf(false)
        private set
    /** Nombre de titres envoyés à des amis depuis le lancement de l'appli. */
    var helped by mutableIntStateOf(0)
        private set

    private val lock = Mutex()

    /** Réveillé par une notification silencieuse : traite toutes les demandes en attente puis s'arrête. */
    suspend fun drain(ctx: Context) {
        var guard = 0
        while (guard++ < 20 && enabled(ctx) && Backend.hasSession()) {
            if (!runCatching { step(ctx) }.getOrDefault(false)) break
        }
    }

    suspend fun loop(ctx: Context) {
        if (!MbEngine.supported) return
        while (true) {
            if (enabled(ctx) && Backend.hasSession() && !busy) runCatching { step(ctx) }
            delay(12_000)
        }
    }

    private suspend fun step(ctx: Context): Boolean = lock.withLock {
        val jobs = Backend.relayOpen()
        for (i in 0 until jobs.length()) {
            val j = jobs.getJSONObject(i)
            val id = j.getLong("id")
            if (!Backend.relayTake(id)) continue
            busy = true
            try {
                handle(ctx, id, j)
                helped++
            } catch (_: Exception) {
                Backend.relayFail(id)
            } finally {
                busy = false
            }
            return@withLock true
        }
        false
    }

    private suspend fun handle(ctx: Context, id: Long, j: org.json.JSONObject) {
        val fmt = j.optString("fmt", "a").ifEmpty { "a" }
        val video = fmt.startsWith("v")
        var song = MbSong(j.optString("yid"), j.optString("ti"), j.optString("ch"), j.optInt("d"), 0)
        if (song.id.isEmpty()) {
            val q = j.optString("q").ifEmpty { song.title }
            song = MbEngine.search(ctx, q).firstOrNull() ?: throw Exception("introuvable")
        }
        val f = if (video) MbEngine.downloadVideo(ctx, song, MbEngine.videoHeight(fmt)) { } else MbEngine.download(ctx, song, MbEngine.audioKbps(fmt)) { }
        try {
            val media = Backend.uploadFile(f, if (video) "mp4" else "mp3", if (video) "video/mp4" else "audio/mpeg")
            if (!Backend.relayDone(id, media, f.length())) throw Exception("déjà traitée")
        } finally {
            runCatching { f.delete() }
        }
    }
}

/** Appelée par la notification silencieuse (voir MissMessagingService). */
fun onRelayPush(ctx: Context) = RelayService.start(ctx)
