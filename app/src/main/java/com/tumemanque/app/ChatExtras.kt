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
        listOf(Color(0xFFFFD6E0), Color(0xFFFFB3C7)),
        listOf(Color(0xFFE0D4FF), Color(0xFFB9A6FF)),
        listOf(Color(0xFFD4F1FF), Color(0xFFA6DCFF)),
        listOf(Color(0xFFD8F5E4), Color(0xFFA9E5C4)),
        listOf(Color(0xFFFFE9C7), Color(0xFFFFC98A)),
        listOf(Color(0xFF2B2140), Color(0xFF4B2A5E)),
    )
    val defaultColors = listOf(Color(0xFFFFE3EC), Color(0xFFF3E5FF), Color(0xFFFFEFE0))
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
            Box(Modifier.fillMaxSize().background(Color(0x22FFFFFF)))
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
fun ChatHeader(partner: String, onBack: () -> Unit, onMenu: () -> Unit) {
    val glass = Color(0x99FFFFFF)
    val edge = Color(0xB3FFFFFF)
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Box(
            Modifier.align(Alignment.TopStart).size(44.dp).clip(CircleShape).background(glass).border(1.dp, edge, CircleShape).clickable { onBack() },
            contentAlignment = Alignment.Center,
        ) { Text("‹", color = RoseChat, fontSize = 30.sp) }
        Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(56.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFFFFB3CC), Color(0xFFFF6A9C))))
                    .border(2.dp, Color(0xDDFFFFFF), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("💗", fontSize = 28.sp) }
            Spacer(Modifier.height(4.dp))
            Column(
                Modifier.clip(RoundedCornerShape(20.dp)).background(glass).border(1.dp, edge, RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(partner, color = Color(0xFF3B1A28), fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                Text("Ton partenaire ›", color = Color(0x993B1A28), fontSize = 12.sp)
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).size(44.dp).clip(CircleShape).background(glass).border(1.dp, edge, CircleShape).clickable { onMenu() },
            contentAlignment = Alignment.Center,
        ) { Text("🎨", fontSize = 20.sp) }
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
                val p = withContext(Dispatchers.IO) { ImageUtil.prepare(context.applicationContext, uri, 1600) }
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
                                .border(2.dp, Color.White, CircleShape)
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

/** Sticker animé : chaque sticker a son mouvement (battement, balancement, rebond, flottement…). */
@Composable
fun AnimatedSticker(id: String, modifier: Modifier = Modifier) {
    val n = id.drop(1).take(2).toIntOrNull() ?: 0
    val style = n % 5
    val dur = when (style) { 0 -> 900; 1 -> 1400; 2 -> 1000; 3 -> 2600; else -> 1600 }
    val t = rememberInfiniteTransition(label = "stk")
    val phase = t.animateFloat(0f, 1f, infiniteRepeatable(tween(dur, easing = LinearEasing)), label = "p")
    AsyncImage(
        model = "file:///android_asset/stickers/$id.png",
        contentDescription = "Sticker",
        modifier = modifier.graphicsLayer {
            val p = (phase.value + n * 0.13f) % 1f
            val w = sin(2.0 * PI * p).toFloat()
            val d = density
            when (style) {
                0 -> { val s = 1f + 0.09f * (0.5f + 0.5f * w); scaleX = s; scaleY = s }
                1 -> rotationZ = 8f * w
                2 -> { val a = abs(w); translationY = -14f * d * a; scaleY = 1f - 0.05f * (1f - a) }
                3 -> { translationY = 6f * d * w; rotationZ = 3f * cos(2.0 * PI * p).toFloat() }
                else -> { val s = 1f + 0.06f * w; scaleX = s; scaleY = s; rotationZ = 5f * w }
            }
        },
    )
}
