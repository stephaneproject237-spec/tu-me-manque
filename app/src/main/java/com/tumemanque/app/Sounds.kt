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

    /** Petite « bulle » : un glissando court et rond. `up` = monte (envoi), sinon descend (réception). */
    private fun bubble(up: Boolean): ShortArray {
        val total = (RATE * 0.22).toInt()
        val out = ShortArray(total)
        var phase = 0.0
        for (i in 0 until total) {
            val t = i.toDouble() / RATE
            val f = if (up) 500.0 + 900.0 * (1 - exp(-t * 30)) else 500.0 + 900.0 * exp(-t * 30)
            phase += 2 * PI * f / RATE
            val env = (1 - exp(-t * 600)) * exp(-t * 20)
            out[i] = (sin(phase) * env * 0.5 * Short.MAX_VALUE).toInt().coerceIn(-32000, 32000).toShort()
        }
        return out
    }

    private class Part(val at: Double, val dur: Double, val f0: Double, val f1: Double, val vol: Double, val wave: Int)

    /** Mélange plusieurs notes (sinus, triangle ou dent de scie) en un petit son. */
    private fun mix(parts: List<Part>): ShortArray {
        val len = parts.maxOf { it.at + it.dur + 0.03 }
        val total = (RATE * len).toInt()
        val acc = DoubleArray(total)
        for (p in parts) {
            val start = (p.at * RATE).toInt()
            val n = (p.dur * RATE).toInt()
            var phase = 0.0
            for (i in 0 until n) {
                val idx = start + i
                if (idx >= total) break
                val t = i.toDouble() / n
                val f = p.f0 * Math.pow(p.f1 / p.f0, t)
                phase += 2 * PI * f / RATE
                val x = phase % (2 * PI) / (2 * PI)
                val w = when (p.wave) {
                    1 -> 4 * Math.abs(x - 0.5) - 1
                    2 -> 2 * x - 1
                    else -> sin(phase)
                }
                val env = (1 - exp(-i.toDouble() / RATE * 400)) * exp(-t * 4.0)
                acc[idx] += w * env * p.vol
            }
        }
        return ShortArray(total) { (acc[it] * Short.MAX_VALUE).toInt().coerceIn(-32000, 32000).toShort() }
    }

    /** Joie : petit arpège qui monte, avec des étincelles. */
    private val joyData: ShortArray by lazy {
        val notes = listOf(523.25, 659.25, 783.99, 1046.5)
        val parts = ArrayList<Part>()
        notes.forEachIndexed { k, f ->
            parts.add(Part(k * 0.09, 0.28, f, f, 0.30, 1))
            parts.add(Part(k * 0.09, 0.20, f * 2, f * 2, 0.08, 0))
        }
        parts.add(Part(0.42, 0.35, 1568.0, 1568.0, 0.14, 0))
        parts.add(Part(0.50, 0.30, 2093.0, 2093.0, 0.08, 0))
        mix(parts)
    }

    /** Tristesse : « wah-wah » qui descend. */
    private val sadData: ShortArray by lazy {
        mix(listOf(
            Part(0.0, 0.32, 392.0, 330.0, 0.16, 2),
            Part(0.30, 0.55, 349.0, 247.0, 0.16, 2),
            Part(0.30, 0.55, 196.0, 150.0, 0.12, 0),
        ))
    }

    fun joy() = play(joyData)

    fun sad() = play(sadData)

    private val sendData: ShortArray by lazy { bubble(true) }
    private val recvData: ShortArray by lazy { bubble(false) }

    fun send() = play(sendData)

    fun receive() = play(recvData)

    private fun play(d: ShortArray) {
        try {
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
