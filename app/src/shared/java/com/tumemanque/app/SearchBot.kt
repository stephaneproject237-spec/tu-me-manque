package com.tumemanque.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bot @recherche : n'importe qui pose une question dans le chat, un téléphone cherche la réponse en ligne
 * (calcul, heure, météo, réponses instantanées, Wikipédia) et l'envoie dans la conversation, visible de tous.
 *
 * Messages (kind "text", meta.bot) :
 *  - {t:"sq", q}                         la question (bulle normale)
 *  - {t:"sr", r, q, ti, a, u, src, img, o:[{ti}]}   la réponse (carte au centre) ; r = id de la question ou du choix
 *  - {t:"spk", r, k, ti}                 « voir plutôt… » : une autre piste proposée dans la réponse r
 */
object AskBot {
    data class Answer(val title: String, val text: String, val url: String, val source: String, val img: String, val others: List<String>)

    private val busy = HashSet<Long>()
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ask_bot", Context.MODE_PRIVATE)
    private fun done(ctx: Context, id: Long) = prefs(ctx).getBoolean("h$id", false)
    private fun markDone(ctx: Context, id: Long) { prefs(ctx).edit().putBoolean("h$id", true).apply() }

    fun bot(m: ChatMsg): JSONObject? = ChatBot.bot(m)
    fun isSearch(t: String) = t == "sq" || t == "sr" || t == "spk"

    fun ask(q: String) = ChatBot.send?.invoke("@recherche $q", JSONObject().put("t", "sq").put("q", q.take(200)))
    fun pick(res: ChatMsg, k: Int) {
        val o = bot(res)?.optJSONArray("o")?.optJSONObject(k) ?: return
        ChatBot.send?.invoke("🔎 Voir plutôt : ${o.optString("ti")}", JSONObject().put("t", "spk").put("r", res.id).put("k", k).put("ti", o.optString("ti")))
    }

    /** Le téléphone de celui qui demande répond tout de suite ; l'autre prend le relais au bout de 20 s. */
    fun process(ctx: Context, msgs: List<ChatMsg>, scope: CoroutineScope) {
        val now = System.currentTimeMillis()
        for (m in msgs) {
            if (m.id == 0L || m.pending || m.deleted) continue
            val b = bot(m) ?: continue
            val t = b.optString("t")
            if (t != "sq" && t != "spk") continue
            if (now - m.createdAt > 10 * 60_000L) continue
            if (done(ctx, m.id) || m.id in busy) continue
            if (msgs.any { bot(it)?.let { x -> x.optString("t") == "sr" && x.optLong("r") == m.id } == true }) { markDone(ctx, m.id); continue }
            if (!m.mine && now - m.createdAt < 20_000L) continue
            busy.add(m.id)
            scope.launch {
                try {
                    val q = if (t == "sq") b.optString("q") else b.optString("ti")
                    val a = withContext(Dispatchers.IO) { if (t == "sq") search(q) else wikiPage(b.optString("ti"), "fr") }
                    if (msgs.any { bot(it)?.let { x -> x.optString("t") == "sr" && x.optLong("r") == m.id } == true }) return@launch
                    if (a == null) {
                        ChatBot.send?.invoke("🔎 Je n'ai pas trouvé de réponse fiable à « $q ». Essaie avec d'autres mots.", JSONObject().put("t", "sr").put("r", m.id).put("q", q.take(120)).put("none", true))
                    } else {
                        val o = JSONArray(); a.others.take(3).forEach { o.put(JSONObject().put("ti", it.take(70))) }
                        val meta = JSONObject().put("t", "sr").put("r", m.id).put("q", q.take(120)).put("ti", a.title.take(90)).put("a", a.text.take(480))
                            .put("u", a.url.take(220)).put("src", a.source.take(30)).put("img", a.img.take(260)).put("o", o)
                        ChatBot.send?.invoke("🔎 " + (if (a.title.isNotBlank()) a.title + " : " else "") + a.text.take(300), meta)
                    }
                    markDone(ctx, m.id)
                } catch (_: Exception) {
                } finally { busy.remove(m.id) }
            }
        }
    }

    // ------------------------------------------------------------------ moteur

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "TuMeManques/1.0 (assistant de chat)")
        c.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9")
        return c.inputStream.bufferedReader().use { it.readText() }
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private val QWORDS = Regex(
        "^(dis[- ]moi|peux[- ]tu me dire|sais[- ]tu|est[- ]ce que|qu'?est[- ]ce que?|c'?est quoi|qui est|qui était|qui sont|qui a|quel(le)?s? (est|sont|était|étaient)|quel(le)?s?|combien (de|d')?|comment|pourquoi|où (est|se trouve|sont)?|quand (est|a|a eu lieu)?|explique[- ]moi|définition de|definition de|cherche|trouve)\\s+",
        RegexOption.IGNORE_CASE,
    )
    fun keywords(q: String): String {
        var s = q.trim().trimEnd('?', '!', '.', ' ')
        repeat(3) { s = s.replace(QWORDS, "").trim() }
        s = s.replace(Regex("^(le|la|les|l'|un|une|des|du|de la|de l')\\s*", RegexOption.IGNORE_CASE), "")
        return s.ifBlank { q }
    }

    fun search(q0: String): Answer? {
        val q = q0.trim()
        if (q.isEmpty()) return null
        calc(q)?.let { return it }
        clock(q)?.let { return it }
        if (Regex("m[ée]t[ée]o|quel temps|temp[ée]rature", RegexOption.IGNORE_CASE).containsMatchIn(q)) runCatching { weather(q) }.getOrNull()?.let { return it }
        val kw = keywords(q)
        runCatching { ddgAnswer(q) }.getOrNull()?.let { return it }
        runCatching { wiki(kw, "fr") }.getOrNull()?.let { return it }
        if (kw != q) runCatching { wiki(q, "fr") }.getOrNull()?.let { return it }
        runCatching { ddgAbstract(kw) }.getOrNull()?.let { return it }
        runCatching { wiki(kw, "en") }.getOrNull()?.let { return it }
        return null
    }

    private fun calc(q: String): Answer? {
        val e = q.lowercase().replace("combien font", "").replace("combien fait", "").replace("calcule", "").replace("=", "")
            .replace("×", "*").replace("x", "*").replace("÷", "/").replace(",", ".").trim().trimEnd('?')
        if (!Regex("^[0-9+\\-*/().%\\s]+$").matches(e) || !Regex("[0-9]").containsMatchIn(e) || !Regex("[+\\-*/%]").containsMatchIn(e)) return null
        val v = runCatching { Expr(e).parse() }.getOrNull() ?: return null
        val s = if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else "%.6f".format(Locale.FRANCE, v).trimEnd('0').trimEnd(',')
        return Answer("Calcul", "$e = $s", "", "Calcul", "", emptyList())
    }

    private class Expr(val s: String) {
        var i = 0
        fun parse(): Double { val v = add(); if (i < s.length && s.substring(i).isNotBlank()) throw Exception(); return v }
        private fun ws() { while (i < s.length && s[i] == ' ') i++ }
        private fun add(): Double { var v = mul(); while (true) { ws(); if (i < s.length && s[i] == '+') { i++; v += mul() } else if (i < s.length && s[i] == '-') { i++; v -= mul() } else return v } }
        private fun mul(): Double { var v = unary(); while (true) { ws(); if (i < s.length && s[i] == '*') { i++; v *= unary() } else if (i < s.length && s[i] == '/') { i++; v /= unary() } else if (i < s.length && s[i] == '%') { i++; v %= unary() } else return v } }
        private fun unary(): Double { ws(); if (i < s.length && s[i] == '-') { i++; return -unary() }; return atom() }
        private fun atom(): Double {
            ws()
            if (i < s.length && s[i] == '(') { i++; val v = add(); ws(); if (i < s.length && s[i] == ')') i++; return v }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(st, i).toDouble()
        }
    }

    private fun clock(q: String): Answer? {
        val l = q.lowercase()
        val now = Date()
        if (Regex("quelle heure|l'heure qu'il est").containsMatchIn(l)) return Answer("Heure", "Il est " + SimpleDateFormat("HH:mm", Locale.FRANCE).format(now) + " (heure de ce téléphone).", "", "Horloge", "", emptyList())
        if (Regex("quel jour|quelle date|on est le combien|date d'aujourd'hui").containsMatchIn(l)) return Answer("Date", "Nous sommes le " + SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRANCE).format(now) + ".", "", "Calendrier", "", emptyList())
        return null
    }

    private val WMO = mapOf(
        0 to "ciel dégagé", 1 to "plutôt dégagé", 2 to "partiellement nuageux", 3 to "couvert", 45 to "brouillard", 48 to "brouillard givrant",
        51 to "bruine légère", 53 to "bruine", 55 to "forte bruine", 61 to "pluie faible", 63 to "pluie", 65 to "forte pluie",
        71 to "neige faible", 73 to "neige", 75 to "forte neige", 80 to "averses", 81 to "averses", 82 to "fortes averses",
        95 to "orages", 96 to "orages avec grêle", 99 to "orages avec grêle",
    )
    private fun weather(q: String): Answer? {
        val place = Regex("(?:à|a|au|aux|de|sur|pour|in)\\s+([\\p{L}' -]{2,40})\\s*\\??$", RegexOption.IGNORE_CASE).find(q.trim())?.groupValues?.get(1)?.trim()
            ?.replace(Regex("^(la |le |les |l')", RegexOption.IGNORE_CASE), "") ?: return null
        val g = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=fr&name=" + enc(place))).optJSONArray("results")?.optJSONObject(0) ?: return null
        val f = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=${g.getDouble("latitude")}&longitude=${g.getDouble("longitude")}&current=temperature_2m,weather_code,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=1"))
        val c = f.getJSONObject("current"); val d = f.getJSONObject("daily")
        val name = g.optString("name") + (g.optString("country").takeIf { it.isNotBlank() }?.let { ", $it" } ?: "")
        val txt = "Maintenant : ${c.getDouble("temperature_2m").toInt()} °C, ${WMO[c.optInt("weather_code")] ?: "temps variable"}, vent ${c.optDouble("wind_speed_10m").toInt()} km/h. " +
            "Aujourd'hui entre ${d.getJSONArray("temperature_2m_min").getDouble(0).toInt()} et ${d.getJSONArray("temperature_2m_max").getDouble(0).toInt()} °C" +
            (d.optJSONArray("precipitation_probability_max")?.optInt(0)?.let { ", risque de pluie $it %." } ?: ".")
        return Answer("Météo à $name", txt, "https://open-meteo.com", "Open-Meteo", "", emptyList())
    }

    private fun ddg(q: String): JSONObject = JSONObject(get("https://api.duckduckgo.com/?format=json&no_html=1&skip_disambig=1&kl=fr-fr&q=" + enc(q)))
    private fun ddgAnswer(q: String): Answer? {
        val j = ddg(q)
        val a = j.optString("Answer").trim()
        if (a.isEmpty() || a.startsWith("{")) return null
        return Answer(j.optString("Heading").ifBlank { "Réponse" }, a, j.optString("AbstractURL"), "DuckDuckGo", "", emptyList())
    }
    private fun ddgAbstract(q: String): Answer? {
        val j = ddg(q)
        val a = j.optString("AbstractText").trim()
        if (a.length < 30) return null
        val img = j.optString("Image").let { if (it.startsWith("/")) "https://duckduckgo.com$it" else it }
        return Answer(j.optString("Heading"), shorten(a), j.optString("AbstractURL"), j.optString("AbstractSource").ifBlank { "DuckDuckGo" }, img, emptyList())
    }

    private fun shorten(t: String, max: Int = 460): String {
        val s = t.replace(Regex("\\s+"), " ").trim()
        if (s.length <= max) return s
        val cut = s.take(max)
        val end = cut.lastIndexOf(". ")
        return if (end > 120) cut.take(end + 1) else cut.trimEnd() + "…"
    }

    private fun wikiQuery(lang: String, params: String): JSONArray? {
        val j = JSONObject(get("https://$lang.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&redirects=1&prop=extracts|pageimages|info&exintro=1&explaintext=1&exsentences=4&piprop=thumbnail&pithumbsize=240&inprop=url&$params"))
        return j.optJSONObject("query")?.optJSONArray("pages")
    }
    private fun wiki(q: String, lang: String): Answer? {
        val pages = wikiQuery(lang, "generator=search&gsrlimit=5&gsrsearch=" + enc(q)) ?: return null
        val l = (0 until pages.length()).map { pages.getJSONObject(it) }.sortedBy { it.optInt("index", 99) }
            .filter { it.optString("extract").length > 40 && !it.optString("extract").contains("peut désigner") && !it.optString("extract").contains("may refer to") }
        val p = l.firstOrNull() ?: return null
        return Answer(
            p.optString("title"), shorten(p.optString("extract")), p.optString("fullurl"),
            if (lang == "fr") "Wikipédia" else "Wikipedia (en)", p.optJSONObject("thumbnail")?.optString("source") ?: "",
            l.drop(1).map { it.optString("title") }.take(3),
        )
    }
    fun wikiPage(title: String, lang: String): Answer? {
        val pages = wikiQuery(lang, "titles=" + enc(title)) ?: return null
        val p = pages.optJSONObject(0) ?: return null
        if (p.optString("extract").isBlank()) return wiki(title, lang)
        return Answer(p.optString("title"), shorten(p.optString("extract")), p.optString("fullurl"), "Wikipédia", p.optJSONObject("thumbnail")?.optString("source") ?: "", emptyList())
    }
}

private val SPink = Color(0xFFFF4F86)
private val STxt = Color(0xFFF5EEF2)
private val SSoft = Color(0xFFC9A9BA)

/** Réponse (ou choix) de @recherche : carte au centre, comme le QCM et @music. */
@Composable
fun AskBotRow(m: ChatMsg, partner: String, msgs: List<ChatMsg>) {
    val b = AskBot.bot(m) ?: return
    val t = b.optString("t")
    val ctx = LocalContext.current
    val who = if (m.mine) "Toi" else "@$partner"
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.Center) {
        Column(
            Modifier.fillMaxWidth(0.94f).clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(Color(0xDB3A2834), Color(0xD1241822))))
                .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(22.dp))
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(Brush.linearGradient(listOf(SPink, Color(0xFF8E24AA)))), contentAlignment = Alignment.Center) { Text("🔎", fontSize = 13.sp) }
                Spacer(Modifier.width(8.dp))
                Text("@recherche", color = SSoft, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            if (t == "spk") {
                Text("$who a choisi : ${b.optString("ti")}", color = STxt, fontSize = 14.sp)
                if (msgs.none { AskBot.bot(it)?.let { x -> x.optString("t") == "sr" && x.optLong("r") == m.id } == true }) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = SSoft)
                        Spacer(Modifier.width(8.dp))
                        Text("Recherche en cours…", color = SSoft, fontSize = 12.sp)
                    }
                }
                return@Column
            }
            Text("« ${b.optString("q")} »", color = SSoft, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            if (b.optBoolean("none")) {
                Text(m.body?.removePrefix("🔎 ") ?: "Pas de réponse trouvée.", color = STxt, fontSize = 15.sp)
                return@Column
            }
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    if (b.optString("ti").isNotBlank()) Text(b.optString("ti"), color = STxt, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    Text(b.optString("a"), color = STxt, fontSize = 14.sp, lineHeight = 20.sp)
                }
                val img = b.optString("img")
                if (img.isNotBlank()) {
                    Spacer(Modifier.width(10.dp))
                    AsyncImage(model = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
                }
            }
            val u = b.optString("u")
            Spacer(Modifier.height(8.dp))
            Text(
                "Source : " + b.optString("src").ifBlank { "web" } + if (u.isNotBlank()) "  ·  ouvrir ↗" else "",
                color = Color(0xFF9FC3FF), fontSize = 12.sp,
                modifier = if (u.isNotBlank()) Modifier.clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } else Modifier,
            )
            val o = b.optJSONArray("o")
            if (o != null && o.length() > 0) {
                Spacer(Modifier.height(10.dp))
                Text("Ce n'est pas ça ? Autres pistes :", color = SSoft, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                for (k in 0 until o.length()) {
                    val ti = o.getJSONObject(k).optString("ti")
                    val taken = msgs.any { AskBot.bot(it)?.let { x -> x.optString("t") == "spk" && x.optLong("r") == m.id && x.optInt("k") == k } == true }
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (taken) Color(0x33FF4886) else Color(0x0FFFFFFF))
                            .border(1.dp, if (taken) SPink else Color(0x1FFFFFFF), RoundedCornerShape(14.dp))
                            .clickable(enabled = !taken && m.id != 0L) { AskBot.pick(m, k) }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(if (taken) SPink else Color(0x1AFFFFFF)), Alignment.Center) {
                            Text("ABC"[k % 3].toString(), color = if (taken) Color.White else SSoft, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(ti, color = STxt, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
