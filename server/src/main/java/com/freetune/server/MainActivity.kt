package com.freetune.server

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var bridge: Bridge? = null

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Core.init(this)
        startServers()
        web = WebView(this)
        setContentView(web)
        val loader = WebViewAssetLoader.Builder().setDomain("appassets.androidplatform.net")
            .addPathHandler("/ui/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false; allowContentAccess = false
        }
        web.setBackgroundColor(0xFF0A0D13.toInt())
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url
                if (u.host == "appassets.androidplatform.net" && (u.path ?: "").startsWith("/ftmedia/"))
                    return Core.media.handle(u.path!!.removePrefix("/ftmedia/"), r.requestHeaders["Range"])
                return loader.shouldInterceptRequest(u)
            }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                if (r.url.host != "appassets.androidplatform.net" && r.url.scheme == "https") startActivity(Intent(Intent.ACTION_VIEW, r.url))
                return r.url.host != "appassets.androidplatform.net"
            }
        }
        bridge = Bridge(web)
        web.addJavascriptInterface(bridge!!, "FTA")
        web.loadUrl("https://appassets.androidplatform.net/ui/index.html")
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { web.evaluateJavascript("window.__back && window.__back()", null); moveTaskToBack(true) }
        })
        askPermissions()
    }

    private fun startServers() {
        val i = Intent(this, ServerService::class.java)
        ContextCompat.startForegroundService(this, i)
    }

    private fun askPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        // Pour que les serveurs ne soient pas coupés par l'économie de batterie.
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (e: Exception) { /* réglage indisponible */ }
        }
    }

    override fun onDestroy() { bridge?.detach(); super.onDestroy() }
}
