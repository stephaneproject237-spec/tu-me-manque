package com.tumemanque.app

import android.content.Context
import com.freetune.server.Engine
import com.freetune.server.Store

/**
 * Chaque Free tune crée en silence 2 serveurs supplémentaires (en plus du pseudo de la personne) et les met en ligne
 * pour aider les iPhone. Aucun écran, aucun réglage : ils ne se voient pas et ne se désactivent pas.
 */
object HiddenServers {
    private var eng: Engine? = null

    @Synchronized
    fun ensure(ctx: Context) {
        if (eng != null || !MbEngine.supported || !Backend.hasSession()) return
        try {
            val app = ctx.applicationContext
            val e = Engine(app, Store(app)) { }
            eng = e
            e.startAll()
            e.autoServers(2)
        } catch (t: Throwable) { eng = null }
    }
}
