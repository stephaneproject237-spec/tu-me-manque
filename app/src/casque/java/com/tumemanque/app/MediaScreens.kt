package com.tumemanque.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

// =============================================================================================
//  Téléchargements
// =============================================================================================

@Composable
fun DownloadsScreen() {
    val tasks by Dl.tasks.collectAsState()
    val bottom = LocalBottomInset.current
    val active = tasks.filter { it.active }
    val done = tasks.filter { !it.active && it.status != DlStatus.CANCELED }

    GlassScaffold(header = {
        LargeTitle("Téléchargements") {
            if (done.isNotEmpty()) GlassChip("Effacer", null, P.blueSoft) { Dl.clearFinished() }
        }
    }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp + LocalTopInset.current, bottom = bottom + 12.dp)) {
            if (tasks.none { it.status != DlStatus.CANCELED }) {
                item {
                    EmptyState(
                        Ic.Download, "Aucun téléchargement",
                        "Cherche une chanson dans « Rechercher », puis choisis MP3 ou vidéo. Tout ce que tu télécharges apparaît ici, puis dans Audios ou Vidéos.",
                        "Rechercher",
                    ) { Shell.tab = "search" }
                }
            }
            if (active.isNotEmpty()) {
                item { SectionTitle("EN COURS · ${active.size}") }
                items(active, key = { it.id }) { t -> DownloadRow(t) }
            }
            if (done.isNotEmpty()) {
                item { SectionTitle("TERMINÉS", Modifier.padding(top = 8.dp)) }
                items(done, key = { it.id }) { t -> DownloadRow(t) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(t: DlTask) {
    val ctx = LocalContext.current
    val ok = t.status == DlStatus.DONE && Dl.stillThere(t)
    var menu by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).glass(RoundedCornerShape(20.dp), 0.05f)
            .combinedClickable(onClick = { if (ok) openResult(ctx, t) }, onLongClick = { if (ok) menu = true }).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(88.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(P.card2)) {
                AsyncImage(model = t.video.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(
                    Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(if (t.kind.video) Color(0xCC1B2333) else Color(0xCC1B4FB8))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) { Text(t.kind.ext.uppercase(), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.video.title, color = P.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    when (t.status) {
                        DlStatus.DONE -> "${t.kind.label} · ${fmtBytes(t.size)}" + if (!ok) " · supprimé" else ""
                        DlStatus.FAILED -> t.error ?: "Échec"
                        DlStatus.RUNNING -> "${t.kind.label} · ${(t.progress * 100).toInt()} %" + if (t.eta > 0) " · ${fmt(t.eta * 1000)} restant" else ""
                        else -> "${t.kind.label} · ${t.phase}"
                    },
                    color = when (t.status) { DlStatus.FAILED -> P.danger; DlStatus.DONE -> P.sub; else -> P.blueSoft },
                    fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            when {
                t.active -> RoundBtn(Ic.Close, { Dl.cancel(t.id) }, size = 36.dp, iconSize = 18.dp)
                t.status == DlStatus.FAILED -> RoundBtn(Ic.Refresh, { Dl.retry(ctx, t.id) }, size = 36.dp, iconSize = 18.dp, tint = P.blueSoft)
                ok -> RoundBtn(Ic.Play, { openResult(ctx, t) }, size = 36.dp, iconSize = 20.dp, fill = P.blue, tint = Color.White)
                else -> RoundBtn(Ic.Delete, { Dl.remove(t.id) }, size = 36.dp, iconSize = 18.dp, tint = P.sub)
            }
            if (ok) {
                Box {
                    RoundBtn(Ic.More, { menu = true }, size = 34.dp, iconSize = 18.dp, fill = Color.Transparent, tint = P.sub)
                    DropdownMenu(menu, { menu = false }, containerColor = P.card2) {
                        DropdownMenuItem(text = { Text(if (t.kind.video) "Regarder" else "Écouter", color = P.text) }, onClick = { menu = false; openResult(ctx, t) })
                        DropdownMenuItem(text = { Text("Envoyer hors de Free tune…", color = P.text) }, onClick = {
                            menu = false
                            t.resultId?.let { if (t.kind.video) Share.videos(ctx, listOf(it)) else Share.tracks(ctx, listOf(it)) }
                        })
                        if (!t.kind.video) DropdownMenuItem(text = { Text("Ajouter à une playlist…", color = P.text) }, onClick = { menu = false; pick = true })
                        DropdownMenuItem(text = { Text("Retirer de la liste", color = P.text) }, onClick = { menu = false; Dl.remove(t.id) })
                        DropdownMenuItem(text = { Text("Supprimer le fichier", color = P.danger) }, onClick = { menu = false; del = true })
                    }
                }
            } else if (t.status == DlStatus.FAILED) {
                Spacer(Modifier.width(6.dp))
                RoundBtn(Ic.Close, { Dl.remove(t.id) }, size = 30.dp, iconSize = 14.dp, fill = Color.Transparent, tint = P.sub)
            }
        }
        if (t.active) {
            Spacer(Modifier.height(10.dp))
            if (t.status == DlStatus.RUNNING && t.progress > 0f) {
                LinearProgressIndicator(
                    progress = { t.progress }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = P.blueSoft, trackColor = Color(0x1AFFFFFF), drawStopIndicator = {},
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)), color = P.blueSoft, trackColor = Color(0x1AFFFFFF))
            }
            if (t.status == DlStatus.PROCESSING) Text(t.phase, color = P.sub, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (pick) t.resultId?.let { id -> PlaylistPicker(listOf(id)) { pick = false } }
    if (del) {
        AlertDialog(
            onDismissRequest = { del = false }, containerColor = P.card2,
            title = { Text("Supprimer le fichier ?", color = P.text) },
            text = { Text("« ${t.video.title} » (${t.kind.label}) sera effacé du téléphone.", color = P.sub, fontSize = 14.sp) },
            confirmButton = {
                TextButton({
                    del = false
                    val id = t.resultId
                    if (id != null) {
                        if (t.kind.video) { if (Vp.key == id) Vp.stop(); Vids.delete(ctx, id) } else Lib.deleteTracks(listOf(id))
                    }
                    Dl.remove(t.id)
                }) { Text("Supprimer", color = P.danger) }
            },
            dismissButton = { TextButton({ del = false }) { Text("Annuler", color = P.sub) } },
        )
    }
}

// =============================================================================================
//  Vidéos téléchargées
// =============================================================================================

@Composable
fun VideosScreen(onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val vids by Vids.items.collectAsState()
    val bottom = LocalBottomInset.current
    var deleting by remember { mutableStateOf<List<String>?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }

    GlassScaffold(header = {
        if (selecting) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundBtn(Ic.Close, { selected = emptySet() })
                Spacer(Modifier.width(12.dp))
                Text("${selected.size} sélectionnée(s)", color = P.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                RoundBtn(Ic.Check, { selected = if (selected.size == vids.size) emptySet() else vids.map { it.id }.toSet() })
                Spacer(Modifier.width(8.dp))
                RoundBtn(Ic.Share, { Share.videos(ctx, selected.toList()) })
                Spacer(Modifier.width(8.dp))
                RoundBtn(Ic.Delete, { deleting = selected.toList() }, fill = P.danger, tint = Color.White)
            }
        } else {
            LargeTitle("Vidéos") { RoundBtn(Ic.Gear, onSettings) }
        }
    }) {
        if (vids.isEmpty()) {
            Box(Modifier.padding(top = LocalTopInset.current)) { EmptyState(
                Ic.VideoLib, "Pas encore de vidéo",
                "Les clips que tu télécharges en MP4 s'affichent ici. Tu peux les regarder sans connexion.",
                "Trouver une vidéo",
            ) { Shell.tab = "search" } }
            return@GlassScaffold
        }
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp + LocalTopInset.current, bottom = bottom + 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(2) }) {
                Text(
                    if (selecting) "Touche les vidéos pour les sélectionner" else "${vids.size} vidéo(s) · ${fmtBytes(vids.sumOf { it.size })} · appui long pour sélectionner",
                    color = P.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
            }
            items(vids, key = { it.id }) { v ->
                val sel = v.id in selected
                VideoTile(
                    v, sel, selecting,
                    onClick = {
                        if (selecting) selected = if (sel) selected - v.id else selected + v.id
                        else { Vp.playLocal(ctx, v); Vp.fullscreen = true }
                    },
                    onLong = { selected = if (sel) selected - v.id else selected + v.id },
                    onDelete = { deleting = listOf(v.id) },
                )
            }
        }
    }

    deleting?.let { ids ->
        AlertDialog(
            onDismissRequest = { deleting = null }, containerColor = P.card2,
            title = { Text(if (ids.size == 1) "Supprimer cette vidéo ?" else "Supprimer ${ids.size} vidéos ?", color = P.text) },
            text = { Text("Elles seront effacées du téléphone.", color = P.sub, fontSize = 14.sp) },
            confirmButton = {
                TextButton({
                    ids.forEach { id -> if (Vp.key == id) Vp.stop(); Vids.delete(ctx, id) }
                    deleting = null; selected = emptySet()
                }) { Text("Supprimer", color = P.danger) }
            },
            dismissButton = { TextButton({ deleting = null }) { Text("Annuler", color = P.sub) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoTile(v: VideoItem, selected: Boolean, selecting: Boolean, onClick: () -> Unit, onLong: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val thumb = remember(v.id) { Covers.load(ctx, v.id, 480) }
    val pos = remember(v.id, Vp.key) { Vids.position(v.id) }
    Column(Modifier.clip(RoundedCornerShape(18.dp)).combinedClickable(onClick = onClick, onLongClick = onLong)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)).background(P.card2)) {
            if (thumb != null) androidx.compose.foundation.Image(thumb.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else AsyncImage(model = v.video.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (selected) {
                Box(Modifier.fillMaxSize().background(Color(0x992F7BFF)), contentAlignment = Alignment.Center) { CIcon(Ic.Check, Color.White, 34.dp) }
            } else if (selecting) {
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp).glass(CircleShape, 0.15f))
            } else {
                Box(Modifier.align(Alignment.Center).size(42.dp).glass(CircleShape, 0.2f), contentAlignment = Alignment.Center) { CIcon(Ic.Play, Color.White, 24.dp) }
            }
            Text(
                fmt(v.durationMs), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xAA000000)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
            if (pos > 0 && v.durationMs > 0) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x55FFFFFF))) {
                    Box(Modifier.fillMaxWidth((pos.toFloat() / v.durationMs).coerceIn(0f, 1f)).height(3.dp).background(P.blueSoft))
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(v.video.title, color = P.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${v.video.channel} · ${v.format}", color = P.sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!selecting) Box {
                Box(Modifier.size(28.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) { CIcon(Ic.More, P.sub, 18.dp) }
                DropdownMenu(menu, { menu = false }, containerColor = P.card2) {
                    DropdownMenuItem(text = { Text("Regarder", color = P.text) }, onClick = { menu = false; onClick() })
                    DropdownMenuItem(text = { Text("Sélectionner", color = P.text) }, onClick = { menu = false; onLong() })
                    DropdownMenuItem(text = { Text("Envoyer hors de Free tune…", color = P.text) }, onClick = { menu = false; Share.videos(ctx, listOf(v.id)) })
                    DropdownMenuItem(text = { Text("Supprimer", color = P.danger) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
