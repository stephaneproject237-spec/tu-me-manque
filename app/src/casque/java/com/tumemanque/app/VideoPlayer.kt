package com.tumemanque.app

import android.app.Activity
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.delay

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
    var ended by mutableStateOf(false)
        private set
    var fullscreen by mutableStateOf(false)
    var aspect by mutableFloatStateOf(16f / 9f)
        private set
    private var localId: String? = null

    fun player(ctx: Context): ExoPlayer {
        exo?.let { return it }
        val p = ExoPlayer.Builder(ctx.applicationContext)
            .setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).setUsage(C.USAGE_MEDIA).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (!isPlaying) saveLocal()
            }
            override fun onPlaybackStateChanged(state: Int) {
                loading = state == Player.STATE_BUFFERING
                ended = state == Player.STATE_ENDED
                if (state == Player.STATE_ENDED) saveLocal()
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
            }
        })
        exo = p
        surface?.let { p.setVideoTextureView(it) }
        return p
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
    suspend fun playYt(ctx: Context, v: YtVideo) {
        val p = player(ctx)
        saveLocal()
        localId = null
        key = v.id; title = v.title; error = null; ended = false; loading = true
        p.stop(); p.clearMediaItems()
        Hub.player.pause()
        val info = try { Yt.info(v.id) } catch (e: Exception) {
            if (key == v.id) { loading = false; error = "Impossible d'ouvrir cette vidéo pour le moment." }
            return
        }
        if (key != v.id) return
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(info.headers)
        info.headers["User-Agent"]?.let { http.setUserAgent(it) }
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http))
        val src = when {
            info.playUrl != null -> factory.createMediaSource(MediaItem.fromUri(info.playUrl))
            info.videoUrl != null && info.audioUrl != null -> MergingMediaSource(
                factory.createMediaSource(MediaItem.fromUri(info.videoUrl)),
                factory.createMediaSource(MediaItem.fromUri(info.audioUrl)),
            )
            info.videoUrl != null -> factory.createMediaSource(MediaItem.fromUri(info.videoUrl))
            else -> { loading = false; error = "Aucun flux lisible pour cette vidéo."; return }
        }
        p.setMediaSource(src)
        p.prepare()
        p.play()
    }

    /** Lit une vidéo téléchargée, là où on s'était arrêté. */
    fun playLocal(ctx: Context, item: VideoItem) {
        val p = player(ctx)
        saveLocal()
        localId = item.id
        key = item.id; title = item.video.title; error = null; ended = false
        Hub.player.pause()
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

    fun stop() {
        saveLocal()
        exo?.stop(); exo?.clearMediaItems()
        key = null; localId = null; playing = false; loading = false; error = null
        fullscreen = false
    }
}

private fun Context.activity(): Activity? {
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
                RoundBtn(if (big) Ic.FullscreenExit else Ic.Fullscreen, onFullscreen, size = 36.dp, iconSize = 22.dp, fill = Color.Transparent, tint = Color.White)
            }
        }
    }
    if (Vp.error != null) {
        Column(Modifier.align(Alignment.Center).padding(16.dp).glass(androidx.compose.foundation.shape.RoundedCornerShape(18.dp), 0.1f).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Vp.error ?: "", color = Color.White, fontSize = 13.sp)
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
        VideoSurface(Modifier.fillMaxSize().align(Alignment.Center))
        VideoControls(big = true) { Vp.fullscreen = false }
        Row(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
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
