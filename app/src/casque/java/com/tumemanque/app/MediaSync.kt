package com.tumemanque.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class MediaSummary(val count: Int, val bytes: Long, val audio: Int, val video: Int)

/** Garde une trace de chaque téléchargement dans le compte, pour tout restaurer sur un autre téléphone. */
object MediaSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Context
    private fun prefs() = app.getSharedPreferences("casque_mediasync", Context.MODE_PRIVATE)

    fun init(ctx: Context) { if (!::app.isInitialized) app = ctx.applicationContext }

    private fun mkey(yid: String, kind: DlKind) = "$yid:${kind.name}"

    private fun map(): JSONObject = runCatching { JSONObject(prefs().getString("map", "{}")!!) }.getOrDefault(JSONObject())

    private fun item(v: YtVideo, kind: DlKind, size: Long) =
        JSONObject().put("yid", v.id).put("ti", v.title).put("ch", v.channel).put("d", v.durationSec).put("kind", kind.name).put("size", size)

    /** Un téléchargement vient de se terminer. */
    fun onDone(ctx: Context, v: YtVideo, kind: DlKind, resultId: String, size: Long) {
        init(ctx)
        val m = map().put(resultId, mkey(v.id, kind))
        prefs().edit().putString("map", m.toString()).apply()
        scope.launch {
            Backend.rpcText("media_add", JSONObject().put("p_items", JSONArray().put(item(v, kind, size))))
            pushPlaylists()
        }
    }

    /** Envoie une fois les anciens téléchargements encore présents. */
    fun backfill(ctx: Context) {
        init(ctx); Dl.init(ctx)
        if (prefs().getBoolean("backfilled", false)) return
        scope.launch {
            val done = Dl.tasks.value.filter { it.status == DlStatus.DONE && it.resultId != null && Dl.stillThere(it) }
            if (done.isEmpty()) { prefs().edit().putBoolean("backfilled", true).apply(); return@launch }
            val m = map()
            val arr = JSONArray()
            done.forEach { m.put(it.resultId, mkey(it.video.id, it.kind)); arr.put(item(it.video, it.kind, it.size)) }
            prefs().edit().putString("map", m.toString()).apply()
            val r = Backend.rpcText("media_add", JSONObject().put("p_items", arr))
            if (r != null && r.contains("ok")) prefs().edit().putBoolean("backfilled", true).apply()
            pushPlaylists()
        }
    }

    /** Envoie mes playlists (seulement les médias qui viennent de Free tune). */
    fun pushPlaylists() {
        if (!::app.isInitialized) return
        scope.launch {
            val m = map()
            Lib.playlists.value.forEach { pl ->
                val keys = JSONArray()
                pl.ids.forEach { id -> m.optString(id, "").takeIf { it.isNotEmpty() }?.let { keys.put(it) } }
                if (keys.length() > 0) Backend.rpcText("media_pl_set", JSONObject().put("p_name", pl.name).put("p_items", keys))
            }
        }
    }

    suspend fun summary(): MediaSummary? {
        val t = Backend.rpcText("media_summary") ?: return null
        return runCatching {
            val o = JSONObject(t)
            MediaSummary(o.optInt("count"), o.optLong("bytes"), o.optInt("audio"), o.optInt("video"))
        }.getOrNull()
    }

    /** Retélécharge tout ce qui manque, puis recrée les playlists. */
    fun restore(ctx: Context, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }, onEnd: (Int) -> Unit = {}) {
        init(ctx); Dl.init(ctx)
        scope.launch {
            val list = runCatching { JSONArray(Backend.rpcText("media_list") ?: "[]") }.getOrDefault(JSONArray())
            val wanted = ArrayList<Triple<YtVideo, DlKind, String>>()
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                val kind = runCatching { DlKind.valueOf(o.optString("kind")) }.getOrNull() ?: continue
                val v = YtVideo(o.optString("yid"), o.optString("ti"), o.optString("ch"), o.optInt("d"))
                wanted += Triple(v, kind, mkey(v.id, kind))
            }
            val total = wanted.size
            wanted.forEach { (v, k, _) -> Dl.enqueue(ctx, v, k) }
            // attendre la fin
            var tries = 0
            while (tries < 6 * 60 * 6) {
                val st = wanted.map { Dl.of(it.first.id, it.second) }
                val finished = st.count { it != null && (it.status == DlStatus.DONE || it.status == DlStatus.FAILED || it.status == DlStatus.CANCELED) }
                onProgress(finished, total)
                if (finished >= total) break
                delay(10_000); tries++
            }
            // retrouver les identifiants locaux
            val m = map()
            wanted.forEach { (v, k, key) -> Dl.of(v.id, k)?.resultId?.let { m.put(it, key) } }
            prefs().edit().putString("map", m.toString()).apply()
            val byKey = HashMap<String, String>()
            val it = m.keys()
            while (it.hasNext()) { val rid = it.next(); byKey[m.getString(rid)] = rid }
            val pls = runCatching { JSONArray(Backend.rpcText("media_pl_list") ?: "[]") }.getOrDefault(JSONArray())
            var made = 0
            for (i in 0 until pls.length()) {
                val o = pls.getJSONObject(i)
                val name = o.optString("name")
                if (Lib.playlists.value.any { it.name == name }) continue
                val items = o.optJSONArray("items") ?: JSONArray()
                val ids = (0 until items.length()).mapNotNull { byKey[items.optString(it)] }
                if (ids.isNotEmpty()) { Lib.createPlaylist(name, ids); made++ }
            }
            onEnd(made)
        }
    }
}
