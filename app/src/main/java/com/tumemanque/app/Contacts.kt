package com.tumemanque.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** Les noms enregistrés dans les contacts du téléphone (uniquement sur Android). */
object Contacts {
    /** Clé de comparaison d'un numéro : ses 9 derniers chiffres (ignore +237, 00237, espaces…). */
    fun key(number: String?): String? {
        val d = number?.filter { it.isDigit() } ?: return null
        return if (d.length >= 9) d.takeLast(9) else null
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun load(context: Context): Map<String, String> {
        if (!hasPermission(context)) return emptyMap()
        val map = HashMap<String, String>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val k = key(c.getString(0)) ?: continue
                    val name = c.getString(1)?.trim().orEmpty()
                    if (name.isNotEmpty() && !map.containsKey(k)) map[k] = name
                }
            }
        } catch (e: Exception) {
            // contacts indisponibles : on affichera les pseudos
        }
        return map
    }
}

/** « Stéphane » si le numéro est dans tes contacts, sinon « @pseudo ». */
fun partnerLabel(state: UiState): String =
    Contacts.key(state.partnerPhone)?.let { state.contacts[it] } ?: "@${state.partnerName}"
