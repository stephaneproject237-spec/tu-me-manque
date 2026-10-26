package com.tumemanque.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Rose = Color(0xFFE91E63)
private val RoseClair = Color(0xFFFFD9E4)
private val Fond = Color(0xFFFCEFF3)

private fun hhmm(t: Long): String = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(t))

private fun sameDay(a: Long, b: Long): Boolean {
    val x = Calendar.getInstance().apply { timeInMillis = a }
    val y = Calendar.getInstance().apply { timeInMillis = b }
    return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
}

private fun dayLabel(t: Long): String {
    val now = System.currentTimeMillis()
    if (sameDay(t, now)) return "Aujourd'hui"
    if (sameDay(t, now - 24L * 3600 * 1000)) return "Hier"
    val s = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(Date(t))
    return s.replaceFirstChar { it.uppercase() }
}

private fun mmss(sec: Int): String = "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"

/** Un à trois emojis seuls : on les affiche en grand, sans bulle. */
private fun isBigEmoji(text: String?): Boolean {
    val t = text?.trim() ?: return false
    if (t.isEmpty() || t.length > 24) return false
    var bases = 0
    var i = 0
    var afterJoiner = false
    while (i < t.length) {
        val cp = t.codePointAt(i)
        i += Character.charCount(cp)
        if (cp < 128 || Character.isLetterOrDigit(cp)) return false
        if (cp == 0x200D) { afterJoiner = true; continue }
        if (cp == 0xFE0F || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF || Character.isWhitespace(cp)) continue
        if (afterJoiner) { afterJoiner = false; continue }
        bases++
    }
    return bases in 1..3
}

@Composable
fun ChatScreen(partner: String, vm: ChatViewModel, wall: String?, onWall: (String?) -> Unit, onBack: () -> Unit, onRead: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    var text by rememberSaveable { mutableStateOf("") }
    var panel by rememberSaveable { mutableIntStateOf(0) } // 0 aucun, 1 emojis, 2 stickers, 3 GIF
    var recording by remember { mutableStateOf(false) }
    var recSecs by remember { mutableIntStateOf(0) }
    var viewer by remember { mutableStateOf<ChatMsg?>(null) }
    var showWall by remember { mutableStateOf(false) }
    val recorder = remember { VoiceRecorder(context.applicationContext) }

    DisposableEffect(Unit) {
        ChatVisibility.open = true
        onDispose {
            ChatVisibility.open = false
            VoicePlayer.stop()
            recorder.cancel()
        }
    }
    LaunchedEffect(Unit) {
        vm.onRead = onRead
        vm.ensureLoaded()
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.poll()
                delay(2500)
            }
        }
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.pollTyping()
                delay(1500)
            }
        }
    }
    var burstId by remember { mutableStateOf("") }
    var burstN by remember { mutableIntStateOf(0) }
    LaunchedEffect(vm) { vm.onSticker = { burstId = it; burstN++ } }
    LaunchedEffect(recording) {
        while (recording) {
            recSecs = recorder.elapsedSeconds()
            if (recSecs >= 300) {
                recorder.stop()?.let { vm.sendVoice(it.first, it.second) }
                recording = false
            }
            delay(250)
        }
    }

    BackHandler {
        when {
            recording -> { recorder.cancel(); recording = false }
            panel != 0 -> panel = 0
            else -> onBack()
        }
    }

    fun beginRecording() {
        if (recorder.start()) { recording = true; recSecs = 0 } else vm.showToast("Micro indisponible.")
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginRecording() else vm.showToast("Micro refusé. Autorise-le dans les réglages pour envoyer des vocaux.")
    }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.onPhotoPicked(context.applicationContext, uri)
    }

    Box(Modifier.fillMaxSize()) {
        WallpaperLayer(wall)
        Column(Modifier.fillMaxSize()) {
            ChatHeader(partner, state.typing, onBack, onMenu = { showWall = true })

            Column(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().imePadding()) {
                MessagesArea(state, partner, vm, Modifier.weight(1f), onReply = { vm.setReply(it) }) { viewer = it }

                if (state.typing && !recording) TypingBubble()
                state.replyTo?.let { ReplyBar(it, partner) { vm.clearReply() } }

                InputBar(
                    recording = recording, recSecs = recSecs, panel = panel, text = text,
                    onText = { text = it; vm.onTextChanged(it.isNotBlank()) },
                    onPanel = { panel = it },
                    onCancelRec = { recorder.cancel(); recording = false },
                    onStopRec = {
                        val r = recorder.stop()
                        recording = false
                        if (r == null) vm.showToast("Vocal trop court.") else vm.sendVoice(r.first, r.second)
                    },
                    onMic = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            beginRecording()
                        } else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onPhoto = { photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onSend = { vm.sendText(text); text = "" },
                )

                if (panel != 0 && !recording) {
                    PanelArea(panel, vm, onPanel = { panel = it }, onEmoji = { text += it })
                }
            }
        }
        StickerBurst(burstId, burstN)
    }

    WallpaperMenu(showWall, { showWall = false }, onWall, { vm.showToast(it) })
    state.photo?.let { ph -> PhotoPreview(ph, vm) }
    viewer?.let { m -> PhotoViewer(m) { viewer = null } }
}

@Composable
private fun RoundButton(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(if (active) Rose else Color(0xFFF3F3F3)).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 20.sp, color = if (active) Color.White else Color.Black) }
}

@Composable
private fun DayChip(t: Long) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), Alignment.Center) {
        Text(
            dayLabel(t), color = Color(0xFF7A5566), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BubbleRow(m: ChatMsg, partner: String, onOpenPhoto: () -> Unit, onReply: () -> Unit) {
    SwipeToReply(!m.pending, onReply) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
            Column(horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
                m.reply?.let {
                    QuoteChip(it, partner)
                    Spacer(Modifier.height(2.dp))
                }
                Bubble(m, onOpenPhoto)
            }
        }
    }
}

@Composable
private fun Meta(m: ChatMsg, color: Color, modifier: Modifier = Modifier) {
    val tick = when {
        !m.mine -> ""
        m.pending -> "  🕓"
        m.read -> "  ✓✓"
        else -> "  ✓"
    }
    Text(
        hhmm(m.createdAt) + tick,
        fontSize = 11.sp,
        color = if (m.mine && m.read && !m.pending) Color(0xFFBDF1FF) else color,
        modifier = modifier,
        textAlign = TextAlign.End,
    )
}

@Composable
private fun Bubble(m: ChatMsg, onOpenPhoto: () -> Unit) {
    val mine = m.mine
    val bg = if (mine) Rose.copy(alpha = 0.86f) else Color.White.copy(alpha = 0.6f)
    val fg = if (mine) Color.White else Color(0xFF231319)
    val sub = if (mine) Color(0xB3FFFFFF) else Color(0x99231319)
    val shape = RoundedCornerShape(21.dp, 21.dp, if (mine) 6.dp else 21.dp, if (mine) 21.dp else 6.dp)

    when (m.kind) {
        "sticker" -> Column(horizontalAlignment = Alignment.End) {
            AnimatedSticker(m.body ?: "", Modifier.size(150.dp))
            Meta(m, Color.Gray)
        }
        "text" -> if (isBigEmoji(m.body)) {
            Column(horizontalAlignment = Alignment.End) {
                Text(m.body ?: "", fontSize = 52.sp)
                Meta(m, Color.Gray)
            }
        } else {
            Surface(color = bg, shape = shape, shadowElevation = 0.dp, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)), modifier = Modifier.widthIn(max = 290.dp)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 7.dp)) {
                    Text(m.body ?: "", color = fg, fontSize = 17.sp)
                    Meta(m, sub, Modifier.align(Alignment.End))
                }
            }
        }
        "gif" -> Surface(color = bg, shape = shape, shadowElevation = 0.dp, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))) {
            Column(Modifier.padding(3.dp)) {
                AsyncImage(
                    model = m.media,
                    contentDescription = "GIF",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(240.dp)
                        .aspectRatio(if (m.w > 0 && m.h > 0) m.w.toFloat() / m.h else 1f)
                        .clip(RoundedCornerShape(13.dp)),
                )
                Meta(m, sub, Modifier.align(Alignment.End).padding(horizontal = 6.dp, vertical = 1.dp))
            }
        }
        "image" -> Surface(color = bg, shape = shape, shadowElevation = 0.dp, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))) {
            Column(Modifier.padding(3.dp)) {
                AsyncImage(
                    model = m.localBytes ?: Backend.mediaUrl(m.media ?: ""),
                    contentDescription = "Photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(240.dp)
                        .aspectRatio(if (m.w > 0 && m.h > 0) m.w.toFloat() / m.h else 1f)
                        .clip(RoundedCornerShape(13.dp))
                        .clickable { onOpenPhoto() },
                )
                if (!m.body.isNullOrEmpty()) {
                    Text(m.body, color = fg, fontSize = 16.sp, modifier = Modifier.widthIn(max = 240.dp).padding(horizontal = 6.dp, vertical = 4.dp))
                }
                Meta(m, sub, Modifier.align(Alignment.End).padding(horizontal = 6.dp, vertical = 1.dp))
            }
        }
        "voice" -> VoiceBubble(m, bg, fg, sub, shape)
        else -> Unit
    }
}

@Composable
private fun VoiceBubble(m: ChatMsg, bg: Color, fg: Color, sub: Color, shape: RoundedCornerShape) {
    val play by VoicePlayer.state.collectAsState()
    val source = m.localFile ?: Backend.mediaUrl(m.media ?: "")
    val active = play.key == m.key
    val playing = active && play.playing
    val fraction = if (active && play.dur > 0) play.pos.toFloat() / play.dur else 0f
    val shownSeconds = if (active && play.dur > 0 && play.pos > 0) play.pos / 1000 else m.dur
    val barColor = if (m.mine) Color(0x66FFFFFF) else Color(0x33000000)
    val doneColor = if (m.mine) Color.White else Rose
    val seed = (m.media ?: m.key).hashCode()
    val heights = remember(seed) {
        var h = seed.toLong() and 0xFFFFFFFFL
        List(30) {
            h = (h * 1103515245L + 12345L) and 0xFFFFFFFFL
            0.22f + ((h shr 8) % 78).toFloat() / 100f
        }
    }

    Surface(color = bg, shape = shape, shadowElevation = 0.dp, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(Modifier.width(230.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(if (m.mine) Color.White else Rose)
                        .clickable { VoicePlayer.toggle(m.key, source) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (play.error && active) "⚠" else if (playing) "❚❚" else "▶",
                        color = if (m.mine) Rose else Color.White, fontSize = 15.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                androidx.compose.foundation.Canvas(
                    Modifier.weight(1f).height(30.dp).pointerInput(m.key) {
                        detectTapGestures { offset ->
                            if (VoicePlayer.state.value.key == m.key) VoicePlayer.seek(m.key, offset.x / size.width)
                            else VoicePlayer.toggle(m.key, source)
                        }
                    },
                ) {
                    val n = heights.size
                    val step = size.width / n
                    val bw = step * 0.62f
                    heights.forEachIndexed { i, hh ->
                        val bh = size.height * hh
                        drawRoundRect(
                            color = if (i < (n * fraction).toInt()) doneColor else barColor,
                            topLeft = Offset(i * step, (size.height - bh) / 2f),
                            size = Size(bw, bh),
                            cornerRadius = CornerRadius(bw / 2f, bw / 2f),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(mmss(shownSeconds), color = fg, fontSize = 12.sp)
            }
            Meta(m, sub, Modifier.align(Alignment.End))
        }
    }
}

@Composable
private fun EmojiPanel(onPick: (String) -> Unit) {
    var cat by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            itemsIndexed(Emojis.cats) { i, c ->
                Text(
                    c.icon, fontSize = 22.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .background(if (i == cat) RoseClair else Color.Transparent)
                        .clickable { cat = i }.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        LazyVerticalGrid(columns = GridCells.Fixed(8), modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
            items(Emojis.cats[cat].emojis) { e ->
                Text(
                    e, fontSize = 26.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(e) }.padding(vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun StickerPanel(onPick: (String) -> Unit) {
    val context = LocalContext.current
    val ids = remember {
        (context.assets.list("stickers") ?: emptyArray())
            .filter { it.endsWith(".png") }.sorted().map { it.removeSuffix(".png") }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxSize().padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(ids) { id ->
            AnimatedSticker(id, Modifier.fillMaxWidth().aspectRatio(1f).clickable { onPick(id) })
        }
    }
}

@Composable
private fun GifPanel(vm: ChatViewModel, onPick: (Gif) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val gridState = rememberLazyStaggeredGridState()

    LaunchedEffect(query) {
        delay(if (query.isEmpty()) 0 else 400)
        vm.searchGifs(query)
    }
    val count = state.gifs.size
    LaunchedEffect(gridState, count) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { if (count > 0 && it >= count - 4) vm.moreGifs() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = query,
                onValueChange = { query = it.take(60) },
                placeholder = { Text("Rechercher un GIF…") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text("Powered by GIPHY", fontSize = 10.sp, color = Color.Gray)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.gifs.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
                    when {
                        state.gifLoading -> CircularProgressIndicator()
                        state.gifError -> Text("Les GIF ne sont pas disponibles pour le moment.", color = Color.Gray, textAlign = TextAlign.Center)
                        else -> Text("Aucun GIF trouvé.", color = Color.Gray)
                    }
                }
            } else {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalItemSpacing = 4.dp,
                ) {
                    items(count) { i ->
                        val g = state.gifs[i]
                        AsyncImage(
                            model = g.preview,
                            contentDescription = "GIF",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(if (g.w > 0 && g.h > 0) g.w.toFloat() / g.h else 1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFEEEEEE))
                                .clickable { onPick(g) },
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun MessagesArea(state: ChatState, partner: String, vm: ChatViewModel, modifier: Modifier, onReply: (ChatMsg) -> Unit, onOpenPhoto: (ChatMsg) -> Unit) {
                Box(modifier.fillMaxWidth()) {
                    val items = remember(state.messages) { state.messages.asReversed() }
                    val listState = rememberLazyListState()

                    LaunchedEffect(items.firstOrNull()?.key) {
                        val first = items.firstOrNull()
                        if (first != null && (first.mine || listState.firstVisibleItemIndex <= 2)) {
                            listState.animateScrollToItem(0)
                        }
                    }
                    LaunchedEffect(listState, items.size) {
                        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                            .collect { if (items.isNotEmpty() && it >= items.size - 3) vm.loadOlder() }
                    }

                    when {
                        state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                        state.error -> Column(
                            Modifier.fillMaxSize().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text("Impossible de charger la conversation.", textAlign = TextAlign.Center)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { vm.retry() }) { Text("Réessayer") }
                        }
                        items.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                            Text("Dis quelque chose de gentil à $partner 💗", textAlign = TextAlign.Center, color = Color.Gray)
                        }
                        else -> LazyColumn(
                            state = listState,
                            reverseLayout = true,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            itemsIndexed(items, key = { _, m -> m.key }) { i, m ->
                                val older = items.getOrNull(i + 1)
                                Column(Modifier.fillMaxWidth()) {
                                    if (older == null || !sameDay(older.createdAt, m.createdAt)) DayChip(m.createdAt)
                                    BubbleRow(m, partner, { onOpenPhoto(m) }, { onReply(m) })
                                }
                            }
                        }
                    }

                    if (state.toast != null) {
                        LaunchedEffect(state.toast) {
                            delay(3500)
                            vm.clearToast()
                        }
                        Box(Modifier.fillMaxSize().padding(bottom = 12.dp), Alignment.BottomCenter) {
                            Text(
                                state.toast!!, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xCC000000)).padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }

}

@Composable
private fun InputBar(
    recording: Boolean, recSecs: Int, panel: Int, text: String,
    onText: (String) -> Unit, onPanel: (Int) -> Unit,
    onCancelRec: () -> Unit, onStopRec: () -> Unit,
    onMic: () -> Unit, onPhoto: () -> Unit, onSend: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    if (recording) {
        Row(
            Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.7f)).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🗑", fontSize = 24.sp, modifier = Modifier.clickable { onCancelRec() }.padding(10.dp))
            Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFFE53935)))
            Spacer(Modifier.width(8.dp))
            Text(mmss(recSecs), fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Text("Enregistrement…", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.weight(1f))
            RoundButton("➤", true) { onStopRec() }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.62f))
                    .border(1.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                    .clickable {
                        if (panel != 0) onPanel(0)
                        else { keyboard?.hide(); focus.clearFocus(); onPanel(2) }
                    },
                contentAlignment = Alignment.Center,
            ) { Text(if (panel != 0) "⌨" else "+", fontSize = 28.sp, color = Rose) }
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.62f))
                    .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(24.dp)),
                verticalAlignment = Alignment.Bottom,
            ) {
                TextField(
                    value = text,
                    onValueChange = { onText(it) },
                    placeholder = { Text("Message") },
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused) onPanel(0) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                Text("📷", fontSize = 22.sp, modifier = Modifier.clickable { onPhoto() }.padding(horizontal = 6.dp, vertical = 10.dp))
                Box(Modifier.padding(end = 4.dp, bottom = 5.dp)) {
                    if (text.isBlank()) Text("🎤", fontSize = 22.sp, modifier = Modifier.clickable { onMic() }.padding(horizontal = 6.dp, vertical = 3.dp))
                    else Box(
                        Modifier.size(34.dp).clip(CircleShape).background(Rose).clickable { onSend() },
                        contentAlignment = Alignment.Center,
                    ) { Text("➤", fontSize = 17.sp, color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun PanelArea(panel: Int, vm: ChatViewModel, onPanel: (Int) -> Unit, onEmoji: (String) -> Unit) {
                    Column(Modifier.fillMaxWidth().height(290.dp).background(Color.White)) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (panel) {
                                2 -> StickerPanel { vm.sendSticker(it); onPanel(0) }
                                else -> GifPanel(vm) { vm.sendGif(it); onPanel(0) }
                            }
                        }
                        Row(Modifier.fillMaxWidth()) {
                            listOf("💗 Stickers", "GIF").forEachIndexed { i, label ->
                                TextButton(
                                    onClick = { onPanel(i + 2) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        label,
                                        color = if (panel == i + 2) Rose else Color.Gray,
                                        fontWeight = if (panel == i + 2) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }
}

@Composable
private fun PhotoPreview(ph: PreparedPhoto, vm: ChatViewModel) {
        var caption by remember { mutableStateOf("") }
        Dialog(
            onDismissRequest = { vm.cancelPhoto() },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().imePadding()) {
                Text(
                    "✕", color = Color.White, fontSize = 26.sp,
                    modifier = Modifier.clickable { vm.cancelPhoto() }.padding(16.dp),
                )
                AsyncImage(
                    model = ph.bytes,
                    contentDescription = "Photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth().background(Color(0xFF111111)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = caption,
                        onValueChange = { caption = it.take(300) },
                        placeholder = { Text("Ajouter une légende…") },
                        modifier = Modifier.weight(1f),
                        maxLines = 3,
                    )
                    Spacer(Modifier.width(8.dp))
                    RoundButton("➤", true) { vm.sendPhoto(caption) }
                }
            }
        }
}

@Composable
private fun PhotoViewer(m: ChatMsg, onClose: () -> Unit) {
        Dialog(
            onDismissRequest = { onClose() },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color(0xEE000000)).clickable { onClose() }, Alignment.Center) {
                AsyncImage(
                    model = m.localBytes ?: Backend.mediaUrl(m.media ?: ""),
                    contentDescription = "Photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
}
