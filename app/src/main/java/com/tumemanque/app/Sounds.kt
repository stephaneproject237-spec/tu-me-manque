package com.tumemanque.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Petits sons générés par le code (aucun fichier). */
object Sounds {
    private const val RATE = 44100

    private fun pcm(): ShortArray {
        // Deux notes rapides et montantes, avec une fin douce : un petit « pop » sympa.
        val notes = listOf(880.0 to 0.0, 1318.5 to 0.07)
        val total = (RATE * 0.26).toInt()
        val out = ShortArray(total)
        for (i in 0 until total) {
            val t = i.toDouble() / RATE
            var v = 0.0
            for ((f, start) in notes) {
                val tt = t - start
                if (tt < 0) continue
                val env = (1 - exp(-tt * 400)) * exp(-tt * 18)
                v += sin(2 * PI * f * tt) * env + 0.25 * sin(4 * PI * f * tt) * env
            }
            out[i] = (v * 0.35 * Short.MAX_VALUE).toInt().coerceIn(-32000, 32000).toShort()
        }
        return out
    }

    private val data: ShortArray by lazy { pcm() }

    fun send() {
        try {
            val d = data
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(d.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(d, 0, d.size)
            track.setNotificationMarkerPosition(d.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack?) { t?.release() }
                override fun onPeriodicNotification(t: AudioTrack?) {}
            })
            track.play()
        } catch (e: Exception) {
            // pas de son : tant pis
        }
    }
}
