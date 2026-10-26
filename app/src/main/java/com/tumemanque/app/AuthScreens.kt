package com.tumemanque.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun availText(a: String?): String = when (a) {
    "checking" -> "Vérification…"
    "available" -> "✓ Ce pseudo est disponible"
    "taken" -> "✗ Ce pseudo est déjà pris"
    "invalid" -> "3 à 20 caractères, commence par une lettre (a-z, 0-9 ou _)"
    "error" -> "Impossible de vérifier pour le moment"
    else -> ""
}

@Composable
private fun Msg(text: String?) {
    if (text != null) {
        Spacer(Modifier.height(12.dp))
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun PseudoAgeFields(s: AuthState, vm: AuthViewModel) {
    OutlinedTextField(
        value = s.pseudo,
        onValueChange = { vm.setPseudo(it) },
        label = { Text("Ton pseudo") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    val ok = s.avail == "available"
    Text(
        availText(s.avail),
        fontSize = 13.sp,
        color = if (ok) Color(0xFF2E7D32) else if (s.avail == "checking" || s.avail == null) Color.Gray else Color(0xFFC62828),
        modifier = Modifier.fillMaxWidth().height(22.dp),
    )
    ContactFields(s, vm)
}

@Composable
fun ContactFields(s: AuthState, vm: AuthViewModel) {
    var picker by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    OutlinedButton(onClick = { picker = true }, modifier = Modifier.fillMaxWidth()) {
        Text(if (s.birthOk) "🎂 " + birthPretty(s.birth) else "🎂 Choisir ma date de naissance")
    }
    if (picker) BirthPickerDialog(onPick = { vm.setBirth(it); picker = false }, onDismiss = { picker = false })
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = s.phone,
        onValueChange = { vm.setPhone(it) },
        label = { Text("Ton numéro de téléphone (avec l'indicatif)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Compte existant : on demande la date de naissance et le numéro une fois. */
@Composable
fun ContactScreen(s: AuthState, vm: AuthViewModel, onSkip: () -> Unit, onDone: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("🎂", fontSize = 56.sp)
        Spacer(Modifier.height(8.dp))
        Text("Complète ton profil", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Ajoute ta date de naissance et ton numéro de téléphone.", textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        ContactFields(s, vm)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { vm.saveContact(onDone) },
            enabled = s.contactOk,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Enregistrer") }
        Msg(s.msg)
        TextButton(onClick = onSkip) { Text("Plus tard") }
    }
}

@Composable
private fun MethodButtons(s: AuthState, vm: AuthViewModel, needOk: Boolean) {
    val context = LocalContext.current
    val enabled = !s.busy && (!needOk || s.canContinue)
    Button(
        onClick = { vm.startOAuth(context, "google") },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Continuer avec Google") }
    if (Config.APPLE_LOGIN) {
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { vm.startOAuth(context, "apple") },
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Continuer avec Apple") }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { vm.phoneStart() },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Recevoir un code par SMS") }
}

/** Accueil, création de compte, connexion, numéro de téléphone et code SMS. */
@Composable
fun AuthScreen(s: AuthState, vm: AuthViewModel, onDone: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        when (s.screen) {
            "create" -> {
                Text("💗", fontSize = 56.sp)
                Text("Créer mon compte", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Choisis ton pseudo : ton partenaire s'en servira pour te retrouver.", textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                PseudoAgeFields(s, vm)
                Spacer(Modifier.height(8.dp))
                Text("Puis choisis comment te connecter :", color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                MethodButtons(s, vm, needOk = true)
                Msg(s.msg)
                TextButton(onClick = { vm.goWelcome() }) { Text("Retour") }
            }
            "login" -> {
                Text("💗", fontSize = 56.sp)
                Text("Me connecter", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Utilise la même méthode que lors de la création de ton compte pour retrouver ton pseudo et tes conversations.",
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                MethodButtons(s, vm, needOk = false)
                Msg(s.msg)
                TextButton(onClick = { vm.goWelcome() }) { Text("Retour") }
            }
            "phone" -> {
                Text("Ton numéro", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Nous t'envoyons un code par SMS pour confirmer ton numéro.", textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = s.phone,
                    onValueChange = { vm.setPhone(it) },
                    label = { Text("Numéro (avec l'indicatif)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.sendCode() },
                    enabled = s.phoneOk && !s.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Envoyer le code") }
                Msg(s.msg)
                TextButton(onClick = { vm.phoneBack() }) { Text("Retour") }
            }
            "code" -> {
                Text("Entre le code", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Code envoyé par SMS au ${Backend.e164(s.phone)}.", textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = s.code,
                    onValueChange = { vm.setCode(it) },
                    label = { Text("Code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.verifyCode(onDone) },
                    enabled = s.code.length >= 6 && !s.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Valider") }
                Msg(s.msg)
                TextButton(onClick = { vm.sendCode() }, enabled = !s.busy) { Text("Renvoyer le code") }
                TextButton(onClick = { vm.phoneStart() }) { Text("Changer de numéro") }
            }
            else -> {
                Text("💗", fontSize = 56.sp)
                Text("Tu me manques", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Un petit mot, un bouton, et la personne que tu aimes sait que tu penses à elle.",
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = { vm.goCreate() }, modifier = Modifier.fillMaxWidth()) { Text("Créer un compte") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.goLogin() }, modifier = Modifier.fillMaxWidth()) { Text("J'ai déjà un compte") }
                Msg(s.msg)
            }
        }
    }
}

/** Connecté mais sans pseudo : on demande le pseudo et l'âge. */
@Composable
fun ProfileScreen(s: AuthState, vm: AuthViewModel, onDone: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("💗", fontSize = 56.sp)
        Spacer(Modifier.height(8.dp))
        Text("Bienvenue", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Choisis ton pseudo : ton partenaire s'en servira pour te retrouver.", textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        PseudoAgeFields(s, vm)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { vm.createProfile(onDone) },
            enabled = s.canContinue,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Créer mon compte") }
        Msg(s.msg)
    }
}
