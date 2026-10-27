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
fun HotPicFlow(title: String, onResult: (PreparedPhoto, Boolean) -> Unit, onClose: () -> Unit) {
    var shot by remember { mutableStateOf<File?>(null) }
    val f = shot
    if (f == null) HotCamera(title, onShot = { shot = it }, onClose = onClose)
    else PhotoEditor(f, title, onResult = { ph, once -> onResult(ph, once); onClose() }, onCancel = { shot = null })
}

@SuppressLint("UnsafeOptInUsageWarning")
@Composable
private fun HotCamera(title: String, onShot: (File) -> Unit, onClose: () -> Unit) {
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
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0x73000000)).clickable { onClose() }, Alignment.Center) { Ico(R.drawable.ic_close, Color.White, 22.dp) }
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Box(Modifier.size(42.dp))
            }
            if (!allowed && denied) Text(
                "Autorise la caméra dans les réglages du téléphone, ou choisis une photo dans la galerie 🖼.",
                color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
            error?.let { Text(it, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(32.dp)) }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 26.dp, start = 12.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceAround,
            ) {
                Box(Modifier.size(54.dp).clip(CircleShape).background(Color(0x2EFFFFFF)).clickable { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Alignment.Center) { Text("🖼", fontSize = 24.sp) }
                Box(
                    Modifier.size(80.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(7.dp).clip(CircleShape).background(if (busy) Color(0xFFBBBBBB) else Color.White)
                        .clickable(enabled = allowed && !busy) {
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
                Box(Modifier.size(54.dp).clip(CircleShape).background(Color(0x2EFFFFFF)).clickable { front = !front }, Alignment.Center) { Text("🔄", fontSize = 24.sp) }
            }
        }
    }
}

private class EditBridge(val onDone: (ByteArray, Int, Int, Boolean) -> Unit, val onCancel: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())

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
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0C0709)).statusBarsPadding().navigationBarsPadding()) {
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
                        addJavascriptInterface(EditBridge({ bytes, w, h, once -> onResult(PreparedPhoto(bytes, w, h), once) }, onCancel), "Native")
                        val src = "https://appassets.androidplatform.net/cam/" + URLEncoder.encode(file.name, "UTF-8")
                        loadUrl("https://appassets.androidplatform.net/assets/call/edit.html?src=${URLEncoder.encode(src, "UTF-8")}&title=${URLEncoder.encode(title, "UTF-8")}")
                    }
                },
                onRelease = { runCatching { it.destroy() } },
            )
        }
    }
}
