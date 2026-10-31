package com.tumemanque.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Première page de « Casque » : le lecteur audio, et ses paramètres. */
/** Entretien invisible du moteur de téléchargement (mise à jour automatique). */
suspend fun engineMaintenance(ctx: android.content.Context) { HiddenServers.ensure(ctx); Yt.maintainNow(ctx) }

@Composable
fun CasqueMain(screen: String, updateVm: UpdateViewModel, hasUpdate: Boolean, username: String?, onScreen: (String) -> Unit) {
    val ctx = LocalContext.current
    remember { Hub.init(ctx); true }
    LaunchedEffect(Unit) { runCatching { Sponsor.fromClipboard(ctx.applicationContext) } }
    PipHost()
    Box(Modifier.fillMaxSize().background(P.bg)) {
        if (screen == "settings") {
            CasqueSettings(updateVm, username, onHelp = { onScreen("help") }, onBack = { onScreen("player") })
        } else {
            CasqueShell(onSettings = { onScreen("settings") }, hasUpdate = hasUpdate)
        }
        // Fenêtre flottante d'Android : on ne montre que la vidéo (le reste de l'écran reste en mémoire).
        if (Vp.inPip) PipScreen()
        else {
            // Abonnement : rappel avant la fin, écran de paiement quand l'essai ou l'abonnement est terminé.
            if (Sub.paywall == null) SubReminder()
            Paywall()
        }
    }
    LaunchedEffect(Unit) { while (true) { Sub.refresh(); kotlinx.coroutines.delay(60_000L) } }
}

@Composable
private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.card).padding(18.dp), content = content)
}

@Composable
fun CasqueSettings(upd: UpdateViewModel, username: String?, onHelp: () -> Unit, onBack: () -> Unit) {
    val s by upd.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scan by Lib.scan.collectAsState()
    val tracks by Lib.tracks.collectAsState()
    val sources by Lib.sources.collectAsState()
    BackHandler { onBack() }
    LaunchedEffect(Unit) { if (!s.checked) upd.check(context = ctx) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scope.launch { Lib.scanPhone(ctx) }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundBtn(Ic.Back, onBack)
            Spacer(Modifier.width(14.dp))
            Text("Paramètres", color = P.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))

        // ------------------------------------------------------------ version et mise à jour
        SettingsCard {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !s.checking && !s.downloading) { upd.check(context = ctx) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(P.card2), contentAlignment = Alignment.Center) { CIcon(Ic.Update, P.blueSoft, 22.dp) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Version de l'application", color = P.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Version ${BuildConfig.VERSION_NAME}", color = P.sub, fontSize = 14.sp)
                }
                if (s.checking) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = P.blue)
                else Text("Vérifier", color = P.blueSoft, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            val info = s.available
            when {
                s.downloading -> {
                    Text("Téléchargement de la version ${info?.name ?: ""}… ${(s.progress * 100).toInt()} %", color = P.text, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(), color = P.blue, trackColor = P.card2)
                }
                s.ready && info != null -> {
                    Text(
                        if (s.needPermission) "Autorise l'installation pour « Free tune », puis reviens ici et touche « Installer »."
                        else "La version ${info.name} est prête à être installée.",
                        color = P.text, fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    BlueButton("Installer", Modifier.fillMaxWidth()) { upd.install(ctx) }
                }
                info != null -> {
                    Text("La version ${info.name} est disponible", color = P.blueSoft, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if (info.notes.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(info.notes, color = P.sub, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    BlueButton("Mettre à jour maintenant", Modifier.fillMaxWidth(), Ic.Update) { upd.update(ctx) }
                }
                s.error -> {
                    Text("Impossible de vérifier pour le moment. Vérifie ta connexion.", color = P.danger, fontSize = 14.sp)
                    if (s.errorDetail.isNotBlank()) Text(s.errorDetail, color = P.sub, fontSize = 11.sp)
                }
                s.checking -> Text("Vérification en cours…", color = P.sub, fontSize = 14.sp)
                s.checked -> Text("Tu as la dernière version.", color = P.ok, fontSize = 14.sp)
                else -> Text("Touche « Vérifier » pour chercher une mise à jour.", color = P.sub, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(14.dp))

        // ------------------------------------------------------------ bibliothèque
        SettingsCard {
            Text("Bibliothèque", color = P.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${tracks.size} morceau(x) · ${sources.size} dossier(s) source",
                color = P.sub, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
            if (scan.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = P.blue, trackColor = P.card2)
                Text(scan.message, color = P.sub, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            } else if (scan.message.isNotEmpty()) {
                Text(scan.message, color = P.sub, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
            BlueButton("Scanner tout le téléphone", Modifier.fillMaxWidth(), Ic.Search) {
                if (Lib.hasPermission(ctx)) scope.launch { Lib.scanPhone(ctx) } else permLauncher.launch(Lib.permission())
            }
        }
        Spacer(Modifier.height(14.dp))

        // ------------------------------------------------------------ aider les amis iPhone
        SettingsCard {
            var help by remember { mutableStateOf(Relay.enabled(ctx)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Aider mes amis iPhone", color = P.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                androidx.compose.material3.Switch(checked = help, onCheckedChange = { help = it; Relay.setEnabled(ctx, it) })
            }
        }
        Spacer(Modifier.height(14.dp))

        // ------------------------------------------------------------ où sont les fichiers
        SettingsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(P.card2), contentAlignment = Alignment.Center) { CIcon(Ic.Download, P.blueSoft, 22.dp) }
                Spacer(Modifier.width(14.dp))
                Text("Mes téléchargements", color = P.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Les audios sont enregistrés dans Musique/Casque, les vidéos restent dans l'application (elles n'apparaissent pas dans la galerie).",
                color = P.sub, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp),
            )
        }
        Spacer(Modifier.height(14.dp))

        // ------------------------------------------------------------ vibrations + restauration
        SettingsCard {
            var ticks by remember { mutableStateOf(Feel.enabled(ctx)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Vibrations", color = P.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("De petits clics, comme un engrenage, quand tu fais défiler les résultats, et une légère vibration quand tu lances une lecture.", color = P.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                }
                androidx.compose.material3.Switch(checked = ticks, onCheckedChange = { ticks = it; Feel.setEnabled(ctx, it) })
            }
        }
        Spacer(Modifier.height(14.dp))
        SettingsCard {
            var sum by remember { mutableStateOf<MediaSummary?>(null) }
            var asking by remember { mutableStateOf(false) }
            var busy by remember { mutableStateOf(false) }
            var info by remember { mutableStateOf("") }
            Text("Restaurer mes médias", color = P.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Tout ce que tu télécharges est noté dans ton compte. Sur un nouveau téléphone, retrouve tes musiques, tes vidéos et tes playlists.",
                color = P.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            if (info.isNotEmpty()) Text(info, color = P.blueSoft, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
            BlueButton(if (busy) "Un instant…" else "Restaurer mes médias", Modifier.fillMaxWidth(), Ic.Download) {
                if (busy) return@BlueButton
                busy = true; info = ""
                scope.launch {
                    val r = MediaSync.summary()
                    busy = false
                    if (r == null) info = "Impossible de joindre ton compte pour l'instant."
                    else if (r.count == 0) info = "Aucun média dans ton compte pour le moment."
                    else { sum = r; asking = true }
                }
            }
            val r = sum
            if (asking && r != null) {
                AlertDialog(
                    onDismissRequest = { asking = false },
                    containerColor = P.card,
                    title = { Text("Restaurer mes médias", color = P.text) },
                    text = { Text("${r.count} médias (${r.audio} audio, ${r.video} vidéo) · environ ${fmtBytes(r.bytes)}.\nLes télécharger de nouveau sur ce téléphone et restaurer tes playlists ?", color = P.sub) },
                    confirmButton = {
                        TextButton({
                            asking = false
                            info = "Restauration lancée : suis-la dans les téléchargements."
                            MediaSync.restore(ctx)
                        }) { Text("Oui, télécharger", color = P.blueSoft) }
                    },
                    dismissButton = { TextButton({ asking = false }) { Text("Non", color = P.sub) } },
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        // ------------------------------------------------------------ parrainage
        SettingsCard {
            Text("Envoyer l'application", color = P.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Envoie à tes amis un lien : il ouvre une page où ils téléchargent Free tune (toujours la dernière version), pour Android ou iPhone. Ils sauront que c'est toi qui les as invités.",
                color = P.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            BlueButton("Envoyer l'application", Modifier.fillMaxWidth(), Ic.Download) {
                if (!username.isNullOrBlank()) Sponsor.share(ctx, username)
            }
            Spacer(Modifier.height(18.dp))
            var typed by remember { mutableStateOf("") }
            var msg by remember { mutableStateOf("") }
            LaunchedEffect(Unit) { Sponsor.refresh() }
            Text("Parrain", color = P.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            val sp = Sponsor.name
            if (sp != null) {
                Text("@$sp", color = P.blueSoft, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
            } else if (Sponsor.loaded) {
                Text("Tu n'as pas de parrain. Si quelqu'un t'a fait découvrir Free tune, écris son pseudo :", color = P.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.card2).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    if (typed.isEmpty()) Text("Pseudo du parrain", color = P.sub, fontSize = 15.sp)
                    BasicTextField(
                        value = typed, onValueChange = { typed = it.take(30) }, singleLine = true,
                        textStyle = TextStyle(color = P.text, fontSize = 15.sp), cursorBrush = SolidColor(P.blueSoft),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                BlueButton("Valider", Modifier.fillMaxWidth(), outlined = true) {
                    if (typed.isNotBlank()) scope.launch { msg = Sponsor.set(typed) }
                }
                if (msg.isNotEmpty()) Text(msg, color = P.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Spacer(Modifier.height(14.dp))

        // ------------------------------------------------------------ cagnotte de parrainage
        SettingsCard {
            var ref by remember { mutableStateOf<JSONObject?>(null) }
            var form by remember { mutableStateOf(false) }
            var ph by remember { mutableStateOf("") }
            var nm by remember { mutableStateOf("") }
            var rmsg by remember { mutableStateOf("") }
            LaunchedEffect(Unit) { while (true) { ref = Backend.refGet() ?: ref; delay(15_000) } }
            Text("Ma cagnotte", color = P.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            val wallet = ref?.optInt("wallet") ?: 0
            Text("$wallet FCFA", color = P.blueSoft, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
            Text(
                "Tu gagnes 100 FCFA quand un ami que tu as invité paie son premier abonnement." +
                    (ref?.let { "\n${it.optInt("filleuls")} filleul(s) · ${it.optInt("payes")} ont payé" } ?: ""),
                color = P.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            val pend = ref?.optJSONObject("pending")
            val last = ref?.optJSONObject("last")
            if (pend != null) {
                Text("⏳ Retrait de ${pend.optInt("amount")} FCFA demandé pour le ${pend.optString("phone")} (${pend.optString("name")}). Il sera envoyé bientôt.", color = P.text, fontSize = 14.sp)
            } else if (wallet >= 500) {
                if (!form) {
                    BlueButton("Retirer ma cagnotte", Modifier.fillMaxWidth()) { form = true; rmsg = "" }
                } else {
                    Text("Numéro qui recevra l'argent :", color = P.sub, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.card2).padding(horizontal = 14.dp, vertical = 12.dp)) {
                        if (ph.isEmpty()) Text("6XXXXXXXX", color = P.sub, fontSize = 15.sp)
                        BasicTextField(value = ph, onValueChange = { ph = it.filter { c -> c.isDigit() }.take(12) }, singleLine = true,
                            textStyle = TextStyle(color = P.text, fontSize = 15.sp), cursorBrush = SolidColor(P.blueSoft), modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Nom qui doit apparaître lors de la transaction :", color = P.sub, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.card2).padding(horizontal = 14.dp, vertical = 12.dp)) {
                        if (nm.isEmpty()) Text("Nom et prénom", color = P.sub, fontSize = 15.sp)
                        BasicTextField(value = nm, onValueChange = { nm = it.take(60) }, singleLine = true,
                            textStyle = TextStyle(color = P.text, fontSize = 15.sp), cursorBrush = SolidColor(P.blueSoft), modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(10.dp))
                    BlueButton("Valider la demande", Modifier.fillMaxWidth()) {
                        scope.launch {
                            rmsg = when (Backend.payoutRequest(ph, nm)) {
                                "ok" -> { form = false; ref = Backend.refGet() ?: ref; "" }
                                "bad_phone" -> "Numéro invalide : 9 chiffres, qui commencent par 6."
                                "bad_name" -> "Écris le nom qui apparaît lors de la transaction."
                                "too_low" -> "Il faut au moins 500 FCFA."
                                "already" -> "Une demande est déjà en attente."
                                else -> "Impossible pour le moment. Vérifie ta connexion."
                            }
                        }
                    }
                    if (rmsg.isNotEmpty()) Text(rmsg, color = P.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
            } else {
                Text("Retrait possible à partir de 500 FCFA.", color = P.sub, fontSize = 13.sp)
            }
            if (last != null && pend == null) {
                Spacer(Modifier.height(10.dp))
                Text("✅ Dépôt effectué : ${last.optInt("amount")} FCFA envoyés au ${last.optString("phone")}.", color = P.text, fontSize = 14.sp)
                val pr = last.optString("proof")
                if (pr.isNotBlank() && pr != "null") {
                    Spacer(Modifier.height(6.dp))
                    BlueButton("Voir la preuve de paiement", Modifier.fillMaxWidth(), outlined = true) {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Config.SUPABASE_URL + "/storage/v1/object/public/chat/" + pr)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        // ------------------------------------------------------------ à propos
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.card).clickable { onHelp() }.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(Brush.linearGradient(listOf(P.blue, P.blueDeep))),
                contentAlignment = Alignment.Center,
            ) { CIcon(Ic.Chat, Color.White, 22.dp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Tchat", color = P.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            CIcon(Ic.ChevronRight, P.sub, 22.dp)
        }
        Spacer(Modifier.height(24.dp))
        Text("Free tune · version ${BuildConfig.VERSION_NAME}", color = P.sub, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

// =============================================================================================
//  Aide = messagerie : liste d'amis, demandes, conversations
// =============================================================================================

private data class Friend(
    val id: String, val name: String, val phone: String?, val unread: Int,
    val lastBody: String?, val lastKind: String?, val lastAt: String?, val lastMine: Boolean, val selected: Boolean,
)

private fun parseFriends(text: String): List<Friend> {
    val a = JSONArray(text)
    return (0 until a.length()).map {
        val o = a.getJSONObject(it)
        fun s(k: String): String? = if (o.isNull(k)) null else o.optString(k)
        Friend(
            o.getString("id"), o.optString("username"), s("phone"), o.optInt("unread"),
            s("last_body"), s("last_kind"), s("last_at"), o.optBoolean("last_mine"), o.optBoolean("selected"),
        )
    }
}

private fun preview(f: Friend): String {
    val base = when (f.lastKind) {
        null -> "Dis bonjour !"
        "text" -> f.lastBody ?: ""
        "image" -> "Photo"
        "voice" -> "Message vocal"
        "video" -> "Vidéo"
        "gif" -> "GIF"
        "sticker" -> "Sticker"
        else -> "Message"
    }
    return if (f.lastMine && f.lastKind != null) "Toi : $base" else base
}

private fun timeLabel(iso: String?): String {
    if (iso.isNullOrEmpty()) return ""
    return runCatching {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        val d = fmt.parse(iso.substringBefore('.').substringBefore('+').substringBefore('Z')) ?: return ""
        val now = java.util.Calendar.getInstance()
        val c = java.util.Calendar.getInstance().apply { time = d }
        // l'heure du serveur peut avoir un décalage : on affiche seulement jour ou heure locale approximative
        if (now.get(java.util.Calendar.DAY_OF_YEAR) == c.get(java.util.Calendar.DAY_OF_YEAR) && now.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR))
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(d)
        else java.text.SimpleDateFormat("d MMM", java.util.Locale.FRANCE).format(d)
    }.getOrDefault("")
}

@Composable
fun CasqueFriends(
    state: UiState, vm: AppViewModel, chatVm: ChatViewModel,
    onBack: () -> Unit, onInvite: () -> Unit, onOpenChat: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var friends by remember { mutableStateOf<List<Friend>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<Friend?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        runCatching { parseFriends(Backend.callRaw("POST", "/rest/v1/rpc/friends_list", "{}")) }
            .onSuccess { friends = it; loaded = true }
    }
    LaunchedEffect(Unit) { while (true) { load(); delay(3500) } }
    BackHandler { onBack() }

    fun open(f: Friend) {
        scope.launch {
            openError = null
            if (!f.selected) {
                chatVm.reset()
                val r = Backend.gameCall("select_peer", JSONObject().put("p_id", f.id))
                if (r?.optString("status") != "ok") { openError = "Impossible d'ouvrir cette conversation."; return@launch }
                vm.refresh()
            }
            onOpenChat()
        }
    }

    fun label(f: Friend): String = Contacts.key(f.phone)?.let { state.contacts[it] } ?: "@${f.name}"

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundBtn(Ic.Back, onBack)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Tchat", color = P.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (!state.username.isNullOrEmpty()) Text("Mon pseudo : @${state.username}", color = P.sub, fontSize = 13.sp)
            }
            RoundBtn(Ic.Phone, onInvite, tint = P.blueSoft)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            // demandes reçues
            items(state.incoming, key = { "in${it.id}" }) { req ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(P.card).padding(16.dp)) {
                    Text("@${req.fromName} veut devenir ton ami", color = P.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BlueButton("Accepter", Modifier.weight(1f)) { vm.respond(req.id, true) }
                        BlueButton("Refuser", Modifier.weight(1f), outlined = true) { vm.respond(req.id, false) }
                    }
                }
            }
            // ajout d'un ami
            item(key = "add") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(P.card).padding(horizontal = 14.dp, vertical = 14.dp)) {
                            BasicTextField(
                                query, { query = it.lowercase().filter { c -> c in 'a'..'z' || c in '0'..'9' || c in "_+ ()-" }.take(24) }, singleLine = true,
                                textStyle = TextStyle(color = P.text, fontSize = 16.sp), cursorBrush = SolidColor(P.blue),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (query.isEmpty()) Text("Ajouter un ami : pseudo ou numéro", color = P.sub, fontSize = 15.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        RoundBtn(Ic.Add, { if (query.trim().length >= 3 && !state.busy) { vm.addPartner(query); query = "" } }, size = 50.dp, fill = P.blue, tint = Color.White)
                    }
                    state.message?.let { Text(it, color = P.blueSoft, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                    openError?.let { Text(it, color = P.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                    state.outgoing.forEach { Text("En attente de @${it.toName}…", color = P.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
            item(key = "title") { SectionTitle("MES AMIS") }
            if (!loaded) item(key = "load") { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = P.blue) } }
            else if (friends.isEmpty()) item(key = "none") {
                Text(
                    "Tu n'as pas encore d'ami. Ajoute quelqu'un avec son pseudo ou son numéro, ou invite-le à installer Free tune.",
                    color = P.sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
            items(friends, key = { it.id }) { f ->
                Row(
                    Modifier.fillMaxWidth().clickable { open(f) }.padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(Brush.linearGradient(listOf(P.card2, P.blueDeep))),
                        contentAlignment = Alignment.Center,
                    ) { Text(label(f).removePrefix("@").take(1).uppercase(), color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(label(f), color = P.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(timeLabel(f.lastAt), color = if (f.unread > 0) P.blueSoft else P.sub, fontSize = 12.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                preview(f), color = if (f.unread > 0) P.text else P.sub, fontSize = 14.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                fontWeight = if (f.unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            if (f.unread > 0) {
                                Box(Modifier.padding(start = 8.dp).clip(CircleShape).background(P.blue).padding(horizontal = 7.dp, vertical = 2.dp)) {
                                    Text("${f.unread}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    RoundBtn(Ic.Delete, { removing = f }, size = 36.dp, iconSize = 18.dp, fill = Color.Transparent, tint = P.sub)
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }

    removing?.let { f ->
        AlertDialog(
            onDismissRequest = { removing = null }, containerColor = P.card2,
            title = { Text("Retirer ${label(f)} ?", color = P.text) },
            text = { Text("Vous ne serez plus amis. La conversation est conservée : elle reviendra si vous redevenez amis.", color = P.sub) },
            confirmButton = {
                TextButton({
                    val id = f.id
                    removing = null
                    scope.launch {
                        Backend.gameCall("remove_friend", JSONObject().put("p_id", id))
                        chatVm.reset()
                        vm.refresh()
                        load()
                    }
                }) { Text("Retirer", color = P.danger) }
            },
            dismissButton = { TextButton({ removing = null }) { Text("Annuler", color = P.sub) } },
        )
    }
}
