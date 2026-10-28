package com.tumemanque.app

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Abonnement Free tune : essai gratuit de 24 h (3 téléchargements), puis 500 FCFA / 30 jours
 * payés par Orange Money ou MTN MoMo (Campay, via la fonction Supabase « abonnement »).
 * Si le serveur n'est pas encore installé ou ne répond pas, rien n'est bloqué.
 */
object Sub {
    const val PRICE = "500 FCFA"

    /** active | trial | expired | null (inconnu : on ne bloque rien) */
    var state by mutableStateOf<String?>(null)
    var paidUntil by mutableStateOf<Long?>(null)
    var trialEnd by mutableStateOf<Long?>(null)
    var trialLeft by mutableStateOf(3)
    /** Écran d'abonnement ouvert : raison affichée (null = fermé). */
    var paywall by mutableStateOf<String?>(null)
    var reminderHidden by mutableStateOf(false)

    private fun parse(s: String?): Long? {
        if (s.isNullOrBlank() || s == "null") return null
        return runCatching {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            f.parse(s.take(19))?.time
        }.getOrNull()
    }
    fun fmtDate(ms: Long?): String = if (ms == null) "" else SimpleDateFormat("d MMMM yyyy", Locale.FRANCE).format(ms)

    suspend fun refresh() {
        val j = runCatching { JSONObject(Backend.callRaw("POST", "/rest/v1/rpc/sub_status", "{}")) }.getOrNull() ?: return
        if (j.optString("status") != "ok") return
        state = j.optString("state")
        paidUntil = parse(j.optString("paid_until"))
        trialEnd = parse(j.optString("trial_end"))
        trialLeft = j.optInt("trial_left", 3)
        if (state == "expired") paywall = if (trialLeft <= 0 && paidUntil == null) "dl" else "expired"
    }

    /** Avant chaque téléchargement : compte l'essai, ou ouvre l'écran de paiement. */
    fun download(ctx: android.content.Context, scope: CoroutineScope, go: () -> Unit) {
        scope.launch {
            val j = runCatching { JSONObject(Backend.callRaw("POST", "/rest/v1/rpc/use_download", "{}")) }.getOrNull()
            when (j?.optString("status")) {
                "pay" -> { paywall = "dl"; state = "expired" }
                "ok" -> {
                    go()
                    if (j.optString("state") == "trial") {
                        trialLeft = j.optInt("trial_left", 0)
                        Toast.makeText(ctx, if (trialLeft > 0) "Essai gratuit : encore $trialLeft téléchargement(s)" else "C'était ton dernier téléchargement gratuit", Toast.LENGTH_SHORT).show()
                    }
                }
                else -> go() // serveur pas encore prêt : on ne bloque pas
            }
        }
    }

    suspend fun pay(phone: String): JSONObject =
        runCatching { JSONObject(Backend.callRaw("POST", "/functions/v1/abonnement", JSONObject().put("action", "pay").put("phone", phone).toString())) }
            .getOrElse { JSONObject().put("status", "error").put("message", it.message ?: "") }

    suspend fun check(ref: String): JSONObject =
        runCatching { JSONObject(Backend.callRaw("POST", "/functions/v1/abonnement", JSONObject().put("action", "check").put("reference", ref).toString())) }
            .getOrElse { JSONObject().put("status", "pending") }

    fun operator(d: String): String = when {
        Regex("^6(7\\d|5[0-4]|8[0-4])").containsMatchIn(d) -> "MTN Mobile Money"
        Regex("^6(9\\d|5[5-9]|8[5-9])").containsMatchIn(d) -> "Orange Money"
        else -> ""
    }
}

/** Petit rappel quand l'abonnement se termine dans moins de 3 jours. */
@Composable
fun SubReminder() {
    val until = Sub.paidUntil ?: return
    if (Sub.state != "active" || Sub.reminderHidden) return
    val left = until - System.currentTimeMillis()
    if (left > 3 * 86_400_000L) return
    Row(
        Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 14.dp, vertical = 6.dp).glass(RoundedCornerShape(18.dp), 0.1f, P.blue)
            .clickable { Sub.paywall = "renew" }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Ton abonnement se termine le ${Sub.fmtDate(until)}", color = P.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Touche ici pour le renouveler (${Sub.PRICE} / mois)", color = P.blueSoft, fontSize = 12.sp)
        }
        RoundBtn(Ic.Close, { Sub.reminderHidden = true }, size = 30.dp, iconSize = 16.dp)
    }
}

/** Écran d'abonnement : 500 FCFA / mois par Orange Money ou MTN MoMo. */
@Composable
fun Paywall() {
    val reason = Sub.paywall ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var step by remember { mutableStateOf("form") } // form | wait | ok | fail
    var msg by remember { mutableStateOf("") }
    var ussd by remember { mutableStateOf("") }
    var ref by remember { mutableStateOf<String?>(null) }
    val blocking = Sub.state == "expired"
    BackHandler { if (!blocking || step == "ok") Sub.paywall = null }

    // Attente de la confirmation sur le téléphone : on vérifie toutes les 4 secondes (3 minutes maximum).
    LaunchedEffect(ref) {
        val r = ref ?: return@LaunchedEffect
        repeat(45) {
            delay(4000)
            val j = Sub.check(r)
            when (j.optString("status")) {
                "successful" -> { Sub.refresh(); step = "ok"; return@LaunchedEffect }
                "failed" -> { step = "fail"; msg = "Le paiement a été refusé ou annulé. Vérifie ton solde et réessaie."; ref = null; return@LaunchedEffect }
            }
        }
        if (step == "wait") { step = "fail"; msg = "Pas de confirmation reçue. Si tu as été débité, ton abonnement s'activera dans quelques minutes."; ref = null }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0E1B38), P.bg, P.bg))).clickable(enabled = false) {}) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                if (!blocking || step == "ok") RoundBtn(Ic.Close, { Sub.paywall = null })
            }
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(Color(0xFF3A8DFF), Color(0xFF1B4FB8), Color(0xFF0A1838)))), contentAlignment = Alignment.Center) {
                CIcon(Ic.Headphones, Color.White, 54.dp)
            }
            Spacer(Modifier.height(16.dp))
            Text("Free tune Premium", color = P.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                when (reason) {
                    "dl" -> "Tu as utilisé tes 3 téléchargements gratuits."
                    "expired" -> if (Sub.paidUntil != null) "Ton abonnement est terminé." else "Ton essai gratuit est terminé."
                    else -> "Continue à profiter de Free tune."
                },
                color = P.sub, fontSize = 15.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp), 0.06f).padding(18.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("500", color = P.text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text(" FCFA / mois", color = P.sub, fontSize = 16.sp, modifier = Modifier.padding(bottom = 7.dp))
                }
                Spacer(Modifier.height(10.dp))
                listOf(
                    "Téléchargements illimités, en audio et en vidéo",
                    "Lecture hors connexion, playlists, paroles karaoké",
                    "Valable 30 jours sur ton compte (Android et iPhone)",
                    "Sans renouvellement automatique : tu paies quand tu veux",
                ).forEach { t ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(Color(0x332F7BFF)), Alignment.Center) { CIcon(Ic.Check, P.blueSoft, 14.dp) }
                        Spacer(Modifier.width(10.dp))
                        Text(t, color = P.text, fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            when (step) {
                "form", "fail" -> {
                    if (step == "fail") {
                        Text(msg, color = P.danger, fontSize = 13.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(10.dp))
                    }
                    Text("Ton numéro Orange Money ou MTN MoMo", color = P.sub, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth().height(56.dp).glass(RoundedCornerShape(18.dp), 0.08f).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("+237", color = P.sub, fontSize = 17.sp)
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f)) {
                            BasicTextField(
                                value = phone, onValueChange = { phone = it.filter { c -> c.isDigit() }.take(9) }, singleLine = true,
                                textStyle = TextStyle(color = P.text, fontSize = 18.sp, letterSpacing = 1.sp), cursorBrush = SolidColor(P.blueSoft),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
                            )
                            if (phone.isEmpty()) Text("6XX XX XX XX", color = Color(0x668D96A3), fontSize = 18.sp)
                        }
                    }
                    val op = Sub.operator(phone)
                    if (op.isNotEmpty()) Text(op, color = P.blueSoft, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    Spacer(Modifier.height(16.dp))
                    BlueButton(if (phone.length == 9) "Payer ${Sub.PRICE}" else "Entre ton numéro", Modifier.fillMaxWidth()) {
                        if (phone.length != 9 || !phone.startsWith("6")) { step = "fail"; msg = "Numéro invalide : 9 chiffres, commençant par 6."; return@BlueButton }
                        step = "wait"; msg = ""
                        scope.launch {
                            val j = Sub.pay(phone)
                            when (j.optString("status")) {
                                "pending" -> { ussd = j.optString("ussd_code"); ref = j.optString("reference") }
                                "bad_phone" -> { step = "fail"; msg = "Numéro invalide : 9 chiffres, commençant par 6." }
                                else -> { step = "fail"; msg = "Paiement impossible pour le moment. " + j.optString("message").take(120) }
                            }
                        }
                    }
                }
                "wait" -> {
                    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp), 0.08f, P.blue).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(36.dp), color = P.blueSoft, strokeWidth = 3.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("Confirme le paiement sur ton téléphone", color = P.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Une demande de ${Sub.PRICE} arrive sur ton téléphone : valide-la avec ton code secret." +
                                if (ussd.isNotBlank()) "\nSi rien n'apparaît, compose $ussd." else "",
                            color = P.sub, fontSize = 13.sp, textAlign = TextAlign.Center,
                        )
                    }
                }
                "ok" -> {
                    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp), 0.08f, P.ok).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(52.dp).clip(CircleShape).background(P.ok), Alignment.Center) { CIcon(Ic.Check, Color.White, 30.dp) }
                        Spacer(Modifier.height(10.dp))
                        Text("Merci ! Ton abonnement est actif", color = P.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("jusqu'au ${Sub.fmtDate(Sub.paidUntil)}", color = P.sub, fontSize = 13.sp)
                        Spacer(Modifier.height(14.dp))
                        BlueButton("Continuer", Modifier.fillMaxWidth()) { Sub.paywall = null }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "Paiement sécurisé par Campay. Conditions générales",
                color = P.sub, fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.clickable {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://stephaneproject237-spec.github.io/tu-me-manque/legal/conditions.html"))) }
                },
            )
        }
    }
}
