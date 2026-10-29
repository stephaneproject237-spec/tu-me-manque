package com.tumemanque.app

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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

/** Barre de chargement néon « verre liquide » : un seul sens, de la gauche vers la droite, proportionnelle au pourcentage. */
@Composable
fun GlassProgress(progress: Float, indeterminate: Boolean, modifier: Modifier = Modifier) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(500, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "gp")
    Canvas(modifier.fillMaxWidth().height(5.dp)) {
        val h = size.height
        val r = CornerRadius(h / 2, h / 2)
        // Rail de verre
        drawRoundRect(Brush.verticalGradient(listOf(Color(0x1FFFFFFF), Color(0x0DFFFFFF))), size = size, cornerRadius = r)
        drawRoundRect(Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x14FFFFFF))), size = size, cornerRadius = r, style = Stroke(1f))
        val inset = 0.8.dp.toPx()
        val innerH = h - inset * 2
        val ir = CornerRadius(innerH / 2, innerH / 2)
        val maxW = size.width - inset * 2
        val w = (maxW * p)
        if (w <= 0.5f) return@Canvas
        val fw = w.coerceAtLeast(innerH)
        val fill = Brush.horizontalGradient(listOf(Color(0xFF2E9BFF), Color(0xFF2E9BFF)), startX = inset, endX = inset + fw)
        // Lueur néon
        drawRoundRect(Color(0x552E9BFF), Offset(inset - 0.5.dp.toPx(), inset - 2.dp.toPx()), Size(fw + 1.dp.toPx(), innerH + 4.dp.toPx()), CornerRadius((innerH + 4.dp.toPx()) / 2))
        // Remplissage
        drawRoundRect(fill, Offset(inset, inset), Size(fw, innerH), ir)
    }
}
