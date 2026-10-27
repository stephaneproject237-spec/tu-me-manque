package com.tumemanque.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Now pic : on propose à l'autre une photo « maintenant » ; s'il accepte, l'appareil photo s'ouvre chez les deux. */
object NowPic {
    var session by mutableStateOf<NowSession?>(null)
    var camera by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)
    private val handled = HashSet<Long>()
    private val toasted = HashSet<Long>()

    suspend fun request(): String {
        val st = try { Backend.nowpicRequest() } catch (e: Exception) { "net" }
        return when (st) {
            "ok" -> { poll(); "📸 Now Pic envoyé." }
            "no_partner" -> "Vous n'êtes plus partenaires."
            "net" -> "Connexion impossible. Réessaie."
            else -> "Impossible d'envoyer le Now Pic."
        }
    }

    suspend fun poll(partner: String = "") {
        val s = try { Backend.nowpicState() } catch (e: Exception) { return }
        session = s
        if (s == null) return
        if (s.state == "declined" && s.byMe && toasted.add(s.id)) toast = "@$partner ne veut pas faire de Now Pic pour l'instant."
        if (s.state == "go" && handled.add(s.id)) camera = true
    }

    /** Envoie la photo prise (sans passer par l'écran de chat, qui peut être fermé). */
    suspend fun sendPhoto(ph: PreparedPhoto, once: Boolean): Boolean = try {
        val path = Backend.upload(ph.bytes, "jpg", "image/jpeg")
        val meta = JSONObject().put("w", ph.w).put("h", ph.h).put("nowpic", true)
        val r = if (once) Backend.sendOnce("image", null, path, meta) else Backend.sendMessage("image", null, path, meta)
        r.status == "ok"
    } catch (e: Exception) { false }
}

@Composable
fun NowPicHost(label: String) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val partner = label.removePrefix("@")
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (!NowPic.camera) NowPic.poll(partner)
                delay(2500)
            }
        }
    }
    NowPic.toast?.let { t ->
        LaunchedEffect(t) { android.widget.Toast.makeText(context, t, android.widget.Toast.LENGTH_LONG).show(); NowPic.toast = null }
    }
    val s = NowPic.session
    if (!NowPic.camera && s != null && s.state == "pending" && !s.byMe) {
        Dialog(onDismissRequest = {}) {
            Column(
                Modifier.clip(RoundedCornerShape(26.dp)).background(Color(0xF5241620)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("📸", fontSize = 52.sp)
                Spacer(Modifier.height(8.dp))
                Text("@$partner veut faire un Now Pic !", color = Color(0xFFF5EEF2), fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text("Une photo prise en même temps, tout de suite. On y va ?", color = Color(0xB3F5EEF2), fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(Color(0xFFFF4F86), Color(0xFFD81B60))))
                        .clickable { scope.launch { try { Backend.nowpicRespond(s.id, true) } catch (e: Exception) { /* ignoré */ }; NowPic.poll(partner) } }.padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Oui, allons-y 📸", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { scope.launch { try { Backend.nowpicRespond(s.id, false) } catch (e: Exception) { /* ignoré */ }; NowPic.session = null } }) {
                    Text("Pas maintenant", color = Color(0xFFD8B8C8))
                }
            }
        }
    }
    if (!NowPic.camera && s != null && s.state == "pending" && s.byMe) {
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 96.dp), Alignment.BottomCenter) {
            Row(
                Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xEB241620)).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⏳ Now Pic envoyé à @$partner…", color = Color(0xFFF5EEF2), fontSize = 14.sp)
                TextButton(onClick = { scope.launch { try { Backend.nowpicCancel(s.id) } catch (e: Exception) { /* ignoré */ }; NowPic.session = null } }) { Text("Annuler", color = Color(0xFFFF8FB4)) }
            }
        }
    }
    if (NowPic.camera) {
        HotPicFlow(
            "📸 Now Pic — maintenant !",
            onResult = { ph, once ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { NowPic.sendPhoto(ph, once) }
                    NowPic.toast = if (ok) "📸 Photo envoyée !" else "Photo non envoyée. Vérifie ta connexion."
                }
            },
            onClose = { NowPic.camera = false },
        )
    }
}
