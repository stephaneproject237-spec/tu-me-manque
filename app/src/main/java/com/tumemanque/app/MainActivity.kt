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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
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

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private val authViewModel: AuthViewModel by viewModels()

    /** Retour de « Continuer avec Google » : tumemanque://auth?code=… */
    private fun handleLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "tumemanque") return
        intent.data = null
        authViewModel.handleLink(uri) { viewModel.start() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleLink(intent)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFFE91E63),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFFFD9E4),
                    onPrimaryContainer = Color(0xFF3E0021),
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                    NotificationPermission()
                    Root(viewModel, authViewModel)
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
    var showChat by rememberSaveable { mutableStateOf(false) }
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

    if (showChat && state.partnerName != null) {
        ChatScreen(
            partner = partnerLabel(state),
            vm = chatVm,
            onBack = { showChat = false },
            onRead = { vm.setUnread(0) },
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
            state.partnerName == null -> PartnerScreen(state, vm) { shareApp(context, state.username) }
            else -> HomeScreen(state, vm) { showChat = true }
        }

        Spacer(Modifier.height(24.dp))
        Text("Version ${BuildConfig.VERSION_NAME}", fontSize = 11.sp, color = Color.LightGray)
    }

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
private fun PartnerScreen(state: UiState, vm: AppViewModel, onInvite: () -> Unit) {
    var partner by rememberSaveable { mutableStateOf("") }
    Text("Salut 👋", fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text("Ajoute ton partenaire avec son pseudo ou son numéro de téléphone.", textAlign = TextAlign.Center)

    state.incoming.forEach { req ->
        Spacer(Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
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
}

@Composable
private fun HomeScreen(state: UiState, vm: AppViewModel, onOpenChat: () -> Unit) {
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
    Button(
        onClick = { vm.sendMiss(false) },
        enabled = !state.busy,
        shape = CircleShape,
        modifier = Modifier.size(240.dp),
    ) {
        Text("Tu me manques 💗", fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = onOpenChat, modifier = Modifier.fillMaxWidth()) {
        Text(if (state.unread > 0) "💬 Chat  (${state.unread})" else "💬 Chat", fontSize = 16.sp)
    }
    Message(state)

    state.lastMiss?.let { miss ->
        Spacer(Modifier.height(32.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("@${miss.from}", color = Color.Gray)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (miss.isReply) "Tu me manques aussi 💗" else "Tu me manques 💗",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!miss.isReply) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { vm.sendMiss(true); onOpenChat() }, enabled = !state.busy) {
                        Text("Tu me manques aussi")
                    }
                }
            }
        }
    }

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
