package com.tumemanque.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Télécharge pour un ami iPhone même quand Free tune est fermé (réveillé par une notification silencieuse). */
class RelayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notif(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(NotificationChannel(CH, "Téléchargements pour mes amis", NotificationManager.IMPORTANCE_LOW))
            }
        }
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Free tune")
            .setContentText("Téléchargement d'un titre pour un ami…")
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIF_ID, notif(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (job?.isActive != true) {
            job = scope.launch {
                withTimeoutOrNull(12 * 60_000L) { Relay.drain(applicationContext) }
                ServiceCompat.stopForeground(this@RelayService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CH = "casque_relay"
        private const val NOTIF_ID = 4243

        fun start(ctx: Context) {
            if (!MbEngine.supported || !Relay.enabled(ctx) || !Backend.hasSession()) return
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, RelayService::class.java)) }
        }
    }
}
