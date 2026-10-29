package com.tumemanque.app

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lecture depuis la page Téléchargements : un audio fait remonter la barre de lecture,
 * une vidéo ouvre un lecteur qui glisse depuis la ligne touchée. Quand un fichier se termine,
 * le suivant (dans l'ordre de téléchargement) démarre tout seul.
 */
object DlPlay {
    /** La lecture continue est en route (elle s'arrête dès qu'on lance autre chose ailleurs). */
    var active by mutableStateOf(false)
        private set
    var curId by mutableStateOf<String?>(null)
        private set
    /** Le lecteur vidéo de la page est ouvert. */
    var videoOpen by mutableStateOf(false)
        private set
    /** Hauteur (dans la page) de la ligne touchée : le lecteur part de là. */
    var fromY by mutableFloatStateOf(0f)
    /** Ce lancement vient de nous : il ne coupe pas la lecture continue. */
    var internal = false
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: kotlinx.coroutines.Job? = null

    /** Les fichiers présents, dans l'ordre où ils ont été téléchargés. */
    fun playable(): List<DlTask> = Dl.tasks.value
        .filter { it.status == DlStatus.DONE && Dl.stillThere(it) }
        .sortedBy { it.created }

    fun start(ctx: Context, t: DlTask, y: Float = fromY) {
        fromY = y
        active = true
        go(ctx.applicationContext, t)
    }

    private fun go(ctx: Context, t: DlTask) {
        job?.cancel()
        job = scope.launch {
            curId = t.id
            val id = t.resultId ?: return@launch
            val wasOpen = videoOpen
            if (wasOpen) { videoOpen = false; delay(280) }   // l'ancien lecteur redescend d'abord
            internal = true
            try {
                if (t.kind.video) {
                    val item = Vids.items.value.firstOrNull { it.id == id } ?: return@launch
                    Hub.player.pause()
                    Vp.playLocal(ctx, item)
                    videoOpen = true
                } else {
                    val tr = Lib.byId.value[id] ?: return@launch
                    if (Vp.key != null) Vp.stop()
                    Hub.play(listOf(tr), 0)
                }
            } finally { internal = false }
        }
    }

    /** Fichier suivant (+1) ou précédent (−1) ; au bout de la liste, la lecture s'arrête. */
    fun step(ctx: Context, d: Int) {
        val l = playable()
        val i = l.indexOfFirst { it.id == curId }
        val n = (if (i < 0) 0 else i + d)
        if (n in l.indices) go(ctx.applicationContext, l[n]) else if (d > 0) end()
    }

    private fun end() { active = false }

    /** Un audio (ou une vidéo) vient de finir. */
    fun onEnded(video: Boolean) {
        if (!active) return
        val cur = Dl.find(curId ?: return) ?: return
        if (cur.kind.video != video) return
        val ctx = appCtx ?: return
        step(ctx, 1)
    }

    var appCtx: Context? = null

    fun closeVideo() { videoOpen = false }
    /** Autre lecture lancée ailleurs : on rend la main. */
    fun release() { if (!internal) { active = false; videoOpen = false } }
}

/** Le lecteur de la page Téléchargements : mêmes proportions que celui de la recherche, il glisse depuis la ligne touchée. */
@Composable
fun DlWindow(top: Float, modifier: Modifier = Modifier) {
    val p = remember { Animatable(0f) }
    var mounted by remember { mutableStateOf(false) }
    LaunchedEffect(DlPlay.videoOpen) {
        if (DlPlay.videoOpen) { mounted = true; p.snapTo(0f); p.animateTo(1f, tween(460, easing = FastOutSlowInEasing)) }
        else if (mounted) { p.animateTo(0f, tween(300, easing = FastOutSlowInEasing)); mounted = false }
    }
    LaunchedEffect(Vp.key) { if (Vp.key == null && DlPlay.videoOpen) DlPlay.closeVideo() }
    if (!mounted) return
    val soft = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
    Box(
        modifier.fillMaxWidth().graphicsLayer {
            val k = 0.82f + 0.18f * p.value
            translationY = (1f - p.value) * (DlPlay.fromY - top)
            scaleX = k; scaleY = k; alpha = p.value
            transformOrigin = TransformOrigin(0.5f, 0f)
        }.aspectRatio(16f / 9f).shadow(16.dp, soft, ambientColor = Color.Black, spotColor = Color.Black).clip(soft).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (!Vp.fullscreen && !Vp.inPip && Vp.key != null) {
            VideoSurface(Modifier.fillMaxSize())
            VideoControls(big = false) { Vp.fullscreen = true }
        }
    }
}
