package com.tumemanque.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.BitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Pochettes : on essaie la miniature du système, puis l'image intégrée au fichier. */
object Art {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val misses = HashSet<String>()

    fun peek(id: String, px: Int): Bitmap? = cache.get("$id@$px")

    /** Oublie ce qu'on savait d'un morceau (une nouvelle pochette vient d'arriver). */
    fun forget(id: String) {
        synchronized(misses) { misses.removeAll { it.startsWith("$id@") } }
        cache.snapshot().keys.filter { it.startsWith("$id@") }.forEach { cache.remove(it) }
    }

    fun loadSync(ctx: Context, t: Track, px: Int): Bitmap? {
        val key = "${t.id}@$px"
        cache.get(key)?.let { return it }
        if (synchronized(misses) { key in misses }) return null
        // Morceau téléchargé dans Casque : la pochette prise sur YouTube.
        var bmp: Bitmap? = Covers.load(ctx, t.id, px)
        val uri = t.uri
        if (bmp == null && Build.VERSION.SDK_INT >= 29 && t.id.startsWith("content://media/")) {
            bmp = runCatching { ctx.contentResolver.loadThumbnail(uri, Size(px, px), null) }.getOrNull()
        }
        if (bmp == null && Build.VERSION.SDK_INT < 29 && t.albumId > 0) {
            bmp = runCatching {
                val u = android.content.ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), t.albumId)
                ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
        if (bmp == null) {
            val r = MediaMetadataRetriever()
            bmp = try {
                r.setDataSource(ctx, uri)
                r.embeddedPicture?.let { data ->
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data, 0, data.size, o)
                    var s = 1
                    while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
                    BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = s })
                }
            } catch (_: Exception) {
                null
            } finally {
                runCatching { r.release() }
            }
        }
        if (bmp != null) cache.put(key, bmp) else synchronized(misses) { misses.add(key) }
        return bmp
    }

    suspend fun load(ctx: Context, t: Track, px: Int): Bitmap? = withContext(Dispatchers.IO) { loadSync(ctx, t, px) }
}

/** Donne à la notification et à l'écran verrouillé la pochette du morceau (même quand elle est dans le fichier). */
class ArtLoader(private val ctx: Context) : BitmapLoader {
    private val exec: ListeningExecutorService = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    override fun supportsMimeType(mimeType: String) = true

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        exec.submit<Bitmap> { BitmapFactory.decodeByteArray(data, 0, data.size) }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        exec.submit<Bitmap> {
            val t = Lib.byId.value[uri.toString()]
            (t?.let { Art.loadSync(ctx, it, 512) }) ?: error("pas de pochette")
        }

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        val id = metadata.extras?.getString("tid") ?: return null
        val t = Lib.byId.value[id] ?: return null
        return exec.submit<Bitmap> { Art.loadSync(ctx, t, 512) ?: error("pas de pochette") }
    }
}

/** Le lecteur : un seul ExoPlayer pour toute l'appli, partagé avec la notification et l'écran verrouillé. */
object Hub {
    private lateinit var app: Context
    private var ready = false
    lateinit var player: ExoPlayer
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var currentId by mutableStateOf<String?>(null)
    var playing by mutableStateOf(false)
    var shuffle by mutableStateOf(false)
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    var queue by mutableStateOf<List<String>>(emptyList())
    var index by mutableIntStateOf(0)
    var speed by mutableFloatStateOf(1f)
    var durationMs by mutableLongStateOf(0L)
    var sleepEndsAt by mutableLongStateOf(0L)
    var sleepEndOfTrack by mutableStateOf(false)
    var buffering by mutableStateOf(false)

    private var sleepJob: Job? = null
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    var eqBands by mutableIntStateOf(0)
    var eqEnabled by mutableStateOf(false)
    var bassLevel by mutableIntStateOf(0)

    private fun prefs() = app.getSharedPreferences("casque_player", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (ready) return
        app = ctx.applicationContext
        Lib.init(app)
        player = ExoPlayer.Builder(app)
            .setAudioAttributes(
                AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).setUsage(C.USAGE_MEDIA).build(), true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        ready = true
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentId = mediaItem?.mediaId
                index = player.currentMediaItemIndex
                durationMs = 0
                if (sleepEndOfTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    player.pause(); sleepEndOfTrack = false
                }
                saveState()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying; if (!isPlaying) saveState() }
            override fun onShuffleModeEnabledChanged(e: Boolean) { shuffle = e; prefs().edit().putBoolean("shuffle", e).apply() }
            override fun onRepeatModeChanged(m: Int) { repeat = m; prefs().edit().putInt("repeat", m).apply() }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) DlPlay.onEnded(false)
                if (state == Player.STATE_READY) durationMs = player.duration.coerceAtLeast(0)
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) { attachFx(audioSessionId) }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Fichier illisible : on passe au suivant.
                if (player.hasNextMediaItem()) { player.seekToNextMediaItem(); player.prepare(); player.play() }
            }
        })
        shuffle = prefs().getBoolean("shuffle", false)
        repeat = prefs().getInt("repeat", Player.REPEAT_MODE_OFF)
        player.shuffleModeEnabled = shuffle
        player.repeatMode = repeat
        speed = prefs().getFloat("speed", 1f)
        player.playbackParameters = PlaybackParameters(speed)
        attachFx(player.audioSessionId)
        restore()
    }

    // ------------------------------------------------------------------ file d'attente
    private fun item(t: Track): MediaItem {
        val extras = Bundle().apply { putString("tid", t.id) }
        return MediaItem.Builder()
            .setMediaId(t.id)
            .setUri(t.uri)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album).setExtras(extras).build()
            )
            .build()
    }

    private fun syncQueue() {
        queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        index = player.currentMediaItemIndex
        currentId = player.currentMediaItem?.mediaId
    }

    private fun ensureService() {
        runCatching { app.startService(Intent(app, PlaybackService::class.java)) }
    }

    /** Joue une liste à partir d'un morceau. */
    fun play(list: List<Track>, start: Int = 0, shuffleOn: Boolean? = null) {
        Feel.tap()
        if (list.isEmpty()) return
        DlPlay.release()
        Shell.miniHidden = false
        ensureService()
        if (shuffleOn != null) player.shuffleModeEnabled = shuffleOn
        player.setMediaItems(list.map(::item), start.coerceIn(0, list.lastIndex), 0)
        player.prepare()
        player.play()
        syncQueue()
        saveState()
    }

    fun playNext(t: Track) {
        ensureService()
        if (player.mediaItemCount == 0) { play(listOf(t)); return }
        player.addMediaItem(player.currentMediaItemIndex + 1, item(t))
        syncQueue(); saveState()
    }

    fun enqueue(list: List<Track>) {
        ensureService()
        if (player.mediaItemCount == 0) { play(list); return }
        player.addMediaItems(list.map(::item))
        syncQueue(); saveState()
    }

    fun removeAt(i: Int) {
        if (i !in 0 until player.mediaItemCount) return
        player.removeMediaItem(i)
        syncQueue(); saveState()
    }

    /** Retire de la file d'attente les morceaux supprimés de la bibliothèque. */
    fun removeIds(ids: Set<String>) {
        if (!::player.isInitialized) return
        for (i in player.mediaItemCount - 1 downTo 0) {
            if (player.getMediaItemAt(i).mediaId in ids) player.removeMediaItem(i)
        }
        syncQueue(); saveState()
    }

    fun move(from: Int, to: Int) {
        if (from == to || from !in 0 until player.mediaItemCount || to !in 0 until player.mediaItemCount) return
        player.moveMediaItem(from, to)
        syncQueue(); saveState()
    }

    fun jump(i: Int) {
        if (i !in 0 until player.mediaItemCount) return
        Shell.miniHidden = false
        ensureService()
        player.seekTo(i, 0)
        player.prepare()
        player.play()
    }

    fun toggle() {
        ensureService()
        if (player.mediaItemCount == 0) return
        if (player.isPlaying) player.pause() else { Shell.miniHidden = false; player.prepare(); player.play() }
    }

    fun next() {
        if (DlPlay.active) { DlPlay.appCtx?.let { DlPlay.step(it, 1); return } }
        if (player.hasNextMediaItem()) player.seekToNextMediaItem() else if (player.mediaItemCount > 0) { player.seekTo(0, 0) }
    }

    fun previous() {
        if (DlPlay.active && player.currentPosition <= 3000) { DlPlay.appCtx?.let { DlPlay.step(it, -1); return } }
        if (player.currentPosition > 3000 || !player.hasPreviousMediaItem()) player.seekTo(0) else player.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) = player.seekTo(ms)

    fun toggleShuffle() { player.shuffleModeEnabled = !player.shuffleModeEnabled }

    fun cycleRepeat() {
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun changeSpeed(v: Float) {
        speed = v
        player.playbackParameters = PlaybackParameters(v)
        prefs().edit().putFloat("speed", v).apply()
    }

    // ------------------------------------------------------------------ minuteur de sommeil
    fun sleepIn(minutes: Int) {
        sleepJob?.cancel()
        sleepEndOfTrack = false
        if (minutes <= 0) { sleepEndsAt = 0; player.volume = 1f; return }
        sleepEndsAt = System.currentTimeMillis() + minutes * 60_000L
        sleepJob = scope.launch {
            while (System.currentTimeMillis() < sleepEndsAt) delay(1000)
            // Fondu de 8 secondes avant la pause.
            for (i in 8 downTo 0) { player.volume = i / 8f; delay(1000) }
            player.pause()
            player.volume = 1f
            sleepEndsAt = 0
        }
    }

    fun sleepAtEndOfTrack() {
        sleepJob?.cancel(); sleepEndsAt = 0; player.volume = 1f
        sleepEndOfTrack = true
    }

    // ------------------------------------------------------------------ égaliseur
    private fun attachFx(sessionId: Int) {
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId == 0) return
        runCatching { eq?.release() }
        runCatching { bass?.release() }
        eq = null; bass = null
        runCatching {
            eq = Equalizer(0, sessionId).also { e ->
                eqBands = e.numberOfBands.toInt()
                val saved = prefs().getString("eq", null)
                if (saved != null) {
                    val parts = saved.split(",")
                    for (b in 0 until e.numberOfBands.toInt()) parts.getOrNull(b)?.toShortOrNull()?.let { e.setBandLevel(b.toShort(), it) }
                }
                eqEnabled = prefs().getBoolean("eq_on", false)
                e.enabled = eqEnabled
            }
        }
        runCatching {
            bass = BassBoost(0, sessionId).also { b ->
                bassLevel = prefs().getInt("bass", 0)
                if (b.strengthSupported) b.setStrength(bassLevel.toShort())
                b.enabled = bassLevel > 0
            }
        }
    }

    fun eqRange(): Pair<Int, Int> = eq?.bandLevelRange?.let { it[0].toInt() to it[1].toInt() } ?: (-1500 to 1500)
    fun eqFreq(band: Int): Int = (eq?.getCenterFreq(band.toShort()) ?: 0) / 1000
    fun eqLevel(band: Int): Int = eq?.getBandLevel(band.toShort())?.toInt() ?: 0
    fun eqPresets(): List<String> = eq?.let { e -> (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) } } ?: emptyList()

    fun changeEq(on: Boolean) {
        eqEnabled = on
        runCatching { eq?.enabled = on }
        prefs().edit().putBoolean("eq_on", on).apply()
    }

    fun setBand(band: Int, level: Int) {
        runCatching { eq?.setBandLevel(band.toShort(), level.toShort()) }
        saveEq()
    }

    fun usePreset(i: Int) {
        runCatching { eq?.usePreset(i.toShort()) }
        saveEq()
        changeEq(true)
    }

    private fun saveEq() {
        val e = eq ?: return
        prefs().edit().putString("eq", (0 until e.numberOfBands.toInt()).joinToString(",") { e.getBandLevel(it.toShort()).toString() }).apply()
    }

    fun setBass(level: Int) {
        bassLevel = level
        runCatching { bass?.let { if (it.strengthSupported) it.setStrength(level.toShort()); it.enabled = level > 0 } }
        prefs().edit().putInt("bass", level).apply()
    }

    // ------------------------------------------------------------------ mémoire de la dernière écoute
    fun saveState() {
        if (!ready || player.mediaItemCount == 0) return
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        prefs().edit()
            .putString("queue", ids.joinToString("\n"))
            .putInt("index", player.currentMediaItemIndex)
            .putLong("pos", player.currentPosition)
            .apply()
    }

    private fun restore() {
        val saved = prefs().getString("queue", null) ?: return
        val ids = saved.split("\n").filter { it.isNotEmpty() }
        if (ids.isEmpty()) return
        val byId = Lib.byId.value
        val tracks = ids.mapNotNull { byId[it] }
        if (tracks.isEmpty()) return
        val idx = prefs().getInt("index", 0).coerceIn(0, tracks.lastIndex)
        player.setMediaItems(tracks.map(::item), idx, prefs().getLong("pos", 0))
        player.prepare()
        player.pause()
        syncQueue()
    }
}

/** Service de lecture : notification avec boutons, écran verrouillé, casque Bluetooth, lecture en arrière-plan. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    companion object {
        @Volatile private var inst: PlaybackService? = null
        private var videoAdded = false

        /** La vidéo en cours a sa propre session : ses commandes s'ajoutent à la notification. */
        fun attachVideo() { inst?.addVideo() }
        fun detachVideo() { inst?.removeVideo() }
    }

    private fun addVideo() {
        val v = Vp.session ?: return
        if (!videoAdded) { runCatching { addSession(v); videoAdded = true } }
    }

    private fun removeVideo() {
        val v = Vp.session ?: return
        if (videoAdded) { runCatching { removeSession(v) }; videoAdded = false }
    }

    override fun onCreate() {
        super.onCreate()
        inst = this
        videoAdded = false
        Hub.init(applicationContext)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, Hub.player)
            .setSessionActivity(open)
            .setBitmapLoader(ArtLoader(applicationContext))
            .build()
        // Sans contrôleur externe, c'est ce qui fait apparaître la notification de lecture.
        addSession(session!!)
        if (Vp.key != null) addVideo()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Appli fermée depuis la liste des applications : la musique continue si elle joue, sinon on s'arrête.
        if (!Hub.player.playWhenReady || Hub.player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        inst = null
        videoAdded = false
        session?.release()
        session = null
        super.onDestroy()
    }
}
