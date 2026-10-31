package com.freetune.server

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

const val MEDIA_BASE = "https://appassets.androidplatform.net/ftmedia/"

/** Relie la page (la même que sur Mac et Windows) au moteur Kotlin : window.FTA.invoke -> window.__ftres. */
class Bridge(private val web: WebView) {
    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()
    private var pending = false

    init {
        Core.listener = { push() }
    }

    fun detach() { Core.listener = null }

    /** Envoie l'état à la page (au plus une fois toutes les 400 ms). */
    private fun push() {
        if (pending) return
        pending = true
        ui.postDelayed({
            pending = false
            val j = try { Core.snapshot().toString() } catch (e: Exception) { return@postDelayed }
            web.evaluateJavascript("window.__ftstate && window.__ftstate(${JSONObject.quote(j)})", null)
        }, 400)
    }

    @JavascriptInterface
    fun invoke(id: Int, ch: String, arg: String) {
        pool.execute {
            val out = try { handle(ch, if (arg == "null" || arg.isEmpty()) null else arg) } catch (e: Throwable) { JSONObject().put("ok", false).put("msg", (e.message ?: "erreur").take(160)) }
            val s = if (out == null || out === JSONObject.NULL) "null" else if (out is String) JSONObject.quote(out) else out.toString()
            ui.post { web.evaluateJavascript("window.__ftres($id, ${JSONObject.quote(s)})", null) }
        }
    }

    private fun obj(a: String?) = if (a == null) JSONObject() else try { JSONObject(a) } catch (e: Exception) { JSONObject() }
    private fun str(a: String?): String = if (a == null) "" else try { JSONArray("[$a]").get(0).toString() } catch (e: Exception) { a }

    private fun handle(ch: String, a: String?): Any? {
        when (ch) {
            "state" -> return Core.snapshot()
            "dev:id" -> return Core.store.deviceId
            "adm:unlock" -> return JSONObject().put("ok", Core.unlock(obj(a).optString("pw")))
            "adm:lock" -> { Core.lock(); return true }
            "adm:setpw" -> { val o = obj(a); return Core.setPw(o.optString("old"), o.optString("pw")) }
            "hist:missing" -> {
                val l = try { JSONArray(a ?: "[]") } catch (e: Exception) { JSONArray() }
                val r = JSONArray()
                for (i in 0 until minOf(l.length(), 600)) { val x = l.optJSONObject(i); r.put(x != null && Core.dl.has(x.optString("artist"), x.optString("title"), x.optString("kind"))) }
                return r
            }
            "acc:add" -> { Core.need(); val o = obj(a); return Core.engine.createAccount(o.optString("username"), o.optString("code")) }
            "acc:bulk" -> {
                Core.need()
                val o = obj(a)
                val prefix = o.optString("prefix").lowercase().replace(Regex("[^a-z0-9_]"), "").take(14)
                val n = o.optInt("count", 1).coerceIn(1, 30)
                val out = JSONArray()
                for (i in 1..n) {
                    val u = prefix + i.toString().padStart(2, '0')
                    val r = Core.engine.createAccount(u, Http.hex(9).take(12)); r.put("username", u); out.put(r)
                    if (!r.optBoolean("ok") && Regex("Impossible|Trop").containsMatchIn(r.optString("msg"))) break
                }
                return out
            }
            "acc:remove" -> { Core.need(); Core.engine.remove(str(a)); return true }
            "acc:share" -> { Core.need(); val o = obj(a); Core.engine.setShare(o.optString("username"), o.optBoolean("on")); return true }
            "acc:code" -> { Core.need(); val u = str(a); return Core.store.accounts().find { it.username == u }?.code ?: "" }
            "settings:set" -> {
                val o = obj(a)
                if (o.has("maxJobs")) Core.store.putInt("maxJobs", o.optInt("maxJobs", 3).coerceIn(1, 12))
                if (o.has("autostart")) Core.store.putBool("autostart", o.optBoolean("autostart"))
                if (o.has("autoUpdate")) { Core.store.putBool("autoUpdate", o.optBoolean("autoUpdate")); if (o.optBoolean("autoUpdate")) Thread { Core.checkUpdate() }.start() }
                Core.listener?.invoke(); return true
            }
            "settings:dir", "dl:show" -> return true
            "search:yt" -> return try { JSONObject().put("ok", true).put("list", Tools.search(str(a), 15)) } catch (e: Exception) { JSONObject().put("ok", false).put("msg", (e.message ?: "erreur").lines().last().take(160)) }
            "dl:start" -> return Core.dl.start(obj(a))
            "dl:ctl" -> {
                val o = obj(a); val id = o.optInt("id")
                return when (o.optString("op")) { "pause" -> Core.dl.pause(id); "resume" -> Core.dl.resume(id); "cancel" -> Core.dl.cancel(id); "clear" -> { Core.dl.clear(); true }; else -> false }
            }
            "play:file" -> {
                val f = File(str(a)).canonicalFile
                val root = Core.dlDir().canonicalFile
                val m = Core.media.mime(f.name)
                if (!f.path.startsWith(root.path + File.separator) || m.isEmpty() || !f.exists()) return JSONObject().put("ok", false).put("msg", "Fichier introuvable (déplacé ou supprimé ?)")
                return JSONObject().put("ok", true).put("src", MEDIA_BASE + Core.media.add(Entry(f, null, emptyMap()))).put("video", m.startsWith("video/"))
            }
            "play:yt" -> {
                val o = obj(a)
                var yid = o.optString("yid")
                if (!Regex("^[A-Za-z0-9_-]{11}$").matches(yid)) yid = Tools.bestMatch(o.optString("artist"), o.optString("title"), o.optInt("dur")) ?: return JSONObject().put("ok", false).put("msg", "Introuvable sur YouTube")
                val video = o.optBoolean("video")
                val m = Tools.resolveMedia(yid, video)
                return JSONObject().put("ok", true).put("src", MEDIA_BASE + Core.media.add(Entry(null, m.url, m.headers))).put("video", video).put("yid", yid).put("duration", m.duration)
            }
            "upd:check" -> { Core.updMsg = ""; val u = Core.checkUpdate(); if (u == null) { Core.updMsg = "Free tune est à jour."; Core.listener?.invoke() }; return u != null }
            "upd:install", "open:url" -> {
                val url = if (ch == "open:url") str(a) else Core.upd?.optString("url") ?: ""
                if (!url.startsWith("https://")) return false
                ui.post { web.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return true
            }
        }
        throw Exception("canal inconnu")
    }
}
