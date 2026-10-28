package com.tumemanque.app

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun fmtTotal(ms: Long): String {
    val m = ms / 60000
    return if (m >= 60) "${m / 60} h ${"%02d".format(m % 60)} min" else "$m min"
}

fun fmtSize(b: Long): String = if (b >= 1_048_576) "%.1f Mo".format(b / 1_048_576.0) else "${b / 1024} Ko"

/** Couleur moyenne d'une pochette, adoucie pour rester dans la charte sombre. */
fun avgColor(b: Bitmap): Color {
    val s = Bitmap.createScaledBitmap(b, 8, 8, true)
    var r = 0; var g = 0; var bl = 0
    for (x in 0 until 8) for (y in 0 until 8) {
        val p = s.getPixel(x, y)
        r += AColor.red(p); g += AColor.green(p); bl += AColor.blue(p)
    }
    val n = 64
    return Color(r / n / 255f, g / n / 255f, bl / n / 255f)
}

private val placeholders = listOf(
    listOf(Color(0xFF1B4FB8), Color(0xFF0E1E3F)),
    listOf(Color(0xFF3B4658), Color(0xFF151A22)),
    listOf(Color(0xFF2F7BFF), Color(0xFF10264F)),
    listOf(Color(0xFF4A5568), Color(0xFF1A1F27)),
    listOf(Color(0xFF2B6CB0), Color(0xFF12202F)),
)

/** Pochette du morceau (ou un dégradé bleu/gris avec une note de musique). */
@Composable
fun Cover(t: Track?, size: Dp, corner: Dp = 10.dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceIn(96, 1000)
    val bmp by produceState<Bitmap?>(initialValue = t?.let { Art.peek(it.id, px) }, t?.id, px, Artwork.version) {
        value = if (t == null) null else Art.peek(t.id, px) ?: Art.load(ctx, t, px)
    }
    Box(modifier.size(size).clip(RoundedCornerShape(corner)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), null, Modifier.size(size), contentScale = ContentScale.Crop)
        } else {
            val seed = abs((t?.album ?: "").hashCode()) % placeholders.size
            Box(Modifier.size(size).background(Brush.linearGradient(placeholders[seed])), contentAlignment = Alignment.Center) {
                CIcon(Ic.Note, Color(0x66FFFFFF), size * 0.42f)
            }
        }
    }
}

/** Petites barres animées : le morceau est en cours de lecture. */
@Composable
fun Bars(color: Color = P.blueSoft, animate: Boolean = true) {
    val tr = rememberInfiniteTransition(label = "bars")
    val a by tr.animateFloat(0.3f, 1f, infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by tr.animateFloat(1f, 0.35f, infiniteRepeatable(tween(430, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val c by tr.animateFloat(0.45f, 0.95f, infiniteRepeatable(tween(610, easing = LinearEasing), RepeatMode.Reverse), label = "c")
    Row(Modifier.size(18.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(a, b, c).forEach { h ->
            Box(Modifier.width(4.dp).fillMaxHeight(if (animate) h else 0.5f).clip(RoundedCornerShape(1.dp)).background(color))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    t: Track, current: Boolean, playing: Boolean, selected: Boolean, selecting: Boolean,
    fav: Boolean, onClick: () -> Unit, onLong: () -> Unit, menu: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) Color(0x262F7BFF) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Cover(t, 48.dp, 8.dp)
            if (selected) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xAA2F7BFF)), contentAlignment = Alignment.Center) {
                    CIcon(Ic.Check, Color.White, 24.dp)
                }
            } else if (current) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                    Bars(P.blueSoft, playing)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.title, color = if (current) P.blueSoft else P.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${t.artist} · ${fmt(t.durationMs)}", color = P.sub, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (fav) CIcon(Ic.Star, P.blueSoft, 16.dp)
        if (!selecting) {
            Box { menu() }
        }
    }
}

/** Le menu « ⋮ » d'un morceau. */
@Composable
fun TrackMenu(
    t: Track, fav: Boolean, onAddToPlaylist: () -> Unit, onInfo: () -> Unit, extra: List<Pair<String, () -> Unit>> = emptyList(),
) {
    var open by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    Box {
        Box(Modifier.size(40.dp).clip(CircleShape).combinedClickableSimple { open = true }, contentAlignment = Alignment.Center) {
            CIcon(Ic.More, P.sub, 20.dp)
        }
        DropdownMenu(open, { open = false }, containerColor = P.card2) {
            @Composable fun item(label: String, f: () -> Unit) = DropdownMenuItem(
                text = { Text(label, color = P.text) }, onClick = { open = false; f() },
            )
            item("Lire ensuite") { Hub.playNext(t) }
            item("Ajouter à la file d'attente") { Hub.enqueue(listOf(t)) }
            item("Ajouter à une playlist…") { onAddToPlaylist() }
            item("Envoyer hors de Free tune…") { Share.tracks(ctx, listOf(t.id)) }
            item("Paroles (karaoké)") {
                if (Hub.currentId != t.id) { Hub.playNext(t); Hub.next(); if (!Hub.playing) Hub.toggle() }
                Shell.lyrics = true; Shell.tab = "audios"; Shell.openNow++
            }
            item(if (fav) "Retirer des favoris" else "Ajouter aux favoris") { Lib.toggleFav(t.id) }
            extra.forEach { (l, f) -> item(l, f) }
            item("Informations") { onInfo() }
        }
    }
}

fun Modifier.combinedClickableSimple(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = P.sub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        (if (selected) Modifier.clip(RoundedCornerShape(50)).background(Brush.verticalGradient(listOf(P.blueSoft, P.blue)))
            .background(Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x00FFFFFF), Color(0x14FFFFFF))))
            .border(0.8.dp, Brush.verticalGradient(listOf(Color(0x80FFFFFF), Color(0x1AFFFFFF))), RoundedCornerShape(50))
        else Modifier.glass(RoundedCornerShape(50), 0.09f))
            .pressable(onClick).padding(horizontal = 16.dp, vertical = 9.dp),
    ) { Text(label, color = if (selected) Color.White else P.sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun BlueButton(label: String, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, outlined: Boolean = false, onClick: () -> Unit) {
    Row(
        (if (outlined) modifier.glass(RoundedCornerShape(50), 0.07f)
        else modifier.clip(RoundedCornerShape(50)).background(Brush.verticalGradient(listOf(P.blueSoft, P.blue)))
            .border(0.7.dp, Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x00FFFFFF))), RoundedCornerShape(50)))
            .pressable(onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { CIcon(icon, if (outlined) P.blueSoft else Color.White, 18.dp); Spacer(Modifier.width(8.dp)) }
        Text(label, color = if (outlined) P.text else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
