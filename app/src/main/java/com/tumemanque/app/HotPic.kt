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
fun HotPicFlow(title: String, icon: Int = R.drawable.ic_flame, onResult: (PreparedPhoto, Boolean) -> Unit, onClose: () -> Unit) {
    var shot by remember { mutableStateOf<File?>(null) }
    val f = shot
    if (f == null) HotCamera(title, icon, onShot = { shot = it }, onClose = onClose)
    else PhotoEditor(f, title, onResult = { ph, once -> onResult(ph, once); onClose() }, onCancel = { shot = null })
}

@SuppressLint("UnsafeOptInUsageWarning")
@Composable
private fun HotCamera(title: String, icon: Int, onShot: (File) -> Unit, onClose: () -> Unit) {
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
        if (uri != null) {
            val out = File.createTempFile("pick", ".jpg", File(context.cacheDir, "cam").apply { mkdirs() })
            val ok = runCatching { context.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } } }.isSuccess
            if (ok && out.length() > 0) onShot(out) else error = "Impossible de lire cette photo."
        }
    }

    BackHandler { onClose() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (allowed) AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp)) {
                GlassCircle(44.dp, Modifier.align(Alignment.CenterStart), onClick = { onClose() }) { Ico(R.drawable.ic_xmark, Color.White, 20.dp) }
                GlassPill(Modifier.align(Alignment.Center), height = 38.dp, onClick = null) {
                    Ico(icon, Color(0xFFFFB454), 18.dp)
                    Spacer(Modifier.width(7.dp))
                    Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
            if (!allowed && denied) Text(
                "Autorise la caméra dans les réglages du téléphone, ou choisis une photo dans la galerie.",
                color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
            error?.let { Text(it, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(32.dp)) }
            // 3 colonnes égales : galerie à gauche, déclencheur pile au centre, caméra à droite.
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 64.dp, start = 28.dp, end = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), Alignment.CenterStart) {
                    GlassCircle(52.dp, onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Ico(R.drawable.ic_photos, Color.White, 24.dp) }
                }
                val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val down by src.collectIsPressedAsState()
                val k by animateFloatAsState(if (down) 0.84f else 1f, spring(0.45f, 600f), label = "shutter")
                Box(Modifier.size(80.dp).clip(CircleShape).border(4.dp, Color.White.copy(alpha = 0.95f), CircleShape), Alignment.Center) {
                    Box(
                        Modifier.size(62.dp).graphicsLayer { scaleX = k; scaleY = k }.clip(CircleShape).background(if (busy) Color(0xFFBBBBBB) else Color.White)
                            .clickable(interactionSource = src, indication = null, enabled = allowed && !busy) {
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

private class EditBridge(val onDone: (ByteArray, Int, Int, Boolean) -> Unit, val onCancel: () -> Unit, val onReady: () -> Unit = {}) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun ready() { main.post { onReady() } }

    @JavascriptInterface
    fun done(b64: String, w: Int, h: Int, once: Boolean) {
        val bytes = try { Base64.decode(b64, Base64.DEFAULT) } catch (e: Exception) { ByteArray(0) }
        main.post { if (bytes.isEmpty()) onCancel() else onDone(bytes, w, h, once) }
    }

    @JavascriptInterface
    fun cancel() { main.post { onCancel() } }
}

/** L'éditeur de photo (le même code que sur le web) dans une page de l'appli. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PhotoEditor(file: File, title: String, onResult: (PreparedPhoto, Boolean) -> Unit, onCancel: () -> Unit) {
    BackHandler { onCancel() }
    var pageReady by remember { mutableStateOf(false) }
    var stuck by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(7000); if (!pageReady) stuck = true }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0C0709)).statusBarsPadding().navigationBarsPadding().imePadding()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(0xFF0C0709.toInt())
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        val loader = WebViewAssetLoader.Builder()
                            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                            .addPathHandler("/cam/", WebViewAssetLoader.InternalStoragePathHandler(ctx, File(ctx.cacheDir, "cam")))
                            .build()
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                loader.shouldInterceptRequest(request.url)

                            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                                Handler(Looper.getMainLooper()).post { onCancel() }
                                return true
                            }
                        }
                        addJavascriptInterface(EditBridge({ bytes, w, h, once -> onResult(PreparedPhoto(bytes, w, h), once) }, onCancel, { pageReady = true }), "Native")
                        val src = "https://appassets.androidplatform.net/cam/" + URLEncoder.encode(file.name, "UTF-8")
                        loadUrl("https://appassets.androidplatform.net/assets/call/edit.html?src=${URLEncoder.encode(src, "UTF-8")}&title=${URLEncoder.encode(title, "UTF-8")}")
                    }
                },
                onRelease = { runCatching { it.destroy() } },
            )
            if (stuck && !pageReady) Column(Modifier.fillMaxSize().background(Color(0xFF0C0709)).padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                Text("L'éditeur n'a pas pu s'ouvrir.", color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                GlassPill(onClick = onCancel) { Text("Fermer", color = Color.White, fontWeight = FontWeight.SemiBold) }
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
