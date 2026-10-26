package com.tumemanque.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** Jeux à deux dans le chat. Premier jeu : « Me connais-tu ? ». */

data class GQ(val id: Int, val q: String, val o: List<String>)
data class GRes(val idx: Int, val guess: String, val ok: Boolean, val answer: String)
data class GVerdict(val ok: Boolean, val answer: String, val guess: String, val idx: Int)
data class GRound(
    val id: Long, val state: String, val byMe: Boolean, val qids: List<Int>,
    val myAnswers: Int, val theirAnswers: Int, val myGuesses: Int, val theirGuesses: Int,
    val myList: Map<Int, String>, val myResults: List<GRes>, val theirResults: List<GRes>,
)

private val GPink = Color(0xFFE91E63)
private val GSoft = Color(0xFFFF8FB4)
private val GTxt = Color(0xFFF5EEF2)
private val GSub = Color(0xFFB7A3AE)
private val GCard = Color(0xB33A2830)

private fun parseRes(a: JSONArray?): List<GRes> {
    if (a == null) return emptyList()
    return List(a.length()) {
        val o = a.getJSONObject(it)
        GRes(o.optInt("idx"), o.optString("guess"), o.optBoolean("ok"), o.optString("answer"))
    }
}

private fun parseRound(o: JSONObject?): GRound? {
    if (o == null) return null
    val q = o.optJSONArray("qids") ?: JSONArray()
    val l = o.optJSONArray("my_answer_list") ?: JSONArray()
    val map = HashMap<Int, String>()
    for (i in 0 until l.length()) { val x = l.getJSONObject(i); map[x.optInt("idx")] = x.optString("answer") }
    return GRound(
        id = o.optLong("id"), state = o.optString("state"), byMe = o.optBoolean("by_me"),
        qids = List(q.length()) { q.getInt(it) },
        myAnswers = o.optInt("my_answers"), theirAnswers = o.optInt("their_answers"),
        myGuesses = o.optInt("my_guesses"), theirGuesses = o.optInt("their_guesses"),
        myList = map, myResults = parseRes(o.optJSONArray("my_results")), theirResults = parseRes(o.optJSONArray("their_results")),
    )
}

class GameCtl(private val scope: CoroutineScope, private val ctx: Context, private val chatSay: (String) -> Unit) {
    var round by mutableStateOf<GRound?>(null)
    var bank by mutableStateOf<Map<Int, GQ>>(emptyMap())
    var overlay by mutableStateOf(false)
    var menu by mutableStateOf(false)
    var verdict by mutableStateOf<GVerdict?>(null)
    var editing by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)
    var fx by mutableIntStateOf(0)
    var fxOk by mutableStateOf(true)
    var partner = ""
    val draft = mutableStateListOf("", "", "", "", "", "", "", "", "", "")
    private var busy = false

    init {
        scope.launch(Dispatchers.IO) {
            try {
                val txt = ctx.assets.open("questions.json").bufferedReader().use { it.readText() }
                val a = JSONArray(txt)
                val m = HashMap<Int, GQ>()
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val oa = o.getJSONArray("o")
                    m[o.getInt("id")] = GQ(o.getInt("id"), o.getString("q"), List(oa.length()) { k -> oa.getString(k) })
                }
                bank = m
            } catch (e: Exception) { /* les questions s'afficheront en « … » */ }
        }
    }

    fun q(i: Int): GQ = bank[round?.qids?.getOrNull(i) ?: -1] ?: GQ(0, "…", listOf("", "", "", ""))

    /** Texte de la réponse numéro [v] (0 à 3) de la question [i]. */
    fun opt(i: Int, v: String): String = q(i).o.getOrNull(v.toIntOrNull() ?: -1) ?: v

    suspend fun refresh() {
        if (busy) return
        val j = Backend.gameCall("game_state") ?: return
        if (j.optString("status") != "ok") return
        apply(parseRound(j.optJSONObject("round")))
    }

    private fun apply(r: GRound?) {
        val prev = round
        if (r != null && prev?.id != r.id) {
            for (i in 0 until 10) draft[i] = ""
            verdict = null; editing = false
        }
        if (prev != null && prev.state == "invite" && prev.byMe && r == null && overlay && !menu) {
            toast = "@$partner ne souhaite pas jouer pour l'instant."
            menu = true
        }
        round = r
    }

    fun openOverlay() {
        val r = round
        menu = r == null
        overlay = true
    }
    fun closeOverlay() { overlay = false; menu = false }
    fun showMenu() { menu = true }

    private fun call(name: String, args: JSONObject = JSONObject(), after: (JSONObject) -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            val j = Backend.gameCall(name, args)
            busy = false
            if (j == null) { toast = "Connexion impossible. Réessaie."; return@launch }
            if (j.optString("status") == "no_partner") { toast = "Vous n'êtes plus partenaires."; return@launch }
            if (j.has("round") && j.optString("status") == "ok") apply(parseRound(j.optJSONObject("round")))
            after(j)
        }
    }

    fun invite() {
        val n = if (bank.isNotEmpty()) bank.size else 664
        call("game_invite", JSONObject().put("p_total", n)) { j ->
            if (j.optString("status") == "ok") {
                menu = false
                chatSay("🎮 Je te propose de jouer à « Me connais-tu ? » — réponds Oui ou Non juste au-dessus du message !")
            }
        }
    }
    fun respond(yes: Boolean) {
        val r = round ?: return
        call("game_respond", JSONObject().put("p_round", r.id).put("p_accept", yes)) { if (yes) openOverlay() }
    }
    fun cancel() {
        val r = round ?: return
        call("game_cancel", JSONObject().put("p_round", r.id)) { menu = true }
    }
    fun edit() {
        val r = round ?: return
        for (i in 0 until 10) draft[i] = r.myList[i] ?: ""
        editing = true
    }
    fun saveAnswers() {
        val r = round ?: return
        val arr = JSONArray()
        for (i in 0 until 10) {
            if (draft[i].isBlank()) { toast = "Choisis une réponse à chaque question."; return }
            arr.put(draft[i])
        }
        call("game_save_answers", JSONObject().put("p_round", r.id).put("p_answers", arr)) { j ->
            when (j.optString("status")) {
                "locked" -> toast = "@$partner a déjà commencé à deviner : tes réponses sont figées."
                "ok" -> { editing = false; toast = "Tes réponses sont enregistrées 💗" }
            }
        }
    }
    fun guess(raw: String) {
        val r = round ?: return
        val i = r.myGuesses
        val v = raw
        if (v.isBlank()) { toast = "Choisis une réponse."; return }
        call("game_guess", JSONObject().put("p_round", r.id).put("p_idx", i).put("p_guess", v)) { j ->
            if (j.optString("status") == "ok") {
                val ok = j.optBoolean("ok")
                verdict = GVerdict(ok, j.optString("answer"), v, i)
                round = round?.copy(myGuesses = i + 1)
                fxOk = ok; fx += 1
                scope.launch { delay(600); refresh() }
            }
        }
    }
    fun next() { verdict = null }
}

// ---------- Pastille dans le chat ----------
@Composable
private fun GPill(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (on) Brush.linearGradient(listOf(Color(0xFFFF4F86), Color(0xFFD81B60))) else Brush.linearGradient(listOf(Color(0xB3281C24), Color(0xB3281C24))))
            .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(50))
            .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(text, color = if (on) Color.White else GSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun GameChip(ctl: GameCtl, partner: String) {
    ctl.partner = partner
    val r = ctl.round
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (r == null) GPill("🎮 Veux-tu jouer ?", false) { ctl.openOverlay() }
        else if (r.state == "invite" && r.byMe) GPill("⏳ Invitation envoyée…", false) { ctl.openOverlay() }
        else if (r.state == "invite") {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xCC281C24))
                    .border(1.dp, Color(0x73E91E63), RoundedCornerShape(18.dp)).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🎮 @$partner te propose « Me connais-tu ? »", color = GTxt, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                GPill("Oui", true) { ctl.respond(true) }
                Spacer(Modifier.width(6.dp))
                GPill("Non", false) { ctl.respond(false) }
            }
        } else if (r.state == "playing") GPill("🎮 Partie en cours — continuer", true) { ctl.openOverlay() }
        else GPill("🎮 Voir les résultats", false) { ctl.openOverlay() }
    }
}

// ---------- Plein écran ----------
@Composable
fun GameOverlay(ctl: GameCtl, partner: String) {
    BackHandler { ctl.closeOverlay() }
    LaunchedEffect(ctl.toast) {
        if (ctl.toast != null) { delay(2600); ctl.toast = null }
    }
    Box(
        Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF1A0D16), Color(0xFF26122A), Color(0xFF120F1C))))
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color(0xB3281C24)).border(1.dp, Color(0x1AFFFFFF), CircleShape).clickable { ctl.closeOverlay() },
                    contentAlignment = Alignment.Center,
                ) { Ico(R.drawable.ic_close, GPink, 22.dp) }
                Text("🎮 Jeux", color = GTxt, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(Modifier.width(44.dp))
            }
            Column(Modifier.weight(1f).fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp)) {
                GameBody(ctl, partner)
                Spacer(Modifier.height(30.dp))
            }
        }
        Confetti(ctl.fx, ctl.fxOk)
        ctl.toast?.let { t ->
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(bottom = 24.dp), Alignment.BottomCenter) {
                Text(t, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xCC000000)).padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun GBtn(text: String, ghost: Boolean = false, wide: Boolean = false, onClick: () -> Unit) {
    Box(
        (if (wide) Modifier.fillMaxWidth() else Modifier).clip(RoundedCornerShape(50))
            .background(if (ghost) Brush.linearGradient(listOf(Color(0x14FFFFFF), Color(0x14FFFFFF))) else Brush.linearGradient(listOf(Color(0xFFFF4F86), Color(0xFFD81B60))))
            .clickable { onClick() }.padding(horizontal = 22.dp, vertical = if (wide) 15.dp else 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (ghost) GTxt else Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
}

@Composable
private fun GOptions(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { k, t ->
            val on = selected == k.toString()
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (on) Brush.linearGradient(listOf(Color(0x59FF4886), Color(0x4DD6185C))) else Brush.linearGradient(listOf(Color(0x0FFFFFFF), Color(0x0FFFFFFF))))
                    .border(1.dp, if (on) Color(0xFFFF4F86) else Color(0x1FFFFFFF), RoundedCornerShape(14.dp))
                    .clickable { onSelect(k.toString()) }.padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(if (on) GPink else Color(0x1AFFFFFF)), Alignment.Center) {
                    Text("ABCD"[k % 4].toString(), color = if (on) Color.White else GSoft, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text(t, color = GTxt, fontSize = 15.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GameBody(ctl: GameCtl, partner: String) {
    val r = ctl.round
    val v = ctl.verdict
    if (ctl.bank.isEmpty()) { Box(Modifier.fillMaxWidth().padding(60.dp), Alignment.Center) { Text("…", color = GSub) }; return }
    if (v != null) { GVerdictView(ctl, v); return }
    if (ctl.menu || r == null || r.state == "cancelled") { GMenu(ctl, partner); return }
    when (r.state) {
        "invite" -> GInvite(ctl, r, partner)
        "playing" -> GPlay(ctl, r, partner)
        else -> GDone(ctl, r, partner)
    }
}

@Composable
private fun GCenter(emoji: String, title: String, text: String, content: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 64.sp)
        Spacer(Modifier.height(8.dp))
        Text(title, color = GTxt, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(text, color = GSub, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        content()
    }
}

@Composable
private fun GMenu(ctl: GameCtl, partner: String) {
    Text("Choisis un jeu à lancer avec @$partner. De nouveaux jeux arrivent bientôt !", color = GSub, fontSize = 14.sp)
    Spacer(Modifier.height(12.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(GCard).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(22.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("💞", fontSize = 34.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Me connais-tu ?", color = GTxt, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("10 questions sur toi, 10 sur lui/elle. Vous répondez chacun de votre côté, puis vous devinez les réponses de l'autre.", color = GSub, fontSize = 13.sp)
        }
        Spacer(Modifier.width(10.dp))
        GBtn("Jouer") { ctl.invite() }
    }
    Spacer(Modifier.height(12.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0x663A2830)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🎲", fontSize = 34.sp)
        Spacer(Modifier.width(12.dp))
        Column { Text("Prochain jeu", color = GSub, fontWeight = FontWeight.Bold); Text("Bientôt…", color = GSub, fontSize = 13.sp) }
    }
}

@Composable
private fun GInvite(ctl: GameCtl, r: GRound, partner: String) {
    if (r.byMe) {
        GCenter("💌", "Invitation envoyée", "On attend que @$partner accepte de jouer à « Me connais-tu ? ».") {
            GBtn("Annuler", ghost = true) { ctl.cancel() }
        }
    } else {
        GCenter("🎮", "@$partner veut jouer !", "« Me connais-tu ? » — 10 questions pour voir à quel point vous vous connaissez.") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GBtn("Oui, on joue !") { ctl.respond(true) }
                GBtn("Non merci", ghost = true) { ctl.respond(false) }
            }
        }
    }
}

@Composable
private fun GPlay(ctl: GameCtl, r: GRound, partner: String) {
    if (r.myAnswers < 10 || ctl.editing) {
        Text("Étape 1 sur 2", color = GSoft, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("Tes réponses", color = GTxt, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Choisis la réponse qui te correspond. @$partner devra deviner laquelle tu as choisie, il/elle ne la verra pas avant.", color = GSub, fontSize = 14.sp)
        for (i in 0 until 10) {
            val q = ctl.q(i)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 2.dp).size(26.dp).clip(CircleShape).background(Color(0x40E91E63)), Alignment.Center) {
                    Text("${i + 1}", color = GSoft, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(q.q, color = GTxt, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    GOptions(q.o, ctl.draft[i]) { ctl.draft[i] = it }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        GBtn("Valider mes réponses", wide = true) { ctl.saveAnswers() }
        return
    }
    if (r.theirAnswers < 10) {
        GCenter("⏳", "Tes réponses sont prêtes", "On attend que @$partner réponde à ses 10 questions (${r.theirAnswers}/10).") {
            GBtn("Modifier mes réponses", ghost = true) { ctl.edit() }
        }
        return
    }
    val i = r.myGuesses
    if (i >= 10) {
        val sc = r.myResults.count { it.ok }
        GCenter(if (sc >= 7) "🥰" else if (sc >= 4) "🙂" else "😅", "Tu as trouvé $sc / 10", "On attend que @$partner devine tes réponses (${r.theirGuesses}/10).")
        return
    }
    var text by remember(i) { mutableStateOf("") }
    Text("Étape 2 sur 2 · Question ${i + 1} / 10", color = GSoft, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    GBar(i / 10f)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(GCard).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(24.dp)).padding(16.dp)) {
        Text("@$partner a répondu à :", color = GSub, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Text("« ${ctl.q(i).q} »", color = GTxt, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("Que penses-tu qu'il/elle a choisi ?", color = GSub, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        GOptions(ctl.q(i).o, text) { text = it }
    }
    Spacer(Modifier.height(18.dp))
    GBtn("Valider", wide = true) { ctl.guess(text) }
}

@Composable
private fun GBar(p: Float) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(8.dp).clip(RoundedCornerShape(50)).background(Color(0x1AFFFFFF))) {
        Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(Color(0xFFFF4F86), Color(0xFFFFB3CC)))))
    }
}

@Composable
private fun GVerdictView(ctl: GameCtl, v: GVerdict) {
    val sc = remember(v) { Animatable(0.3f) }
    val sh = remember(v) { Animatable(0f) }
    val dens = LocalDensity.current.density
    LaunchedEffect(v) {
        sc.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium))
    }
    LaunchedEffect(v) {
        if (!v.ok) for (k in listOf(-14f, 12f, -8f, 5f, 0f)) sh.animateTo(k, tween(60))
    }
    val last = v.idx + 1 >= 10
    Text("Question ${v.idx + 1} / 10", color = GSoft, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    GBar((v.idx + 1) / 10f)
    Column(
        Modifier.fillMaxWidth().graphicsLayer { scaleX = sc.value; scaleY = sc.value; translationX = sh.value * dens }
            .clip(RoundedCornerShape(26.dp))
            .background(if (v.ok) Color(0x66287846) else Color(0x66963236))
            .padding(horizontal = 16.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(if (v.ok) Color(0xFF2E9E5B) else Color(0xFFD6334A)), Alignment.Center) {
            Text(if (v.ok) "✓" else "✗", color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.height(10.dp))
        Text(if (v.ok) "Bravo, c'est vrai !" else "Raté… c'est faux", color = GTxt, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("« ${ctl.q(v.idx).q} »", color = Color(0xFFE3D6DD), fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        GCmp("Ta réponse", ctl.opt(v.idx, v.guess), GTxt)
        if (!v.ok) { Spacer(Modifier.height(8.dp)); GCmp("La bonne réponse", ctl.opt(v.idx, v.answer), Color(0xFF7BE0A3)) }
    }
    Spacer(Modifier.height(18.dp))
    GBtn(if (last) "Voir mon score" else "Question suivante", wide = true) { ctl.next() }
}

@Composable
private fun GCmp(label: String, value: String, color: Color) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x38000000)).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, color = GSub, fontSize = 12.sp)
        Text(value, color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GDone(ctl: GameCtl, r: GRound, partner: String) {
    val ms = r.myResults.count { it.ok }
    val ts = r.theirResults.count { it.ok }
    val msg = if (ms > ts) "Tu connais @$partner mieux qu'il/elle ne te connaît 😏" else if (ms < ts) "@$partner te connaît mieux 💗" else "Égalité parfaite, vous êtes faits l'un pour l'autre 💞"
    LaunchedEffect(r.id) { ctl.fxOk = true; ctl.fx += 1 }
    Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🏆", fontSize = 70.sp)
        Text("Résultats", color = GTxt, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            GScore(ms, "Toi")
            GScore(ts, "@$partner")
        }
        Spacer(Modifier.height(8.dp))
        Text(msg, color = GSub, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GBtn("Rejouer 🔁") { ctl.invite() }
            GBtn("Autres jeux", ghost = true) { ctl.showMenu() }
        }
    }
    GRecap(ctl, "Ce que tu as deviné sur @$partner", r.myResults)
    GRecap(ctl, "Ce que @$partner a deviné sur toi", r.theirResults)
}

@Composable
private fun GScore(n: Int, label: String) {
    val sc = remember(n) { Animatable(0.3f) }
    LaunchedEffect(n) { sc.animateTo(1f, spring(dampingRatio = 0.4f)) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.graphicsLayer { scaleX = sc.value; scaleY = sc.value }) {
            Text("$n", color = Color(0xFFFF6A9C), fontSize = 54.sp, fontWeight = FontWeight.Bold)
            Text("/10", color = GSub, fontSize = 16.sp, modifier = Modifier.padding(bottom = 10.dp))
        }
        Text(label, color = Color(0xFFE3D6DD), fontSize = 13.sp)
    }
}

@Composable
private fun GRecap(ctl: GameCtl, title: String, list: List<GRes>) {
    Spacer(Modifier.height(20.dp))
    Text(title, color = GSoft, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    for (x in list) {
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x0DFFFFFF)).padding(10.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(if (x.ok) Color(0xFF2E9E5B) else Color(0xFFD6334A)), Alignment.Center) {
                Text(if (x.ok) "✓" else "✗", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(ctl.q(x.idx).q, color = GSub, fontSize = 13.sp)
                Text(if (x.ok) ctl.opt(x.idx, x.answer) else "${ctl.opt(x.idx, x.guess)} → ${ctl.opt(x.idx, x.answer)}", color = GTxt, fontSize = 15.sp)
            }
        }
    }
}

// ---------- Animation de cœurs / cœurs brisés ----------
@Composable
fun Confetti(trigger: Int, ok: Boolean) {
    if (trigger == 0) return
    val emos = if (ok) listOf("💗", "✨", "💖", "🎉", "💕") else listOf("💔", "😅")
    val n = if (ok) 22 else 9
    val seeds = remember(trigger) { List(n) { Triple(Random.nextFloat(), Random.nextFloat() * 0.35f, 18 + Random.nextInt(20)) } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = constraints.maxHeight
        val w = constraints.maxWidth
        seeds.forEachIndexed { k, s ->
            val p = remember(trigger, k) { Animatable(0f) }
            LaunchedEffect(trigger, k) {
                delay((s.second * 1000).toLong())
                p.animateTo(1f, tween(1800))
            }
            if (p.value > 0f && p.value < 1f) {
                Text(
                    emos[k % emos.size], fontSize = s.third.sp,
                    modifier = Modifier
                        .offset { IntOffset((s.first * w * 0.9f).toInt(), (if (ok) h * 0.8f - p.value * h * 0.6f else h * 0.15f + p.value * h * 0.55f).toInt()) }
                        .graphicsLayer { alpha = 1f - p.value * 0.8f },
                )
            }
        }
    }
}
