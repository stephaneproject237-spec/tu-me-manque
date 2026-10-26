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
