package com.tumemanque.app

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Conditions d'utilisation de « Casque » (Free tune) : affichées une seule fois, à la création d'un nouveau compte. */
private const val CASQUE_TERMS = """Article 1 — Objet. Les présentes conditions générales d'utilisation (ci-après « les Conditions ») ont pour objet de définir les modalités et conditions dans lesquelles l'utilisateur (ci-après « l'Utilisateur ») est autorisé à accéder à l'application Free tune (ci-après « l'Application ») et à en faire usage. L'accès à l'Application et la création d'un compte associé emportent l'acceptation pleine, entière et sans réserve des présentes Conditions. À défaut d'acceptation expresse par l'Utilisateur, l'accès à l'Application lui est refusé et la procédure d'inscription ne peut être menée à son terme.

Article 2 — Finalité de l'Application. L'Application a été conçue exclusivement dans un but de divertissement et de facilitation de l'accès aux contenus multimédias par l'Utilisateur. Elle permet à ce dernier de rechercher, d'écouter et, le cas échéant, de conserver localement des contenus audio et vidéo.

Article 3 — Accès au réseau et usage des données mobiles. L'Utilisateur reconnaît et accepte que l'Application requiert, pour son fonctionnement normal, un accès à la connexion internet de l'appareil sur lequel elle est installée, qu'il s'agisse d'une connexion de données mobiles ou d'un réseau local sans fil. Cet accès est utilisé par l'Application afin de rechercher et de récupérer, depuis des services tiers accessibles en ligne, les contenus multimédias demandés par l'Utilisateur. Ce dernier demeure seul responsable des coûts éventuels liés à sa consommation de données, selon les modalités de son abonnement auprès de son opérateur.

Article 4 — Accès aux fichiers multimédias locaux. Afin de proposer à l'Utilisateur une expérience d'écoute continue et cohérente, l'Utilisateur autorise l'Application à accéder aux fichiers musicaux déjà présents sur son appareil. Cet accès a pour seule finalité de permettre l'intégration desdits fichiers dans les listes d'écoute et les fonctionnalités de lecture proposées par l'Application, sans que ceux-ci ne soient transmis, copiés ou communiqués à des tiers autrement que dans le cadre strict décrit par les présentes Conditions.

Article 5 — Compte utilisateur et données associées. La création d'un compte au sein de l'Application peut nécessiter la communication de certaines informations d'identification, telles qu'un identifiant, une adresse électronique ou toute autre donnée requise lors du processus d'inscription.

Article 6 — Responsabilité. L'exploitant de l'Application met tout en œuvre pour assurer un fonctionnement normal et continu du service, sans toutefois pouvoir garantir une disponibilité ininterrompue, le bon fonctionnement pouvant être affecté par des circonstances extérieures telles que des interruptions de réseau, des indisponibilités de services tiers ou des cas de force majeure. L'exploitant ne saurait être tenu responsable des dommages directs ou indirects résultant de l'usage de l'Application, dans la mesure permise par la réglementation applicable.

Article 7 — Modification des Conditions. Les présentes Conditions peuvent être amenées à évoluer, notamment pour tenir compte de modifications fonctionnelles de l'Application ou d'évolutions réglementaires. Toute modification substantielle fera l'objet d'une information appropriée auprès des Utilisateurs. La poursuite de l'utilisation de l'Application après une telle modification vaut acceptation des Conditions ainsi mises à jour.

Article 8 — Entraide entre appareils. L'Utilisateur autorise par ailleurs l'Application à utiliser son appareil Android, dans les limites de ce que permettent ses réglages, pour venir en aide à d'autres utilisateurs de l'Application connectés depuis un appareil iPhone, en servant ponctuellement de relais au sein d'un réseau de partage entre pairs (p2p) destiné à leur transmettre les contenus qu'ils ont demandés."""

@Composable
private fun TermsBlock(accepted: Boolean, onChange: (Boolean) -> Unit) {
    Spacer(Modifier.height(16.dp))
    Box(
        Modifier.fillMaxWidth().height(180.dp)
            .border(1.dp, Color.Gray.copy(alpha = 0.4f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
    ) { Text(CASQUE_TERMS, fontSize = 12.sp, color = Color.Gray) }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = accepted, onCheckedChange = onChange)
        Text("J'ai lu et j'accepte les conditions d'utilisation.", fontSize = 13.sp)
    }
}

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
    if (!s.phoneKnown) OutlinedTextField(
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
private fun MethodButtons(s: AuthState, vm: AuthViewModel, needOk: Boolean, sms: Boolean = true) {
    val context = LocalContext.current
    val enabled = !s.busy && (!needOk || s.canContinue)
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.warmUpBrowser(context) }
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
    if (sms) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { vm.phoneStart() },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Recevoir un code par SMS") }
    }
}

/** Google d'abord, pseudo ensuite : même parcours pour « Tu me manques » et « Casque ». */
private val GOOGLE_FIRST: Boolean get() = true

/** Accueil, création de compte, connexion, numéro de téléphone et code SMS. */
@Composable
fun AuthScreen(s: AuthState, vm: AuthViewModel, onDone: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        when (s.screen) {
            "create" -> if (GOOGLE_FIRST) {
                Text(if (BuildConfig.CASQUE) "🎧" else "💗", fontSize = 56.sp)
                Text("Créer mon compte", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Crée ton compte avec ton compte Google. Tu choisiras ton pseudo juste après.",
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                MethodButtons(s, vm, needOk = false, sms = false)
                Msg(s.msg)
                TextButton(onClick = { vm.goWelcome() }) { Text("Retour") }
            } else {
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
            "login" -> if (GOOGLE_FIRST) {
                Text("💗", fontSize = 56.sp)
                Text("Me connecter", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Connecte-toi avec ton compte Google pour retrouver ton pseudo et tes conversations.",
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                MethodButtons(s, vm, needOk = false, sms = false)
                Msg(s.msg)
                TextButton(onClick = { vm.phoneStart() }) { Text("Compte créé avec un numéro de téléphone ?") }
                TextButton(onClick = { vm.goWelcome() }) { Text("Retour") }
            } else {
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
                    if (BuildConfig.CASQUE) "Ta musique et tes vidéos, partout, même hors connexion." else "Un petit mot, un bouton, et la personne que tu aimes sait que tu penses à elle.",
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

/** Connecté mais sans pseudo : on demande le pseudo (et la date de naissance si elle n'est pas déjà connue). */
@Composable
fun ProfileScreen(s: AuthState, vm: AuthViewModel, onDone: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadKnown() }
    val casque = BuildConfig.CASQUE
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(if (casque) "🎧" else "💗", fontSize = 56.sp)
        Spacer(Modifier.height(8.dp))
        Text(if (casque) "Choisis ton pseudo" else "Bienvenue", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (s.email.isNotEmpty()) Text("Compte Google : ${s.email}", color = Color.Gray, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            if (casque) "Ton compte Google est prêt. Choisis le pseudo sous lequel tes amis te retrouveront, puis connecte-toi."
            else "Choisis ton pseudo : ton partenaire s'en servira pour te retrouver.",
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        if (casque) {
            OutlinedTextField(
                value = s.pseudo, onValueChange = { vm.setPseudo(it) }, label = { Text("Ton pseudo") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                availText(s.avail), fontSize = 13.sp,
                color = if (s.avail == "available") Color(0xFF2E7D32) else if (s.avail == "checking" || s.avail == null) Color.Gray else Color(0xFFC62828),
                modifier = Modifier.fillMaxWidth().height(22.dp),
            )
            if (!s.birthOk) {
                var picker by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
                OutlinedButton(onClick = { picker = true }, modifier = Modifier.fillMaxWidth()) { Text("🎂 Choisir ma date de naissance") }
                if (picker) BirthPickerDialog(onPick = { vm.setBirth(it); picker = false }, onDismiss = { picker = false })
                Spacer(Modifier.height(8.dp))
            }
            TermsBlock(accepted = s.termsAccepted, onChange = { vm.setTermsAccepted(it) })
        } else {
            PseudoAgeFields(s, vm)
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { vm.createProfile(onDone) },
            enabled = if (casque) (s.avail == "available" && s.birthOk && s.termsAccepted && !s.busy) else s.canContinue,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (casque) "Se connecter" else "Créer mon compte") }
        Msg(s.msg)
    }
}
