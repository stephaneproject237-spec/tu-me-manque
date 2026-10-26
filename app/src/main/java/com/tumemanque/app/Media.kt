package com.tumemanque.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

class PreparedPhoto(val bytes: ByteArray, val w: Int, val h: Int)

/** Redimensionne une photo (1280 px max, JPEG) en tenant compte de son orientation. */
object ImageUtil {
    fun prepare(context: Context, uri: Uri, maxSide: Int = 1280, quality: Int = 82): PreparedPhoto? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val orientation = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
        }
        val scale = min(1f, maxSide.toFloat() / max(decoded.width, decoded.height))
        if (scale < 1f) m.postScale(scale, scale)
        val moved = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)

        // Fond blanc (les PNG transparents deviendraient noirs en JPEG).
        val flat = Bitmap.createBitmap(moved.width, moved.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(moved, 0f, 0f, null)
        }
        val out = ByteArrayOutputStream()
        flat.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return PreparedPhoto(out.toByteArray(), flat.width, flat.height)
    }
}

/** Enregistre un message vocal (AAC dans un fichier .m4a). */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    fun elapsedSeconds(): Int = if (recorder == null) 0 else ((System.currentTimeMillis() - startedAt) / 1000).toInt()

    @Suppress("DEPRECATION")
    fun start(): Boolean {
        if (recorder != null) return true
        val f = File(context.cacheDir, "vocal_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64000)
            r.setAudioSamplingRate(44100)
            r.setAudioChannels(1)
            r.setMaxDuration(300000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = f
            startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            runCatching { r.release() }
            f.delete()
            false
        }
    }

    /** Termine l'enregistrement. Renvoie le fichier et sa durée, ou null si trop court / échec. */
    fun stop(): Pair<File, Int>? {
        val r = recorder ?: return null
        val f = file
        val seconds = elapsedSeconds()
        recorder = null
        file = null
        return try {
            r.stop()
            r.release()
            if (f != null && seconds >= 1) f to seconds else { f?.delete(); null }
        } catch (e: Exception) {
            runCatching { r.release() }
            f?.delete()
            null
        }
    }

    fun cancel() {
        val r = recorder ?: return
        val f = file
        recorder = null
        file = null
        runCatching { r.stop() }
        runCatching { r.release() }
        f?.delete()
    }
}

data class PlayState(val key: String? = null, val playing: Boolean = false, val pos: Int = 0, val dur: Int = 0, val error: Boolean = false)

/** Lecteur de vocaux : un seul à la fois. */
object VoicePlayer {
    private var mp: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()

    private val ticker = object : Runnable {
        override fun run() {
            val p = mp ?: return
            try {
                if (p.isPlaying) {
                    _state.value = _state.value.copy(pos = p.currentPosition, dur = p.duration)
                    handler.postDelayed(this, 120)
                }
            } catch (e: IllegalStateException) {
                // lecteur déjà libéré
            }
        }
    }

    fun toggle(key: String, source: String) {
        val p = mp
        if (_state.value.key == key && p != null) {
            try {
                if (p.isPlaying) {
                    p.pause()
                    _state.value = _state.value.copy(playing = false)
                } else {
                    p.start()
                    _state.value = _state.value.copy(playing = true)
                    handler.post(ticker)
                }
            } catch (e: IllegalStateException) {
                stop()
            }
            return
        }
        stop()
        val player = MediaPlayer()
        mp = player
        _state.value = PlayState(key = key)
        try {
            player.setDataSource(source)
            player.setOnPreparedListener {
                it.start()
                _state.value = _state.value.copy(playing = true, dur = it.duration)
                handler.post(ticker)
            }
            player.setOnCompletionListener { stop() }
            player.setOnErrorListener { _, _, _ ->
                stop()
                _state.value = PlayState(error = true)
                true
            }
            player.prepareAsync()
        } catch (e: Exception) {
            stop()
            _state.value = PlayState(error = true)
        }
    }

    fun seek(key: String, fraction: Float) {
        val p = mp ?: return
        if (_state.value.key != key) return
        try {
            p.seekTo((fraction.coerceIn(0f, 1f) * p.duration).toInt())
            _state.value = _state.value.copy(pos = p.currentPosition)
        } catch (e: IllegalStateException) {
            // ignoré
        }
    }

    fun stop() {
        handler.removeCallbacks(ticker)
        runCatching { mp?.release() }
        mp = null
        _state.value = PlayState()
    }
}
