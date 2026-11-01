package com.tumemanque.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Parrainage de Free tune : lien d'invitation et parrain. */
object Sponsor {
    var name by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false)
    private val tag = Regex("freetune-parrain:([A-Za-z0-9_]{2,30})")

    fun link(username: String): String = Config.WEB_URL + "telecharger/?p=" + username

    /** Ouvre le partage d'Android (WhatsApp, SMS…) avec le lien de téléchargement. */
    fun share(ctx: Context, username: String) {
        val text = "Je t'invite sur Free tune 🎧 Musique, vidéos et messages entre amis. Télécharge l'application ici :\n" + link(username)
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(i, "Envoyer Free tune").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun refresh() {
        val n = Backend.sponsorGet()
        name = n
        loaded = true
    }

    /** Je renseigne mon parrain. Renvoie un message pour l'écran. */
    suspend fun set(input: String): String {
        val (st, n) = Backend.sponsorSet(input.trim().removePrefix("@"))
        return when (st) {
            "ok" -> { name = n; "Ton parrain est @$n." }
            "already" -> { name = n; "Tu as déjà un parrain : @$n." }
            "unknown" -> "Ce pseudo n'existe pas."
            "self" -> "Tu ne peux pas être ton propre parrain."
            else -> "Impossible pour le moment. Vérifie ta connexion."
        }
    }

    /** Le lien de téléchargement copie un code dans le presse-papiers : on le lit une fois à l'ouverture. */
    suspend fun fromClipboard(ctx: Context) {
        refresh()
        if (name != null) return
        repeat(4) {
            delay(1500)
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val t = runCatching { cm?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull()
            val m = t?.let { tag.find(it) }
            if (m != null) {
                set(m.groupValues[1])
                runCatching { cm?.setPrimaryClip(ClipData.newPlainText("", "")) }
                return
            }
        }
    }
}
