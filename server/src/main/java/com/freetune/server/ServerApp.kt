package com.freetune.server

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Tout ce qui doit survivre à la fermeture de la fenêtre : comptes, moteur, téléchargements, état. */
object Core {
    lateinit var app: Context
    lateinit var store: Store
    lateinit var engine: Engine
    lateinit var dl: Dl
    val media = Media()
    @Volatile var listener: (() -> Unit)? = null
    @Volatile var upd: JSONObject? = null
    @Volatile var updMsg = ""
    @Volatile private var unlockedUntil = 0L
    private var started = false

    fun init(ctx: Context) {
        if (::store.isInitialized) return
        app = ctx.applicationContext
        store = Store(app)
        Tools.init(app)
        engine = Engine(app, store) { listener?.invoke() }
        dl = Dl(app, { dlDir() }) { listener?.invoke() }
    }

    fun dlDir(): File {
        val d = app.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(app.filesDir, "Music")
        d.mkdirs(); return d
    }

    @Synchronized fun start() {
        if (started) return
        started = true
        engine.startAll()
        engine.autoServers()
        Thread { while (true) { checkUpdate(); try { Thread.sleep(2 * 3600 * 1000L) } catch (e: InterruptedException) { return@Thread } } }.also { it.isDaemon = true }.start()
    }

    fun hashPw(pw: String): String = MessageDigest.getInstance("SHA-256").digest(("ft-adm:$pw").toByteArray()).joinToString("") { "%02x".format(it) }
    private val DEFAULT_HASH by lazy { hashPw(Cfg.DEFAULT_ADMIN_PW) }
    private fun curHash() = store.str("admHash").ifEmpty { DEFAULT_HASH }

    fun unlocked() = System.currentTimeMillis() < unlockedUntil
    fun unlock(pw: String): Boolean { val ok = hashPw(pw) == curHash(); unlockedUntil = if (ok) System.currentTimeMillis() + 15 * 60000 else 0; listener?.invoke(); return ok }
    fun lock() { unlockedUntil = 0; listener?.invoke() }
    fun need() { if (!unlocked()) throw Exception("verrouillé"); unlockedUntil = System.currentTimeMillis() + 15 * 60000 }
    fun setPw(old: String, pw: String): JSONObject {
        if (!unlocked()) return JSONObject().put("ok", false).put("msg", "Verrouillé")
        if (hashPw(old) != curHash()) return JSONObject().put("ok", false).put("msg", "Ancien mot de passe incorrect")
        if (pw.length < 6 || pw.length > 60) return JSONObject().put("ok", false).put("msg", "Nouveau mot de passe : 6 caractères minimum")
        store.put("admHash", hashPw(pw)); return JSONObject().put("ok", true)
    }

    fun snapshot(): JSONObject {
        val s = engine.snapshot()
        s.put("unlocked", unlocked()).put("deviceId", store.deviceId)
            .put("settings", JSONObject().put("maxJobs", engine.maxJobs()).put("autostart", store.bool("autostart", true)).put("autoUpdate", store.bool("autoUpdate", true)).put("dlDirShown", "Musique › Free tune server (dans l'application)"))
            .put("downloads", dl.list()).put("version", app.packageManager.getPackageInfo(app.packageName, 0).versionName)
            .put("update", upd ?: JSONObject.NULL).put("updMsg", updMsg).put("hasFfmpeg", true).put("platform", "srv")
        return s
    }

    /** Nouvelle version ? (publications GitHub « server-<numéro> ») */
    fun checkUpdate(): JSONObject? {
        try {
            val cur = (app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "1.0").substringAfter('.').toIntOrNull() ?: 0
            val r = Http.request("GET", "https://api.github.com/repos/${Cfg.REPO}/releases?per_page=30", mapOf("Accept" to "application/vnd.github+json"), null, 20000)
            val l = JSONArray(r.text)
            var best: JSONObject? = null; var bn = cur
            for (i in 0 until l.length()) {
                val x = l.getJSONObject(i)
                val m = Regex("^server-(\\d+)$").find(x.optString("tag_name")) ?: continue
                val n = m.groupValues[1].toInt()
                if (x.optBoolean("draft") || n <= bn) continue
                val as_ = x.optJSONArray("assets") ?: continue
                for (k in 0 until as_.length()) {
                    val a = as_.getJSONObject(k)
                    if (a.optString("name").endsWith(".apk")) { best = JSONObject().put("n", n).put("url", a.optString("browser_download_url")).put("size", a.optLong("size")); bn = n; break }
                }
            }
            val was = upd?.optInt("n") ?: 0
            upd = best
            listener?.invoke()
            if (best != null && store.bool("autoUpdate", true) && best.optInt("n") != was) notifyUpdate()
        } catch (e: Exception) { /* hors ligne */ }
        return upd
    }

    private fun notifyUpdate() {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = android.app.PendingIntent.getActivity(app, 7, android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(upd!!.optString("url"))), android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = androidx.core.app.NotificationCompat.Builder(app, "upd").setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Nouvelle version de Free tune server").setContentText("Touche pour la télécharger et l'installer.").setContentIntent(pi).setAutoCancel(true).build()
        nm.notify(7, n)
    }
}

class ServerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Core.init(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("srv", "Serveurs en ligne", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel("upd", "Mises à jour", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
}
