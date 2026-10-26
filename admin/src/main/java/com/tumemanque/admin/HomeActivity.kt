package com.tumemanque.admin

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

/** Première page : « Paramètres » et « Suivi de l'application ». */
class HomeActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private var inSettings = false

    private var available: AdminUpdate? = null
    private var checking = false
    private var checked = false
    private var errorMsg = ""
    private var downloading = false
    private var progress = 0
    private var apk: File? = null
    private var needPerm = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#160A12"))
            setPadding(dp(20), 0, dp(20), 0)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(20), b.top + dp(24), dp(20), b.bottom + dp(24))
            ins
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (inSettings) { inSettings = false; render() } else finish()
            }
        })
        render()
    }

    override fun onResume() {
        super.onResume()
        // Retour de l'écran d'autorisation : on relance l'installation.
        val f = apk
        if (needPerm && f != null && f.exists()) {
            if (AdminUpdater.install(this, f)) { needPerm = false; render() }
        }
    }

    private fun card(title: String, sub: String, emoji: String, onClick: () -> Unit): View {
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(22))
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(Color.parseColor("#2A1622")); setStroke(dp(1), Color.parseColor("#4A2A3C")) }
            setOnClickListener { onClick() }
        }
        l.addView(TextView(this).apply { text = emoji; textSize = 30f; setPadding(0, 0, dp(16), 0) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = title; textSize = 19f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD) })
        col.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(Color.parseColor("#B89AAA")) })
        l.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        l.addView(TextView(this).apply { text = "›"; textSize = 26f; setTextColor(Color.parseColor("#FF6A9C")) })
        l.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
        return l
    }

    private fun pinkButton(text: String, enabled: Boolean = true, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 16f
        isEnabled = enabled
        background = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(if (enabled) Color.parseColor("#E91E63") else Color.parseColor("#5A3A4A")) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(14) }
    }

    private fun render() {
        root.removeAllViews()
        if (inSettings) renderSettings() else renderHome()
    }

    private fun renderHome() {
        root.addView(TextView(this).apply { text = "Tu me manques"; textSize = 28f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD) })
        root.addView(TextView(this).apply {
            text = "Administration"; textSize = 15f; setTextColor(Color.parseColor("#FF6A9C"))
            setPadding(0, dp(2), 0, dp(32))
        })
        root.addView(card("Suivi de l'application", "Utilisateurs, couples, conversations", "📊") {
            startActivity(Intent(this, MainActivity::class.java))
        })
        root.addView(card("Paramètres", "Version et mises à jour", "⚙️") {
            inSettings = true; render()
            if (!checked && !checking) doCheck()
        })
    }

    private fun renderSettings() {
        root.addView(TextView(this).apply {
            text = "‹ Retour"; textSize = 16f; setTextColor(Color.parseColor("#FF6A9C")); setPadding(0, 0, 0, dp(16))
            setOnClickListener { inSettings = false; render() }
        })
        root.addView(TextView(this).apply { text = "Paramètres"; textSize = 26f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, dp(20)) })

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(Color.parseColor("#2A1622")); setStroke(dp(1), Color.parseColor("#4A2A3C")) }
        }
        box.addView(TextView(this).apply { text = "Mises à jour"; textSize = 13f; setTextColor(Color.parseColor("#B89AAA")) })
        box.addView(TextView(this).apply {
            text = "Version installée : ${AdminUpdater.currentName(this@HomeActivity)}"
            textSize = 17f; setTextColor(Color.WHITE); setPadding(0, dp(4), 0, dp(8))
        })
        val a = available
        val status = when {
            checking -> "Recherche en cours…"
            downloading -> "Téléchargement… $progress %"
            errorMsg.isNotEmpty() -> "Impossible de vérifier :\n$errorMsg"
            a != null && apk != null -> "Version ${a.name} téléchargée."
            a != null -> "Nouvelle version disponible : ${a.name}"
            checked -> "Vous avez la dernière version ✔"
            else -> ""
        }
        box.addView(TextView(this).apply {
            text = status; textSize = 14f
            setTextColor(if (errorMsg.isNotEmpty()) Color.parseColor("#FF8A80") else Color.parseColor("#D8C0CE"))
        })
        if (downloading) {
            box.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; this.progress = this@HomeActivity.progress
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)).apply { topMargin = dp(10) }
            })
        }
        if (a != null && !downloading) {
            box.addView(pinkButton(if (apk != null) "Installer" else "Mettre à jour maintenant") { doUpdate() })
        }
        box.addView(pinkButton("Vérifier les mises à jour", enabled = !checking && !downloading) { doCheck() })
        if (needPerm) {
            box.addView(TextView(this).apply {
                text = "Autorisez l'installation depuis cette application dans les réglages qui viennent de s'ouvrir, puis revenez ici."
                textSize = 13f; setTextColor(Color.parseColor("#FFD180")); setPadding(0, dp(10), 0, 0)
            })
        }
        root.addView(box)
    }

    private fun doCheck() {
        checking = true; errorMsg = ""; render()
        AdminUpdater.check { info, err ->
            runOnUiThread {
                checking = false; checked = true
                if (err != null) errorMsg = err
                else {
                    val newer = if (info != null && info.code > AdminUpdater.currentCode(this)) info else null
                    if (newer?.code != available?.code) apk = null
                    available = newer
                }
                render()
            }
        }
    }

    private fun doUpdate() {
        val info = available ?: return
        val f = apk
        if (f != null && f.exists()) { needPerm = !AdminUpdater.install(this, f); render(); return }
        downloading = true; progress = 0; errorMsg = ""; render()
        AdminUpdater.download(this, info, { p -> runOnUiThread { progress = p; render() } }) { file, err ->
            runOnUiThread {
                downloading = false
                if (file == null) errorMsg = "Téléchargement : $err"
                else { apk = file; needPerm = !AdminUpdater.install(this, file) }
                render()
            }
        }
    }
}
