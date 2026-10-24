package com.tumemanque.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    Root(viewModel)
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
        if (link.isNotEmpty()) append("\nTélécharge l'application : $link")
        if (!username.isNullOrEmpty()) append("\nMon identifiant : @$username")
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Envoyer l'application"))
}

@Composable
private fun Root(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.refresh()
                delay(4000)
            }
        }
    }

    Column(
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
            state.error != null -> {
                Text(state.error!!, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { vm.start() }) { Text("Réessayer") }
            }
            state.username == null -> UsernameScreen(state, vm)
            state.partnerName == null -> PartnerScreen(state, vm) { shareApp(context, state.username) }
            else -> HomeScreen(state, vm)
        }
    }

    state.offerInvite?.let { name ->
        AlertDialog(
            onDismissRequest = { vm.dismissInvite() },
            title = { Text("Identifiant introuvable") },
            text = {
                Text("@$name n'existe pas encore. Cette personne n'a peut-être pas l'application. Veux-tu la lui envoyer ?")
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

private fun filterName(input: String): String =
    input.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(20)

@Composable
private fun Message(state: UiState) {
    state.message?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun UsernameScreen(state: UiState, vm: AppViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    Text("💗", fontSize = 56.sp)
    Spacer(Modifier.height(8.dp))
    Text("Bienvenue", fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text(
        "Choisis ton identifiant. Ton partenaire s'en servira pour te retrouver.",
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = name,
        onValueChange = { name = filterName(it) },
        label = { Text("Ton identifiant") },
        prefix = { Text("@") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { vm.claimUsername(name) },
        enabled = name.length >= 3 && !state.busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Valider") }
    Message(state)
}

@Composable
private fun PartnerScreen(state: UiState, vm: AppViewModel, onInvite: () -> Unit) {
    var partner by rememberSaveable { mutableStateOf("") }
    Text("Salut @${state.username} 👋", fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text("Ajoute ton partenaire avec son identifiant.", textAlign = TextAlign.Center)

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
        onValueChange = { partner = filterName(it) },
        label = { Text("Identifiant de ton partenaire") },
        prefix = { Text("@") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { vm.addPartner(partner) },
        enabled = partner.length >= 3 && !state.busy,
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
private fun HomeScreen(state: UiState, vm: AppViewModel) {
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    Text("@${state.username}  💗  @${state.partnerName}", fontSize = 18.sp, color = Color.Gray)
    Spacer(Modifier.height(32.dp))
    Button(
        onClick = { vm.sendMiss(false) },
        enabled = !state.busy,
        shape = CircleShape,
        modifier = Modifier.size(240.dp),
    ) {
        Text("Tu me manques 💗", fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
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
                    OutlinedButton(onClick = { vm.sendMiss(true) }, enabled = !state.busy) {
                        Text("Tu me manques aussi")
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(32.dp))
    TextButton(onClick = { confirmRemove = true }, enabled = !state.busy) {
        Text("Retirer mon partenaire", color = Color.Gray)
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Retirer @${state.partnerName} ?") },
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
