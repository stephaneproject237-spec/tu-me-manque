package com.tumemanque.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Barre de chargement « verre liquide » : rail de verre, remplissage lumineux, reflet qui glisse. */
@Composable
fun GlassProgress(progress: Float, indeterminate: Boolean, modifier: Modifier = Modifier) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(450), label = "gp")
    val t = rememberInfiniteTransition(label = "gpi")
    val sweep by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart), label = "gps")
    val slide by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "gpl")
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        val h = size.height
        val r = CornerRadius(h / 2, h / 2)
        // Rail de verre
        drawRoundRect(Brush.verticalGradient(listOf(Color(0x1FFFFFFF), Color(0x0DFFFFFF))), size = size, cornerRadius = r)
        drawRoundRect(Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x14FFFFFF))), size = size, cornerRadius = r, style = Stroke(1f))
        val inset = 2.5.dp.toPx()
        val innerH = h - inset * 2
        val ir = CornerRadius(innerH / 2, innerH / 2)
        val maxW = size.width - inset * 2
        val w: Float
        val x0: Float
        if (indeterminate) { w = maxW * 0.32f; x0 = inset + (maxW - w) * slide } else { w = (maxW * p).coerceAtLeast(innerH); x0 = inset }
        val fill = Brush.horizontalGradient(listOf(Color(0xFF5CA8FF), Color(0xFF7C6CFF), Color(0xFF3DDCFF)), startX = x0, endX = x0 + w)
        // Lueur
        drawRoundRect(Brush.horizontalGradient(listOf(Color(0x555CA8FF), Color(0x333DDCFF)), startX = x0, endX = x0 + w), Offset(x0, inset - 2.dp.toPx()), Size(w, innerH + 4.dp.toPx()), CornerRadius((innerH + 4.dp.toPx()) / 2))
        // Remplissage
        drawRoundRect(fill, Offset(x0, inset), Size(w, innerH), ir)
        // Reflet du haut (verre)
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xA6FFFFFF), Color(0x00FFFFFF)), startY = inset, endY = inset + innerH * 0.7f), Offset(x0 + 1.5.dp.toPx(), inset + 0.8.dp.toPx()), Size((w - 3.dp.toPx()).coerceAtLeast(0f), innerH * 0.5f), CornerRadius(innerH / 4))
        // Éclat qui traverse la barre
        val bw = w * 0.35f
        val bx = x0 + (w + bw) * sweep - bw
        drawRoundRect(
            Brush.horizontalGradient(listOf(Color(0x00FFFFFF), Color(0x80FFFFFF), Color(0x00FFFFFF)), startX = bx, endX = bx + bw),
            Offset(maxOf(bx, x0), inset), Size((minOf(bx + bw, x0 + w) - maxOf(bx, x0)).coerceAtLeast(0f), innerH), ir,
        )
    }
}
