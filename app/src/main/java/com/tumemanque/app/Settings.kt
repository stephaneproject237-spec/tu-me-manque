package com.tumemanque.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SetPink = Color(0xFFE91E63)

/** Bouton « réglages » de l'écran d'accueil, avec une pastille quand une mise à jour est disponible. */
@Composable
fun SettingsGear(hasUpdate: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.TopEnd) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(Color(0xB3281C24))
                .border(1.dp, Color(0x1AFFFFFF), CircleShape).clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) { Ico(R.drawable.ic_settings, SetPink, 24.dp) }
        if (hasUpdate) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0xFFE53935)).border(2.dp, Color.White, CircleShape))
        }
    }
}

@Composable
fun SettingsScreen(upd: UpdateViewModel, username: String?, partner: String?, supportUnread: Int, onSupport: () -> Unit, onBack: () -> Unit) {
    val s by upd.state.collectAsState()
    val context = LocalContext.current
    BackHandler { onBack() }
    LaunchedEffect(Unit) { if (!s.checked) upd.check() }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color(0xB3281C24)).border(1.dp, Color(0x1AFFFFFF), CircleShape).clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) { Ico(R.drawable.ic_chevron, SetPink, 30.dp) }
            Spacer(Modifier.width(14.dp))
            Text("Réglages", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF5EEF2))
        }
        Spacer(Modifier.height(20.dp))

        GlassCard(Modifier.fillMaxWidth()) {
            Text("Mon pseudo", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(if (username.isNullOrEmpty()) "—" else "@$username", color = SetPink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (!partner.isNullOrEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Mon partenaire : @$partner", color = Color(0xFFB7A3AE), fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(14.dp))

        GlassCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !s.checking && !s.downloading) { upd.check() }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Version de l'application", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("Version ${BuildConfig.VERSION_NAME}", color = Color.Gray, fontSize = 14.sp)
                }
                if (s.checking) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                else Text("Vérifier", color = SetPink, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            val info = s.available
            when {
                s.downloading -> {
                    Text("Téléchargement de la version ${info?.name ?: ""}… ${(s.progress * 100).toInt()} %", fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(), color = SetPink)
                }
                s.ready && info != null -> {
                    Text(
                        if (s.needPermission) "Autorise l'installation pour « Tu me manques », puis reviens ici et touche « Installer »."
                        else "La version ${info.name} est prête à être installée.",
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    GlassButton("Installer", Modifier.fillMaxWidth()) { upd.install(context) }
                }
                info != null -> {
                    Text("🎉 La version ${info.name} est disponible !", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = SetPink)
                    if (info.notes.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(info.notes, fontSize = 14.sp, color = Color(0xFFE3D6DD))
                    }
                    Spacer(Modifier.height(12.dp))
                    GlassButton("Mettre à jour maintenant", Modifier.fillMaxWidth()) { upd.update(context) }
                }
                s.error -> {
                    Text("Impossible de vérifier pour le moment. Vérifie ta connexion.", fontSize = 14.sp, color = Color(0xFFD32F2F))
                    if (s.errorDetail.isNotBlank()) Text(s.errorDetail, fontSize = 11.sp, color = Color.Gray)
                }
                s.checking -> Text("Vérification en cours…", fontSize = 14.sp, color = Color.Gray)
                s.checked -> Text("✓ Tu as la dernière version.", fontSize = 14.sp, color = Color(0xFF66BB6A))
                else -> Text("Touche « Vérifier » pour chercher une mise à jour.", fontSize = 14.sp, color = Color.Gray)
            }
        }
        Spacer(Modifier.height(14.dp))

        GlassCard(Modifier.fillMaxWidth()) {
            Text("SAV", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text("Un problème ? Une idée ? Écris-nous, nous te répondons ici.", color = Color.Gray, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            GlassButton(if (supportUnread > 0) "🛟 Contacter le SAV ($supportUnread)" else "🛟 Contacter le SAV", Modifier.fillMaxWidth()) { onSupport() }
        }
    }
}
