package com.tumemanque.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

class MainActivity : androidx.fragment.app.FragmentActivity() {
    private var locked by androidx.compose.runtime.mutableStateOf(false)
    private var stoppedAt = 0L
    private val viewModel: AppViewModel by viewModels()
    private val authViewModel: AuthViewModel by viewModels()

    /** Retour de « Continuer avec Google » : tumemanque://auth?code=… */
    private fun handleLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != BuildConfig.OAUTH_SCHEME) return
        intent.data = null
        authViewModel.handleLink(uri) { viewModel.start() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = System.currentTimeMillis()
    }

    /** Verrouillage : au lancement, et au retour dans l'appli après plus de 30 secondes d'absence. */
    override fun onStart() {
        super.onStart()
        if (AppLock.enabled(this) && stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 30_000) locked = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locked = AppLock.enabled(this)
        handleLink(intent)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFE91E63),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFF4A1230),
                    onPrimaryContainer = Color(0xFFFFD9E4),
                    background = Color(0xFF160A12),
                    onBackground = Color(0xFFF5EEF2),
                    surface = Color(0xFF261820),
                    onSurface = Color(0xFFF5EEF2),
                    onSurfaceVariant = Color(0xFFB7A3AE),
                    surfaceVariant = Color(0xFF3A2A33),
                    outline = Color(0xFF6B5A64),
                ),
                shapes = Shapes(
                    extraSmall = RoundedCornerShape(16.dp),
                    small = RoundedCornerShape(16.dp),
                    medium = RoundedCornerShape(20.dp),
                ),
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent, contentColor = Color(0xFFF5EEF2)) {
                    AppBackground()
                    NotificationPermission()
                    Root(viewModel, authViewModel)
                    if (locked) LockScreen { locked = false }
                }
            }
        }
    }
}

@Composable
private fun NotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}

private fun shareApp(context: Context, username: String?) {
    val link = BuildConfig.APK_URL
    val text = buildString {
        append("Je t'invite sur « Tu me manques » 💗")
        if (link.isNotEmpty()) append("\nAndroid : $link")
        append("\niPhone : ${Config.WEB_URL} (à ouvrir dans Safari, puis « Sur l'écran d'accueil »)")
        if (!username.isNullOrEmpty()) append("\nMon identifiant : @$username")
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Envoyer l'application"))
}

@Composable
private fun Root(vm: AppViewModel, authVm: AuthViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val chatVm: ChatViewModel = viewModel()
    val supportVm: SupportViewModel = viewModel()
    val updateVm: UpdateViewModel = viewModel()
    val remVm: ReminderViewModel = viewModel()
    val updState by updateVm.state.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { updateVm.check(force = false, context = context) }
    var crash by remember { mutableStateOf(CrashLog.take(context)) }
    crash?.let { trace ->
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("L'appli s'est fermée. Envoie une capture de cet écran à Claude :", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(trace, fontSize = 10.sp)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { crash = null }) { Text("Continuer") }
        }
        return
    }
    // « Casque » : la première page est le lecteur audio ; la messagerie est derrière « Paramètres » → « Aide ».
    var casqueScreen by rememberSaveable { mutableStateOf("player") }
    // Casque : il faut être connecté (pseudo, date de naissance, numéro) avant d'arriver au lecteur.
    // Un compte « Tu me manques » existant est repris tel quel : rien n'est redemandé.
    var skipContact by rememberSaveable { mutableStateOf(false) }
    val casqueLogin = BuildConfig.CASQUE && !state.loading &&
        (state.needAuth || (state.error == null && (state.username == null || (state.needContact && !skipContact && !BuildConfig.CASQUE))))
    if (BuildConfig.CASQUE && casqueScreen != "help" && !casqueLogin) {
        CasqueMain(casqueScreen, updateVm, updState.available != null, state.username) { casqueScreen = it }
        return
    }
    val authState by authVm.state.collectAsStateWithLifecycle()
    var askedContacts by rememberSaveable { mutableStateOf(false) }
    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.loadContacts(context)
    }

    // Les noms de tes contacts remplacent les pseudos (Android seulement).
    LaunchedEffect(state.partnerPhone) {
        if (state.partnerPhone != null) {
            if (Contacts.hasPermission(context)) vm.loadContacts(context)
            else if (!askedContacts) {
                askedContacts = true
                contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
        }
    }
    var fxTrigger by remember { mutableIntStateOf(0) }
    var loveTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(chatVm) { chatVm.onLove = { loveTrigger++ } }
    LaunchedEffect(state.lastMiss?.at) {
        val at = state.lastMiss?.at
        if (!at.isNullOrEmpty()) {
            val prefs = context.getSharedPreferences("fx", Context.MODE_PRIVATE)
            if (prefs.getString("last", "") != at) {
                prefs.edit().putString("last", at).apply()
                fxTrigger++
            }
        }
    }
    var showChat by rememberSaveable { mutableStateOf(false) }
    var showSupport by rememberSaveable { mutableStateOf(false) }
    var autoOpened by rememberSaveable { mutableStateOf("") }

    // Plus de partenaire : on ferme le chat et on oublie la conversation.
    LaunchedEffect(state.partnerName, state.loading) {
        if (!state.loading && state.partnerName == null) {
            showChat = false
            chatVm.reset()
        }
    }
    // Après « Tu me manques » puis « Tu me manques aussi », le chat s'ouvre tout seul (une seule fois).
    LaunchedEffect(state.lastMiss) {
        val m = state.lastMiss
        if (m != null && m.isReply && autoOpened != m.at && state.partnerName != null) {
            autoOpened = m.at
            showChat = true
            vm.clearMiss()
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.refresh()
                delay(4000)
            }
        }
    }

    if (BuildConfig.CASQUE) {
        androidx.activity.compose.BackHandler(enabled = !casqueLogin && !showSettings && !showSupport && !(showChat && state.partnerName != null)) { casqueScreen = "settings" }
    }
    if (showSettings) {
        SettingsScreen(updateVm, state.username, state.partnerName, state.supportUnread, onSupport = { showSettings = false; showSupport = true }) { showSettings = false }
    } else if (showSupport) {
        SupportScreen(
            supportVm, onBack = { showSupport = false }, onRead = { vm.setSupportUnread(0) },
            onEnded = { showSupport = false; vm.setSupportHas(false) },
        )
    } else if (showChat && state.partnerName != null) {
        ChatScreen(
            partner = partnerLabel(state),
            vm = chatVm,
            wall = state.wall,
            onWall = { vm.setWall(it) },
            onBack = { showChat = false },
            onRead = { vm.setUnread(0) },
        )
    } else if (BuildConfig.CASQUE && !state.loading && !state.needAuth && state.error == null && state.username != null && !(state.needContact && !skipContact && !BuildConfig.CASQUE)) {
        CasqueFriends(
            state, vm, chatVm,
            onBack = { casqueScreen = "settings" },
            onInvite = { shareApp(context, state.username) },
            onOpenChat = { showChat = true },
        )
    } else Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            state.loading -> CircularProgressIndicator()
            state.needAuth -> AuthScreen(authState, authVm) { vm.start() }
            state.error != null -> {
                Text(state.error!!, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { vm.start() }) { Text("Réessayer") }
            }
            state.username == null -> ProfileScreen(authState, authVm) { vm.start() }
            state.needContact && !skipContact && !BuildConfig.CASQUE -> ContactScreen(authState, authVm, onSkip = { skipContact = true }) { vm.start() }
            state.partnerName == null -> PartnerScreen(state, vm, onSupport = { showSupport = true }) { shareApp(context, state.username) }
            else -> HomeScreen(state, vm, onSupport = { showSupport = true }) { showChat = true }
        }

        Spacer(Modifier.height(24.dp))
        Text("Version ${BuildConfig.VERSION_NAME}", fontSize = 11.sp, color = Color.LightGray)
    }

    ReminderAlarmHost(remVm, state.partnerName != null, partnerLabel(state))
    if (state.partnerName != null) CallHost(partnerLabel(state))
    if (state.partnerName != null) NowPicHost(partnerLabel(state))

    state.offerInvite?.let { name ->
        AlertDialog(
            onDismissRequest = { vm.dismissInvite() },
            title = { Text("Compte introuvable") },
            text = {
                val shown = if (name.any { it.isLetter() || it == '_' }) "@$name" else name
                Text("$shown n'a pas encore de compte. Cette personne n'a peut-être pas l'application. Veux-tu la lui envoyer ?")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissInvite()
                    shareApp(context, state.username)
                }) { Text("Envoyer l'application") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissInvite() }) { Text("Annuler") }
            },
        )
    }

    val onHome = !showSettings && !showSupport && !(showChat && state.partnerName != null)
    if (BuildConfig.CASQUE && !casqueLogin && onHome && (state.loading || state.needAuth || state.error != null || state.username == null || state.needContact && !skipContact)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), Alignment.TopStart) {
            TextButton(onClick = { casqueScreen = "settings" }) { Text("‹ Retour") }
        }
    }
    if (!BuildConfig.CASQUE && onHome && !state.loading && !state.needAuth && state.error == null && state.username != null) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), Alignment.TopEnd) {
            SettingsGear(hasUpdate = updState.available != null) { showSettings = true }
        }
    }

    FlutterOverlay(fxTrigger)
    FlutterOverlay(loveTrigger, heartsOnly = true)
}

private fun filterTarget(input: String): String =
    input.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it in "_+ ()-" }.take(24)

@Composable
private fun Message(state: UiState) {
    state.message?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PartnerScreen(state: UiState, vm: AppViewModel, onSupport: () -> Unit, onInvite: () -> Unit) {
    var partner by rememberSaveable { mutableStateOf("") }
    Text("Salut 👋", fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text("Ajoute ton partenaire avec son pseudo ou son numéro de téléphone.", textAlign = TextAlign.Center)

    state.incoming.forEach { req ->
        Spacer(Modifier.height(16.dp))
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("@${req.fromName} veut être ton partenaire 💗", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.respond(req.id, true) }, enabled = !state.busy) { Text("Accepter") }
                    OutlinedButton(onClick = { vm.respond(req.id, false) }, enabled = !state.busy) { Text("Refuser") }
                }
            }
        }
    }

    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = partner,
        onValueChange = { partner = filterTarget(it) },
        label = { Text("Pseudo ou numéro de ton partenaire") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { vm.addPartner(partner) },
        enabled = partner.trim().length >= 3 && !state.busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Envoyer la demande") }

    state.outgoing.forEach { req ->
        Spacer(Modifier.height(12.dp))
        Text("En attente de @${req.toName}…", color = Color.Gray)
    }

    Message(state)
    Spacer(Modifier.height(24.dp))
    TextButton(onClick = onInvite) { Text("Inviter quelqu'un à installer l'appli") }
    SupportButton(state, onSupport)
}

@Composable
private fun SupportButton(state: UiState, onClick: () -> Unit) {
    if (!state.supportHas) return
    val unread = state.supportUnread
    TextButton(onClick = onClick) {
        Text(if (unread > 0) "🛟 Écrire à @admin  ($unread)" else "🛟 Écrire à @admin")
    }
}

@Composable
private fun HomeScreen(state: UiState, vm: AppViewModel, onSupport: () -> Unit, onOpenChat: () -> Unit) {
    val context = LocalContext.current
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("moi  💗  ", fontSize = 18.sp, color = Color.Gray)
        Text(
            partnerLabel(state),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .clickable(enabled = !state.busy) { confirmRemove = true }
                .padding(8.dp),
        )
    }
    Text("Touche son pseudo pour retirer ton partenaire", fontSize = 12.sp, color = Color.LightGray)
    Spacer(Modifier.height(32.dp))
    BigMissButton(enabled = !state.busy) {
        Haptics.beat(context)
        vm.sendMiss(false)
    }
    Spacer(Modifier.height(20.dp))
    ChatEntryButton(partnerLabel(state), state.unread, modifier = Modifier.fillMaxWidth(), onClick = onOpenChat)
    Message(state)

    state.lastMiss?.let { miss ->
        Spacer(Modifier.height(32.dp))
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("@${miss.from}", color = Color.Gray)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (miss.isReply) "Tu me manques aussi 💗" else "Tu me manques 💗",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!miss.isReply) {
                    Spacer(Modifier.height(12.dp))
                    GlassButton("Tu me manques aussi", enabled = !state.busy) {
                        Haptics.beat(context)
                        vm.sendMiss(true)
                        onOpenChat()
                    }
                }
            }
        }
    }

    SupportButton(state, onSupport)

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Retirer ${partnerLabel(state)} ?") },
            text = { Text("Vous ne serez plus liés. Vous pourrez chacun ajouter quelqu'un d'autre.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    vm.removePartner()
                }) { Text("Retirer") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Annuler") }
            },
        )
    }
}
