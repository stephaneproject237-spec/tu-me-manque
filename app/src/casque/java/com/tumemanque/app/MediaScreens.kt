package com.tumemanque.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        LargeTitle("Téléchargements") {
            if (done.isNotEmpty()) GlassChip("Effacer", null, P.blueSoft) { Dl.clearFinished() }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 4.dp, bottom = bottom + 12.dp)) {
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

@Composable
private fun DownloadRow(t: DlTask) {
    val ctx = LocalContext.current
    val ok = t.status == DlStatus.DONE && Dl.stillThere(t)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).glass(RoundedCornerShape(20.dp), 0.05f)
            .clickable(enabled = ok) { openResult(ctx, t) }.padding(10.dp),
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
                    color = when (t.status) { DlStatus.FAILED -> P.danger; DlStatus.DONE -> if (ok) P.sub else P.sub; else -> P.blueSoft },
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
            if (!t.active && t.status != DlStatus.FAILED && ok) {
                Spacer(Modifier.width(6.dp))
                RoundBtn(Ic.Close, { Dl.remove(t.id) }, size = 30.dp, iconSize = 14.dp, fill = Color.Transparent, tint = P.sub)
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
}

// =============================================================================================
//  Vidéos téléchargées
// =============================================================================================

@Composable
fun VideosScreen(onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val vids by Vids.items.collectAsState()
    val bottom = LocalBottomInset.current
    var deleting by remember { mutableStateOf<VideoItem?>(null) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        LargeTitle("Vidéos") { RoundBtn(Ic.Gear, onSettings) }
        if (vids.isEmpty()) {
            EmptyState(
                Ic.VideoLib, "Pas encore de vidéo",
                "Les clips que tu télécharges en MP4 s'affichent ici. Tu peux les regarder sans connexion.",
                "Trouver une vidéo",
            ) { Shell.tab = "search" }
            return@Column
        }
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = bottom + 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(2) }) {
                Text("${vids.size} vidéo(s) · ${fmtBytes(vids.sumOf { it.size })}", color = P.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
            }
            items(vids, key = { it.id }) { v ->
                VideoTile(v, onPlay = { Vp.playLocal(ctx, v); Vp.fullscreen = true }, onDelete = { deleting = v })
            }
        }
    }

    deleting?.let { v ->
        AlertDialog(
            onDismissRequest = { deleting = null }, containerColor = P.card2,
            title = { Text("Supprimer cette vidéo ?", color = P.text) },
            text = { Text("« ${v.video.title} » sera supprimée du téléphone.", color = P.sub, fontSize = 14.sp) },
            confirmButton = {
                TextButton({ if (Vp.key == v.id) Vp.stop(); Vids.delete(ctx, v.id); deleting = null }) { Text("Supprimer", color = P.danger) }
            },
            dismissButton = { TextButton({ deleting = null }) { Text("Annuler", color = P.sub) } },
        )
    }
}

@Composable
private fun VideoTile(v: VideoItem, onPlay: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val thumb = remember(v.id) { Covers.load(ctx, v.id, 480) }
    val pos = remember(v.id, Vp.key) { Vids.position(v.id) }
    Column(Modifier.pressable(onPlay)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)).background(P.card2)) {
            if (thumb != null) androidx.compose.foundation.Image(thumb.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else AsyncImage(model = v.video.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.Center).size(42.dp).glass(CircleShape, 0.2f), contentAlignment = Alignment.Center) { CIcon(Ic.Play, Color.White, 24.dp) }
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
            Box {
                Box(Modifier.size(28.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) { CIcon(Ic.More, P.sub, 18.dp) }
                DropdownMenu(menu, { menu = false }, containerColor = P.card2) {
                    DropdownMenuItem(text = { Text("Regarder", color = P.text) }, onClick = { menu = false; onPlay() })
                    if (v.id.startsWith("content://")) DropdownMenuItem(text = { Text("Partager", color = P.text) }, onClick = {
                        menu = false
                        runCatching {
                            val i = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, Uri.parse(v.id))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            ctx.startActivity(Intent.createChooser(i, "Partager la vidéo").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    })
                    DropdownMenuItem(text = { Text("Supprimer", color = P.danger) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
