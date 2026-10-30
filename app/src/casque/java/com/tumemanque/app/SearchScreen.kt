package com.tumemanque.app

import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.blur
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
import kotlinx.coroutines.async
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
    var suggestions by remember { mutableStateOf(listOf<Sug>()) }
    var history by remember { mutableStateOf(Yt.history()) }
    var token by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    ScrollTicks(listState)
    val focusReq = remember { FocusRequester() }

    val results: List<YtVideo> = remember(resultsJson) {
        runCatching {
            val a = JSONArray(resultsJson)
            (0 until a.length()).map { YtVideo.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
    // Propositions du même thème que la vidéo choisie, ajoutées sous les résultats.
    // Découverte : un artiste → ses albums ; un film → des genres.
    var kind by remember { mutableStateOf<DiscoKind?>(null) }
    var stage by remember { mutableIntStateOf(0) }
    var albums by remember { mutableStateOf<List<DiscoAlbum>?>(null) }
    var selAlbum by remember { mutableStateOf<DiscoAlbum?>(null) }
    var albumTracks by remember { mutableStateOf<List<DiscoTrack>?>(null) }
    var albumQueue by remember { mutableStateOf<List<YtVideo>?>(null) }
    var genre by remember { mutableStateOf<DiscoGenre?>(null) }
    var genreVids by remember { mutableStateOf<List<YtVideo>?>(null) }
    var collapsed by remember { mutableStateOf(true) }
    var seasons by remember { mutableStateOf<List<Yt.YtPlaylist>>(emptyList()) }
    var selSeason by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(submitted) {
        kind = null; stage = 0; albums = null; selAlbum = null; albumTracks = null; albumQueue = null; genre = null; genreVids = null
        collapsed = true; seasons = emptyList(); selSeason = null
        if (submitted.isNotBlank()) {
            val k = Disco.detect(submitted)
            kind = k; stage = 1
            if (k is DiscoKind.Artist) albums = Disco.albums(k.id)
            else if (k is DiscoKind.Topic && Regex("^(Série|Dessin animé)").containsMatchIn(k.title)) {
                // Une série : y a-t-il des playlists « saison » sur YouTube ?
                val words = submitted.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
                val found = Yt.playlists("$submitted saison")
                    .filter { seasonNumber(it.title) < 999 && words.all { w -> it.title.lowercase().contains(w) } }
                    .distinctBy { seasonNumber(it.title) }.sortedBy { seasonNumber(it.title) }
                if (found.size >= 2) seasons = found
            }
        }
    }
    var related by remember { mutableStateOf(listOf<YtVideo>()) }
    var extraSel by remember { mutableStateOf<YtVideo?>(null) }
    val selected = results.firstOrNull { it.id == selectedId } ?: related.firstOrNull { it.id == selectedId }
        ?: extraSel?.takeIf { it.id == selectedId } ?: results.firstOrNull()

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
                val r = Yt.searchRecent(t)
                if (my != token) return@launch
                resultsJson = JSONArray(r.map { it.toJson() }).toString()
                selectedId = r.firstOrNull()?.id
                if (r.isEmpty()) error = "Aucun résultat pour « $t »."
                listState.scrollToItem(0)
            } catch (e: Exception) {
                if (my != token) return@launch
                error = when (Yt.state) {
                    Yt.State.FAILED -> "La recherche n'est pas disponible pour le moment. Réessaie dans un instant."
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
        suggestions = Disco.suggest(query)
    }
    // Lecture continue : la vidéo suivante est la suivante des résultats ; l'écran suit quand elle change.
    LaunchedEffect(resultsJson, related, albumQueue) { if (results.isNotEmpty()) Vp.queue = ((albumQueue ?: emptyList()) + results + related).distinctBy { it.id } }
    LaunchedEffect(Vp.key) {
        Vp.key?.let { k ->
            val v = (results + related).firstOrNull { it.id == k }
            if (v != null) { selectedId = k; if (results.none { it.id == k }) extraSel = v }
        }
    }
    LaunchedEffect(selected?.id) {
        val cur = selected ?: return@LaunchedEffect
        val base = cur.title.replace(Regex("[(\\[].*?[)\\]]"), " ")
            .replace(Regex("official|video|music|lyrics?|audio|clip|officiel|\\bhd\\b|\\b4k\\b|\\|.*$", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ").trim().ifBlank { cur.title }
        val qs = listOf(base, cur.channel, "${cur.channel.ifBlank { base }} mix", "$base similaire").filter { it.isNotBlank() }.distinct()
        val seen = HashSet<String>().apply { addAll(results.map { it.id }); add(cur.id) }
        val acc = ArrayList<YtVideo>()
        for (q in qs) {
            val found = runCatching { Yt.search(q, 25) }.getOrDefault(emptyList())
            for (f in found) if (seen.add(f.id)) acc.add(f)
            related = acc.take(70).toList()
        }
    }
    // On prépare le lecteur et les tailles des formats dès qu'une vidéo est choisie.
    LaunchedEffect(selected?.id) { selected?.let { v -> runCatching { Yt.info(v.id) } } }

    val showSuggest = focused && query.isNotBlank() && query != submitted

    val winShown = selected != null && !(loading && results.isEmpty())
    var vTop by remember { mutableFloatStateOf(-1f) }
    var hBottom by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize()) {
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
                Text("Préparation de la recherche (seulement la première fois)…", color = P.sub, fontSize = 12.sp)
            }
        }

        // La fenêtre de lecture reste sous la barre de recherche, au même endroit qu'avant :
        // le reste (infos, téléchargements, résultats) défile dessous, en verre flou.
        if (selected != null && winShown) PlayerWindow(selected) { vTop = it }
        Spacer(Modifier.fillMaxWidth().height(0.dp).onGloballyPositioned { hBottom = it.positionInRoot().y })
        // Les infos de la vidéo restent fixes sous la vidéo.
        if (selected != null && winShown) PlayerInfo(selected)
    }) {
        // ------------------------------------------------------------------ contenu
        LazyColumn(
            Modifier.fillMaxSize().imePadding().then(if (showSuggest) Modifier.blur(22.dp) else Modifier), state = listState,
            contentPadding = PaddingValues(top = 6.dp + LocalTopInset.current, bottom = bottom + 12.dp),
        ) {

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

            if (selected != null) {
                val dk = kind
                if (dk != null && stage != 2) item(key = "disco") {
                    DiscoverCard(
                        dk, albums, selAlbum?.id, genre?.name,
                        collapsed = collapsed, onToggle = { collapsed = !collapsed },
                        seasons = seasons, selSeason = selSeason,
                        onSeason = { se ->
                            selSeason = se.id; genre = DiscoGenre("", seasonLabel(se.title), ""); genreVids = null; selAlbum = null; albumTracks = null; albumQueue = null
                            scope.launch { genreVids = Yt.playlistVideos(se.id) }
                        },
                        onAlbum = { a ->
                            selAlbum = a; albumTracks = null; albumQueue = null; genre = null; genreVids = null
                            scope.launch {
                                val tr = Disco.tracks(a.id)
                                if (selAlbum?.id != a.id) return@launch
                                albumTracks = tr
                                val artist = (dk as? DiscoKind.Artist)?.name ?: ""
                                // La liste des titres s'affiche ; rien ne démarre tant qu'on n'a pas touché un titre.
                                val found = tr.map { t -> scope.async { runCatching { Yt.search("$artist ${t.title}", 3) }.getOrNull()?.firstOrNull() } }
                                val q = found.mapNotNull { it.await() }.distinctBy { it.id }
                                if (selAlbum?.id == a.id) albumQueue = q
                            }
                        },
                        onGenre = { g ->
                            selSeason = null; genre = g; genreVids = null; selAlbum = null; albumTracks = null; albumQueue = null
                            scope.launch { genreVids = runCatching { Yt.search(g.query, 25) }.getOrDefault(emptyList()) }
                        },
                    )
                }
                val al = selAlbum
                if (al != null && stage == 1) {
                    item(key = "alt") { SectionTitle("ALBUM · ${al.name.uppercase()}", Modifier.padding(top = 4.dp)) }
                    val tr = albumTracks
                    if (tr == null) item(key = "alw") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = P.blueSoft)
                        }
                    } else items(tr, key = { "tk${al.id}_${it.n}_${it.title}" }) { t ->
                        TrackRow(t) {
                            val artist = (kind as? DiscoKind.Artist)?.name ?: ""
                            scope.launch {
                                val v = albumQueue?.getOrNull(tr.indexOf(t)) ?.takeIf { albumQueue?.size == tr.size }
                                    ?: runCatching { Yt.search("$artist ${t.title}", 3) }.getOrNull()?.firstOrNull() ?: return@launch
                                Vp.stop()
                                extraSel = v; selectedId = v.id
                                listState.animateScrollToItem(0)
                                Vp.playYt(ctx, v)
                            }
                        }
                    }
                }
                val gn = genre
                if (gn != null && stage == 1) {
                    item(key = "gnt") { SectionTitle(gn.name.uppercase(), Modifier.padding(top = 4.dp)) }
                    val gv = genreVids
                    if (gv == null) item(key = "gnw") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = P.blueSoft)
                        }
                    } else items(gv, key = { "gv${it.id}" }) { v ->
                        ResultRow(v) {
                            Vp.stop()
                            selectedId = v.id; extraSel = v
                            scope.launch { listState.animateScrollToItem(0) }
                        }
                    }
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
                        extraSel = null
                        scope.launch { listState.animateScrollToItem(0) }
                    }
                }
                val rel = related.filter { r -> r.id != selected.id && results.none { it.id == r.id } }
                if (rel.isNotEmpty()) item(key = "rt") { SectionTitle("DANS LA MÊME THÉMATIQUE", Modifier.padding(top = 8.dp)) }
                items(rel, key = { "t${it.id}" }) { v ->
                    ResultRow(v) {
                        Vp.stop()
                        selectedId = v.id
                        extraSel = v
                        scope.launch { listState.animateScrollToItem(0) }
                    }
                }
            }
        }
    }
    // Propositions : le texte démarre à la hauteur de la vidéo (devant elle, sans la flouter) ; le bas de l'écran reste flouté.
    if (showSuggest) {
        val dens = LocalDensity.current
        val textTop = with(dens) { (if (winShown && vTop >= 0f) vTop + 6.dp.toPx() else hBottom + 8.dp.toPx()).toDp() }
        Box(Modifier.fillMaxSize().offset { IntOffset(0, (hBottom - 12.dp.toPx()).toInt().coerceAtLeast(0)) }.background(Color(0x5A07090D)).clickable(remember { MutableInteractionSource() }, indication = null) { })
        Column(Modifier.fillMaxSize().padding(top = textTop).verticalScroll(rememberScrollState()).imePadding().padding(bottom = bottom + 12.dp)) {
                suggestions.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null) { run(s.text) }.padding(start = 24.dp, end = 12.dp, top = 13.dp, bottom = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CIcon(if (s.artist) Ic.Mic else Ic.Search, if (s.artist) P.blueSoft else Color(0x99FFFFFF), if (s.artist) 22.dp else 20.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.label, color = Color.White, fontSize = if (s.artist) 22.sp else 19.sp, fontWeight = if (s.artist) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(shadow = Shadow(Color(0x99000000), Offset(0f, 2f), 14f)),
                            )
                            if (s.sub.isNotBlank()) Text(s.sub, color = Color(0xA0FFFFFF), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(Modifier.size(34.dp).clip(CircleShape).clickable { query = "${s.text} " }, contentAlignment = Alignment.Center) {
                            CIcon(Ic.ArrowNW, Color(0x88FFFFFF), 18.dp)
                        }
                    }
                }
                if (suggestions.isEmpty()) Row(
                    Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null) { run(query) }.padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CIcon(Ic.Search, P.blueSoft, 20.dp); Spacer(Modifier.width(16.dp))
                    Text("Rechercher « $query »", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                }
        }
    }
    }
}

/** La fenêtre de lecture : elle reste en haut de l'écran pendant qu'on fait défiler le reste. */
@Composable
private fun PlayerWindow(v: YtVideo, onTop: (Float) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val loaded = Vp.key == v.id
    val soft = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { Vp.slot = null } }
    // Retour dans l'appli depuis la fenêtre flottante : le lecteur « absorbe » la bulle et la vidéo continue au même endroit.
    val absorb = remember { androidx.compose.animation.core.Animatable(1f) }
    var wasPip by remember { mutableStateOf(Vp.inPip) }
    LaunchedEffect(Vp.inPip) {
        if (wasPip && !Vp.inPip) {
            absorb.snapTo(0f)
            absorb.animateTo(1f, androidx.compose.animation.core.tween(380, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
        wasPip = Vp.inPip
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).graphicsLayer {
        val k = 0.55f + 0.45f * absorb.value
        scaleX = k; scaleY = k; alpha = 0.35f + 0.65f * absorb.value
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.9f, 0.1f)
    }) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).onGloballyPositioned { onTop(it.positionInRoot().y); Vp.slot = it.boundsInRoot() }.shadow(16.dp, soft, ambientColor = Color.Black, spotColor = Color.Black).clip(soft).background(Color.Black),
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
    }
}

/** Titre de la vidéo choisie et réglage « lecture continue ». */
@Composable
private fun PlayerInfo(v: YtVideo) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Vp.loadPrefs(ctx) }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 0.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(v.title, color = P.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(v.channel, fmtViews(v.views), fmtAgo(v.published)).filter { it.isNotBlank() }.joinToString(" · "),
                color = P.sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        AutoplaySwitch(Vp.autoplay) { Vp.setAutoplay(ctx, !Vp.autoplay) }
    }
}

/** Lecture automatique, comme l'interrupteur de YouTube : une pastille avec une icône lecture dans le rond. */
@Composable
private fun AutoplaySwitch(on: Boolean, onClick: () -> Unit) {
    val x by androidx.compose.animation.core.animateDpAsState(if (on) 18.dp else 0.dp, androidx.compose.animation.core.tween(220), label = "ap")
    val track by androidx.compose.animation.animateColorAsState(if (on) P.blue else Color(0x44FFFFFF), androidx.compose.animation.core.tween(220), label = "apc")
    Box(
        Modifier.width(40.dp).height(22.dp).clip(RoundedCornerShape(50)).background(track).pressable(onClick).padding(2.dp),
    ) {
        Box(Modifier.offset(x = x).size(18.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            CIcon(if (on) Ic.Play else Ic.Pause, Color(0xFF202225), 11.dp)
        }
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
    var open by remember(v.id) { mutableStateOf(false) }
    val mine = tasks.filter { it.video.id == v.id && it.status != DlStatus.CANCELED }
    val sub = when {
        mine.any { it.active } -> "Téléchargement en cours…"
        mine.any { it.status == DlStatus.DONE } -> "Téléchargé · touche pour d'autres formats"
        else -> "Audio · Vidéo · touche pour choisir"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).glass(RoundedCornerShape(26.dp), 0.05f).animateContentSize(androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 380f)).padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null) { open = !open }.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CIcon(Ic.Download, P.blueSoft, 20.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Télécharger", color = P.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(sub, color = P.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (open && i == null) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = P.sub)
            val chev by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, androidx.compose.animation.core.tween(280), label = "chev")
            CIcon(Ic.ChevronDown, P.sub, 22.dp, Modifier.graphicsLayer { rotationZ = chev })
        }
        if (open) {
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
}

@Composable
private fun FormatRow(k: DlKind, size: String, task: DlTask?, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val done = task?.status == DlStatus.DONE && Dl.stillThere(task)
    Column(Modifier.fillMaxWidth()) {
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
                    task.active -> if (task.status == DlStatus.RUNNING && task.progress > 0) "${(task.progress * 100).toInt()} %" else ""
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
    if (task?.active == true) GlassProgress(task.progress, task.progress <= 0f || task.status == DlStatus.QUEUED || task.status == DlStatus.PROCESSING, Modifier.padding(start = 70.dp, end = 16.dp, bottom = 8.dp))
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
    Row(Modifier.fillMaxWidth().tapGlow { onClick() }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
            val meta = listOf(fmtViews(v.views), fmtAgo(v.published)).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, color = P.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
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
