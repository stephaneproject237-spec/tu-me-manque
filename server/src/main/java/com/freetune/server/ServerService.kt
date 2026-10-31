package com.freetune.server

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/** Garde les serveurs en ligne même quand la fenêtre est fermée. */
class ServerService : Service() {
    private var lock: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        Core.init(this)
        val pi = PendingIntent.getActivity(this, 0, (packageManager.getLaunchIntentForPackage(packageName) ?: Intent()), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, "srv").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Free tune server").setContentText("Tes serveurs sont en ligne").setContentIntent(pi).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        if (lock == null) {
            lock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "freetune:server").also { it.setReferenceCounted(false); it.acquire() }
        }
        Core.start()
        return START_STICKY
    }

    override fun onDestroy() { lock?.let { if (it.isHeld) it.release() }; super.onDestroy() }
}
