package com.tumemanque.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Req(val id: String, val fromName: String, val toName: String)

data class Miss(val from: String, val isReply: Boolean, val at: String = "")

data class UiState(
    val loading: Boolean = true,
    val error: String? = null,
    val username: String? = null,
    val partnerName: String? = null,
    val wall: String? = null,
    val supportUnread: Int = 0,
    val supportHas: Boolean = false,
    val partnerPhone: String? = null,
    /** Contacts du téléphone : fin du numéro → nom enregistré (Android seulement). */
    val contacts: Map<String, String> = emptyMap(),
    /** Personne n'est connecté : on affiche la création de compte / connexion. */
    val needAuth: Boolean = false,
    val incoming: List<Req> = emptyList(),
    val outgoing: List<Req> = emptyList(),
    val lastMiss: Miss? = null,
    val unread: Int = 0,
    val busy: Boolean = false,
    val message: String? = null,
    /** Identifiant introuvable : on propose d'envoyer l'appli. */
    val offerInvite: String? = null,
)

class AppViewModel : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        start()
    }

    fun start() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            if (!Backend.hasSession()) {
                _state.update { it.copy(loading = false, needAuth = true) }
                return@launch
            }
            try {
                Backend.ensureSession()
                launch { runCatching { Backend.saveToken() } }
                applySnapshot(Backend.loadSnapshot())
            } catch (e: Exception) {
                if (e.message == Backend.NO_SESSION) {
                    _state.update { it.copy(loading = false, needAuth = true) }
                } else {
                    _state.update {
                        it.copy(loading = false, error = "Connexion impossible. Vérifie ta connexion internet puis réessaie.")
                    }
                }
            }
        }
    }

    /** Appelé régulièrement tant que l'appli est ouverte. */
    fun refresh() {
        if (_state.value.loading || _state.value.error != null || _state.value.needAuth) return
        viewModelScope.launch {
            try {
                applySnapshot(Backend.loadSnapshot())
            } catch (e: Exception) {
                // Pas de réseau pour l'instant : on garde l'écran actuel.
            }
        }
    }

    private var wallLockUntil = 0L

    /** Fond changé ici : on l'affiche tout de suite (les mises à jour du serveur sont ignorées 6 s). */
    fun setWall(v: String?) {
        wallLockUntil = System.currentTimeMillis() + 6000
        _state.update { it.copy(wall = v) }
    }

    private fun applySnapshot(s: Snapshot) {
        val keepWall = System.currentTimeMillis() < wallLockUntil
        _state.update {
            it.copy(
                supportUnread = s.supportUnread,
                supportHas = s.supportHas,
                wall = if (keepWall) it.wall else if (s.partnerName != null) s.wall else null,
                loading = false,
                error = null,
                username = s.username,
                partnerName = s.partnerName,
                partnerPhone = s.partnerPhone,
                needAuth = false,
                lastMiss = s.miss,
                unread = s.unread,
                incoming = s.incoming,
                outgoing = s.outgoing,
            )
        }
    }

    private fun clean(name: String) = name.trim().removePrefix("@").lowercase()

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                block()
                applySnapshot(Backend.loadSnapshot())
            } catch (e: Exception) {
                _state.update { it.copy(message = "Une erreur est survenue. Vérifie ta connexion et réessaie.") }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    private fun say(text: String) = _state.update { it.copy(message = text) }

    fun addPartner(raw: String) = act {
        val name = clean(raw)
        val (status, found) = Backend.sendRequest(name)
        when (status) {
            "sent" -> say("Demande envoyée à @${found ?: name}. Il/elle va recevoir une notification.")
            "linked" -> Unit
            "not_found" -> _state.update { it.copy(offerInvite = name) }
            "self" -> say("C'est toi !")
            "already_has_partner" -> say("Tu as déjà un partenaire.")
            "partner_busy" -> say("@$name a déjà un partenaire.")
            "already_sent" -> say("Tu as déjà envoyé une demande à @$name.")
            "invalid" -> say("Pseudo ou numéro invalide.")
            else -> say("Impossible d'envoyer la demande.")
        }
    }

    fun respond(requestId: String, accept: Boolean) = act {
        when (Backend.respondRequest(requestId, accept)) {
            "ok" -> Unit
            "partner_busy" -> say("Cette personne a déjà un partenaire.")
            else -> say("Cette demande n'existe plus.")
        }
    }

    fun sendMiss(reply: Boolean) = act {
        when (Backend.sendMiss(reply)) {
            "ok" -> say(if (reply) "Envoyé : Tu me manques aussi 💗" else "Envoyé 💗")
            else -> say("Impossible d'envoyer pour le moment.")
        }
    }

    fun removePartner() = act {
        when (Backend.removePartner()) {
            "ok" -> say("Partenaire retiré. Tu peux en ajouter un autre.")
            else -> say("Impossible de retirer le partenaire pour le moment.")
        }
    }

    /** Le chat vient de s'ouvrir après « Tu me manques aussi » : on efface la carte de l'écran d'accueil. */
    fun clearMiss() {
        _state.update { it.copy(lastMiss = null) }
        viewModelScope.launch { runCatching { Backend.clearMiss() } }
    }

    fun setUnread(n: Int) = _state.update { it.copy(unread = n) }

    fun setSupportHas(v: Boolean) = _state.update { it.copy(supportHas = v) }

    fun setSupportUnread(n: Int) = _state.update { it.copy(supportUnread = n) }

    /** Lit les contacts du téléphone (si l'autorisation est donnée) pour afficher les vrais noms. */
    fun loadContacts(context: Context) {
        viewModelScope.launch {
            val map = withContext(Dispatchers.IO) { Contacts.load(context.applicationContext) }
            _state.update { it.copy(contacts = map) }
        }
    }

    fun dismissInvite() = _state.update { it.copy(offerInvite = null) }
}
