package com.takemyvoice.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())
    private var demandeMicro: PermissionRequest? = null
    private var choixFichier: ValueCallback<Array<Uri>>? = null

    private val majEcran: () -> Unit = {
        main.removeCallbacks(rafraichir)
        main.postDelayed(rafraichir, 120)
    }
    private val rafraichir = Runnable { if (::web.isInitialized) web.evaluateJavascript("window.tmvMaj && tmvMaj()", null) }

    private val permMicro = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val r = demandeMicro ?: return@registerForActivityResult
        demandeMicro = null
        if (ok) r.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else {
            r.deny(); toast("Autorisez le micro pour enregistrer.")
        }
    }
    private val permNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val fichiers = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        choixFichier?.onReceiveValue(uris.toTypedArray()); choixFichier = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Moteur.canal(this)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/files/", WebViewAssetLoader.InternalStoragePathHandler(this, filesDir))
            .build()

        web = WebView(this)
        web.setBackgroundColor(0xFF0B0B10.toInt())
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
        }
        web.addJavascriptInterface(Bridge(this), "TMV")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(r.url)

            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                if (!r.isForMainFrame || r.url.host == "appassets.androidplatform.net") return false
                runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, r.url)) }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                main.post {
                    if (!request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) { request.deny(); return@post }
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else {
                        demandeMicro = request
                        permMicro.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }

            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: WebChromeClient.FileChooserParams): Boolean {
                choixFichier?.onReceiveValue(emptyArray())
                choixFichier = cb
                return try { fichiers.launch("audio/*"); true } catch (_: Exception) { choixFichier = null; false }
            }
        }
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.tmvRetour ? tmvRetour() : false") { r ->
                    if (r != "true") finish()
                }
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Store.ecouter(majEcran)
        Moteur.relancer(this)
    }

    override fun onResume() {
        super.onResume()
        Moteur.relancer(this)
        majEcran()
    }

    override fun onDestroy() {
        Store.oublier(majEcran)
        super.onDestroy()
    }

    fun evenement(nom: String, data: JSONObject) {
        main.post {
            if (::web.isInitialized)
                web.evaluateJavascript("window.tmvEvenement && tmvEvenement(${JSONObject.quote(nom)}, $data)", null)
        }
    }

    fun toast(msg: String) {
        main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }
}
