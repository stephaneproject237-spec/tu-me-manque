package com.tumemanque.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/** Une platine vinyle : disque qui tourne, reflets fixes, bras de lecture qui se pose sur le sillon. */
@Composable
fun Turntable(t: Track, playing: Boolean, modifier: Modifier = Modifier) {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (playing) {
            while (true) angle.animateTo(angle.value + 360f, tween(3000, easing = LinearEasing))
        } else {
            angle.animateTo(angle.value + 40f, tween(1100, easing = FastOutSlowInEasing))
        }
    }
    val arm by animateFloatAsState(if (playing) 0f else -32f, tween(900, easing = FastOutSlowInEasing), label = "arm")

    androidx.compose.foundation.layout.BoxWithConstraints(modifier.aspectRatio(1f)) {
        val s = maxWidth
        // plateau
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2
            drawCircle(Brush.sweepGradient(listOf(Color(0xFF2B3038), Color(0xFF59616E), Color(0xFF2B3038), Color(0xFF4A515C), Color(0xFF2B3038)), c), r * 0.99f, c)
            drawCircle(Color(0xFF0D0F12), r * 0.955f, c)
        }
        // disque (tourne)
        Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = angle.value }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val c = center
                val r = size.minDimension / 2
                drawCircle(Color(0xFF08090B), r * 0.93f, c)
                var rr = r * 0.30f
                var i = 0
                while (rr < r * 0.92f) {
                    drawCircle(
                        if (i % 5 == 0) Color(0xFF1C1F25) else Color(0xFF111317), rr, c,
                        style = Stroke(width = if (i % 5 == 0) 1.6f else 1f),
                    )
                    rr += r * 0.0105f
                    i++
                }
                // bandes lisses entre les morceaux
                drawCircle(Color(0xFF0B0C0F), r * 0.56f, c, style = Stroke(width = r * 0.012f))
            }
            Cover(t, s * 0.34f, 999.dp)
            Box(Modifier.size(s * 0.34f).clip(CircleShape).background(Color(0x22000000)))
            Box(Modifier.size(s * 0.018f).clip(CircleShape).background(Color(0xFFBFC6D1)))
        }
        // reflets de la lumière (ne tournent pas)
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2
            val hi = Color(0x2EFFFFFF)
            val clear = Color(0x00FFFFFF)
            drawCircle(
                Brush.sweepGradient(
                    0f to clear, 0.05f to hi, 0.10f to clear, 0.45f to clear, 0.55f to hi, 0.60f to clear, 1f to clear,
                    center = c,
                ),
                r * 0.93f, c,
            )
            drawCircle(Color(0x14FFFFFF), r * 0.93f, c, style = Stroke(2f))
        }
        // bras de lecture
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val pivot = Offset(w * 0.93f, w * 0.10f)
            drawCircle(Color(0xFF3A414C), w * 0.055f, pivot)
            drawCircle(Color(0xFF8D96A3), w * 0.03f, pivot)
            rotate(arm, pivot) {
                val ang = Math.toRadians(115.0)
                val len = w * 0.68f
                val tip = Offset(pivot.x + (len * cos(ang)).toFloat(), pivot.y + (len * sin(ang)).toFloat())
                drawLine(Color(0xFFB9C1CC), pivot, tip, strokeWidth = w * 0.014f, cap = StrokeCap.Round)
                // tête de lecture
                val ah = Math.toRadians(115.0 + 12.0)
                val head = Offset(tip.x + (w * 0.07f * cos(ah)).toFloat(), tip.y + (w * 0.07f * sin(ah)).toFloat())
                drawLine(Color(0xFF2F7BFF), tip, head, strokeWidth = w * 0.034f, cap = StrokeCap.Round)
                // contrepoids
                val back = Offset(pivot.x - (w * 0.10f * cos(ang)).toFloat(), pivot.y - (w * 0.10f * sin(ang)).toFloat())
                drawLine(Color(0xFF59616E), pivot, back, strokeWidth = w * 0.034f, cap = StrokeCap.Round)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlaying(t: Track, favs: Set<String>, onClose: () -> Unit, onAddToPlaylist: (Track) -> Unit, onInfo: (Track) -> Unit) {
    val ctx = LocalContext.current
    var pos by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var sheet by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(Hub.currentId) {
        while (true) { if (!dragging) pos = Hub.player.currentPosition; delay(250) }
    }
    val dur = (Hub.durationMs.takeIf { it > 0 } ?: t.durationMs).coerceAtLeast(1L)
    val tint by produceState(P.card2, t.id) {
        value = Art.load(ctx, t, 96)?.let { avgColor(it) } ?: P.card2
    }
    val bgTop by animateColorAsState(tint.copy(alpha = 0.55f), tween(600), label = "bg")
    BackHandler(enabled = sheet.isEmpty()) { onClose() }

    Box(Modifier.fillMaxSize().background(P.bg).background(Brush.verticalGradient(listOf(bgTop, P.bg, P.bg)))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundBtn(Ic.ChevronDown, onClose, fill = Color(0x26FFFFFF))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("LECTURE EN COURS", color = P.sub, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(t.album, color = P.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    RoundBtn(Ic.More, { menu = true }, fill = Color(0x26FFFFFF))
                    DropdownMenu(menu, { menu = false }, containerColor = P.card2) {
                        DropdownMenuItem(text = { Text("Ajouter à une playlist…", color = P.text) }, onClick = { menu = false; onAddToPlaylist(t) })
                        DropdownMenuItem(text = { Text("Informations", color = P.text) }, onClick = { menu = false; onInfo(t) })
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Turntable(t, Hub.playing, Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = P.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, color = P.sub, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val fav = t.id in favs
                RoundBtn(if (fav) Ic.Star else Ic.StarOutline, { Lib.toggleFav(t.id) }, fill = Color.Transparent, tint = if (fav) P.blueSoft else P.sub)
            }

            Spacer(Modifier.height(10.dp))
            Slider(
                value = if (dragging) dragValue else (pos.toFloat() / dur).coerceIn(0f, 1f),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = { Hub.seekTo((dragValue * dur).toLong()); pos = (dragValue * dur).toLong(); dragging = false },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = P.blue, inactiveTrackColor = Color(0x33FFFFFF)),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text(fmt(if (dragging) (dragValue * dur).toLong() else pos), color = P.sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("-" + fmt(dur - (if (dragging) (dragValue * dur).toLong() else pos)), color = P.sub, fontSize = 12.sp)
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                RoundBtn(Ic.Shuffle, { Hub.toggleShuffle() }, fill = Color.Transparent, tint = if (Hub.shuffle) P.blueSoft else P.sub)
                RoundBtn(Ic.Prev, { Hub.previous() }, size = 56.dp, iconSize = 32.dp, fill = Color.Transparent)
                RoundBtn(if (Hub.playing) Ic.Pause else Ic.Play, { Hub.toggle() }, size = 74.dp, iconSize = 38.dp, fill = P.blue, tint = Color.White)
                RoundBtn(Ic.Next, { Hub.next() }, size = 56.dp, iconSize = 32.dp, fill = Color.Transparent)
                RoundBtn(
                    if (Hub.repeat == Player.REPEAT_MODE_ONE) Ic.RepeatOne else Ic.Repeat, { Hub.cycleRepeat() },
                    fill = Color.Transparent, tint = if (Hub.repeat != Player.REPEAT_MODE_OFF) P.blueSoft else P.sub,
                )
            }

            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(if (Hub.speed != 1f) P.blue else Color(0x26FFFFFF)).clickable { sheet = "speed" }.padding(horizontal = 14.dp, vertical = 9.dp),
                ) { Text("${"%.2f".format(Hub.speed).trimEnd('0').trimEnd('.').replace('.', ',')}×", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                RoundBtn(Ic.Moon, { sheet = "sleep" }, fill = if (Hub.sleepEndsAt > 0 || Hub.sleepEndOfTrack) P.blue else Color(0x26FFFFFF), tint = Color.White, size = 42.dp, iconSize = 20.dp)
                RoundBtn(Ic.Equalizer, { sheet = "eq" }, fill = if (Hub.eqEnabled) P.blue else Color(0x26FFFFFF), tint = Color.White, size = 42.dp, iconSize = 20.dp)
                RoundBtn(Ic.Queue, { sheet = "queue" }, fill = Color(0x26FFFFFF), size = 42.dp, iconSize = 20.dp)
            }
        }
    }

    if (sheet.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = { sheet = "" }, containerColor = P.panel) {
            when (sheet) {
                "queue" -> QueueSheet()
                "sleep" -> SleepSheet { sheet = "" }
                "speed" -> SpeedSheet { sheet = "" }
                "eq" -> EqSheet()
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, color = P.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}

@Composable
private fun QueueSheet() {
    val byId by Lib.byId.collectAsState()
    val state = rememberLazyListState()
    LaunchedEffect(Unit) { if (Hub.index > 2) state.scrollToItem(Hub.index - 2) }
    SheetTitle("File d'attente · ${Hub.queue.size}")
    LazyColumn(Modifier.fillMaxWidth().height(440.dp), state = state) {
        itemsIndexed(Hub.queue, key = { i, id -> "$i$id" }) { i, id ->
            val t = byId[id]
            if (t != null) {
                val cur = i == Hub.index
                Row(Modifier.fillMaxWidth().clickable { Hub.jump(i) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp)) {
                        Cover(t, 44.dp, 8.dp)
                        if (cur) Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)), contentAlignment = Alignment.Center) { Bars(P.blueSoft, Hub.playing) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = if (cur) P.blueSoft else P.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.artist, color = P.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    RoundBtn(Ic.ArrowUp, { Hub.move(i, i - 1) }, size = 34.dp, iconSize = 16.dp, fill = Color.Transparent, tint = P.sub)
                    RoundBtn(Ic.ArrowDown, { Hub.move(i, i + 1) }, size = 34.dp, iconSize = 16.dp, fill = Color.Transparent, tint = P.sub)
                    RoundBtn(Ic.Close, { Hub.removeAt(i) }, size = 34.dp, iconSize = 16.dp, fill = Color.Transparent, tint = P.sub)
                }
            }
        }
    }
}

@Composable
private fun SleepSheet(onDone: () -> Unit) {
    SheetTitle("Minuteur de sommeil")
    if (Hub.sleepEndsAt > 0 || Hub.sleepEndOfTrack) {
        val left = if (Hub.sleepEndOfTrack) "à la fin du morceau" else "dans ${((Hub.sleepEndsAt - System.currentTimeMillis()) / 60000 + 1).coerceAtLeast(1)} min"
        Text("La musique s'arrêtera $left.", color = P.blueSoft, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    }
    Column(Modifier.padding(bottom = 28.dp)) {
        listOf(5, 10, 15, 30, 45, 60, 90).forEach { m ->
            Text("$m minutes", color = P.text, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { Hub.sleepIn(m); onDone() }.padding(horizontal = 20.dp, vertical = 13.dp))
        }
        Text("À la fin du morceau", color = P.text, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { Hub.sleepAtEndOfTrack(); onDone() }.padding(horizontal = 20.dp, vertical = 13.dp))
        if (Hub.sleepEndsAt > 0 || Hub.sleepEndOfTrack) {
            Text("Désactiver le minuteur", color = P.danger, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { Hub.sleepIn(0); onDone() }.padding(horizontal = 20.dp, vertical = 13.dp))
        }
    }
}

@Composable
private fun SpeedSheet(onDone: () -> Unit) {
    SheetTitle("Vitesse de lecture")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { v ->
            Box(Modifier.weight(1f)) {
                Pill("${v.toString().trimEnd('0').trimEnd('.').replace('.', ',')}×", Hub.speed == v) { Hub.setSpeed(v); onDone() }
            }
        }
    }
}

@Composable
private fun EqSheet() {
    SheetTitle("Égaliseur")
    if (Hub.eqBands == 0) {
        Text("L'égaliseur n'est pas disponible sur cet appareil.", color = P.sub, modifier = Modifier.padding(20.dp))
        return
    }
    val (lo, hi) = Hub.eqRange()
    var tick by remember { mutableStateOf(0) }
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Activer l'égaliseur", color = P.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Switch(Hub.eqEnabled, { Hub.setEqEnabled(it) }, colors = SwitchDefaults.colors(checkedTrackColor = P.blue))
        }
        val presets = Hub.eqPresets()
        if (presets.isNotEmpty()) {
            Row(Modifier.padding(top = 8.dp).fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEachIndexed { i, n -> Pill(n, false) { Hub.usePreset(i); tick++ } }
            }
        }
        key(tick) {
            for (b in 0 until Hub.eqBands) {
                val f = Hub.eqFreq(b)
                var v by remember { mutableFloatStateOf(Hub.eqLevel(b).toFloat()) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (f >= 1000) "${f / 1000} kHz" else "$f Hz", color = P.sub, fontSize = 12.sp, modifier = Modifier.width(56.dp))
                    Slider(
                        v, { v = it; Hub.setBand(b, it.toInt()); if (!Hub.eqEnabled) Hub.setEqEnabled(true) },
                        valueRange = lo.toFloat()..hi.toFloat(), modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = P.blue, inactiveTrackColor = Color(0x33FFFFFF)),
                    )
                }
            }
        }
        Text("Renfort des basses", color = P.text, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
        var bass by remember { mutableFloatStateOf(Hub.bassLevel.toFloat()) }
        Slider(
            bass, { bass = it; Hub.setBass(it.toInt()) }, valueRange = 0f..1000f,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = P.blue, inactiveTrackColor = Color(0x33FFFFFF)),
        )
    }
}

