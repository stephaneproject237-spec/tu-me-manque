package com.tumemanque.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val RoseChat = Color(0xFFE91E63)

/** Fond d'écran de la conversation : commun aux deux partenaires, enregistré sur le serveur. */
object Wallpaper {
    val presets: List<List<Color>> = listOf(
        listOf(Color(0xFF3A1424), Color(0xFF6B1E3F)),
        listOf(Color(0xFF241B45), Color(0xFF3F2A7A)),
        listOf(Color(0xFF10263A), Color(0xFF1D4A6B)),
        listOf(Color(0xFF10301F), Color(0xFF1F5A3A)),
        listOf(Color(0xFF3A2410), Color(0xFF6B4218)),
        listOf(Color(0xFF0E0E12), Color(0xFF25252E)),
    )
    val defaultColors = listOf(Color(0xFF1A0D16), Color(0xFF26122A), Color(0xFF120F1C))
}

@Composable
fun WallpaperLayer(value: String?) {
    val v = value ?: ""
    when {
        v.startsWith("photo:") -> Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = Backend.mediaUrl(v.removePrefix("photo:")), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Color(0x0F000000)))
        }
        v.startsWith("preset:") -> {
            val i = v.removePrefix("preset:").toIntOrNull() ?: 0
            val cols = Wallpaper.presets.getOrElse(i) { Wallpaper.defaultColors }
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(cols)))
        }
        else -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(Wallpaper.defaultColors)))
    }
}

@Composable
fun ChatHeader(partner: String, typing: Boolean, onBack: () -> Unit, onMenu: () -> Unit) {
    val glass = Color(0xA3281C24)
    val edge = Color(0x1AFFFFFF)
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Box(
            Modifier.align(Alignment.TopStart).size(44.dp).clip(CircleShape).background(glass).border(1.dp, edge, CircleShape).clickable { onBack() },
            contentAlignment = Alignment.Center,
        ) { Ico(R.drawable.ic_chevron, RoseChat, 30.dp) }
        Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(56.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFFFFB3CC), Color(0xFFFF6A9C))))
                    .border(2.dp, Color(0x2EFFFFFF), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("💗", fontSize = 28.sp) }
            Spacer(Modifier.height(4.dp))
            Column(
                Modifier.clip(RoundedCornerShape(20.dp)).background(glass).border(1.dp, edge, RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(partner, color = Color(0xFFF5EEF2), fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                Text(if (typing) "est en train d'écrire…" else "Ton partenaire ›", color = if (typing) RoseChat else Color(0xFFB7A3AE), fontSize = 12.sp)
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).size(44.dp).clip(CircleShape).background(glass).border(1.dp, edge, CircleShape).clickable { onMenu() },
            contentAlignment = Alignment.Center,
        ) { Ico(R.drawable.ic_settings, RoseChat, 22.dp) }
    }
}

/** Fenêtre « Fond d'écran » : couleurs, photo de la galerie (sélecteur système), retour au fond d'origine. */
@Composable
fun WallpaperMenu(show: Boolean, onDismiss: () -> Unit, onChanged: (String?) -> Unit, onToast: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun choose(value: String?) {
        onChanged(value)
        onDismiss()
        scope.launch {
            val ok = runCatching { Backend.setWallpaper(value) }.getOrDefault(false)
            if (!ok) onToast("Impossible de changer le fond pour le moment.")
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            try {
                val p = withContext(Dispatchers.IO) { ImageUtil.prepare(context.applicationContext, uri, 2400, 93) }
                if (p == null) { onToast("Photo illisible."); return@launch }
                val path = Backend.upload(p.bytes, "jpg", "image/jpeg")
                val value = "photo:$path"
                onChanged(value)
                onDismiss()
                if (!Backend.setWallpaper(value)) onToast("Impossible de changer le fond pour le moment.")
            } catch (e: Exception) {
                onToast("Impossible d'envoyer la photo.")
            }
        }
    }
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fond d'écran") },
        text = {
            Column {
                Text("Il change chez vous deux. Couleurs :", fontSize = 13.sp, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Wallpaper.presets.forEachIndexed { i, cols ->
                        Box(
                            Modifier.size(38.dp).clip(CircleShape)
                                .background(Brush.linearGradient(cols))
                                .border(2.dp, Color(0x66FFFFFF), CircleShape)
                                .clickable { choose("preset:$i") },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, modifier = Modifier.fillMaxWidth()) { Text("📷 Choisir une photo dans la galerie") }
                TextButton(onClick = { choose(null) }, modifier = Modifier.fillMaxWidth()) { Text("↺ Fond d'origine") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

/** Les stickers « a01_… » sont de vrais stickers animés (WebP) ; les « s01_… » sont des PNG animés par le code. */
fun isAnimatedSticker(id: String): Boolean = id.length > 1 && id[0] == 'a' && id[1].isDigit()

/** Sticker animé : chaque sticker a son mouvement (battement, balancement, rebond, flottement…). */
@Composable
fun AnimatedSticker(id: String, modifier: Modifier = Modifier) {
    val real = isAnimatedSticker(id)
    val n = id.drop(1).take(2).toIntOrNull() ?: 0
    val style = if (real) -1 else n % 5
    val dur = when (style) { 0 -> 900; 1 -> 1400; 2 -> 1000; 3 -> 2600; else -> 1600 }
    val t = rememberInfiniteTransition(label = "stk")
    val phase = t.animateFloat(0f, 1f, infiniteRepeatable(tween(dur, easing = LinearEasing)), label = "p")
    AsyncImage(
        model = "file:///android_asset/stickers/$id.${if (isAnimatedSticker(id)) "webp" else "png"}",
        contentDescription = "Sticker",
        modifier = modifier.graphicsLayer {
            val p = (phase.value + n * 0.13f) % 1f
            val w = sin(2.0 * PI * p).toFloat()
            val d = density
            when (style) {
                -1 -> {}
                0 -> { val s = 1f + 0.09f * (0.5f + 0.5f * w); scaleX = s; scaleY = s }
                1 -> rotationZ = 8f * w
                2 -> { val a = abs(w); translationY = -14f * d * a; scaleY = 1f - 0.05f * (1f - a) }
                3 -> { translationY = 6f * d * w; rotationZ = 3f * cos(2.0 * PI * p).toFloat() }
                else -> { val s = 1f + 0.06f * w; scaleX = s; scaleY = s; rotationZ = 5f * w }
            }
        },
    )
}
