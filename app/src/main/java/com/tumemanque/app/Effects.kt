package com.tumemanque.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin
import kotlin.random.Random

private val RoseVif = Color(0xFFE91E63)

/** Vibration « battement de cœur » quand on appuie sur Tu me manques. */
object Haptics {
    fun beat(context: Context) {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (v == null || !v.hasVibrator()) return
            val pattern = longArrayOf(0, 60, 90, 90, 200, 140)
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            else @Suppress("DEPRECATION") v.vibrate(pattern, -1)
        } catch (_: Throwable) { }
    }
}

/** Fond dégradé rose / lilas / pêche avec de grandes taches lumineuses qui dérivent doucement. */
@Composable
fun AppBackground() {
    val t = rememberInfiniteTransition(label = "fond")
    val drift by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(14000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "drift",
    )
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.linearGradient(
                listOf(Color(0xFF160A12), Color(0xFF231224), Color(0xFF100E1A)),
                start = Offset.Zero, end = Offset(size.width, size.height),
            )
        )
        val r = size.width * 0.8f
        val c1 = Offset(size.width * (0.1f + 0.2f * drift), size.height * (0.1f + 0.08f * drift))
        drawCircle(Brush.radialGradient(listOf(Color(0x55E91E63), Color.Transparent), center = c1, radius = r), r, c1)
        val c2 = Offset(size.width * (0.95f - 0.25f * drift), size.height * (0.9f - 0.1f * drift))
        drawCircle(Brush.radialGradient(listOf(Color(0x446E50DC), Color.Transparent), center = c2, radius = r), r, c2)
        val c3 = Offset(size.width * (0.9f - 0.2f * drift), size.height * (0.35f + 0.1f * drift))
        drawCircle(Brush.radialGradient(listOf(Color(0x33FF7A5A), Color.Transparent), center = c3, radius = r * 0.7f), r * 0.7f, c3)
    }
}

/** Carte « verre » : blanc translucide, bord lumineux, ombre rose douce. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .shadow(14.dp, shape, ambientColor = Color(0x44E91E63), spotColor = Color(0x44E91E63))
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xC0302028), Color(0x9920141C))))
            .border(1.dp, Brush.linearGradient(listOf(Color(0x24FFFFFF), Color(0x0DFFFFFF))), shape)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** Bouton secondaire en verre. */
@Composable
fun GlassButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.5f)
            .shadow(8.dp, shape, ambientColor = Color(0x33E91E63), spotColor = Color(0x33E91E63))
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xCC3A2830), Color(0x99281A22))))
            .border(1.dp, Brush.linearGradient(listOf(Color(0x24FFFFFF), Color(0x0DFFFFFF))), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = RoseVif, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
}

/** Le grand bouton rond « Tu me manques » : bille rose brillante qui bat comme un cœur. */
@Composable
fun BigMissButton(enabled: Boolean, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "coeur")
    val pulse by t.animateFloat(
        1f, 1.05f,
        infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(
        Modifier
            .size(250.dp)
            .scale(if (enabled) pulse else 1f)
            .alpha(if (enabled) 1f else 0.6f)
            .shadow(26.dp, CircleShape, ambientColor = Color(0x88E91E63), spotColor = Color(0x99E91E63))
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFFFF8FB4), Color(0xFFE91E63), Color(0xFFC2185B))))
            .border(2.dp, Brush.linearGradient(listOf(Color(0xDDFFFFFF), Color(0x11FFFFFF))), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp)
                .width(150.dp).height(70.dp)
                .clip(RoundedCornerShape(50))
                .background(Brush.verticalGradient(listOf(Color(0x77FFFFFF), Color.Transparent)))
        )
        Text(
            "Tu me manques 💗", color = Color.White, fontSize = 25.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 28.dp),
        )
    }
}

private class Particle(
    val glyph: String, val x0: Float, val size: Float, val delay: Float,
    val speed: Float, val sway: Float, val phase: Float,
)

/** Des cœurs et des papillons qui s'envolent depuis le bas de l'écran. `trigger` > 0 relance l'animation. */
@Composable
fun FlutterOverlay(trigger: Int, heartsOnly: Boolean = false) {
    if (trigger == 0) return
    val progress = remember(trigger) { Animatable(0f) }
    val particles = remember(trigger) {
        val glyphs = if (heartsOnly) listOf("❤️", "💗", "💖", "💕", "💞", "💘") else listOf("💗", "💖", "💕", "🦋", "🦋", "💞", "🦋")
        List(26) {
            Particle(
                glyphs.random(), Random.nextFloat(), 22f + Random.nextFloat() * 22f,
                Random.nextFloat() * 0.3f, 0.7f + Random.nextFloat() * 0.3f,
                10f + Random.nextFloat() * 26f, Random.nextFloat() * 6.28f,
            )
        }
    }
    val paint = remember { android.graphics.Paint().apply { isAntiAlias = true } }
    LaunchedEffect(trigger) { progress.animateTo(1f, tween(4800, easing = LinearEasing)) }
    Canvas(Modifier.fillMaxSize()) {
        val p = progress.value
        val d = density
        drawIntoCanvas { c ->
            for (pt in particles) {
                val t = ((p - pt.delay) / (1f - pt.delay)).coerceIn(0f, 1f)
                if (t <= 0f || t >= 1f) continue
                val rise = (t * pt.speed).coerceAtMost(1f)
                val y = size.height + 40f * d - rise * (size.height + 120f * d)
                val x = pt.x0 * size.width + sin(t * 7f + pt.phase) * pt.sway * d
                val a = when {
                    t < 0.1f -> t / 0.1f
                    t > 0.75f -> (1f - t) / 0.25f
                    else -> 1f
                }
                paint.textSize = pt.size * d
                paint.alpha = (a * 255).toInt().coerceIn(0, 255)
                c.nativeCanvas.drawText(pt.glyph, x, y, paint)
            }
        }
    }
}
