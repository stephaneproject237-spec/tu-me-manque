package com.tumemanque.app

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.roundToInt
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.Gravity
import android.view.TextureView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.common.ForwardingPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Le lecteur vidéo : un seul pour toute l'appli (recherche et vidéos téléchargées). */
object Vp {
    private var exo: ExoPlayer? = null
    private var surface: TextureView? = null
    private var frame: AspectRatioFrameLayout? = null

    /** Ce qui est chargé : identifiant YouTube ou fichier téléchargé. */
    var key by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf("")
        private set
    var playing by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var errorDetail by mutableStateOf("")
        private set
    private var lastYt: YtVideo? = null
    var ended by mutableStateOf(false)
        private set
    var fullscreen by mutableStateOf(false)
    /** Réduite en petite fenêtre flottante dans l'appli. */
    var mini by mutableStateOf(false)
    /** Position à l'écran de la fenêtre du lecteur (onglet Rechercher) : on peut y poser la bulle. */
    var slot by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    /** Fenêtre flottante d'Android (par-dessus les autres applis). */
    var inPip by mutableStateOf(false)
    var aspect by mutableFloatStateOf(16f / 9f)
        private set
    private var localId: String? = null
    /** La petite fenêtre a été poussée vers la gauche : on ne la voit plus, mais le son continue. */
    var miniHidden by mutableStateOf(false)
    /** Lecture continue : à la fin d'une vidéo YouTube, une autre du même thème démarre. */
    var autoplay by mutableStateOf(false)
        private set
    /** Vidéos proposées juste avant (résultats de la recherche) : la suivante part en premier. */
    var queue: List<YtVideo> = emptyList()
    private val played = LinkedHashSet<String>()
    /** Résolution choisie pour les vidéos YouTube (240 par défaut) et celles que la vidéo en cours propose. */
    var quality by mutableIntStateOf(240)
        private set
    var heights by mutableStateOf<List<Int>>(emptyList())
        private set
    var curH by mutableIntStateOf(240)
        private set
    private var curInfo: YtInfo? = null
    /** La vidéo suivante, déjà préparée : elle démarre toute seule, même écran verrouillé. */
    private var pending: YtVideo? = null
    private var pendingInfo: YtInfo? = null
    private var pendingH = 360
    private var preloadJob: kotlinx.coroutines.Job? = null
    private var appCtx: Context? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    /** Une vidéo YouTube (et non un fichier téléchargé) est chargée. */
    val isYt: Boolean get() = key != null && localId == null

    /** Relit le réglage « lecture continue » (avant même de lancer une vidéo). */
    fun loadPrefs(ctx: Context) {
        val sp = ctx.applicationContext.getSharedPreferences("casque_vp", Context.MODE_PRIVATE)
        autoplay = sp.getBoolean("autoplay", false)
        quality = sp.getInt("quality", 240)
    }

    fun setAutoplay(ctx: Context, on: Boolean) {
        autoplay = on
        ctx.applicationContext.getSharedPreferences("casque_vp", Context.MODE_PRIVATE).edit().putBoolean("autoplay", on).apply()
        if (on) preloadNext() else dropPending()
    }

    private fun dropPending() {
        preloadJob?.cancel()
        val p = exo
        if (pending != null && p != null && p.mediaItemCount > 1) p.removeMediaItem(p.mediaItemCount - 1)
        pending = null; pendingInfo = null
    }

    /** Résolution : on relit la vidéo là où on en était. */
    fun setQuality(ctx: Context, h: Int) {
        val app = ctx.applicationContext
        quality = h
        app.getSharedPreferences("casque_vp", Context.MODE_PRIVATE).edit().putInt("quality", h).apply()
        val p = exo ?: return
        val info = curInfo ?: return
        val id = lastYt?.id ?: return
        if (!isYt) return
        dropPending()
        val (src, got) = buildSource(app, info, h, id) ?: return
        val pos = p.currentPosition
        val wasPlaying = p.playWhenReady
        curH = got
        p.setMediaSource(src, pos)
        p.prepare()
        p.playWhenReady = wasPlaying
        preloadNext()
    }

    private fun updateHeights(info: YtInfo, got: Int) {
        heights = (info.videoUrls.keys + (if (info.playUrl != null) listOf(360) else emptyList())).filter { it in 144..2160 }.distinct().sorted()
        curH = got
    }

    /** Prépare la source d'une vidéo à la résolution voulue (renvoie aussi la hauteur obtenue). */
    private fun buildSource(ctx: Context, info: YtInfo, h: Int, id: String): Pair<MediaSource, Int>? {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(info.headers)
        info.headers["User-Agent"]?.let { http.setUserAgent(it) }
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http))
        fun item(u: String) = MediaItem.Builder().setUri(u).setMediaId(id).build()
        val best = info.videoUrls.keys.filter { it <= h }.maxOrNull()
        if (!(h == 360 && info.playUrl != null) && best != null && info.bestAudioUrl != null) {
            return MergingMediaSource(
                factory.createMediaSource(item(info.videoUrls.getValue(best))),
                factory.createMediaSource(MediaItem.fromUri(info.bestAudioUrl)),
            ) to best
        }
        return when {
            info.playUrl != null -> factory.createMediaSource(item(info.playUrl)) to 360
            info.videoUrl != null && info.audioUrl != null -> MergingMediaSource(
                factory.createMediaSource(item(info.videoUrl)),
                factory.createMediaSource(MediaItem.fromUri(info.audioUrl)),
            ) to 0
            info.videoUrl != null -> factory.createMediaSource(item(info.videoUrl)) to 0
            else -> null
        }
    }

    private fun nextCandidate(cur: YtVideo): YtVideo? =
        queue.dropWhile { it.id != cur.id }.drop(1).firstOrNull { it.id !in played }
            ?: queue.firstOrNull { it.id !in played }

    /** Choisit et charge à l'avance la vidéo suivante : à la fin de la vidéo, la lecture continue sans réseau ni appli au premier plan. */
    fun preloadNext() {
        val ctx = appCtx ?: return
        val p = exo ?: return
        val cur = lastYt ?: return
        if (!autoplay || !isYt || pending != null) return
        preloadJob?.cancel()
        preloadJob = scope.launch {
            var n = nextCandidate(cur)
            if (n == null) {
                val found = runCatching { Yt.search(cur.title, 12) }.getOrDefault(emptyList())
                queue = (queue + found).distinctBy { it.id }
                n = nextCandidate(cur)
            }
            if (n == null || key != cur.id || !autoplay) return@launch
            val info = runCatching { Yt.info(n.id) }.getOrNull() ?: return@launch
            if (key != cur.id || !autoplay || pending != null) return@launch
            val (src, got) = buildSource(ctx, info, quality, n.id) ?: return@launch
            pending = n; pendingInfo = info; pendingH = got
            p.addMediaSource(src)
        }
    }

    /** Lance la vidéo suivante du même thème (la suivante des résultats, sinon une recherche sur le titre). */
    fun playNextRelated() {
        val ctx = appCtx ?: return
        val cur = lastYt ?: return
        val wl = runCatching {
            (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "casque:suivante").apply { acquire(90_000L) }
        }.getOrNull()
        scope.launch {
          try {
            played.add(cur.id)
            var n = queue.dropWhile { it.id != cur.id }.drop(1).firstOrNull { it.id !in played }
                ?: queue.firstOrNull { it.id !in played }
            if (n == null) {
                val found = runCatching { Yt.search(cur.title, 12) }.getOrDefault(emptyList())
                queue = (queue + found).distinctBy { it.id }
                n = found.firstOrNull { it.id !in played }
            }
            if (n != null && key == cur.id) playYt(ctx, n, auto = true)
          } finally { runCatching { if (wl?.isHeld == true) wl.release() } }
        }
    }

    fun player(ctx: Context): ExoPlayer {
        exo?.let { return it }
        appCtx = ctx.applicationContext
        loadPrefs(ctx)
        val p = ExoPlayer.Builder(ctx.applicationContext)
            .setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).setUsage(C.USAGE_MEDIA).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (!isPlaying) saveLocal()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val n = pending ?: return
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || mediaItem?.mediaId != n.id) return
                played.add(n.id)
                lastYt = n; key = n.id; title = n.title; localId = null
                error = null; errorDetail = ""; ended = false
                val inf = pendingInfo
                pending = null; pendingInfo = null
                curInfo = inf
                if (inf != null) updateHeights(inf, pendingH)
                // On garde la liste courte : seule la vidéo en cours reste.
                if (p.currentMediaItemIndex > 0) p.removeMediaItems(0, p.currentMediaItemIndex)
                preloadNext()
            }
            override fun onPlaybackStateChanged(state: Int) {
                loading = state == Player.STATE_BUFFERING
                ended = state == Player.STATE_ENDED
                if (state == Player.STATE_ENDED) { saveLocal(); if (autoplay && isYt) playNextRelated() }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    frame?.setAspectRatio(aspect)
                }
            }
            override fun onPlayerError(e: PlaybackException) {
                loading = false
                error = "Lecture impossible. Réessaie, ou télécharge la vidéo."
                errorDetail = e.errorCodeName
            }
        })
        exo = p
        surface?.let { p.setVideoTextureView(it) }
        // Écran verrouillé et notification : lecture, pause, précédent, suivant (comme pour la musique).
        session = MediaSession.Builder(ctx.applicationContext, VideoFwd(p)).setId("video").build()
        return p
    }

    /** La session de la vidéo (écran verrouillé, casque Bluetooth). */
    var session: MediaSession? = null
        private set

    /** Fait apparaître les commandes de l'écran verrouillé pour la vidéo en cours. */
    private fun showControls(ctx: Context) {
        val app = ctx.applicationContext
        PlaybackService.attachVideo()
        runCatching { app.startService(Intent(app, PlaybackService::class.java)) }
    }

    fun attach(tv: TextureView, f: AspectRatioFrameLayout) {
        surface = tv; frame = f
        f.setAspectRatio(aspect)
        exo?.setVideoTextureView(tv)
    }

    fun detach(tv: TextureView) {
        if (surface === tv) {
            exo?.clearVideoTextureView(tv)
            surface = null; frame = null
        }
    }

    /** Lit une vidéo YouTube (sans la télécharger). */
    suspend fun playYt(ctx: Context, v: YtVideo, auto: Boolean = false) {
        if (!auto) Feel.tap()
        val p = player(ctx)
        saveLocal()
        if (!auto) played.clear()
        played.add(v.id)
        miniHidden = false
        Shell.miniHidden = true   // la barre audio descend ; elle remonte quand on relance une chanson
        localId = null
        lastYt = v
        key = v.id; title = v.title; error = null; errorDetail = ""; ended = false; loading = true
        dropPending()
        p.stop(); p.clearMediaItems()
        Hub.player.pause()
        showControls(ctx)
        val info = try { Yt.info(v.id) } catch (e: Exception) {
            if (key == v.id) {
                loading = false
                error = "Impossible d'ouvrir cette vidéo pour le moment."
                errorDetail = (e.message ?: e.javaClass.simpleName).lineSequence().map { it.removePrefix("ERROR: ").trim() }.firstOrNull { it.isNotEmpty() }?.take(140).orEmpty()
            }
            return
        }
        if (key != v.id) return
        val built = buildSource(ctx.applicationContext, info, quality, v.id)
        if (built == null) { loading = false; error = "Aucun flux lisible pour cette vidéo."; return }
        val (src, got) = built
        curInfo = info
        updateHeights(info, got)
        p.setMediaSource(src)
        p.prepare()
        p.play()
        preloadNext()
    }

    /** Lit une vidéo téléchargée, là où on s'était arrêté. */
    fun playLocal(ctx: Context, item: VideoItem) {
        Feel.tap()
        val p = player(ctx)
        saveLocal()
        dropPending()
        curInfo = null; heights = emptyList()
        localId = item.id
        miniHidden = false
        Shell.miniHidden = true
        key = item.id; title = item.video.title; error = null; ended = false
        Hub.player.pause()
        showControls(ctx)
        p.setMediaItem(MediaItem.fromUri(item.id), Vids.position(item.id))
        p.prepare()
        p.play()
    }

    private fun saveLocal() {
        val id = localId ?: return
        val p = exo ?: return
        Vids.savePosition(id, p.currentPosition, p.duration.coerceAtLeast(0))
    }

    fun toggle() {
        val p = exo ?: return
        if (p.isPlaying) p.pause() else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
    }

    fun seekBy(ms: Long) { exo?.let { it.seekTo((it.currentPosition + ms).coerceIn(0, it.duration.coerceAtLeast(0))) } }
    fun seekTo(ms: Long) { exo?.seekTo(ms) }
    fun position(): Long = exo?.currentPosition ?: 0L
    fun duration(): Long = exo?.duration?.takeIf { it > 0 } ?: 0L

    fun pause() { exo?.pause() }

    /** Relance la dernière vidéo YouTube qui n'a pas pu être lue. */
    suspend fun retry(ctx: Context) { lastYt?.let { playYt(ctx, it) } }

    fun stop() {
        saveLocal()
        preloadJob?.cancel(); pending = null; pendingInfo = null; curInfo = null; heights = emptyList()
        exo?.stop(); exo?.clearMediaItems()
        key = null; localId = null; playing = false; loading = false; error = null
        fullscreen = false; mini = false; miniHidden = false
        PlaybackService.detachVideo()
    }
}

/** Le « suivant » de l'écran verrouillé lance la vidéo du même thème ; « précédent » reprend au début. */
private class VideoFwd(p: Player) : ForwardingPlayer(p) {
    private val cmds = intArrayOf(
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    )
    override fun getAvailableCommands(): Player.Commands {
        val b = super.getAvailableCommands().buildUpon()
        if (Vp.isYt) cmds.forEach { b.add(it) } else b.add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        return b.build()
    }
    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
    override fun seekToNext() { Vp.playNextRelated() }
    override fun seekToNextMediaItem() { Vp.playNextRelated() }
    override fun seekToPrevious() { Vp.seekTo(0) }
    override fun seekToPreviousMediaItem() { Vp.seekTo(0) }
    override fun hasNextMediaItem(): Boolean = Vp.isYt
    override fun hasPreviousMediaItem(): Boolean = true
}

fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** L'image de la vidéo (proportions respectées). */
@Composable
fun VideoSurface(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { c ->
            AspectRatioFrameLayout(c).apply {
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                val tv = TextureView(c)
                addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
                tag = tv
                Vp.attach(tv, this)
            }
        },
        modifier = modifier,
        onRelease = { f -> (f.tag as? TextureView)?.let { Vp.detach(it) } },
    )
}

/** Commandes en verre par-dessus la vidéo : lecture, barre de progression, plein écran. */
@Composable
fun BoxScope.VideoControls(big: Boolean, onFullscreen: () -> Unit) {
    var visible by remember { mutableStateOf(true) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var touch by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Vp.key) { while (true) { pos = Vp.position(); dur = Vp.duration(); delay(400) } }
    LaunchedEffect(Vp.playing, touch, visible) {
        if (Vp.playing && visible) { delay(3200); visible = false }
    }
    Box(
        Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, indication = null) { visible = !visible; touch = System.currentTimeMillis() },
    )
    if (Vp.error != null) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        Column(
            Modifier.align(Alignment.Center).padding(16.dp).glass(androidx.compose.foundation.shape.RoundedCornerShape(20.dp), 0.12f).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(Vp.error ?: "", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (Vp.errorDetail.isNotBlank()) Text(Vp.errorDetail, color = Color(0xAAFFFFFF), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(10.dp))
            GlassChip("Réessayer", Ic.Refresh, P.blueSoft) { scope.launch { Vp.retry(ctx) } }
        }
        return
    }
    AnimatedVisibility(visible || !Vp.playing, Modifier.matchParentSize(), enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x55000000), Color(0x11000000), Color(0x88000000))))) {
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (big) 40.dp else 26.dp)) {
                RoundBtn(Ic.Replay10, { Vp.seekBy(-10_000); touch = System.currentTimeMillis() }, size = if (big) 54.dp else 44.dp, iconSize = if (big) 30.dp else 24.dp, tint = Color.White)
                Box(contentAlignment = Alignment.Center) {
                    RoundBtn(
                        if (Vp.playing) Ic.Pause else Ic.Play, { Vp.toggle(); touch = System.currentTimeMillis() },
                        size = if (big) 76.dp else 62.dp, iconSize = if (big) 42.dp else 34.dp, tint = Color.White,
                    )
                    if (Vp.loading) CircularProgressIndicator(Modifier.size(if (big) 76.dp else 62.dp), color = Color.White, strokeWidth = 2.dp)
                }
                RoundBtn(Ic.Forward10, { Vp.seekBy(10_000); touch = System.currentTimeMillis() }, size = if (big) 54.dp else 44.dp, iconSize = if (big) 30.dp else 24.dp, tint = Color.White)
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = if (big) 14.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(fmt(dragging?.toLong() ?: pos), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Slider(
                    value = (dragging ?: pos.toFloat()).coerceIn(0f, dur.toFloat().coerceAtLeast(1f)),
                    onValueChange = { dragging = it; touch = System.currentTimeMillis() },
                    onValueChangeFinished = { dragging?.let { Vp.seekTo(it.toLong()) }; dragging = null },
                    valueRange = 0f..dur.toFloat().coerceAtLeast(1f),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(28.dp),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0x40FFFFFF)),
                )
                Text(fmt(dur), color = Color(0xCCFFFFFF), fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                val act = LocalContext.current.activity()
                if (Vp.isYt && Vp.heights.size > 1) {
                    val c = LocalContext.current
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        Text(
                            if (Vp.curH > 0) "${Vp.curH}p" else "HD", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).clickable { menu = true; touch = System.currentTimeMillis() }.padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            Vp.heights.forEach { h ->
                                DropdownMenuItem(text = { Text(if (h == Vp.curH) "✓  ${h}p" else "${h}p") }, onClick = { menu = false; Vp.setQuality(c, h) })
                            }
                        }
                    }
                }
                if (Vp.isYt) {
                    val c = LocalContext.current
                    RoundBtn(Ic.Repeat, { Vp.setAutoplay(c, !Vp.autoplay); touch = System.currentTimeMillis() }, size = 36.dp, iconSize = 20.dp, fill = Color.Transparent, tint = if (Vp.autoplay) P.blueSoft else Color.White)
                }
                if (act != null && Pip.supported(act)) {
                    RoundBtn(Ic.Pip, { Pip.enter(act) }, size = 36.dp, iconSize = 20.dp, fill = Color.Transparent, tint = Color.White)
                }
                RoundBtn(if (big) Ic.FullscreenExit else Ic.Fullscreen, onFullscreen, size = 36.dp, iconSize = 22.dp, fill = Color.Transparent, tint = Color.White)
            }
        }
    }
}

/** La vidéo en plein écran (bascule en paysage si la vidéo est horizontale). */
@Composable
fun VideoFullscreen() {
    val ctx = LocalContext.current
    val act = remember { ctx.activity() }
    BackHandler { Vp.fullscreen = false }
    DisposableEffect(Unit) {
        val w = act?.window
        val ctl = w?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (Vp.aspect > 1.05f) act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ctl?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(remember { MutableInteractionSource() }, indication = null) {}) {
        if (!Vp.inPip) VideoSurface(Modifier.fillMaxSize().align(Alignment.Center))
        VideoControls(big = true) { Vp.fullscreen = false }
        Row(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundBtn(Ic.ChevronDown, { Vp.fullscreen = false }, size = 40.dp, iconSize = 26.dp, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            RoundBtn(Ic.Close, { Vp.fullscreen = false }, size = 40.dp, iconSize = 20.dp, tint = Color.White)
            Spacer(Modifier.width(12.dp))
            Text(Vp.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Taille lisible d'un fichier. */
fun fmtBytes(b: Long): String = when {
    b <= 0 -> ""
    b >= 1_073_741_824 -> "%.1f Go".format(b / 1_073_741_824.0)
    b >= 1_048_576 -> "%.0f Mo".format(b / 1_048_576.0)
    else -> "${b / 1024} Ko"
}

// =============================================================================================
//  Petite fenêtre flottante dans l'appli (déplaçable)
// =============================================================================================

@Composable
fun BoxScope.MiniVideo(bottom: Dp) {
    val density = LocalDensity.current
    var off by remember { mutableStateOf(Offset.Zero) }
    var showCtl by remember { mutableStateOf(true) }
    LaunchedEffect(showCtl, Vp.playing) { if (showCtl && Vp.playing) { delay(2500); showCtl = false } }
    // Dans l'appli, la bulle ne se cache pas : on peut la poser devant l'écran du lecteur, elle y entre alors.
    val dock = remember { androidx.compose.animation.core.Animatable(0f) }
    var bubble by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var dock0 by remember { mutableStateOf(Offset.Zero) }
    val scopeM = androidx.compose.runtime.rememberCoroutineScope()
    BoxWithConstraints(Modifier.matchParentSize()) {
        val w = 210.dp
        val h = (w / Vp.aspect.coerceIn(0.56f, 2.4f)).coerceAtMost(300.dp)
        val maxX = with(density) { (maxWidth - w - 24.dp).toPx() }.coerceAtLeast(0f)
        val maxY = with(density) { (maxHeight - h - bottom - 60.dp).toPx() }.coerceAtLeast(0f)
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = bottom)
                .offset { IntOffset(off.x.roundToInt(), off.y.roundToInt()) }
                .graphicsLayer {
                    translationX = dock0.x * dock.value; translationY = dock0.y * dock.value
                    val k = 1f - 0.55f * dock.value; scaleX = k; scaleY = k; alpha = 1f - 0.85f * dock.value
                }
                .onGloballyPositioned { bubble = it.boundsInRoot() }
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDragEnd = {
                            val slot = Vp.slot
                            val c = bubble.center
                            if (slot != null && Shell.tab == "search" && slot.contains(c)) {
                                // La bulle entre dans l'écran du lecteur, la vidéo continue sans coupure.
                                dock0 = slot.center - c
                                scopeM.launch {
                                    dock.animateTo(1f, androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                                    Vp.mini = false
                                    dock.snapTo(0f); off = Offset.Zero
                                }
                            }
                        },
                    ) { change, drag ->
                        change.consume()
                        off = Offset((off.x + drag.x).coerceIn(-maxX, 0f), (off.y + drag.y).coerceIn(-maxY, 0f))
                    }
                }
                .size(w, h)
                .shadow(18.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black)
                .border(0.7.dp, Color(0x40FFFFFF), RoundedCornerShape(18.dp))
                .clickable(remember { MutableInteractionSource() }, indication = null) { showCtl = !showCtl },
        ) {
            VideoSurface(Modifier.fillMaxSize())
            if (Vp.loading) CircularProgressIndicator(Modifier.align(Alignment.Center).size(30.dp), color = Color.White, strokeWidth = 2.dp)
            AnimatedVisibility(showCtl || !Vp.playing, Modifier.matchParentSize(), enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
                    RoundBtn(Ic.Close, { Vp.stop() }, Modifier.align(Alignment.TopStart).padding(6.dp), size = 30.dp, iconSize = 16.dp, tint = Color.White)
                    RoundBtn(Ic.Fullscreen, { Vp.mini = false; Vp.fullscreen = true }, Modifier.align(Alignment.TopEnd).padding(6.dp), size = 30.dp, iconSize = 18.dp, tint = Color.White)
                    RoundBtn(if (Vp.playing) Ic.Pause else Ic.Play, { Vp.toggle(); showCtl = true }, Modifier.align(Alignment.Center), size = 46.dp, iconSize = 26.dp, tint = Color.White)
                }
            }
        }
    }
}

// =============================================================================================
//  Fenêtre flottante d'Android (Picture-in-Picture) quand on quitte l'appli
// =============================================================================================

object Pip {
    const val ACTION = "com.casque.app.PIP_TOGGLE"

    fun supported(act: Activity): Boolean =
        Build.VERSION.SDK_INT >= 26 && act.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    @RequiresApi(26)
    fun params(act: Activity): PictureInPictureParams {
        val a = Vp.aspect.coerceIn(0.42f, 2.38f)
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational((a * 1000).roundToInt(), 1000))
        val pi = PendingIntent.getBroadcast(
            act, 11, Intent(ACTION).setPackage(act.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val icon = Icon.createWithResource(act, if (Vp.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        b.setActions(listOf(RemoteAction(icon, if (Vp.playing) "Pause" else "Lecture", "Lecture / pause", pi)))
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(Vp.key != null && Vp.playing)
            b.setSeamlessResizeEnabled(true)
        }
        return b.build()
    }

    fun enter(act: Activity) {
        if (!supported(act) || Vp.key == null) return
        runCatching { act.enterPictureInPictureMode(params(act)) }
    }
}

/** Branche la fenêtre flottante sur l'activité : entrée automatique en quittant l'appli, bouton lecture/pause. */
@Composable
fun PipHost() {
    val ctx = LocalContext.current
    val act = remember { ctx.activity() as? ComponentActivity } ?: return
    if (!Pip.supported(act)) return
    LaunchedEffect(Vp.playing, Vp.key, Vp.aspect) {
        if (Build.VERSION.SDK_INT >= 26) runCatching { act.setPictureInPictureParams(Pip.params(act)) }
    }
    DisposableEffect(act) {
        val leave = Runnable {
            // Android 8 à 11 : on entre nous-mêmes dans la fenêtre flottante.
            if (Build.VERSION.SDK_INT < 31 && Vp.key != null && Vp.playing) Pip.enter(act)
        }
        val mode = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { info ->
            Vp.inPip = info.isInPictureInPictureMode
            if (!info.isInPictureInPictureMode) {
                // Fenêtre fermée avec la croix : l'appli n'est plus visible, on met en pause.
                act.window.decorView.postDelayed({
                    if (!act.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) Vp.pause()
                }, 400)
            }
        }
        val obs = LifecycleEventObserver { _, _ -> }
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { Vp.toggle() }
        }
        act.addOnUserLeaveHintListener(leave)
        act.addOnPictureInPictureModeChangedListener(mode)
        act.lifecycle.addObserver(obs)
        ContextCompat.registerReceiver(act, rx, IntentFilter(Pip.ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            act.removeOnUserLeaveHintListener(leave)
            act.removeOnPictureInPictureModeChangedListener(mode)
            act.lifecycle.removeObserver(obs)
            runCatching { act.unregisterReceiver(rx) }
        }
    }
}

/** Ce qu'on voit dans la fenêtre flottante d'Android : seulement la vidéo. */
@Composable
fun PipScreen() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        VideoSurface(Modifier.fillMaxSize())
    }
}
