package com.tumemanque.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder

/** Demande d'ouverture de l'écran d'appel : « out » = j'appelle, « in » = ça sonne chez moi. */
data class CallReq(val mode: String, val video: Boolean, val cid: String = "", val after: Long = 0)

object Calls {
    var request by mutableStateOf<CallReq?>(null)
    private val handled = HashSet<String>()
    private var lastId = 0L

    fun start(video: Boolean) { if (request == null) request = CallReq("out", video) }

    /** Regarde si l'autre personne m'appelle. Renvoie false si le réseau (ou le serveur) ne répond pas. */
    suspend fun pollOnce(): Boolean {
        val sigs = try { Backend.callPoll(lastId) } catch (e: Exception) { return false }
        for ((i, s) in sigs.withIndex()) {
            if (s.id > lastId) lastId = s.id
            if (s.kind != "ring" || s.cid.isEmpty() || s.cid in handled) continue
            val over = sigs.drop(i + 1).any { it.cid == s.cid && it.kind in listOf("hangup", "reject", "accept") }
            handled.add(s.cid)
            if (!over && request == null) request = CallReq("in", s.video, s.cid, s.id - 1)
        }
        return true
    }
}

/** À placer une fois au-dessus de tout : surveille les appels entrants et affiche l'écran d'appel. */
@Composable
fun CallHost(label: String) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                var ok = true
                if (Calls.request == null) ok = Calls.pollOnce()
                delay(if (ok) 1500 else 20000)
            }
        }
    }
    val req = Calls.request
    if (req != null) CallScreen(req, label) { Calls.request = null }
}

private fun perms(video: Boolean) = if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)

private class CallBridge(val scope: CoroutineScope, val web: () -> WebView?, val onEnd: () -> Unit) {
    @JavascriptInterface
    fun http(id: Int, method: String, path: String, body: String) {
        scope.launch {
            var ok = true
            val text = try { Backend.callRaw(method, path, body.ifEmpty { null }) } catch (e: Exception) { ok = false; e.message ?: "erreur" }
            web()?.evaluateJavascript("window.__cb($id,$ok,${JSONObject.quote(text)})", null)
        }
    }

    @JavascriptInterface
    fun ended() { Handler(Looper.getMainLooper()).post { onEnd() } }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CallScreen(req: CallReq, label: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var decided by remember { mutableStateOf(perms(req.video).all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { decided = true }
    LaunchedEffect(Unit) { if (!decided) launcher.launch(perms(req.video)) }

    var speaker by remember { mutableStateOf(req.video) }
    DisposableEffect(Unit) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        onDispose {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        }
    }
    LaunchedEffect(speaker) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = speaker
    }

    var web by remember { mutableStateOf<WebView?>(null) }
    BackHandler { web?.evaluateJavascript("TMM_CALL.hangup()", null) ?: onClose() }

    Box(Modifier.fillMaxSize().zIndex(50f).background(Color(0xFF160A12))) {
        if (decided) {
            Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            setBackgroundColor(0xFF160A12.toInt())
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            keepScreenOn = true
                            val loader = WebViewAssetLoader.Builder()
                                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                                .build()
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                    loader.shouldInterceptRequest(request.url)

                                // Si le moteur web de l'appel plante (manque de mémoire…), on ferme l'appel au lieu de fermer toute l'appli.
                                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                                    Handler(Looper.getMainLooper()).post { onClose() }
                                    return true
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onPermissionRequest(request: PermissionRequest) { request.grant(request.resources) }
                            }
                            addJavascriptInterface(CallBridge(scope, { web }, onClose), "Native")
                            val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
                            loadUrl(
                                "https://appassets.androidplatform.net/assets/call/call.html?mode=${req.mode}&video=${if (req.video) 1 else 0}" +
                                    "&cid=${enc(req.cid)}&after=${req.after}&name=${enc(label.removePrefix("@"))}&label=${enc(label)}",
                            )
                            web = this
                        }
                    },
                    onRelease = { try { it.evaluateJavascript("TMM_CALL.hangup()", null); it.destroy() } catch (e: Exception) { } },
                )
                Box(
                    Modifier.align(Alignment.TopStart).padding(12.dp).size(42.dp).clip(CircleShape).background(Color(0x66000000))
                        .clickable { speaker = !speaker },
                    contentAlignment = Alignment.Center,
                ) { Text(if (speaker) "🔊" else "👂", fontSize = 18.sp) }
            }
        }
    }
}
// relais TURN Cloudflare intégré (page d'appel mise à jour)
// HD call media v6
