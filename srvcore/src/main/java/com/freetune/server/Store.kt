package com.freetune.server

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Acc(val username: String, val code: String, var share: Boolean)

/** Comptes serveur et réglages, gardés dans le stockage privé de l'application. */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("freetune-server", Context.MODE_PRIVATE)

    @Synchronized fun str(k: String, d: String = ""): String = sp.getString(k, d) ?: d
    @Synchronized fun put(k: String, v: String) { sp.edit().putString(k, v).apply() }
    @Synchronized fun bool(k: String, d: Boolean): Boolean = sp.getBoolean(k, d)
    @Synchronized fun putBool(k: String, v: Boolean) { sp.edit().putBoolean(k, v).apply() }
    @Synchronized fun int(k: String, d: Int): Int = sp.getInt(k, d)
    @Synchronized fun putInt(k: String, v: Int) { sp.edit().putInt(k, v).apply() }

    val deviceId: String
        @Synchronized get() {
            var d = str("deviceId")
            if (d.isEmpty()) { d = Http.hex(16); put("deviceId", d) }
            return d
        }

    @Synchronized fun accounts(): List<Acc> {
        val a = try { JSONArray(str("accounts", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { val o = a.getJSONObject(it); Acc(o.getString("u"), o.getString("c"), o.optBoolean("s", true)) }
    }

    private fun write(l: List<Acc>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("u", it.username).put("c", it.code).put("s", it.share)) }
        put("accounts", a.toString())
    }

    @Synchronized fun addAccount(u: String, c: String) { write(accounts().filter { it.username != u } + Acc(u, c, true)) }
    @Synchronized fun removeAccount(u: String) { write(accounts().filter { it.username != u }) }
    @Synchronized fun setShare(u: String, on: Boolean) { write(accounts().map { if (it.username == u) Acc(it.username, it.code, on) else it }) }
}
