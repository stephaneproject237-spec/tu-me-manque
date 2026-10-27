package com.tumemanque.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.net.URLEncoder

/**
 * Appareil photo (selfie ou caméra arrière, qualité maximale du capteur) puis retouche (filtres, texte, stickers, tape).
 * [onResult] reçoit la photo finale et le choix « éphémère ou permanent ».
 */
@Composable
fun HotPicFlow(
    title: String, icon: Int? = R.drawable.ic_flame, allowSched: Boolean = false,
    onVideoRecord: (() -> Unit)? = null, onVideoPicked: ((Uri, String) -> Unit)? = null,
    onResult: (PreparedPhoto, Boolean, Long) -> Unit, onClose: () -> Unit,
) {
    var shot by remember { mutableStateOf<File?>(null) }
    BackHandler { if (shot != null) shot = null else onClose() }
    // Une seule fenêtre pour la caméra et la retouche : fondu enchaîné, jamais de « trou » noir entre les deux.
    val enter = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, androidx.compose.animation.core.tween(380, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    Dialog(onDismissRequest = { if (shot != null) shot = null else onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black).graphicsLayer {
                alpha = enter.value
                val k = 1.04f - 0.04f * enter.value
                scaleX = k; scaleY = k
            },
        ) {
            androidx.compose.animation.Crossfade(targetState = shot, animationSpec = androidx.compose.animation.core.tween(320), label = "hotflow") { f ->
                if (f == null) HotCamera(title, icon, onVideoRecord, onVideoPicked, onShot = { shot = it }, onClose = onClose)
                else PhotoEditorNative(f, title, allowSched, onResult = { ph, once, at -> onResult(ph, once, at); onClose() }, onCancel = { shot = null })
            }
        }
    }
}

@SuppressLint("UnsafeOptInUsageWarning")
@Composable
private fun HotCamera(title: String, icon: Int?, onVideoRecord: (() -> Unit)?, onVideoPicked: ((Uri, String) -> Unit)?, onShot: (File) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val askPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> allowed = ok; denied = !ok }
    LaunchedEffect(Unit) { if (!allowed) askPerm.launch(Manifest.permission.CAMERA) }

    var front by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val capture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
    }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }

    LaunchedEffect(allowed, front) {
        if (!allowed) return@LaunchedEffect
        val fut = ProcessCameraProvider.getInstance(context)
        fut.addListener({
            try {
                val provider = fut.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                provider.unbindAll()
                val sel = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.bindToLifecycle(lifecycle, sel, preview, capture)
                error = null
            } catch (e: Exception) {
                // pas de caméra de ce côté (ou indisponible) : on essaie l'autre
                if (front) front = false else error = "Caméra indisponible."
            }
        }, ContextCompat.getMainExecutor(context))
    }
    DisposableEffect(Unit) {
        onDispose { runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() } }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        val mime = if (uri != null) context.contentResolver.getType(uri) ?: "" else ""
        if (uri != null && mime.startsWith("video/") && onVideoPicked != null) { onVideoPicked(uri, mime); onClose() }
        else if (uri != null) {
            val out = File.createTempFile("pick", ".jpg", File(context.cacheDir, "cam").apply { mkdirs() })
            val ok = runCatching { context.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } } }.isSuccess
            if (ok && out.length() > 0) onShot(out) else error = "Impossible de lire cette photo."
        }
    }

    var videoMode by remember { mutableStateOf(false) }
    run {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (allowed) AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp)) {
                GlassCircle(44.dp, Modifier.align(Alignment.CenterStart), onClick = { onClose() }) { Ico(R.drawable.ic_xmark, Color.White, 20.dp) }
                if (title.isNotEmpty()) GlassPill(Modifier.align(Alignment.Center), height = 38.dp, onClick = null) {
                    if (icon != null) { Ico(icon, Color(0xFFFFB454), 18.dp); Spacer(Modifier.width(7.dp)) }
                    Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
            if (!allowed && denied) Text(
                "Autorise la caméra dans les réglages du téléphone, ou choisis une photo dans la galerie.",
                color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
            error?.let { Text(it, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(32.dp)) }
            if (onVideoRecord != null) {
                Row(
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 156.dp).glassSurface(RoundedCornerShape(50), listOf(Color(0x40000000), Color(0x30000000))).padding(3.dp),
                ) {
                    listOf("Photo" to false, "Vidéo" to true).forEach { (t, v) ->
                        val on = videoMode == v
                        Box(
                            Modifier.clip(RoundedCornerShape(50)).background(if (on) Color(0x59FFFFFF) else Color.Transparent)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { videoMode = v }
                                .padding(horizontal = 22.dp, vertical = 7.dp),
                            Alignment.Center,
                        ) { Text(t, color = if (on) Color.White else Color(0xB3FFFFFF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            // 3 colonnes égales : galerie à gauche, déclencheur pile au centre, caméra à droite.
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 64.dp, start = 28.dp, end = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), Alignment.CenterStart) {
                    GlassCircle(52.dp, onClick = { gallery.launch(PickVisualMediaRequest(if (onVideoPicked != null) ActivityResultContracts.PickVisualMedia.ImageAndVideo else ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Ico(R.drawable.ic_photos, Color.White, 24.dp) }
                }
                val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val down by src.collectIsPressedAsState()
                val k by animateFloatAsState(if (down) 0.84f else 1f, spring(0.45f, 600f), label = "shutter")
                Box(Modifier.size(80.dp).clip(CircleShape).border(4.dp, Color.White.copy(alpha = 0.95f), CircleShape), Alignment.Center) {
                    Box(
                        Modifier.size(62.dp).graphicsLayer { scaleX = k; scaleY = k }.clip(CircleShape).background(if (busy) Color(0xFFBBBBBB) else Color.White)
                            .clickable(interactionSource = src, indication = null, enabled = (allowed || videoMode) && !busy) {
                                if (videoMode) { onVideoRecord?.invoke(); onClose(); return@clickable }
                                busy = true
                                val file = File.createTempFile("hot", ".jpg", File(context.cacheDir, "cam").apply { mkdirs() })
                                capture.takePicture(
                                    ImageCapture.OutputFileOptions.Builder(file).build(),
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageSavedCallback {
                                        override fun onImageSaved(r: ImageCapture.OutputFileResults) { busy = false; onShot(file) }
                                        override fun onError(e: ImageCaptureException) { busy = false; error = "La photo n'a pas pu être prise. Réessaie." }
                                    },
                                )
                            },
                    ) {}
                }
                Box(Modifier.weight(1f), Alignment.CenterEnd) {
                    GlassCircle(52.dp, onClick = { front = !front }) { Ico(R.drawable.ic_flip, Color.White, 24.dp) }
                }
            }
        }
    }
}

// ---- Liquid Glass (iOS 26) : reflet en haut, bord lumineux, forme ronde. Le flou réel derrière n'est pas possible sur l'aperçu caméra. ----
internal fun Modifier.glassSurface(shape: Shape, tint: List<Color>? = null): Modifier = this
    .shadow(10.dp, shape, ambientColor = Color(0x55000000), spotColor = Color(0x55000000))
    .clip(shape)
    .background(Brush.linearGradient(tint ?: listOf(Color(0x52FFFFFF), Color(0x1FFFFFFF), Color(0x2EFFFFFF))))
    .border(0.8.dp, Brush.verticalGradient(listOf(Color(0xA6FFFFFF), Color(0x2EFFFFFF), Color(0x40FFFFFF))), shape)

@Composable
internal fun GlassCircle(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val k by animateFloatAsState(if (down) 0.9f else 1f, spring(0.45f, 600f), label = "glassC")
    Box(
        modifier.size(size).graphicsLayer { scaleX = k; scaleY = k }.glassSurface(CircleShape)
            .clickable(interactionSource = src, indication = null) { onClick() },
        Alignment.Center,
    ) { content() }
}

@Composable
internal fun GlassPill(
    modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 44.dp, tint: List<Color>? = null,
    onClick: (() -> Unit)?, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val k by animateFloatAsState(if (down && onClick != null) 0.93f else 1f, spring(0.45f, 600f), label = "glassP")
    val shape = RoundedCornerShape(50)
    Row(
        modifier.height(height).graphicsLayer { scaleX = k; scaleY = k }.glassSurface(shape, tint)
            .let { if (onClick != null) it.clickable(interactionSource = src, indication = null) { onClick() } else it }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) { content() }
}

/** Entrée douce d'une fenêtre : elle grossit légèrement avec un petit rebond et apparaît en fondu. */
@Composable
internal fun Modifier.softEnter(): Modifier {
    val p = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, androidx.compose.animation.core.spring(0.7f, 420f)) }
    return this.graphicsLayer {
        val v = p.value
        val k = 0.88f + 0.12f * v
        scaleX = k; scaleY = k
        alpha = v.coerceIn(0f, 1f)
    }
}
