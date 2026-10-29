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
    /** Date de naissance saisie : jj/mm/aaaa */
    val birth: String = "",
    /** null, checking, available, taken, invalid ou error */
    val avail: String? = null,
    val phone: String = "+237",
    val code: String = "",
    val busy: Boolean = false,
    val msg: String? = null,
    /** E-mail du compte Google / Apple connecté. */
    val email: String = "",
    /** Le numéro est déjà connu (compte Google / Apple / SMS) : on ne le redemande pas. */
    val phoneKnown: Boolean = false,
) {
    /** Date au format AAAA-MM-JJ, ou null si elle est invalide. */
    val birthIso: String? get() = parseBirth(birth)?.first
    val age: Int? get() = parseBirth(birth)?.second
    val birthOk: Boolean get() = age?.let { it in 5..120 } == true
    val phoneOk: Boolean get() = phone.count { it.isDigit() } >= 8
    val canContinue: Boolean get() = avail == "available" && birthOk && phoneOk && !busy
    val contactOk: Boolean get() = birthOk && phoneOk && !busy
}

private val PSEUDO = Regex("^[a-z][a-z0-9_]{2,19}$")

/** « 05/03/1998 » → ("1998-03-05", âge) ; null si ce n'est pas une vraie date. */
fun parseBirth(v: String): Pair<String, Int>? {
    val m = Regex("^(\\d{2})/(\\d{2})/(\\d{4})$").matchEntire(v) ?: return null
    val (dd, mm, yy) = m.destructured.let { Triple(it.component1().toInt(), it.component2().toInt(), it.component3().toInt()) }
    if (mm !in 1..12 || dd !in 1..31 || yy < 1900) return null
    val cal = java.util.GregorianCalendar(yy, mm - 1, dd).apply { isLenient = false }
    return try {
        cal.timeInMillis // lève une exception si la date n'existe pas (30 février…)
        val now = java.util.Calendar.getInstance()
        var age = now.get(java.util.Calendar.YEAR) - yy
        if (now.get(java.util.Calendar.MONTH) + 1 < mm || (now.get(java.util.Calendar.MONTH) + 1 == mm && now.get(java.util.Calendar.DAY_OF_MONTH) < dd)) age--
        Pair("%04d-%02d-%02d".format(yy, mm, dd), age)
    } catch (e: Exception) { null }
}

class AuthViewModel : ViewModel() {
    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()
    private var availJob: Job? = null

    fun goCreate() = _state.update { AuthState(screen = "create", mode = "create") }
    fun goLogin() = _state.update { AuthState(screen = "login", mode = "login") }
    fun goWelcome() = _state.update { AuthState() }
    fun phoneStart() = _state.update { it.copy(screen = "phone", msg = null) }
    fun phoneBack() = _state.update { it.copy(screen = if (it.mode == "create") "create" else "login", msg = null) }

    /** Saisie de la date : on ajoute les « / » toute seule (jj/mm/aaaa). */
    fun setBirth(v: String) {
        val d = v.filter { it.isDigit() }.take(8)
        val out = buildString {
            d.forEachIndexed { i, c -> if (i == 2 || i == 4) append('/'); append(c) }
        }
        _state.update { it.copy(birth = out) }
    }
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
        if (provider == "apple" && !BuildConfig.CASQUE) {
            viewModelScope.launch {
                if (!Backend.providerEnabled("apple")) {
                    _state.update { it.copy(msg = "La connexion avec Apple n'est pas encore activée. Utilise Google pour le moment.") }
                } else openOAuth(context, provider)
            }
            return
        }
        openOAuth(context, provider)
    }

    private fun openOAuth(context: Context, provider: String) {
        val s = _state.value
        val uri = if (s.mode == "create") Backend.oauthUri(provider, s.pseudo, s.age, s.birthIso, s.phone)
        else Backend.oauthUri(provider, null, null, null, null)
        // Onglet Chrome léger (déjà préchauffé par l'écran de connexion) : bien plus rapide qu'un navigateur complet qui démarre à froid.
        runCatching {
            val tab = androidx.browser.customtabs.CustomTabsIntent.Builder().setShowTitle(false).setShareState(androidx.browser.customtabs.CustomTabsIntent.SHARE_STATE_OFF).build()
            tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            tab.launchUrl(context, uri)
        }.onFailure {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** À appeler quand l'écran de connexion s'affiche : Chrome démarre en avance, la page Google s'ouvre presque tout de suite. */
    fun warmUpBrowser(context: Context) {
        runCatching {
            val pkg = androidx.browser.customtabs.CustomTabsClient.getPackageName(context.applicationContext, null) ?: return
            androidx.browser.customtabs.CustomTabsClient.connectAndInitialize(context.applicationContext, pkg)
        }
    }

    /** Reprend l'e-mail et le numéro du compte connecté : on ne redemande pas ce qu'on connaît déjà. */
    fun loadKnown() {
        if (BuildConfig.CASQUE) return
        val email = Backend.sessionEmail()
        val ph = Backend.sessionPhone()
        _state.update {
            it.copy(
                email = email,
                phone = if (ph.length >= 8) "+$ph" else it.phone,
                phoneKnown = ph.length >= 8,
            )
        }
    }

    /** Retour de Google / Apple : tumemanque://auth?code=… */
    fun handleLink(uri: Uri, onDone: () -> Unit) {
        if (uri.scheme != BuildConfig.OAUTH_SCHEME) return
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
                when (Backend.createAccount(s.pseudo, s.age ?: 0)) {
                    "ok", "has_username" -> {
                        if (s.phoneOk) s.birthIso?.let { Backend.saveContact(it, s.phone) }
                        onDone()
                    }
                    "taken" -> _state.update { it.copy(avail = "taken", msg = "Ce pseudo est déjà pris. Choisis-en un autre.") }
                    "invalid", "invalid_age" -> _state.update { it.copy(msg = "Vérifie ton pseudo et ta date de naissance.") }
                    else -> _state.update { it.copy(msg = "Impossible de créer le compte.") }
                }
            } catch (e: Exception) {
                _state.update { it.copy(msg = "Connexion impossible. Vérifie ta connexion internet.") }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    /** Compte existant : enregistre la date de naissance et le numéro. */
    fun saveContact(onDone: () -> Unit) {
        viewModelScope.launch {
            val s = _state.value
            val iso = s.birthIso ?: return@launch
            _state.update { it.copy(busy = true, msg = null) }
            try {
                when (Backend.saveContact(iso, s.phone)) {
                    "ok" -> onDone()
                    "phone_taken" -> _state.update { it.copy(msg = "Ce numéro est déjà utilisé par un autre compte.") }
                    "invalid_birth" -> _state.update { it.copy(msg = "Vérifie ta date de naissance.") }
                    "invalid_phone" -> _state.update { it.copy(msg = "Vérifie ton numéro (avec l'indicatif).") }
                    else -> _state.update { it.copy(msg = "Impossible d'enregistrer pour le moment.") }
                }
            } catch (e: Exception) {
                _state.update { it.copy(msg = "Connexion impossible. Vérifie ta connexion internet.") }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    private suspend fun finish(onDone: () -> Unit) {
        loadKnown()
        val s = _state.value
        val note = Backend.finishAuth(s.pseudo.ifEmpty { null }, s.age, s.birthIso, s.phone)
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
