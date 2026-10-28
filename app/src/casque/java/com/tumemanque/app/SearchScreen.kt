package com.tumemanque.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray

/** Onglet « Rechercher » : on tape un titre, on lit la vidéo, on la télécharge dans le format voulu. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val bottom = LocalBottomInset.current
    val tracks by Lib.tracks.collectAsState()

    // On retrouve la dernière recherche (même après avoir fermé l'appli).
    val last = remember { Yt.lastSearch() }
    var query by rememberSaveable { mutableStateOf(last?.first ?: "") }
    var submitted by rememberSaveable { mutableStateOf(last?.first ?: "") }
    var resultsJson by rememberSaveable { mutableStateOf(last?.second ?: "") }
    var selectedId by rememberSaveable { mutableStateOf(last?.third) }
    LaunchedEffect(submitted, resultsJson, selectedId) { if (resultsJson.isNotEmpty()) Yt.saveLastSearch(submitted, resultsJson, selectedId) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf(listOf<String>()) }
    var history by remember { mutableStateOf(Yt.history()) }
    var token by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val focusReq = remember { FocusRequester() }

    val results: List<YtVideo> = remember(resultsJson) {
        runCatching {
            val a = JSONArray(resultsJson)
            (0 until a.length()).map { YtVideo.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
    val selected = results.firstOrNull { it.id == selectedId } ?: results.firstOrNull()

    fun run(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        query = t; submitted = t
        focus.clearFocus()
        Yt.remember(t); history = Yt.history()
        val my = ++token
        loading = true; error = null
        scope.launch {
            try {
                val r = Yt.search(t)
                if (my != token) return@launch
                resultsJson = JSONArray(r.map { it.toJson() }).toString()
                selectedId = r.firstOrNull()?.id
                if (r.isEmpty()) error = "Aucun résultat pour « $t »."
                listState.scrollToItem(0)
            } catch (e: Exception) {
                if (my != token) return@launch
                error = when (Yt.state) {
                    Yt.State.FAILED -> "Le moteur n'a pas pu démarrer : ${Yt.error}"
                    else -> "Recherche impossible. Vérifie ta connexion internet."
                }
            } finally {
                if (my == token) loading = false
            }
        }
    }

    // Suggestions pendant la frappe.
    LaunchedEffect(query, focused) {
        if (!focused || query.isBlank() || query == submitted) { suggestions = emptyList(); return@LaunchedEffect }
        delay(220)
        suggestions = Yt.suggest(query)
    }
    // On prépare le lecteur et les tailles des formats dès qu'une vidéo est choisie.
    LaunchedEffect(selected?.id) { selected?.let { v -> runCatching { Yt.info(v.id) } } }

    val showSuggest = focused && query.isNotBlank() && query != submitted

    GlassScaffold(header = {
        LargeTitle("Rechercher")
        // ------------------------------------------------------------------ barre de recherche
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(50.dp).glass(RoundedCornerShape(25.dp), 0.09f)
                .padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CIcon(Ic.Search, P.sub, 20.dp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = TextStyle(color = P.text, fontSize = 17.sp), cursorBrush = SolidColor(P.blueSoft),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { run(query) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusReq).onFocusChanged { focused = it.isFocused },
                )
                if (query.isEmpty()) Text("Titre, artiste, clip…", color = P.sub, fontSize = 17.sp)
            }
            if (query.isNotEmpty()) RoundBtn(Ic.Close, { query = ""; focusReq.requestFocus() }, size = 34.dp, iconSize = 16.dp, fill = Color(0x22FFFFFF))
        }
        if (Yt.state == Yt.State.PREPARING) {
            Row(Modifier.padding(horizontal = 22.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = P.blueSoft)
                Spacer(Modifier.width(8.dp))
                Text("Préparation du moteur (seulement la première fois)…", color = P.sub, fontSize = 12.sp)
            }
        }

    }) {
        // ------------------------------------------------------------------ contenu
        LazyColumn(
            Modifier.fillMaxSize().imePadding(), state = listState,
            contentPadding = PaddingValues(top = 6.dp + LocalTopInset.current, bottom = bottom + 12.dp),
        ) {
            if (showSuggest) {
                items(suggestions, key = { "s$it" }) { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { run(s) }.padding(horizontal = 22.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CIcon(Ic.Search, P.sub, 18.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(s, color = P.text, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.size(32.dp).clip(CircleShape).clickable { query = "$s " }, contentAlignment = Alignment.Center) {
                            CIcon(Ic.ArrowNW, P.sub, 18.dp)
                        }
                    }
                }
                if (suggestions.isEmpty()) item(key = "go") {
                    Row(Modifier.fillMaxWidth().clickable { run(query) }.padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        CIcon(Ic.Search, P.blueSoft, 18.dp); Spacer(Modifier.width(14.dp))
                        Text("Rechercher « $query »", color = P.blueSoft, fontSize = 16.sp)
                    }
                }
                return@LazyColumn
            }

            if (submitted.isEmpty() && results.isEmpty()) {
                if (history.isNotEmpty()) {
                    item(key = "hh") {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Recherches récentes", color = P.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("Effacer", color = P.blueSoft, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { Yt.clearHistory(); history = emptyList() }.padding(8.dp))
                        }
                    }
                    item(key = "hl") {
                        FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            history.forEach { h -> GlassChip(h, Ic.History, P.text) { run(h) } }
                        }
                    }
                }
                item(key = "empty") {
                    EmptyState(
                        Ic.Search, "Trouve une chanson",
                        "Tape le titre d'une chanson ou le nom d'un artiste. Tu pourras regarder la vidéo, puis la télécharger en MP3 ou en vidéo.",
                    )
                }
                return@LazyColumn
            }

            if (loading && results.isEmpty()) {
                item(key = "sk") { SkeletonPlayer() }
                items(5, key = { "sk$it" }) { SkeletonRow() }
                return@LazyColumn
            }
            error?.let { e ->
                item(key = "err") {
                    Column(Modifier.fillMaxWidth().padding(16.dp).glass(RoundedCornerShape(20.dp), 0.06f, P.danger).padding(16.dp)) {
                        Text(e, color = P.text, fontSize = 14.sp)
                        Spacer(Modifier.height(10.dp))
                        GlassChip("Réessayer", Ic.Refresh, P.blueSoft) { run(submitted.ifEmpty { query }) }
                    }
                }
            }
            if (loading) item(key = "lb") {
                Box(Modifier.fillMaxWidth().padding(4.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = P.blueSoft)
                }
            }

            // Ce qu'on a déjà dans la bibliothèque.
            val q = submitted.lowercase()
            val local = if (q.length >= 2) tracks.filter { q in it.title.lowercase() || q in it.artist.lowercase() }.take(3) else emptyList()
            if (local.isNotEmpty()) {
                item(key = "lt") { SectionTitle("DÉJÀ DANS TES AUDIOS") }
                items(local, key = { "l${it.id}" }) { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { Hub.play(local, local.indexOf(t)) }.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(t, 44.dp, 10.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title, color = P.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artist, color = P.sub, fontSize = 12.sp, maxLines = 1)
                        }
                        CIcon(Ic.Play, P.blueSoft, 22.dp)
                    }
                }
            }

            if (selected != null) {
                item(key = "player") {
                    PlayerCard(selected)
                }
                item(key = "formats") {
                    FormatsPanel(selected) { kind ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        Sub.download(ctx, scope) { Dl.enqueue(ctx, selected, kind) }
                    }
                }
                val others = results.filter { it.id != selected.id }
                if (others.isNotEmpty()) item(key = "ot") { SectionTitle("AUTRES RÉSULTATS", Modifier.padding(top = 8.dp)) }
                items(others, key = { "r${it.id}" }) { v ->
                    ResultRow(v) {
                        Vp.stop()
                        selectedId = v.id
                        scope.launch { listState.animateScrollToItem(0) }
                    }
                }
            }
        }
    }
}

/** La vidéo choisie : miniature avec un bouton lecture, puis le lecteur. */
@Composable
private fun PlayerCard(v: YtVideo) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val loaded = Vp.key == v.id
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(24.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (loaded && !Vp.fullscreen && !Vp.mini && !Vp.inPip) {
                VideoSurface(Modifier.fillMaxSize())
                VideoControls(big = false) { Vp.fullscreen = true }
            } else if (!loaded || Vp.mini) {
                AsyncImage(model = v.thumbHd, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x00000000), Color(0x66000000)))))
                Box(
                    Modifier.size(72.dp).glass(CircleShape, 0.18f).pressable { if (loaded) Vp.mini = false else scope.launch { Vp.playYt(ctx, v) } },
                    contentAlignment = Alignment.Center,
                ) { CIcon(Ic.Play, Color.White, 40.dp) }
                if (v.durationSec > 0) {
                    Text(
                        fmtSec(v.durationSec), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).glass(RoundedCornerShape(8.dp), 0.15f).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(v.title, color = P.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        Text(
            listOf(v.channel, fmtViews(v.views)).filter { it.isNotBlank() }.joinToString(" · "),
            color = P.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

/** Les formats de téléchargement, avec la taille estimée et la progression. */
@Composable
private fun FormatsPanel(v: YtVideo, onDownload: (DlKind) -> Unit) {
    val tasks by Dl.tasks.collectAsState()
    var info by remember(v.id) { mutableStateOf(Yt.cachedInfo(v.id)) }
    LaunchedEffect(v.id) { if (info == null) info = runCatching { Yt.info(v.id) }.getOrNull() }
    val dur = (info?.durationSec ?: v.durationSec).coerceAtLeast(0)
    val i = info
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).glass(RoundedCornerShape(26.dp), 0.05f).padding(vertical = 8.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CIcon(Ic.Download, P.blueSoft, 20.dp)
            Spacer(Modifier.width(8.dp))
            Text("Télécharger", color = P.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (i == null) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = P.sub)
        }
        Text("AUDIO", color = P.sub, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp))
        listOf(DlKind.MP3_HQ, DlKind.MP3_LITE, DlKind.M4A).forEach { k ->
            val size = when (k) {
                DlKind.MP3_HQ -> dur * 40_000L
                DlKind.MP3_LITE -> dur * 16_000L
                else -> i?.audioBytes?.takeIf { it > 0 } ?: (dur * 16_000L)
            }
            FormatRow(k, if (dur > 0) "≈ ${fmtBytes(size)}" else "", tasks.firstOrNull { it.video.id == v.id && it.kind == k && it.status != DlStatus.CANCELED }) { onDownload(k) }
        }
        Text("VIDÉO", color = P.sub, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp))
        val videoKinds = listOf(DlKind.MP4_360, DlKind.MP4_720, DlKind.MP4_1080).filter { k -> i == null || i.maxRes <= 0 || i.videoSizes.containsKey(k.res) }
        videoKinds.forEach { k ->
            val s = i?.videoSizes?.get(k.res) ?: 0L
            FormatRow(k, if (s > 0) "≈ ${fmtBytes(s)}" else "", tasks.firstOrNull { it.video.id == v.id && it.kind == k && it.status != DlStatus.CANCELED }) { onDownload(k) }
        }
        if (i != null && videoKinds.isEmpty()) Text("Pas de version vidéo disponible.", color = P.sub, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun FormatRow(k: DlKind, size: String, task: DlTask?, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val done = task?.status == DlStatus.DONE && Dl.stillThere(task)
    Row(
        Modifier.fillMaxWidth().clickable {
            when {
                task?.active == true -> Shell.tab = "downloads"
                done -> openResult(ctx, task!!)
                else -> onClick()
            }
        }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(
                Brush.linearGradient(if (k.video) listOf(Color(0xFF3A4A66), Color(0xFF1B2333)) else listOf(P.blue, P.blueDeep))
            ),
            contentAlignment = Alignment.Center,
        ) { CIcon(if (k.video) Ic.Video else Ic.Note, Color.White, 22.dp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(k.label, color = P.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (size.isNotEmpty()) Text("  $size", color = P.sub, fontSize = 13.sp)
            }
            Text(
                when {
                    task == null || task.status == DlStatus.CANCELED -> k.sub
                    task.active -> task.phase + if (task.status == DlStatus.RUNNING && task.progress > 0) " ${(task.progress * 100).toInt()} %" else ""
                    done -> if (k.video) "Téléchargé · dans Vidéos" else "Téléchargé · dans Audios"
                    task.status == DlStatus.FAILED -> task.error ?: "Échec"
                    else -> k.sub
                },
                color = if (task?.status == DlStatus.FAILED) P.danger else if (done) P.ok else P.sub,
                fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        when {
            task?.active == true -> ProgressRing(task.progress, task.status == DlStatus.QUEUED || task.status == DlStatus.PROCESSING)
            done -> Box(Modifier.size(38.dp).clip(CircleShape).background(Color(0x264CD08A)), contentAlignment = Alignment.Center) { CIcon(Ic.Play, P.ok, 20.dp) }
            else -> Box(Modifier.size(38.dp).glass(CircleShape, 0.08f), contentAlignment = Alignment.Center) { CIcon(Ic.Download, P.blueSoft, 20.dp) }
        }
    }
}

/** Ouvre ce qui a été téléchargé. */
fun openResult(ctx: android.content.Context, t: DlTask) {
    val id = t.resultId ?: return
    if (t.kind.video) {
        Vids.items.value.firstOrNull { it.id == id }?.let { Vp.playLocal(ctx, it); Vp.fullscreen = true }
    } else {
        Lib.byId.value[id]?.let { Hub.play(listOf(it), 0); Shell.tab = "audios"; Shell.openNow++ }
    }
}

@Composable
fun ProgressRing(p: Float, indeterminate: Boolean, size: androidx.compose.ui.unit.Dp = 38.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (indeterminate) CircularProgressIndicator(Modifier.size(size), color = P.blueSoft, strokeWidth = 3.dp, trackColor = Color(0x1FFFFFFF))
        else CircularProgressIndicator(progress = { p.coerceIn(0.02f, 1f) }, modifier = Modifier.size(size), color = P.blueSoft, strokeWidth = 3.dp, trackColor = Color(0x1FFFFFFF))
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(P.blueSoft))
    }
}

@Composable
private fun ResultRow(v: YtVideo, onClick: () -> Unit) {
    val tasks by Dl.tasks.collectAsState()
    val got = tasks.any { it.video.id == v.id && it.status == DlStatus.DONE }
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(P.card2)) {
            AsyncImage(model = v.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (v.durationSec > 0) Text(
                fmtSec(v.durationSec), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xAA000000)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(v.title, color = P.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(v.channel, color = P.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            if (got) Text("Téléchargé", color = P.ok, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun shimmer(): Float {
    val t = rememberInfiniteTransition(label = "sh")
    val a by t.animateFloat(0.35f, 0.75f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    return a
}

@Composable
private fun SkeletonPlayer() {
    val a = shimmer()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).alpha(a).clip(RoundedCornerShape(24.dp)).background(P.card2))
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth(0.8f).height(18.dp).alpha(a).clip(RoundedCornerShape(6.dp)).background(P.card2))
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth(0.4f).height(12.dp).alpha(a).clip(RoundedCornerShape(6.dp)).background(P.card2))
    }
}

@Composable
private fun SkeletonRow() {
    val a = shimmer()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(Modifier.width(132.dp).aspectRatio(16f / 9f).alpha(a).clip(RoundedCornerShape(14.dp)).background(P.card2))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth().height(14.dp).alpha(a).clip(RoundedCornerShape(5.dp)).background(P.card2))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(0.5f).height(11.dp).alpha(a).clip(RoundedCornerShape(5.dp)).background(P.card2))
        }
    }
}
