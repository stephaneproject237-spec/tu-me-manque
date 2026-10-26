package com.tumemanque.admin

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader

/** Application « Admin » : la page d'administration du site, embarquée dans une application Android. */
class MainActivity : ComponentActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == HOST) return false
                if ((u.path ?: "").contains("/auth/v1/authorize")) {
                    // Google refuse la connexion dans une vue intégrée : on ouvre le navigateur, qui revient ici.
                    val b = u.buildUpon().clearQuery()
                    for (name in u.queryParameterNames) {
                        val v = if (name == "redirect_to") "tumemanqueadmin://auth" else u.getQueryParameter(name)
                        b.appendQueryParameter(name, v)
                    }
                    openExternal(b.build())
                    return true
                }
                openExternal(u)
                return true
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else if (!handle(intent)) web.loadUrl(START)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Retour de la connexion Google : on redonne le code à la page. */
    private fun handle(i: Intent?): Boolean {
        val d = i?.data ?: return false
        if (d.scheme != "tumemanqueadmin") return false
        val code = d.getQueryParameter("code")
        val err = d.getQueryParameter("error_description")
        val q = if (code != null) "?code=" + Uri.encode(code) else if (err != null) "?error_description=" + Uri.encode(err) else ""
        web.loadUrl(START + q)
        return true
    }

    private fun openExternal(u: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) { /* aucun navigateur */ }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    private companion object {
        const val HOST = "appassets.androidplatform.net"
        const val START = "https://$HOST/admin/index.html"
    }
}
