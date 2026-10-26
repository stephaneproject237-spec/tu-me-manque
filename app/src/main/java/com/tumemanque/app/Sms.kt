package com.tumemanque.app

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Reçu par SMS pendant que ton partenaire n'avait pas internet. Remplacé par le vrai message dès qu'il arrive. */
data class SmsIn(val id: String, val text: String, val ts: Long)

object SmsInbox {
    private val lock = Any()
    private fun file(c: Context) = File(c.filesDir, "sms_in.json")

    private fun read(c: Context): MutableList<SmsIn> {
        val f = file(c)
        if (!f.exists()) return mutableListOf()
        return try {
            val a = JSONArray(f.readText())
            val limit = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
            MutableList(a.length()) { a.getJSONObject(it).let { o -> SmsIn(o.getString("id"), o.getString("text"), o.getLong("ts")) } }
                .filter { it.ts > limit }.toMutableList()
        } catch (e: Exception) { mutableListOf() }
    }

    private fun write(c: Context, l: List<SmsIn>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("ts", it.ts)) }
        file(c).writeText(a.toString())
    }

    fun all(c: Context): List<SmsIn> = synchronized(lock) { read(c) }
    fun add(c: Context, m: SmsIn) = synchronized(lock) { val l = read(c); if (l.none { it.id == m.id }) { l.add(m); write(c, l) } }
    fun remove(c: Context, id: String) = synchronized(lock) { val l = read(c); if (l.removeAll { it.id == id }) write(c, l) }
}

/** SMS de secours : sans internet, les messages texte partent par SMS vers le partenaire. */
object SmsFallback {
    private var ctx: Context? = null
    fun init(c: Context) { ctx = c.applicationContext }
    private fun c(): Context = ctx ?: error("Sms non initialisé")
    private fun prefs(c: Context) = c.getSharedPreferences("sms", Context.MODE_PRIVATE)

    private val markerRe = Regex("""^💗 @?(.+?) : ([\s\S]*)\n\(Tu me manques · ([0-9a-f]{8})\)\s*$""")

    fun remember(c: Context, me: String?, partner: String?, phone: String?) {
        prefs(c).edit().putString("me", me).putString("partner", partner).putString("phone", phone).apply()
    }

    fun hasPerms(c: Context) =
        ContextCompat.checkSelfPermission(c, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    fun wanted(c: Context) = prefs(c).getBoolean("on", false)
    fun setWanted(c: Context, on: Boolean) = prefs(c).edit().putBoolean("on", on).apply()
    fun enabled(c: Context) = wanted(c) && hasPerms(c)

    @Suppress("DEPRECATION")
    fun hasInternet(c: Context): Boolean {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun digits(s: String?) = (s ?: "").filter { it.isDigit() }.takeLast(9)
    private fun sameNumber(a: String?, b: String?) = digits(a).length >= 8 && digits(a) == digits(b)

    /** Envoie [text] par SMS si l'option est active, qu'il n'y a pas d'internet et que le numéro est connu. */
    @Suppress("DEPRECATION")
    fun trySend(id: String, text: String): Boolean {
        val c = c()
        if (!enabled(c) || hasInternet(c) || text.length > 300) return false
        val p = prefs(c)
        val phone = p.getString("phone", null) ?: return false
        val me = p.getString("me", null) ?: return false
        if (digits(phone).length < 8) return false
        return try {
            val sms = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            val body = "💗 @$me : $text\n(Tu me manques · $id)"
            sms.sendMultipartTextMessage(phone, null, sms.divideMessage(body), null, null)
            true
        } catch (e: Exception) { false }
    }

    fun onSms(c: Context, from: String?, body: String) {
        if (!enabled(c)) return
        val phone = prefs(c).getString("phone", null)
        if (!sameNumber(from, phone)) return
        val m = markerRe.matchEntire(body.trim()) ?: return
        val text = m.groupValues[2]
        val id = m.groupValues[3]
        SmsInbox.add(c, SmsIn(id, text, System.currentTimeMillis()))
        notify(c, text)
    }

    private fun notify(c: Context, text: String) {
        createNotificationChannel(c)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(c, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val who = prefs(c).getString("partner", null)?.let { "@$it" } ?: "Ton partenaire"
        val n = NotificationCompat.Builder(c, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle("📱 $who (par SMS)")
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(soundUri(c))
            .setVibrate(VIBRATION_PATTERN)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(c).notify(System.currentTimeMillis().toInt(), n)
    }
}

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        SmsFallback.onSms(context, parts[0].originatingAddress, parts.joinToString("") { it.messageBody ?: "" })
    }
}
