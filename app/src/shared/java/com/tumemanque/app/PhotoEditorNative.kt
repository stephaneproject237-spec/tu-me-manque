package com.tumemanque.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as ACanvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ============================ Filtres ============================

private class Flt(val name: String, val br: Float = 1f, val ct: Float = 1f, val sat: Float = 1f, val sepia: Float = 0f,
                  val add: FloatArray = floatArrayOf(0f, 0f, 0f), val fade: Float = 0f, val vig: Float = 0f)

private val FILTERS = listOf(
    Flt("Original"),
    Flt("Éclat", br = 1.06f, ct = 1.08f, sat = 1.28f),
    Flt("Chaud", br = 1.03f, sat = 1.12f, add = floatArrayOf(16f, 5f, -14f)),
    Flt("Froid", sat = .95f, add = floatArrayOf(-12f, 1f, 18f)),
    Flt("Rosé", br = 1.04f, sat = 1.15f, add = floatArrayOf(20f, -4f, 8f)),
    Flt("Doré", br = 1.05f, sat = 1.2f, sepia = .25f, add = floatArrayOf(22f, 12f, -10f)),
    Flt("Vintage", ct = .92f, sepia = .45f, fade = .08f, add = floatArrayOf(10f, 4f, -8f), vig = .28f),
    Flt("Fondu", br = 1.08f, ct = .85f, sat = .85f, fade = .12f),
    Flt("Drama", br = .96f, ct = 1.3f, sat = 1.15f, vig = .32f),
    Flt("Noir & blanc", sat = 0f, ct = 1.15f),
    Flt("Nuit", br = .86f, sat = 1.05f, add = floatArrayOf(-10f, -4f, 16f), vig = .2f),
)

private fun Flt.cm(): ColorMatrix {
    val m = ColorMatrix()
    m.setScale(br, br, br, 1f)
    if (ct != 1f) m.postConcat(ColorMatrix(floatArrayOf(ct, 0f, 0f, 0f, 128f * (1 - ct), 0f, ct, 0f, 0f, 128f * (1 - ct), 0f, 0f, ct, 0f, 128f * (1 - ct), 0f, 0f, 0f, 1f, 0f)))
    if (sat != 1f) m.postConcat(ColorMatrix().apply { setSaturation(sat) })
    if (sepia > 0f) {
        val s = sepia
        m.postConcat(ColorMatrix(floatArrayOf(
            1 - s + s * .393f, s * .769f, s * .189f, 0f, 0f,
            s * .349f, 1 - s + s * .686f, s * .168f, 0f, 0f,
            s * .272f, s * .534f, 1 - s + s * .131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f)))
    }
    if (fade > 0f) m.postConcat(ColorMatrix(floatArrayOf(1 - fade, 0f, 0f, 0f, 140f * fade, 0f, 1 - fade, 0f, 0f, 140f * fade, 0f, 0f, 1 - fade, 0f, 140f * fade, 0f, 0f, 0f, 1f, 0f)))
    if (add.any { it != 0f }) m.postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, add[0], 0f, 1f, 0f, 0f, add[1], 0f, 0f, 1f, 0f, add[2], 0f, 0f, 0f, 1f, 0f)))
    return m
}

// ============================ Éléments ============================

private sealed class Item { var x = .5f; var y = .45f; var rot = 0f; var scl = 1f }
private class TxtItem(var text: String, var color: Int, var style: Int, var font: Int) : Item()
private class EmojiItem(val ch: String) : Item()
private class BadgeItem(val text: String, val bg: Int, val fg: Int) : Item()
private class TapeItem(val c1: Int, val c2: Int, val kind: Int) : Item()
private class StrokeItem(val pts: MutableList<Offset>, val color: Int, val w: Float) : Item()

private val TEXT_COLORS = listOf(0xFFFFFFFF, 0xFF111111, 0xFFFF3D7F, 0xFFFFEB3B, 0xFFFF5252, 0xFF69F0AE, 0xFF40C4FF, 0xFFB388FF).map { it.toInt() }
private val FONTS = listOf("Classique", "Arrondi", "Serif", "Mono", "Manuscrit")
private fun typeface(i: Int): Typeface = when (i) {
    1 -> Typeface.create("sans-serif-medium", Typeface.BOLD)
    2 -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
    3 -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    4 -> Typeface.create("cursive", Typeface.BOLD)
    else -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
}
private fun fontFamily(i: Int): FontFamily = when (i) { 2 -> FontFamily.Serif; 3 -> FontFamily.Monospace; 4 -> FontFamily.Cursive; else -> FontFamily.SansSerif }

private val BADGES = listOf(
    Triple("TU ME MANQUES", 0xFFE91E63.toInt(), 0xFFFFFFFF.toInt()), Triple("JE T'AIME", 0xFFFF5252.toInt(), 0xFFFFFFFF.toInt()),
    Triple("BISOUS 💋", 0xFFFFB300.toInt(), 0xFF2B1B00.toInt()), Triple("MISS YOU", 0xFF7C4DFF.toInt(), 0xFFFFFFFF.toInt()),
    Triple("MON AMOUR", 0xFF00BFA5.toInt(), 0xFFFFFFFF.toInt()), Triple("HOT 🔥", 0xFFFF6D00.toInt(), 0xFFFFFFFF.toInt()),
    Triple("NOW 📸", 0xFF212121.toInt(), 0xFFFFEB3B.toInt()), Triple("CÂLIN 🤗", 0xFF29B6F6.toInt(), 0xFFFFFFFF.toInt()),
)
private val TAPES = listOf(
    Triple(0xFFF4A6C0.toInt(), 0xFFFFFFFF.toInt(), 0), Triple(0xFFFFD54F.toInt(), 0xFFFFFFFF.toInt(), 1), Triple(0xFF80DEEA.toInt(), 0xFFFFFFFF.toInt(), 2),
    Triple(0xFFE91E63.toInt(), 0xFFFFC1D8.toInt(), 2), Triple(0xFFB39DDB.toInt(), 0xFFFFFFFF.toInt(), 1),
)

private fun textLayout(it: TxtItem, W: Float): StaticLayout {
    val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = .075f * W; typeface = typeface(it.font); color = it.color }
    @Suppress("DEPRECATION")
    return StaticLayout(it.text, p, (.84f * W).toInt().coerceAtLeast(10), Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
}

private fun bounds(it: Item, W: Float): Pair<Float, Float> = when (it) {
    is TxtItem -> textLayout(it, W).let { l -> var mw = 0f; for (i in 0 until l.lineCount) mw = max(mw, l.getLineWidth(i)); (mw + .05f * W) to (l.height.toFloat() + .03f * W) }
    is EmojiItem -> (.14f * W) to (.14f * W)
    is BadgeItem -> { val p = Paint().apply { textSize = .05f * W; typeface = Typeface.DEFAULT_BOLD }; (p.measureText(it.text) + .07f * W) to (.095f * W) }
    is TapeItem -> (.42f * W) to (.075f * W)
    is StrokeItem -> 0f to 0f
}

private fun drawItem(c: ACanvas, it: Item, W: Float, H: Float) {
    if (it is StrokeItem) {
        if (it.pts.isEmpty()) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = it.color; strokeWidth = it.w * W; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE }
        if (it.pts.size == 1) { p.style = Paint.Style.FILL; c.drawCircle(it.pts[0].x * W, it.pts[0].y * H, it.w * W / 2, p); return }
        val path = Path(); path.moveTo(it.pts[0].x * W, it.pts[0].y * H)
        for (i in 1 until it.pts.size - 1) path.quadTo(it.pts[i].x * W, it.pts[i].y * H, (it.pts[i].x + it.pts[i + 1].x) / 2 * W, (it.pts[i].y + it.pts[i + 1].y) / 2 * H)
        path.lineTo(it.pts.last().x * W, it.pts.last().y * H)
        c.drawPath(path, p)
        return
    }
    c.save()
    c.translate(it.x * W, it.y * H); c.rotate(Math.toDegrees(it.rot.toDouble()).toFloat()); c.scale(it.scl, it.scl)
    when (it) {
        is TxtItem -> {
            val l = textLayout(it, W)
            var mw = 0f; for (i in 0 until l.lineCount) mw = max(mw, l.getLineWidth(i))
            c.translate(-l.width / 2f, -l.height / 2f)
            if (it.style == 1) {
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (it.color == 0xFF111111.toInt()) 0xE6FFFFFF.toInt() else 0x99000000.toInt() }
                val r = RectF((l.width - mw) / 2 - .025f * W, -.015f * W, (l.width + mw) / 2 + .025f * W, l.height + .015f * W)
                c.drawRoundRect(r, .035f * W, .035f * W, bg)
            }
            if (it.style == 2) {
                l.paint.style = Paint.Style.STROKE; l.paint.strokeWidth = .016f * W; l.paint.strokeJoin = Paint.Join.ROUND
                l.paint.color = if (it.color == 0xFF111111.toInt()) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
                l.draw(c)
                l.paint.style = Paint.Style.FILL; l.paint.color = it.color
            }
            l.draw(c)
        }
        is EmojiItem -> {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = .12f * W; textAlign = Paint.Align.CENTER }
            c.drawText(it.ch, 0f, -(p.ascent() + p.descent()) / 2f, p)
        }
        is BadgeItem -> {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = .05f * W; typeface = Typeface.DEFAULT_BOLD; color = it.fg; textAlign = Paint.Align.CENTER }
            val w = tp.measureText(it.text) + .07f * W; val h = .095f * W
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = it.bg }
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = .008f * W }
            c.drawRoundRect(RectF(-w / 2, -h / 2, w / 2, h / 2), h / 2, h / 2, bg)
            c.drawRoundRect(RectF(-w / 2, -h / 2, w / 2, h / 2), h / 2, h / 2, ring)
            c.drawText(it.text, 0f, -(tp.ascent() + tp.descent()) / 2f, tp)
        }
        is TapeItem -> {
            val w = .42f * W; val h = .075f * W
            val rect = RectF(-w / 2, -h / 2, w / 2, h / 2)
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = it.c1; alpha = 225 }
            c.save(); c.clipRect(rect)
            c.drawRect(rect, bg)
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = it.c2; alpha = 170; strokeWidth = h * .16f }
            when (it.kind) {
                1 -> { var x = -w / 2 + h * .35f; while (x < w / 2) { var y = -h / 2 + h * .3f; while (y < h / 2) { c.drawCircle(x, y, h * .08f, sp); y += h * .4f }; x += h * .42f } }
                2 -> { var x = -w / 2 - h; while (x < w / 2) { c.drawLine(x, h / 2, x + h, -h / 2, sp); x += h * .55f } }
                else -> { c.drawLine(-w / 2, -h * .22f, w / 2, -h * .22f, sp); c.drawLine(-w / 2, h * .22f, w / 2, h * .22f, sp) }
            }
            c.restore()
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000; style = Paint.Style.STROKE; strokeWidth = 1.5f }
            c.drawRect(rect, edge)
        }
        is StrokeItem -> {}
    }
    c.restore()
}

private fun drawScene(c: ACanvas, W: Float, H: Float, bmp: Bitmap, f: Flt, items: List<Item>, sel: Item?) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    p.colorFilter = ColorMatrixColorFilter(f.cm())
    c.drawBitmap(bmp, null, RectF(0f, 0f, W, H), p)
    if (f.vig > 0f) {
        val r = hypot(W, H) * .55f
        val vp = Paint().apply { shader = RadialGradient(W / 2, H / 2, r, intArrayOf(0x00000000, 0x00000000, ((f.vig * 255).toInt() shl 24)), floatArrayOf(0f, min(W, H) * .3f / r, 1f), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, W, H, vp)
    }
    items.forEach { drawItem(c, it, W, H) }
    if (sel != null && sel !is StrokeItem) {
        val (bw, bh) = bounds(sel, W)
        c.save(); c.translate(sel.x * W, sel.y * H); c.rotate(Math.toDegrees(sel.rot.toDouble()).toFloat()); c.scale(sel.scl, sel.scl)
        val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = max(2f, W / 400f); pathEffect = android.graphics.DashPathEffect(floatArrayOf(W / 60f, W / 90f), 0f) }
        c.drawRoundRect(RectF(-bw / 2 - 8, -bh / 2 - 8, bw / 2 + 8, bh / 2 + 8), 18f, 18f, fp)
        c.restore()
    }
}

// ============================ Chargement / export ============================

private fun decode(file: File, maxSide: Int): Bitmap? {
    val path = file.absolutePath
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    if (o.outWidth <= 0) return null
    var s = 1
    while (max(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
    var b = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
    val ori = try { ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
    val m = Matrix()
    when (ori) {
        ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
    }
    val big = max(b.width, b.height)
    val k = if (big > maxSide) maxSide.toFloat() / big else 1f
    if (k < 1f) m.postScale(k, k)
    if (!m.isIdentity) {
        val nb = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (nb !== b) b.recycle()
        b = nb
    }
    return b
}

private fun exportJpeg(file: File, f: Flt, items: List<Item>): PreparedPhoto? {
    for (side in intArrayOf(4096, 3072, 2048, 1400)) {
        try {
            val b = decode(file, side) ?: continue
            val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
            drawScene(ACanvas(out), b.width.toFloat(), b.height.toFloat(), b, f, items, null)
            b.recycle()
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, 93, bos)
            val w = out.width; val h = out.height
            out.recycle()
            return PreparedPhoto(bos.toByteArray(), w, h)
        } catch (e: OutOfMemoryError) { /* on réessaie plus petit */ }
    }
    return null
}

// ============================ Écran ============================

private enum class Tab { FILTRES, TEXTE, DESSIN, STICKERS, TAPE }

private fun hitTest(items: List<Item>, px: Float, py: Float, W: Float, H: Float): Item? {
    for (i in items.indices.reversed()) {
        val it = items[i]
        if (it is StrokeItem) continue
        val (bw, bh) = bounds(it, W)
        val dx = px - it.x * W; val dy = py - it.y * H
        val a = -it.rot
        val lx = (dx * cos(a) - dy * sin(a)) / it.scl; val ly = (dx * sin(a) + dy * cos(a)) / it.scl
        val pad = W * .03f
        if (abs(lx) <= bw / 2 + pad && abs(ly) <= bh / 2 + pad) return it
    }
    return null
}

@Composable
fun PhotoEditorNative(file: File, title: String, allowSched: Boolean, onResult: (PreparedPhoto, Boolean, Long) -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    val bmp by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { decode(file, 1500) }.getOrNull() }
    }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(bmp) { if (bmp == null) { kotlinx.coroutines.delay(8000); if (bmp == null) failed = true } }

    val layers = remember { mutableListOf<Item>() }
    var ver by remember { mutableIntStateOf(0) }
    var sel by remember { mutableStateOf<Item?>(null) }
    var fi by remember { mutableIntStateOf(0) }
    var tab by remember { mutableStateOf(Tab.FILTRES) }
    var stk by remember { mutableIntStateOf(0) }
    var dColor by remember { mutableIntStateOf(TEXT_COLORS[0]) }
    var dSize by remember { mutableIntStateOf(1) }
    var composing by remember { mutableStateOf<TxtItem?>(null) }
    var composeNew by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var askSched by remember { mutableStateOf(false) }
    var lastTapT by remember { mutableStateOf(0L) }
    var lastTapItem by remember { mutableStateOf<Item?>(null) }
    var txtDefault by remember { mutableStateOf(Triple(TEXT_COLORS[0], 0, 0)) }
    val thumb = remember(bmp) { bmp?.let { b -> val k = 120f / max(b.width, b.height); Bitmap.createScaledBitmap(b, max(1, (b.width * k).toInt()), max(1, (b.height * k).toInt()), true) } }
    val sizes = floatArrayOf(.007f, .014f, .026f)

    fun touch() { ver++ }
    fun add(i: Item) { layers.add(i); sel = i; touch() }

    fun finish(once: Boolean, at: Long) {
        if (busy) return
        busy = true
        val snap = layers.toList(); val flt = FILTERS[fi]
        scope.launch {
            val ph = withContext(Dispatchers.Default) { exportJpeg(file, flt, snap) }
            if (ph == null) { busy = false; failed = true } else onResult(ph, once, at)
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0709))) {
        val b = bmp
        if (b == null) {
            Column(Modifier.fillMaxSize().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (!failed) { CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(32.dp)); Spacer(Modifier.height(14.dp)); Text("Chargement de la photo…", color = Color(0xCCFFFFFF), fontSize = 15.sp) }
                else { Text("Impossible d'ouvrir cette photo.", color = Color.White, fontSize = 16.sp); Spacer(Modifier.height(16.dp)); GlassPill(onClick = onCancel) { Text("Fermer", color = Color.White, fontWeight = FontWeight.SemiBold) } }
            }
            return@Box
        }
        val ratio = b.width.toFloat() / b.height
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 64.dp, bottom = 262.dp, start = 10.dp, end = 10.dp), Alignment.Center) {
            BoxWithConstraints(Modifier.fillMaxSize(), Alignment.Center) {
                val dens = LocalDensity.current
                val mw = with(dens) { maxWidth.toPx() }; val mh = with(dens) { maxHeight.toPx() }
                val fw = if (mw / mh < ratio) mw else mh * ratio
                val fh = fw / ratio
                Canvas(
                    Modifier.size(with(dens) { fw.toDp() }, with(dens) { fh.toDp() }).clip(RoundedCornerShape(26.dp))
                        .pointerInput(tab, fw, fh) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val W = fw; val H = fh
                                if (tab == Tab.DESSIN) {
                                    val s = StrokeItem(mutableListOf(Offset(down.position.x / W, down.position.y / H)), dColor, sizes[dSize])
                                    layers.add(s); sel = null; touch()
                                    do {
                                        val ev = awaitPointerEvent()
                                        ev.changes.forEach { ch -> if (ch.positionChanged()) { val q = Offset(ch.position.x / W, ch.position.y / H); val l = s.pts.last(); if (hypot(q.x - l.x, q.y - l.y) > .002f) { s.pts.add(q); touch() }; ch.consume() } }
                                    } while (ev.changes.any { it.pressed })
                                } else {
                                    val hit = hitTest(layers, down.position.x, down.position.y, W, H)
                                    if (hit !== sel) { sel = hit; touch() }
                                    var moved = false
                                    if (hit != null) { layers.remove(hit); layers.add(hit) }
                                    do {
                                        val ev = awaitPointerEvent()
                                        val t = sel
                                        if (t != null && t !is StrokeItem) {
                                            val z = ev.calculateZoom(); val r = ev.calculateRotation(); val pan = ev.calculatePan()
                                            if (pan != Offset.Zero || z != 1f || r != 0f) {
                                                if (hypot(pan.x, pan.y) > 1.5f || z != 1f || r != 0f) moved = true
                                                t.x = (t.x + pan.x / W).coerceIn(0f, 1f); t.y = (t.y + pan.y / H).coerceIn(0f, 1f)
                                                t.scl = (t.scl * z).coerceIn(.15f, 6f); t.rot += Math.toRadians(r.toDouble()).toFloat()
                                                touch()
                                            }
                                        }
                                        ev.changes.forEach { ch: PointerInputChange -> if (ch.positionChanged()) ch.consume() }
                                    } while (ev.changes.any { it.pressed })
                                    val now = System.currentTimeMillis()
                                    if (!moved && hit is TxtItem && hit === lastTapItem && now - lastTapT < 350) { composing = hit; composeNew = false; lastTapT = 0 }
                                    else { lastTapT = now; lastTapItem = hit }
                                }
                            }
                        },
                ) {
                    @Suppress("UNUSED_EXPRESSION") ver
                    drawIntoCanvas { cv -> drawScene(cv.nativeCanvas, size.width, size.height, b, FILTERS[fi], layers, sel) }
                }
            }
        }

        // Haut : fermer, annuler, supprimer
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassCircle(44.dp, onClick = onCancel) { Ico(R.drawable.ic_xmark, Color.White, 20.dp) }
            Spacer(Modifier.weight(1f))
            @Suppress("UNUSED_EXPRESSION") ver
            val s = sel
            if (s != null && s !is StrokeItem) GlassCircle(44.dp, onClick = { layers.remove(s); sel = null; touch() }) { Ico(R.drawable.ic_trash, Color(0xFFFF6B6B), 20.dp) }
            else if (layers.isNotEmpty()) GlassCircle(44.dp, onClick = { layers.removeAt(layers.lastIndex); sel = null; touch() }) { Ico(R.drawable.ic_undo, Color.White, 20.dp) }
        }

        // Bas : panneau, onglets, envoi
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val panelH = when (tab) { Tab.STICKERS -> 132.dp; Tab.DESSIN -> 112.dp; else -> 96.dp }
            Box(Modifier.fillMaxWidth().height(panelH).glassSurface(RoundedCornerShape(30.dp), listOf(Color(0x40FFFFFF), Color(0x1AFFFFFF), Color(0x26FFFFFF))), Alignment.Center) {
                when (tab) {
                    Tab.FILTRES -> LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        items(FILTERS.size) { i ->
                            Column(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { fi = i; touch() }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Canvas(Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).let { m -> if (fi == i) m.border(2.5.dp, Color.White, RoundedCornerShape(16.dp)) else m }) {
                                    val t = thumb
                                    if (t != null) drawIntoCanvas { cv ->
                                        val sc = max(size.width / t.width, size.height / t.height)
                                        cv.nativeCanvas.save(); cv.nativeCanvas.clipRect(0f, 0f, size.width, size.height)
                                        cv.nativeCanvas.translate((size.width - t.width * sc) / 2, (size.height - t.height * sc) / 2); cv.nativeCanvas.scale(sc, sc)
                                        cv.nativeCanvas.drawBitmap(t, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(FILTERS[i].cm()) })
                                        cv.nativeCanvas.restore()
                                    }
                                }
                                Text(FILTERS[i].name, color = Color.White.copy(alpha = if (fi == i) 1f else .8f), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Tab.TEXTE -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                        GlassPill(height = 50.dp, tint = listOf(Color(0xCCFF8CB2), Color(0x99F45C92)), onClick = {
                            composing = TxtItem("", txtDefault.first, txtDefault.second, txtDefault.third); composeNew = true
                        }) { Text("＋  Ajouter du texte", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                        @Suppress("UNUSED_EXPRESSION") ver
                        val s = sel
                        if (s is TxtItem) GlassPill(height = 50.dp, onClick = { composing = s; composeNew = false }) { Text("Modifier", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                    }
                    Tab.DESSIN -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            items(TEXT_COLORS) { col -> ColorDot(col, dColor == col) { dColor = col } }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            for (i in 0..2) Box(Modifier.size(38.dp).clip(CircleShape).background(if (dSize == i) Color(0x59FFFFFF) else Color(0x1FFFFFFF)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { dSize = i }, Alignment.Center) {
                                Box(Modifier.size((6 + i * 5).dp).clip(CircleShape).background(Color.White))
                            }
                        }
                    }
                    Tab.STICKERS -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.padding(top = 4.dp, bottom = 4.dp).clip(RoundedCornerShape(50)).background(Color(0x47000000)).padding(2.dp)) {
                            listOf("Emojis", "Autocollants").forEachIndexed { i, n ->
                                Box(Modifier.clip(RoundedCornerShape(50)).background(if (stk == i) Color(0x59FFFFFF) else Color.Transparent).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { stk = i }.padding(horizontal = 18.dp, vertical = 5.dp)) {
                                    Text(n, color = if (stk == i) Color.White else Color(0xBFFFFFFF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        if (stk == 0) LazyHorizontalGrid(GridCells.Fixed(2), Modifier.height(84.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
                            items(Emojis.cats.flatMap { it.emojis }.distinct()) { e ->
                                Box(Modifier.size(42.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { add(EmojiItem(e)) }, Alignment.Center) { Text(e, fontSize = 27.sp) }
                            }
                        } else LazyRow(Modifier.height(84.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            items(BADGES.size) { i ->
                                val (t, bg, fg) = BADGES[i]
                                Box(Modifier.clip(RoundedCornerShape(50)).background(Color(bg)).clickable { add(BadgeItem(t, bg, fg).also { it.rot = ((Math.random() - .5) * .2).toFloat() }) }.padding(horizontal = 14.dp, vertical = 9.dp)) {
                                    Text(t, color = Color(fg), fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                    Tab.TAPE -> LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        items(TAPES.size) { i ->
                            val (c1, c2, k) = TAPES[i]
                            Canvas(Modifier.size(104.dp, 34.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x1AFFFFFF)).clickable { add(TapeItem(c1, c2, k).also { it.rot = ((Math.random() - .5) * .5).toFloat() }) }) {
                                drawIntoCanvas { cv -> cv.nativeCanvas.save(); cv.nativeCanvas.translate(size.width / 2, size.height / 2); drawItem(cv.nativeCanvas, TapeItem(c1, c2, k), size.width / .42f * .8f, size.height); cv.nativeCanvas.restore() }
                            }
                        }
                    }
                }
            }
            // Onglets
            Row(Modifier.glassSurface(RoundedCornerShape(50), listOf(Color(0x40FFFFFF), Color(0x1AFFFFFF), Color(0x26FFFFFF))).padding(4.dp)) {
                Tab.values().forEach { t ->
                    val on = tab == t
                    Box(
                        Modifier.size(58.dp, 48.dp).clip(RoundedCornerShape(50)).background(if (on) Color(0x4DFFFFFF) else Color.Transparent)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                tab = t
                                if (t == Tab.DESSIN) { sel = null; touch() }
                                if (t == Tab.TEXTE && sel !is TxtItem) { composing = TxtItem("", txtDefault.first, txtDefault.second, txtDefault.third); composeNew = true }
                            },
                        Alignment.Center,
                    ) {
                        val tint = if (on) Color.White else Color(0xB8FFFFFF)
                        when (t) {
                            Tab.FILTRES -> Ico(R.drawable.ic_filters, tint, 24.dp)
                            Tab.TEXTE -> Text("Aa", color = tint, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            Tab.DESSIN -> Ico(R.drawable.ic_pencil, tint, 24.dp)
                            Tab.STICKERS -> Ico(R.drawable.ic_smile, tint, 24.dp)
                            Tab.TAPE -> Ico(R.drawable.ic_tape, tint, 24.dp)
                        }
                    }
                }
            }
            // Envoi
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (allowSched) GlassCircle(50.dp, onClick = { askSched = true }) { Ico(R.drawable.ic_clock, Color.White, 22.dp) }
                GlassPill(Modifier.weight(1f), height = 50.dp, tint = listOf(Color(0x94FFBE6E), Color(0x61FF823C)), onClick = { finish(true, 0) }) {
                    Ico(R.drawable.ic_flame, Color.White, 19.dp); Spacer(Modifier.width(8.dp)); Text("Éphémère", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
                GlassPill(Modifier.weight(1f), height = 50.dp, tint = listOf(Color(0xCCFF8CB2), Color(0x99F45C92)), onClick = { finish(false, 0) }) {
                    Ico(R.drawable.ic_paperplane, Color.White, 19.dp); Spacer(Modifier.width(8.dp)); Text("Envoyer", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
            }
        }

        // Écrire sur la photo
        val comp = composing
        if (comp != null) TextComposer(comp, composeNew, onCancel = { composing = null }, onOk = { txt, col, sty, fnt ->
            txtDefault = Triple(col, sty, fnt)
            if (txt.isNotBlank()) {
                comp.text = txt.trim(); comp.color = col; comp.style = sty; comp.font = fnt
                if (composeNew) add(comp) else { sel = comp; touch() }
            }
            composing = null
        })

        if (askSched) SendChoiceSheet("Programmer l'envoi", canOnce = false, startSched = true) { o -> askSched = false; if (o != null && o.at > 0) finish(false, o.at) }
        if (busy) Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(enabled = true) {}, Alignment.Center) { CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(38.dp)) }
    }
}

@Composable
private fun ColorDot(c: Int, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(if (on) 36.dp else 32.dp).clip(CircleShape).background(Color(c))
            .border(if (on) 3.dp else 1.dp, if (on) Color.White else Color(0x8CFFFFFF), CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
    )
}

@Composable
private fun TextComposer(item: TxtItem, isNew: Boolean, onCancel: () -> Unit, onOk: (String, Int, Int, Int) -> Unit) {
    var text by remember { mutableStateOf(item.text) }
    var col by remember { mutableIntStateOf(item.color) }
    var sty by remember { mutableIntStateOf(item.style) }
    var fnt by remember { mutableIntStateOf(item.font) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().background(Color(0xE60A0608)).statusBarsPadding().navigationBarsPadding().imePadding().softEnter()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            GlassPill(height = 44.dp, onClick = onCancel) { Text("Annuler", color = Color.White, fontWeight = FontWeight.SemiBold) }
            GlassPill(height = 44.dp, tint = listOf(Color(0xCCFF8CB2), Color(0x99F45C92)), onClick = { onOk(text, col, sty, fnt) }) { Text("OK", color = Color.White, fontWeight = FontWeight.SemiBold) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), Alignment.Center) {
            BasicTextField(
                value = text, onValueChange = { if (it.length <= 160) text = it },
                textStyle = TextStyle(
                    color = Color(col), fontSize = 32.sp, textAlign = TextAlign.Center, fontFamily = fontFamily(fnt), fontWeight = FontWeight.Bold,
                    shadow = if (sty == 2) androidx.compose.ui.graphics.Shadow(if (col == 0xFF111111.toInt()) Color.White else Color.Black, Offset(0f, 0f), 10f) else null,
                ),
                cursorBrush = SolidColor(Color(0xFFFF4F86)),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).let { m -> if (sty == 1) m.clip(RoundedCornerShape(18.dp)).background(if (col == 0xFF111111.toInt()) Color(0xE6FFFFFF) else Color(0x99000000)).padding(12.dp) else m },
                decorationBox = { inner -> Box(contentAlignment = Alignment.Center) { if (text.isEmpty()) Text("Écris ici…", color = Color(0x66FFFFFF), fontSize = 32.sp, fontWeight = FontWeight.Bold); inner() } },
            )
        }
        LazyRow(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(FONTS.size) { i ->
                Box(Modifier.clip(RoundedCornerShape(50)).background(if (fnt == i) Color.White else Color(0x24FFFFFF)).clickable { fnt = i }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(FONTS[i], color = if (fnt == i) Color(0xFF111111) else Color.White, fontFamily = fontFamily(i), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            items(TEXT_COLORS) { c -> ColorDot(c, col == c) { col = c } }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp).clip(RoundedCornerShape(50)).background(Color(0x47000000)).padding(2.dp)) {
            listOf("Simple", "Fond", "Contour").forEachIndexed { i, n ->
                Box(Modifier.clip(RoundedCornerShape(50)).background(if (sty == i) Color(0x59FFFFFF) else Color.Transparent).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { sty = i }.padding(horizontal = 20.dp, vertical = 6.dp)) {
                    Text(n, color = if (sty == i) Color.White else Color(0xBFFFFFFF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
