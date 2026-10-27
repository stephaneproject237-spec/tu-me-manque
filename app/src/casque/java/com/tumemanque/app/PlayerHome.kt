package com.tumemanque.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed class Detail {
    data class Album(val name: String) : Detail()
    data class Artist(val name: String) : Detail()
    data class Pl(val id: String) : Detail()
    data class Dir(val path: String) : Detail()
}

private val TABS = listOf("Titres", "Albums", "Artistes", "Playlists", "Dossiers")
private val SORTS = listOf("Titre (A → Z)", "Artiste", "Album", "Ajoutés récemment", "Durée")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerHome(onSettings: () -> Unit, hasUpdate: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tracks by Lib.tracks.collectAsState()
    val byId by Lib.byId.collectAsState()
    val scan by Lib.scan.collectAsState()
    val playlists by Lib.playlists.collectAsState()
    val favs by Lib.favs.collectAsState()
    val sources by Lib.sources.collectAsState()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var stack by remember { mutableStateOf(listOf<Detail>()) }
    var showNow by rememberSaveable { mutableStateOf(false) }
    // Ouverture du lecteur demandée depuis le mini-lecteur flottant.
    LaunchedEffect(Shell.openNow) { if (Shell.openNow > 0) { Shell.openNow = 0; showNow = true } }
    LaunchedEffect(showNow) { Shell.nowOpen = showNow }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var pickFor by remember { mutableStateOf<List<String>?>(null) }
    var infoFor by remember { mutableStateOf<Track?>(null) }
    var newPlaylistFor by remember { mutableStateOf<List<String>?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var selectedDirs by remember { mutableStateOf(setOf<String>()) }
    var deleteTracksFor by remember { mutableStateOf<List<String>?>(null) }
    var deleteDirsFor by remember { mutableStateOf<Set<String>?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scope.launch { Lib.scanPhone(ctx) }
    }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Lib.addSource(ctx, uri)
            scope.launch { Lib.scanSources(ctx) }
        }
    }
    fun scanPhone() {
        if (Lib.hasPermission(ctx)) scope.launch { Lib.scanPhone(ctx) } else permLauncher.launch(Lib.permission())
    }

    // Au lancement : on met la liste à jour discrètement (sans explorer tout le stockage).
    LaunchedEffect(Unit) {
        if (Lib.hasPermission(ctx)) Lib.quickRefresh(ctx)
    }

    val detail = stack.lastOrNull()
    BackHandler(enabled = showNow) { showNow = false }
    BackHandler(enabled = !showNow && selected.isNotEmpty()) { selected = emptySet() }
    BackHandler(enabled = !showNow && selectedDirs.isNotEmpty()) { selectedDirs = emptySet() }
    BackHandler(enabled = !showNow && selected.isEmpty() && selectedDirs.isEmpty() && stack.isNotEmpty()) { stack = stack.dropLast(1) }
    BackHandler(enabled = !showNow && selected.isEmpty() && selectedDirs.isEmpty() && stack.isEmpty() && searching) { searching = false; query = "" }

    val currentTrack = Hub.currentId?.let { byId[it] }

    Box(Modifier.fillMaxSize().background(P.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // ---------------------------------------------------------------- barre du haut
            if (selectedDirs.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundBtn(Ic.Close, { selectedDirs = emptySet() })
                    Spacer(Modifier.width(12.dp))
                    Text("${selectedDirs.size} dossier(s) sélectionné(s)", color = P.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    RoundBtn(Ic.Delete, { deleteDirsFor = selectedDirs }, fill = P.danger, tint = Color.White)
                }
            } else if (selected.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundBtn(Ic.Close, { selected = emptySet() })
                    Spacer(Modifier.width(12.dp))
                    Text("${selected.size} sélectionné(s)", color = P.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    RoundBtn(Ic.Queue, {
                        Hub.enqueue(selected.mapNotNull { byId[it] }); selected = emptySet()
                    })
                    Spacer(Modifier.width(8.dp))
                    RoundBtn(Ic.Share, { Share.tracks(ctx, selected.toList()) })
                    Spacer(Modifier.width(8.dp))
                    RoundBtn(Ic.PlaylistAdd, { pickFor = selected.toList() })
                    Spacer(Modifier.width(8.dp))
                    RoundBtn(Ic.Delete, { deleteTracksFor = selected.toList() }, fill = P.danger, tint = Color.White)
                }
            } else if (detail == null) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Audios", color = P.text, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, modifier = Modifier.weight(1f))
                    RoundBtn(Ic.Search, { searching = !searching; if (!searching) query = ""; if (searching) tab = 0 }, fill = if (searching) P.blue else P.card, tint = if (searching) Color.White else P.text)
                    Spacer(Modifier.width(8.dp))
                    RoundBtn(Ic.Refresh, { scope.launch { if (Lib.hasPermission(ctx)) Lib.refreshAll(ctx) else scanPhone() } })
                    Spacer(Modifier.width(8.dp))
                    Box {
                        RoundBtn(R_SETTINGS, onSettings)
                        if (hasUpdate) Box(Modifier.align(Alignment.TopEnd).size(12.dp).clip(CircleShape).background(P.danger))
                    }
                }
                if (searching) {
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).glass(RoundedCornerShape(22.dp), 0.08f)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        BasicTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            textStyle = TextStyle(color = P.text, fontSize = 16.sp), cursorBrush = SolidColor(P.blue),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (query.isEmpty()) Text("Chercher dans tes audios…", color = P.sub, fontSize = 16.sp)
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TABS.forEachIndexed { i, t -> Pill(t, tab == i) { tab = i } }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundBtn(Ic.Back, { stack = stack.dropLast(1) })
                    Spacer(Modifier.width(12.dp))
                    Text(
                        when (detail) {
                            is Detail.Album -> detail.name
                            is Detail.Artist -> detail.name
                            is Detail.Dir -> detail.path.substringAfterLast('/')
                            is Detail.Pl -> if (detail.id == FAV_ID) "Favoris" else if (detail.id == "recent") "Ajoutés récemment" else playlists.firstOrNull { it.id == detail.id }?.name ?: "Playlist"
                        },
                        color = P.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ---------------------------------------------------------------- état de l'analyse
            if (scan.running) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = P.blue, trackColor = P.card)
                    Text("${scan.message}${if (scan.found > 0) " · ${scan.found}" else ""}", color = P.sub, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }

            // ---------------------------------------------------------------- contenu
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val q = query.trim().lowercase()
                if (tracks.isEmpty() && !scan.running) {
                    Onboarding(scan.message, onScan = { scanPhone() }, onFolder = { treeLauncher.launch(null) })
                } else if (q.isNotEmpty() && detail == null) {
                    val res = tracks.filter { q in it.title.lowercase() || q in it.artist.lowercase() || q in it.album.lowercase() }
                    TrackList(
                        res, "Aucun résultat pour « $query »", favs, selected, { selected = it },
                        { l, i -> Hub.play(l, i) }, { pickFor = it }, { infoFor = it }, header = null,
                        onDelete = { deleteTracksFor = it },
                    )
                } else if (detail != null) {
                    DetailView(
                        detail, tracks, byId, playlists, favs, selected, { selected = it },
                        { pickFor = it }, { infoFor = it }, { stack = emptyList() },
                        onDelete = { deleteTracksFor = it },
                    )
                } else when (tab) {
                    0 -> {
                        val sorted = when (sort) {
                            0 -> tracks.sortedBy { it.title.lowercase() }
                            1 -> tracks.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.title.lowercase() }))
                            2 -> tracks.sortedWith(compareBy({ it.album.lowercase() }, { it.title.lowercase() }))
                            3 -> tracks.sortedByDescending { it.added }
                            else -> tracks.sortedByDescending { it.durationMs }
                        }
                        TrackList(
                            sorted, "Aucun morceau", favs, selected, { selected = it },
                            { l, i -> Hub.play(l, i) }, { pickFor = it }, { infoFor = it },
                            onDelete = { deleteTracksFor = it },
                            header = {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        BlueButton("Aléatoire", Modifier.weight(1f), Ic.Shuffle) { Hub.play(tracks.shuffled(), 0, true) }
                                        BlueButton("Tout lire", Modifier.weight(1f), Ic.Play, outlined = true) { Hub.play(sorted, 0, false) }
                                    }
                                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("${tracks.size} morceaux · ${fmtTotal(tracks.sumOf { it.durationMs })}", color = P.sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                        Box {
                                            Row(Modifier.clip(RoundedCornerShape(50)).clickable { sortMenu = true }.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                CIcon(Ic.Sort, P.sub, 16.dp); Spacer(Modifier.width(6.dp))
                                                Text(SORTS[sort], color = P.sub, fontSize = 12.sp)
                                            }
                                            DropdownMenu(sortMenu, { sortMenu = false }, containerColor = P.card2) {
                                                SORTS.forEachIndexed { i, s ->
                                                    DropdownMenuItem(text = { Text(s, color = if (i == sort) P.blueSoft else P.text) }, onClick = { sort = i; sortMenu = false })
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                        )
                    }
                    1 -> AlbumsGrid(tracks) { stack = stack + Detail.Album(it) }
                    2 -> ArtistsList(tracks) { stack = stack + Detail.Artist(it) }
                    3 -> PlaylistsTab(
                        tracks, playlists, favs,
                        onOpen = { stack = stack + Detail.Pl(it) },
                        onNew = { newPlaylistFor = emptyList() },
                    )
                    else -> FoldersTab(
                        tracks, sources, scan.running,
                        onOpen = { stack = stack + Detail.Dir(it) },
                        onAddFolder = { treeLauncher.launch(null) },
                        onRemove = { Lib.removeSource(it) },
                        onScanPhone = { scanPhone() },
                        onRescan = { scope.launch { Lib.scanSources(ctx) } },
                        selectedDirs = selectedDirs, setSelectedDirs = { selectedDirs = it },
                    )
                }
            }

            // Le mini-lecteur et la barre d'onglets flottent par-dessus (voir CasqueShell).
        }

        AnimatedVisibility(
            showNow && currentTrack != null,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
        ) {
            if (currentTrack != null) {
                NowPlaying(
                    currentTrack, favs, onClose = { showNow = false },
                    onAddToPlaylist = { pickFor = listOf(it.id) }, onInfo = { infoFor = it },
                )
            }
        }

        // Dialogues
        pickFor?.let { ids ->
            ModalBottomSheet(onDismissRequest = { pickFor = null }, containerColor = P.panel) {
                Column(Modifier.padding(bottom = 24.dp)) {
                    Text("Ajouter à une playlist", color = P.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    SheetRow(Ic.Add, "Nouvelle playlist…", P.blueSoft) { newPlaylistFor = ids; pickFor = null }
                    SheetRow(Ic.Star, "Favoris", P.text) {
                        ids.forEach { if (it !in favs) Lib.toggleFav(it) }; pickFor = null; selected = emptySet()
                    }
                    playlists.forEach { p ->
                        SheetRow(Ic.Queue, "${p.name}  ·  ${p.ids.size}", P.text) {
                            Lib.addToPlaylist(p.id, ids); pickFor = null; selected = emptySet()
                        }
                    }
                }
            }
        }
        newPlaylistFor?.let { ids ->
            NameDialog("Nouvelle playlist", "", "Créer", { newPlaylistFor = null }) { name ->
                Lib.createPlaylist(name, ids); newPlaylistFor = null; selected = emptySet()
            }
        }
        deleteTracksFor?.let { ids ->
            AlertDialog(
                onDismissRequest = { deleteTracksFor = null }, containerColor = P.card2,
                title = { Text(if (ids.size == 1) "Supprimer ce morceau ?" else "Supprimer ${ids.size} morceaux ?", color = P.text) },
                text = {
                    Text(
                        "Ils n'apparaîtront plus dans la bibliothèque, les playlists, les favoris ni la file d'attente. " +
                            "Les morceaux téléchargés avec Casque sont aussi effacés du téléphone ; les autres fichiers y restent.",
                        color = P.sub, fontSize = 14.sp,
                    )
                },
                confirmButton = { TextButton({ Lib.deleteTracks(ids); deleteTracksFor = null; selected = emptySet() }) { Text("Supprimer", color = P.danger) } },
                dismissButton = { TextButton({ deleteTracksFor = null }) { Text("Annuler", color = P.sub) } },
            )
        }
        deleteDirsFor?.let { dirs ->
            val n = tracks.count { it.folder in dirs }
            AlertDialog(
                onDismissRequest = { deleteDirsFor = null }, containerColor = P.card2,
                title = { Text(if (dirs.size == 1) "Supprimer ce dossier ?" else "Supprimer ${dirs.size} dossiers ?", color = P.text) },
                text = {
                    Text(
                        "Les $n morceau(x) qu'il contient n'apparaîtront plus dans la bibliothèque ni dans les playlists. Les fichiers restent sur ton téléphone.",
                        color = P.sub, fontSize = 14.sp,
                    )
                },
                confirmButton = { TextButton({ Lib.deleteFolders(dirs); deleteDirsFor = null; selectedDirs = emptySet() }) { Text("Supprimer", color = P.danger) } },
                dismissButton = { TextButton({ deleteDirsFor = null }) { Text("Annuler", color = P.sub) } },
            )
        }
        infoFor?.let { t ->
            AlertDialog(
                onDismissRequest = { infoFor = null }, containerColor = P.card2,
                title = { Text(t.title, color = P.text) },
                text = {
                    Text(
                        "Artiste : ${t.artist}\nAlbum : ${t.album}\nDurée : ${fmt(t.durationMs)}\nTaille : ${fmtSize(t.size)}\nDossier : ${t.folder}",
                        color = P.sub, fontSize = 14.sp,
                    )
                },
                confirmButton = { TextButton({ infoFor = null }) { Text("Fermer", color = P.blueSoft) } },
            )
        }
    }
}

private val R_SETTINGS get() = Ic.Gear

@Composable
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        CIcon(icon, tint, 22.dp)
        Spacer(Modifier.width(16.dp))
        Text(label, color = tint, fontSize = 16.sp)
    }
}

@Composable
fun NameDialog(title: String, initial: String, ok: String, onCancel: () -> Unit, onOk: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel, containerColor = P.card2,
        title = { Text(title, color = P.text) },
        text = {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(P.card).padding(14.dp)) {
                BasicTextField(
                    v, { v = it.take(40) }, singleLine = true, textStyle = TextStyle(color = P.text, fontSize = 16.sp),
                    cursorBrush = SolidColor(P.blue), modifier = Modifier.fillMaxWidth(),
                )
                if (v.isEmpty()) Text("Nom", color = P.sub, fontSize = 16.sp)
            }
        },
        confirmButton = { TextButton({ if (v.isNotBlank()) onOk(v) }) { Text(ok, color = P.blueSoft) } },
        dismissButton = { TextButton(onCancel) { Text("Annuler", color = P.sub) } },
    )
}

@Composable
private fun Onboarding(message: String, onScan: () -> Unit, onFolder: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 32.dp, bottom = 32.dp + LocalBottomInset.current), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(
            Modifier.size(110.dp).clip(CircleShape).background(Brush.linearGradient(listOf(P.blue, P.blueDeep))),
            contentAlignment = Alignment.Center,
        ) { CIcon(Ic.Headphones, Color.White, 60.dp) }
        Spacer(Modifier.height(24.dp))
        Text("Ta musique, partout", color = P.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Scanne ton téléphone pour retrouver tous tes fichiers mp3, ou choisis le dossier où se trouvent tes chansons.",
            color = P.sub, fontSize = 15.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        BlueButton("Scanner le téléphone", Modifier.fillMaxWidth(), Ic.Search) { onScan() }
        Spacer(Modifier.height(12.dp))
        BlueButton("Choisir un dossier source", Modifier.fillMaxWidth(), Ic.FolderAdd, outlined = true) { onFolder() }
        if (message.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(message, color = P.sub, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun TrackList(
    list: List<Track>, empty: String, favs: Set<String>, selected: Set<String>, setSelected: (Set<String>) -> Unit,
    onPlay: (List<Track>, Int) -> Unit, onAddToPlaylist: (List<String>) -> Unit, onInfo: (Track) -> Unit,
    header: (@Composable () -> Unit)?, extraMenu: (Track) -> List<Pair<String, () -> Unit>> = { emptyList() },
    onDelete: ((List<String>) -> Unit)? = null,
) {
    val selecting = selected.isNotEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp + LocalBottomInset.current)) {
        if (header != null) item(key = "header") { header() }
        if (list.isEmpty()) item(key = "empty") {
            Text(empty, color = P.sub, modifier = Modifier.fillMaxWidth().padding(40.dp), textAlign = TextAlign.Center)
        }
        items(list, key = { it.id }) { t ->
            TrackRow(
                t, Hub.currentId == t.id, Hub.playing, t.id in selected, selecting, t.id in favs,
                onClick = {
                    if (selecting) setSelected(if (t.id in selected) selected - t.id else selected + t.id)
                    else onPlay(list, list.indexOf(t))
                },
                onLong = { setSelected(if (t.id in selected) selected - t.id else selected + t.id) },
                menu = {
                    TrackMenu(
                        t, t.id in favs, { onAddToPlaylist(listOf(t.id)) }, { onInfo(t) },
                        extraMenu(t) + (if (onDelete != null) listOf("Supprimer de Casque" to { onDelete(listOf(t.id)) }) else emptyList()),
                    )
                },
            )
        }
    }
}

@Composable
private fun AlbumsGrid(tracks: List<Track>, onOpen: (String) -> Unit) {
    val albums = remember(tracks) {
        tracks.groupBy { it.album }.map { (name, l) -> Triple(name, l.first().artist, l) }.sortedBy { it.first.lowercase() }
    }
    if (albums.isEmpty()) { Text("Aucun album", color = P.sub, modifier = Modifier.fillMaxWidth().padding(40.dp), textAlign = TextAlign.Center); return }
    LazyVerticalGrid(
        GridCells.Fixed(2), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp + LocalBottomInset.current),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        gridItems(albums, key = { it.first }) { (name, artist, l) ->
            Column(Modifier.clickable { onOpen(name) }) {
                androidx.compose.foundation.layout.BoxWithConstraints {
                    Cover(l.first(), maxWidth, 14.dp)
                }
                Text(name, color = P.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Text("$artist · ${l.size}", color = P.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ArtistsList(tracks: List<Track>, onOpen: (String) -> Unit) {
    val artists = remember(tracks) { tracks.groupBy { it.artist }.toList().sortedBy { it.first.lowercase() } }
    if (artists.isEmpty()) { Text("Aucun artiste", color = P.sub, modifier = Modifier.fillMaxWidth().padding(40.dp), textAlign = TextAlign.Center); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp + LocalBottomInset.current)) {
        items(artists, key = { it.first }) { (name, l) ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(name) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(P.card2), contentAlignment = Alignment.Center) {
                    Text(name.take(1).uppercase(), color = P.blueSoft, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, color = P.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${l.size} morceau(x) · ${l.map { it.album }.distinct().size} album(s)", color = P.sub, fontSize = 12.sp)
                }
                CIcon(Ic.ChevronRight, P.sub, 20.dp)
            }
        }
    }
}

@Composable
private fun PlaylistsTab(
    tracks: List<Track>, playlists: List<Playlist>, favs: Set<String>, onOpen: (String) -> Unit, onNew: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp + LocalBottomInset.current)) {
        item {
            Row(Modifier.fillMaxWidth().clickable { onNew() }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(P.blue), contentAlignment = Alignment.Center) { CIcon(Ic.Add, Color.White, 28.dp) }
                Spacer(Modifier.width(14.dp))
                Text("Nouvelle playlist", color = P.blueSoft, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        item { PlaylistRow("Favoris", "${favs.count { f -> tracks.any { it.id == f } }} morceau(x)", Ic.Star) { onOpen(FAV_ID) } }
        item { PlaylistRow("Ajoutés récemment", "Les 50 derniers morceaux", Ic.Refresh) { onOpen("recent") } }
        items(playlists, key = { it.id }) { p ->
            PlaylistRow(p.name, "${p.ids.size} morceau(x)", Ic.Queue) { onOpen(p.id) }
        }
    }
}

@Composable
private fun PlaylistRow(name: String, sub: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(P.card2), contentAlignment = Alignment.Center) { CIcon(icon, P.blueSoft, 26.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = P.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = P.sub, fontSize = 12.sp)
        }
        CIcon(Ic.ChevronRight, P.sub, 20.dp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FoldersTab(
    tracks: List<Track>, sources: List<String>, scanning: Boolean, onOpen: (String) -> Unit,
    onAddFolder: () -> Unit, onRemove: (String) -> Unit, onScanPhone: () -> Unit, onRescan: () -> Unit,
    selectedDirs: Set<String>, setSelectedDirs: (Set<String>) -> Unit,
) {
    val selecting = selectedDirs.isNotEmpty()
    val dirs = remember(tracks) { tracks.groupBy { it.folder }.toList().sortedBy { it.first.lowercase() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp + LocalBottomInset.current)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BlueButton("Scanner le téléphone", Modifier.weight(1f), Ic.Search) { onScanPhone() }
                    BlueButton("Ajouter un dossier", Modifier.weight(1f), Ic.FolderAdd, outlined = true) { onAddFolder() }
                }
            }
        }
        if (sources.isNotEmpty()) {
            item { SectionTitle("DOSSIERS SOURCES") }
            items(sources, key = { "s$it" }) { s ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CIcon(Ic.Folder, P.blueSoft, 24.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(Lib.treeName(s), color = P.text, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    RoundBtn(Ic.Refresh, { if (!scanning) onRescan() }, size = 38.dp, iconSize = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    RoundBtn(Ic.Delete, { onRemove(s) }, size = 38.dp, iconSize = 18.dp, tint = P.danger)
                }
            }
        }
        item { SectionTitle("TOUS LES DOSSIERS") }
        if (dirs.isEmpty()) item { Text("Aucun dossier pour l'instant", color = P.sub, modifier = Modifier.padding(24.dp)) }
        items(dirs, key = { "d${it.first}" }) { (path, l) ->
            val sel = path in selectedDirs
            val toggle = { setSelectedDirs(if (sel) selectedDirs - path else selectedDirs + path) }
            Row(
                Modifier.fillMaxWidth().background(if (sel) Color(0x262F7BFF) else Color.Transparent)
                    .combinedClickable(onClick = { if (selecting) toggle() else onOpen(path) }, onLongClick = { toggle() })
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(if (sel) P.blue else P.card2), contentAlignment = Alignment.Center) {
                    CIcon(if (sel) Ic.Check else Ic.Folder, if (sel) Color.White else P.blueSoft, 24.dp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(path.substringAfterLast('/'), color = P.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${l.size} morceau(x) · $path", color = P.sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!selecting) CIcon(Ic.ChevronRight, P.sub, 20.dp)
            }
        }
    }
}

@Composable
private fun DetailView(
    d: Detail, all: List<Track>, byId: Map<String, Track>, playlists: List<Playlist>, favs: Set<String>,
    selected: Set<String>, setSelected: (Set<String>) -> Unit,
    onAddToPlaylist: (List<String>) -> Unit, onInfo: (Track) -> Unit, onGone: () -> Unit,
    onDelete: ((List<String>) -> Unit)? = null,
) {
    var editing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val pl = (d as? Detail.Pl)?.let { p -> playlists.firstOrNull { it.id == p.id } }
    val list: List<Track> = when (d) {
        is Detail.Album -> all.filter { it.album == d.name }.sortedBy { it.title.lowercase() }
        is Detail.Artist -> all.filter { it.artist == d.name }.sortedWith(compareBy({ it.album.lowercase() }, { it.title.lowercase() }))
        is Detail.Dir -> all.filter { it.folder == d.path }.sortedBy { it.title.lowercase() }
        is Detail.Pl -> when (d.id) {
            FAV_ID -> favs.mapNotNull { byId[it] }
            "recent" -> all.sortedByDescending { it.added }.take(50)
            else -> pl?.ids?.mapNotNull { byId[it] } ?: emptyList()
        }
    }
    val userPlaylist = d is Detail.Pl && d.id != FAV_ID && d.id != "recent" && pl != null
    LaunchedEffect(d is Detail.Pl, pl == null) { if (d is Detail.Pl && d.id != FAV_ID && d.id != "recent" && pl == null) onGone() }

    TrackList(
        list, "Rien ici pour l'instant", favs, selected, setSelected,
        { l, i -> Hub.play(l, i) }, onAddToPlaylist, onInfo, onDelete = onDelete,
        header = {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(list.firstOrNull(), 96.dp, 14.dp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("${list.size} morceau(x)", color = P.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(fmtTotal(list.sumOf { it.durationMs }), color = P.sub, fontSize = 13.sp)
                    }
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BlueButton("Lecture", Modifier.weight(1f), Ic.Play) { Hub.play(list, 0, false) }
                    BlueButton("Aléatoire", Modifier.weight(1f), Ic.Shuffle, outlined = true) { Hub.play(list.shuffled(), 0, true) }
                }
                if (userPlaylist) {
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BlueButton(if (editing) "Terminé" else "Modifier l'ordre", Modifier.weight(1f), outlined = true) { editing = !editing }
                        BlueButton("Renommer", Modifier.weight(1f), outlined = true) { renaming = true }
                        BlueButton("Supprimer", Modifier.weight(1f), outlined = true) { deleting = true }
                    }
                }
            }
        },
        extraMenu = { t ->
            if (userPlaylist && pl != null) listOf(
                "Retirer de la playlist" to { Lib.removeFromPlaylist(pl.id, t.id) },
                "Monter" to { val i = pl.ids.indexOf(t.id); Lib.movePlaylistTrack(pl.id, i, i - 1) },
                "Descendre" to { val i = pl.ids.indexOf(t.id); Lib.movePlaylistTrack(pl.id, i, i + 1) },
            ) else emptyList()
        },
    )
    if (renaming && pl != null) NameDialog("Renommer la playlist", pl.name, "OK", { renaming = false }) { Lib.renamePlaylist(pl.id, it); renaming = false }
    if (deleting && pl != null) {
        AlertDialog(
            onDismissRequest = { deleting = false }, containerColor = P.card2,
            title = { Text("Supprimer « ${pl.name} » ?", color = P.text) },
            text = { Text("Les morceaux restent sur ton téléphone.", color = P.sub) },
            confirmButton = { TextButton({ Lib.deletePlaylist(pl.id); deleting = false }) { Text("Supprimer", color = P.danger) } },
            dismissButton = { TextButton({ deleting = false }) { Text("Annuler", color = P.sub) } },
        )
    }
}

@Composable
private fun MiniPlayer(t: Track, onOpen: () -> Unit) {
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Hub.currentId, Hub.playing) {
        while (true) { pos = Hub.player.currentPosition; delay(500) }
    }
    val dur = Hub.durationMs.takeIf { it > 0 } ?: t.durationMs
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(P.card)
            .clickable { onOpen() },
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(t, 46.dp, 10.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, color = P.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artist, color = P.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RoundBtn(Ic.Prev, { Hub.previous() }, size = 40.dp, iconSize = 22.dp, fill = Color.Transparent)
            RoundBtn(if (Hub.playing) Ic.Pause else Ic.Play, { Hub.toggle() }, size = 44.dp, iconSize = 24.dp, fill = P.blue, tint = Color.White)
            RoundBtn(Ic.Next, { Hub.next() }, size = 40.dp, iconSize = 22.dp, fill = Color.Transparent)
        }
        LinearProgressIndicator(
            progress = { if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp), color = P.blue, trackColor = Color(0x14FFFFFF),
        )
    }
}
