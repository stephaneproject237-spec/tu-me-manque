package com.tumemanque.app

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Verrouillage de l'appli par empreinte, visage ou code du téléphone. */
object AppLock {
    private fun prefs(c: Context) = c.getSharedPreferences("lock", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("on", false)
    fun set(c: Context, on: Boolean) = prefs(c).edit().putBoolean("on", on).apply()

    /** Biométrie du téléphone ; à partir d'Android 11, le code/schéma du téléphone sert aussi de secours. */
    private fun authenticators(): Int =
        if (Build.VERSION.SDK_INT >= 30) BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        else BiometricManager.Authenticators.BIOMETRIC_WEAK

    fun available(c: Context): Boolean =
        BiometricManager.from(c).canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(activity: FragmentActivity, title: String, onOk: () -> Unit, onFail: (String) -> Unit = {}) {
        val cb = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onOk() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // 10 = annulé par l'utilisateur, 13 = bouton « Annuler » : pas une vraie erreur
                onFail(if (errorCode == 10 || errorCode == 13) "" else errString.toString())
            }
        }
        val p = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), cb)
        val b = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Tu me manques 💗")
            .setAllowedAuthenticators(authenticators())
        if (Build.VERSION.SDK_INT < 30) b.setNegativeButtonText("Annuler")
        try { p.authenticate(b.build()) } catch (e: Exception) { onFail(e.message ?: "Erreur") }
    }
}

fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is FragmentActivity) return c; c = c.baseContext }
    return null
}

/** Écran qui cache tout tant que l'appli est verrouillée. */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val activity = context.findFragmentActivity()
    val busy = androidx.compose.runtime.remember { booleanArrayOf(false) }
    var err by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    fun ask(force: Boolean) {
        if (activity == null) { err = "Impossible d'ouvrir la vérification."; return }
        if (busy[0] && !force) return
        busy[0] = true
        err = null
        AppLock.prompt(
            activity, "Déverrouiller l'appli",
            onOk = { busy[0] = false; onUnlocked() },
            onFail = { busy[0] = false; if (it.isNotEmpty()) err = it },
        )
    }
    BackHandler(enabled = true) { }
    // À chaque retour au premier plan, on relance la vérification (le système peut l'avoir interrompue).
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            busy[0] = false
            kotlinx.coroutines.delay(350)
            ask(false)
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF160A12)).clickable(enabled = true) { }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(32.dp)) {
            Text("💗", fontSize = 64.sp)
            Spacer(Modifier.height(10.dp))
            Text("Tu me manques est verrouillée", color = Color(0xFFF5EEF2), fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("Déverrouille avec ton empreinte, ton visage ou le code du téléphone.", color = Color(0xFFB7A3AE), fontSize = 14.sp, textAlign = TextAlign.Center)
            err?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp, textAlign = TextAlign.Center) }
            Spacer(Modifier.height(24.dp))
            GlassButton("🔓 Déverrouiller", Modifier.fillMaxWidth()) { ask(true) }
        }
    }
}
