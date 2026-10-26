package com.tumemanque.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.Icon
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private val Pink = Color(0xFFE91E63)

/** Glisser un message vers la droite = répondre à ce message. */
@Composable
fun SwipeToReply(enabled: Boolean, onReply: () -> Unit, onLongPress: () -> Unit, content: @Composable () -> Unit) {
    var off by remember { mutableFloatStateOf(0f) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val limit = with(LocalDensity.current) { 80.dp.toPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .offset { IntOffset(off.roundToInt(), 0) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onLongPress = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onLongPress()
                })
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = { if (off > limit * 0.65f) onReply(); off = 0f },
                    onDragCancel = { off = 0f },
                    onHorizontalDrag = { change, dx -> change.consume(); off = (off + dx).coerceIn(0f, limit) },
                )
            },
    ) { content() }
}

/** Petit encadré « réponse à… » au-dessus d'une bulle. */
@Composable
fun QuoteChip(r: Reply, partner: String) {
    Column(
        Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x33FFFFFF))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(if (r.fromMe) "Toi" else partner, color = Pink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(r.text, color = Color(0xFFE3D6DD), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Barre au-dessus du champ de saisie : le message auquel on répond. */
@Composable
fun ReplyBar(r: Reply, partner: String, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp).clip(RoundedCornerShape(16.dp))
            .background(Color(0xCC2A1B24)).padding(start = 0.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(40.dp).background(Pink))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(if (r.fromMe) "Toi" else partner, color = Pink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(r.text, color = Color(0xFFE3D6DD), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.clickable { onCancel() }.padding(10.dp)) { Ico(R.drawable.ic_close, Color(0xFFA58F9B), 20.dp) }
    }
}

/** Trois points qui rebondissent : « est en train d'écrire… ». */
@Composable
fun TypingBubble() {
    val t = rememberInfiniteTransition(label = "typ")
    val p = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "tp")
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)).background(Color(0xBC42383F))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            for (i in 0..2) {
                val a = 0.35f + 0.65f * max(0f, sin(2.0 * PI * (p.value - i * 0.17f)).toFloat())
                Box(Modifier.padding(horizontal = 2.5.dp).size(8.dp).clip(CircleShape).background(Pink.copy(alpha = a)))
            }
            }
    }
}

private class StickerParticle(val x0: Float, val size: Float, val delay: Float, val speed: Float, val sway: Float, val phase: Float)

/** Le même sticker s'envole en plusieurs exemplaires (comme les papillons). */
@Composable
fun StickerBurst(id: String, trigger: Int) {
    if (trigger == 0 || id.isEmpty()) return
    val progress = remember(trigger) { Animatable(0f) }
    val items = remember(trigger) {
        List(14) {
            StickerParticle(
                Random.nextFloat(), 44f + Random.nextFloat() * 56f, Random.nextFloat() * 0.3f,
                0.7f + Random.nextFloat() * 0.3f, 10f + Random.nextFloat() * 30f, Random.nextFloat() * 6.28f,
            )
        }
    }
    LaunchedEffect(trigger) { progress.animateTo(1f, tween(4800, easing = LinearEasing)) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val p = progress.value
        for (pt in items) {
            val t = ((p - pt.delay) / (1f - pt.delay)).coerceIn(0f, 1f)
            if (t <= 0f || t >= 1f) continue
            val rise = (t * pt.speed).coerceAtMost(1f)
            val swayDp = (pt.sway * sin(pt.phase + t * 9f)).dp
            val fade = when {
                t < 0.08f -> t / 0.08f
                t > 0.8f -> (1f - t) / 0.2f
                else -> 1f
            }
            AsyncImage(
                model = "file:///android_asset/stickers/$id.${if (isAnimatedSticker(id)) "webp" else "png"}",
                contentDescription = null,
                modifier = Modifier
                    .size(pt.size.dp)
                    .offset(x = w * pt.x0 * 0.85f + swayDp, y = h + 40.dp - (h + 140.dp) * rise)
                    .graphicsLayer { this.alpha = fade; rotationZ = 10f * sin(pt.phase + t * 7f) },
            )
        }
    }
}

/** Icône vectorielle teintée. */
@Composable
fun Ico(res: Int, tint: Color, size: androidx.compose.ui.unit.Dp = 24.dp, modifier: Modifier = Modifier) {
    Icon(painterResource(res), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

private object ThumbCache {
    val map = object : android.util.LruCache<String, ImageBitmap>(40) {}
}

private fun loadThumb(m: ChatMsg): ImageBitmap? {
    val key = m.localFile ?: m.media ?: return null
    ThumbCache.map.get(key)?.let { return it }
    val r = MediaMetadataRetriever()
    return try {
        if (m.localFile != null) r.setDataSource(m.localFile) else r.setDataSource(Backend.mediaUrl(m.media ?: ""), HashMap())
        val bmp = r.getFrameAtTime(100_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.asImageBitmap()
        if (bmp != null) ThumbCache.map.put(key, bmp)
        bmp
    } catch (e: Exception) {
        null
    } finally {
        try { r.release() } catch (e: Exception) { /* ignoré */ }
    }
}

/** Vignette d'une vidéo dans le chat : image, bouton lecture et durée. */
@Composable
fun VideoTile(m: ChatMsg, onOpen: () -> Unit) {
    val thumb by produceState<ImageBitmap?>(null, m.media, m.localFile) {
        value = withContext(Dispatchers.IO) { loadThumb(m) }
    }
    val ratio = if (m.w > 0 && m.h > 0) (m.w.toFloat() / m.h).coerceIn(0.5f, 1.8f) else 1.2f
    Box(
        Modifier.width(240.dp).aspectRatio(ratio).clip(RoundedCornerShape(13.dp)).background(Color(0xFF2A1520)).clickable { onOpen() },
        contentAlignment = Alignment.Center,
    ) {
        thumb?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(Color(0x99000000)),
            contentAlignment = Alignment.Center,
        ) { Ico(R.drawable.ic_play, Color.White, 30.dp) }
        if (m.dur > 0) {
            Text(
                "%d:%02d".format(m.dur / 60, m.dur % 60), color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color(0x88000000)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** Lecture d'une vidéo en plein écran. */
@Composable
fun VideoViewer(m: ChatMsg, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        val mc = MediaController(ctx)
                        mc.setAnchorView(this)
                        setMediaController(mc)
                        if (m.localFile != null) setVideoPath(m.localFile) else setVideoURI(Uri.parse(Backend.mediaUrl(m.media ?: "")))
                        setOnPreparedListener { it.isLooping = false; start() }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.align(Alignment.TopStart).padding(16.dp).size(44.dp).clip(CircleShape).background(Color(0x99000000)).clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) { Ico(R.drawable.ic_close, Color.White, 22.dp) }
        }
    }
}

/** Message supprimé : une bulle discrète à la place. */
@Composable
fun DeletedBubble(mine: Boolean) {
    Text(
        "🚫 Message supprimé", color = Color(0xFF9B8793), fontSize = 15.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
        modifier = Modifier.clip(RoundedCornerShape(20.dp))
            .background(if (mine) Color(0x1AE91E63) else Color(0x0DFFFFFF))
            .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
