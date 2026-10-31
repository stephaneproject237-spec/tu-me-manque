package com.freetune.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Au démarrage du téléphone (ou après une mise à jour), les serveurs repartent seuls. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Core.init(c)
        if (!Core.store.bool("autostart", true)) return
        try { ContextCompat.startForegroundService(c, Intent(c, ServerService::class.java)) } catch (e: Exception) { /* Android refuse en arrière-plan : la prochaine ouverture relancera */ }
    }
}
