package com.tumemanque.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthState(
    /** welcome, create, login, phone ou code */
    val screen: String = "welcome",
    /** create ou login */
    val mode: String = "create",
    val pseudo: String = "",
    val age: String = "",
    /** null, checking, available, taken, invalid ou error */
    val avail: String? = null,
    val phone: String = "+237",
    val code: String = "",
    val busy: Boolean = false,
    val msg: String? = null,
) {
    val ageOk: Boolean get() = age.length in 1..3 && age.toIntOrNull()?.let { it in 1..120 } == true
    val canContinue: Boolean get() = avail == "available" && ageOk && !busy
    val phoneOk: Boolean get() = phone.count { it.isDigit() } >= 8
}

private val PSEUDO = Regex("^[a-z][a-z0-9_]{2,19}$")

class AuthViewModel : ViewModel() {
    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()
    private var availJob: Job? = null

    fun goCreate() = _state.update { AuthState(screen = "create", mode = "create") }
    fun goLogin() = _state.update { AuthState(screen = "login", mode = "login") }
    fun goWelcome() = _state.update { AuthState() }
    fun phoneStart() = _state.update { it.copy(screen = "phone", msg = null) }
    fun phoneBack() = _state.update { it.copy(screen = if (it.mode == "create") "create" else "login", msg = null) }

    fun setAge(v: String) = _state.update { it.copy(age = v.filter { c -> c.isDigit() }.take(3)) }
    fun setPhone(v: String) = _state.update { it.copy(phone = v.filter { c -> c.isDigit() || c in "+ ()-" }.take(20)) }
    fun setCode(v: String) = _state.update { it.copy(code = v.filter { c -> c.isDigit() }.take(8)) }

    fun setPseudo(raw: String) {
        val v = raw.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(20)
        availJob?.cancel()
        if (!PSEUDO.matches(v)) {
            _state.update { it.copy(pseudo = v, avail = if (v.isEmpty()) null else "invalid") }
            return
        }
        _state.update { it.copy(pseudo = v, avail = "checking") }
        availJob = viewModelScope.launch {
            delay(400)
            val st = try { Backend.usernameAvailable(v) } catch (e: Exception) { "error" }
            _state.update { if (it.pseudo == v) it.copy(avail = st) else it }
        }
    }

    /** Ouvre le navigateur pour se connecter avec Google ou Apple. */
    fun startOAuth(context: Context, provider: String) {
        val s = _state.value
        val uri = if (s.mode == "create") Backend.oauthUri(provider, s.pseudo, s.age.toIntOrNull())
        else Backend.oauthUri(provider, null, null)
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Retour de Google / Apple : tumemanque://auth?code=… */
    fun handleLink(uri: Uri, onDone: () -> Unit) {
        if (uri.scheme != "tumemanque") return
        val code = uri.getQueryParameter("code")
        val err = uri.getQueryParameter("error_description")
        viewModelScope.launch {
            if (code == null) {
                if (err != null) _state.update { it.copy(msg = "Connexion refusée : $err") }
                return@launch
            }
            _state.update { it.copy(busy = true, msg = null) }
            try {
                if (Backend.exchangeCode(code)) finish(onDone)
                else _state.update { it.copy(msg = "Connexion impossible. Réessaie.") }
            } catch (e: Exception) {
                _state.update { it.copy(msg = "Connexion impossible. Vérifie ta connexion internet.") }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    fun sendCode() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, msg = null) }
            try {
                when (Backend.sendOtp(Backend.e164(_state.value.phone))) {
                    "ok" -> _state.update { it.copy(screen = "code", code = "") }
                    "rate" -> _state.update { it.copy(msg = "Trop d'essais. Attends une minute puis réessaie.") }
                    "disabled" -> _state.update { it.copy(msg = "La connexion par SMS n'est pas encore activée. Utilise Google pour le moment.") }
                    else -> _state.update { it.copy(msg = "Impossible d'envoyer le SMS pour le moment.") }
                }
            } catch (e: Exception) {
                _state.update { it.copy(msg = "Connexion impossible. Vérifie ta connexion internet.") }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    fun verifyCode(onDone: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, msg = null) }
            try {
                val s = _state.value
                if (Backend.verifyOtp(Backend.e164(s.phone), s.code)) finish(onDone)
                else _state.update { it.copy(msg = "Code incorrect ou expiré.") }
            } catch (e: Exception) {
                _state.update { it.copy(msg = "Connexion impossible. Vérifie ta connexion internet.") }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    /** Écran « Bienvenue » : connecté mais sans pseudo. */
    fun createProfile(onDone: () -> Unit) {
        viewModelScope.launch {
            val s = _state.value
            _state.update { it.copy(busy = true, msg = null) }
            try {
                when (Backend.createAccount(s.pseudo, s.age.toIntOrNull() ?: 0)) {
                    "ok", "has_username" -> onDone()
                    "taken" -> _state.update { it.copy(avail = "taken", msg = "Ce pseudo est déjà pris. Choisis-en un autre.") }
                    "invalid", "invalid_age" -> _state.update { it.copy(msg = "Vérifie ton pseudo et ton âge.") }
                    else -> _state.update { it.copy(msg = "Impossible de créer le compte.") }
                }
            } catch (e: Exception) {
                _state.update { it.copy(msg = "Connexion impossible. Vérifie ta connexion internet.") }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    private suspend fun finish(onDone: () -> Unit) {
        val s = _state.value
        val note = Backend.finishAuth(s.pseudo.ifEmpty { null }, s.age.toIntOrNull())
        if (note != null) {
            _state.update {
                it.copy(
                    screen = "welcome", mode = "create", msg = note.ifEmpty { null },
                    pseudo = if (note.startsWith("Ce pseudo")) "" else it.pseudo,
                    avail = if (note.startsWith("Ce pseudo")) null else it.avail,
                )
            }
        }
        onDone()
    }
}
