package com.tumemanque.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File

/** Envoyer des fichiers hors de Casque (WhatsApp, Bluetooth, Drive…). */
object Share {
    private fun shareable(ctx: Context, id: String): Uri? = runCatching {
        val u = Uri.parse(id)
        if (u.scheme == "file") FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(u.path ?: return null)) else u
    }.getOrNull()

    fun files(ctx: Context, ids: List<String>, mime: String, title: String) {
        val uris = ids.mapNotNull { shareable(ctx, it) }
        if (uris.isEmpty()) return
        val i = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        i.type = mime
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Nécessaire pour que l'appli choisie puisse lire tous les fichiers.
        val clip = ClipData.newRawUri("", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        i.clipData = clip
        runCatching {
            ctx.startActivity(Intent.createChooser(i, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun tracks(ctx: Context, ids: List<String>) =
        files(ctx, ids, "audio/*", if (ids.size == 1) "Envoyer le morceau" else "Envoyer ${ids.size} morceaux")

    fun videos(ctx: Context, ids: List<String>) =
        files(ctx, ids, "video/*", if (ids.size == 1) "Envoyer la vidéo" else "Envoyer ${ids.size} vidéos")
}

/** Choix d'une playlist (utilisable depuis n'importe quel écran). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPicker(ids: List<String>, onDone: () -> Unit) {
    val playlists by Lib.playlists.collectAsState()
    val favs by Lib.favs.collectAsState()
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        NameDialog("Nouvelle playlist", "", "Créer", { naming = false; onDone() }) { name ->
            Lib.createPlaylist(name, ids); naming = false; onDone()
        }
        return
    }
    ModalBottomSheet(onDismissRequest = onDone, containerColor = P.panel) {
        Column(Modifier.padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(
                if (ids.size == 1) "Ajouter à une playlist" else "Ajouter ${ids.size} morceaux à une playlist",
                color = P.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            PickRow(Ic.Add, "Nouvelle playlist…", P.blueSoft) { naming = true }
            PickRow(Ic.Star, "Favoris", P.text) { ids.forEach { if (it !in favs) Lib.toggleFav(it) }; onDone() }
            playlists.forEach { p ->
                PickRow(Ic.Queue, "${p.name}  ·  ${p.ids.size}", P.text) { Lib.addToPlaylist(p.id, ids); onDone() }
            }
        }
    }
}

@Composable
private fun PickRow(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        CIcon(icon, tint, 22.dp)
        Spacer(Modifier.width(16.dp))
        Text(label, color = tint, fontSize = 16.sp)
    }
}
