package com.freetune.server

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/** Free tune server 2 : pas d'écran. Au premier lancement elle démarre ses 4 serveurs, puis son icône disparaît de l'écran d'accueil. */
class Launch : ComponentActivity() {
    private val battery = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { finish2() }
    private val notif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { askBattery() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Core.init(this)
        ContextCompat.startForegroundService(this, Intent(this, ServerService::class.java))
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
        else askBattery()
    }

    private fun askBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) { finish2(); return }
        try { battery.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (e: Exception) { finish2() }
    }

    private fun finish2() {
        Toast.makeText(this, "Free tune server 2 : 4 serveurs en ligne. L'icône disparaît.", Toast.LENGTH_LONG).show()
        // Plus d'icône sur l'écran d'accueil : les serveurs continuent en arrière-plan et au démarrage du téléphone.
        packageManager.setComponentEnabledSetting(ComponentName(this, Launch::class.java), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        finish()
    }
}
