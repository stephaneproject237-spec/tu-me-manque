package com.tumemanque.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri
import androidx.core.content.FileProvider
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
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
import kotlinx.coroutines.launch
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
    val remVm: ReminderViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

    var text by rememberSaveable { mutableStateOf("") }
    var sendTick by remember { mutableIntStateOf(0) }
    var panel by rememberSaveable { mutableIntStateOf(0) } // 0 aucun, 1 emojis, 2 stickers, 3 GIF
    var recording by remember { mutableStateOf(false) }
    var recSecs by remember { mutableIntStateOf(0) }
    var viewer by remember { mutableStateOf<ChatMsg?>(null) }
    var showWall by remember { mutableStateOf(false) }
    val recorder = remember { VoiceRecorder(context.applicationContext) }
    val gameScope = androidx.compose.runtime.rememberCoroutineScope()
    val game = remember { GameCtl(gameScope, context.applicationContext, { vm.sendText(it) }, { vm.pollSoon() }) }
    DisposableEffect(Unit) { vm.onGame = { game.onIncoming(it) }; onDispose { vm.onGame = null } }
    LaunchedEffect(Unit) { while (true) { game.refresh(); delay(2500) } }

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
    var showCam by remember { mutableStateOf(false) }
    var askText by remember { mutableStateOf(false) }
    var askVideo by remember { mutableStateOf<Pair<File, String>?>(null) }
    var showSched by remember { mutableStateOf(false) }
    var showHot by remember { mutableStateOf(false) }
    // Bot @music : « @music » (suivi ou non d'une chanson) ouvre sa boîte de dialogue au lieu d'envoyer le message.
    var musicBot by remember { mutableStateOf<String?>(null) }
    // Le bot : envoi de ses messages, et travail fait par ce téléphone quand c'est son tour.
    DisposableEffect(vm) {
        ChatBot.send = { body, bot -> vm.sendBot(body, bot) }
        ChatBot.sendSong = { f, s, ref -> vm.sendSong(f, s.title, s.channel, if (s.id.startsWith("http")) s.id else s.thumb, s.durationSec, ref) }
        onDispose { ChatBot.send = null; ChatBot.sendSong = null }
    }
    LaunchedEffect(state.messages) { ChatBot.process(context.applicationContext, state.messages, gameScope) }
    LaunchedEffect(ChatBot.prefill) { ChatBot.prefill?.let { text = it; ChatBot.prefill = null } }
    var menuMsg by remember { mutableStateOf<ChatMsg?>(null) }
    var editing by remember { mutableStateOf<ChatMsg?>(null) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirmDel by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var camPath by rememberSaveable { mutableStateOf("") }
    fun newCamFile(ext: String): Pair<File, Uri> {
        val dir = File(context.cacheDir, "cam").apply { mkdirs() }
        val f = File.createTempFile("cap", ext, dir)
        camPath = f.absolutePath
        return f to FileProvider.getUriForFile(context, context.packageName + ".files", f)
    }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: ""
            if (mime.startsWith("video/")) vm.onVideoPicked(context.applicationContext, uri, mime) { f, mt -> askVideo = f to mt }
            else vm.onPhotoPicked(context.applicationContext, uri)
        }
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && camPath.isNotEmpty()) vm.onPhotoPicked(context.applicationContext, Uri.fromFile(File(camPath)))
    }
    val captureVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        if (ok && camPath.isNotEmpty()) askVideo = File(camPath) to "video/mp4"
    }
    // L'appli déclare maintenant la permission caméra (appels vidéo) : le système exige alors qu'elle soit accordée avant d'ouvrir l'appareil photo.
    var afterCam by remember { mutableStateOf<(() -> Unit)?>(null) }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val f = afterCam; afterCam = null
        if (granted) f?.invoke() else vm.showToast("Autorise la caméra dans les réglages du téléphone pour prendre une photo.")
    }
    fun withCam(f: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) f()
        else { afterCam = f; camPerm.launch(Manifest.permission.CAMERA) }
    }

    var topPx by remember { mutableIntStateOf(0) }
    var botPx by remember { mutableIntStateOf(0) }
    val dens = androidx.compose.ui.platform.LocalDensity.current.density
    Box(Modifier.fillMaxSize()) {
        WallpaperLayer(wall)
        // La liste occupe tout l'écran : elle passe sous l'en-tête et sous la barre de saisie (verre liquide).
        androidx.compose.runtime.CompositionLocalProvider(LocalGameCtl provides game, LocalChatMsgs provides state.messages) {
            MessagesArea(
                state, partner, vm, Modifier.fillMaxSize(),
                padTop = (topPx / dens).dp, padBottom = (botPx / dens).dp,
                onReply = { vm.setReply(it) }, onMenu = { menuMsg = it },
                selected = selected,
                onToggle = { m -> selected = if (m.id in selected) selected - m.id else selected + m.id },
            ) { viewer = it }
        }

        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topPx = it.height }) {
            if (selected.isEmpty()) ChatHeader(partner, state.typing, onBack, onMenu = { showWall = true }, onCall = { Calls.start(it) })
            else SelectionBar(selected.size, onClose = { selected = emptySet() }, onDelete = { confirmDel = true })
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { botPx = it.height }
                .navigationBarsPadding().imePadding(),
        ) {
            if (!recording) GameChip(game, partner, remVm, onSched = { showSched = true }, onHot = { showHot = true }, onNow = { gameScope.launch { vm.showToast(NowPic.request()) } })
            if (state.typing && !recording) TypingBubble()
            editing?.let { em -> ReplyBar(Reply(em.id, true, "text", em.body ?: ""), partner, "✏️  Modifier le message") { editing = null; text = "" } }
            if (editing == null) state.replyTo?.let { ReplyBar(it, partner) { vm.clearReply() } }

            // Suggestion quand on commence à taper « @ ».
            val tt = text.trim().lowercase()
            if (tt.startsWith("@") && "@music".startsWith(tt.take(6)) && !recording) {
                Row(
                    Modifier.padding(start = 14.dp, bottom = 6.dp).clip(RoundedCornerShape(50)).background(Color(0xE6261820))
                        .clickable { text = "@music " + (text.trim().drop(6).trim().takeIf { tt.startsWith("@music") } ?: "") }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🎵", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("@music", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("  ·  chercher et télécharger une chanson", color = Color(0xFFC9A9BA), fontSize = 13.sp)
                }
            }
            InputBar(
                flash = sendTick, recording = recording, recSecs = recSecs, panel = panel, text = text,
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
                onPhoto = { showHot = true },
                onSend = {
                    val em = editing
                    if (em != null) vm.editMessage(em, text) { editing = null; text = "" }
                    else if (text.trim().lowercase().startsWith("@music")) {
                        val q = text.trim().drop(6).trim()
                        if (q.isEmpty()) ChatBot.ask() else ChatBot.query(q)
                        text = ""; sendTick++
                    }
                    else { vm.sendText(text); text = ""; sendTick++ }
                },
                onSendLong = { if (editing == null && text.isNotBlank()) askText = true },
            )

            if (panel != 0 && !recording) {
                PanelArea(panel, vm, onPanel = { panel = it }, onEmoji = { text += it })
            }
        }
        StickerBurst(burstId, burstN)
        if (game.overlay) GameOverlay(game, partner)
        if (remVm.overlay) RemindersOverlay(remVm, partner)
        musicBot?.let { q ->
            MusicBotOverlay(q, partner, onSendToChat = { f, s ->
                // On garde une copie : le fichier du bot peut être effacé à la fermeture.
                val keep = File(context.cacheDir, "cam/send_${System.currentTimeMillis()}.mp3").also { f.copyTo(it, overwrite = true) }
                vm.sendSong(keep, s.title, s.channel.removeSuffix(" - Topic"), s.thumb, s.durationSec)
            }) { musicBot = null }
        }
    }

    menuMsg?.let { mm ->
        MessageMenu(
            m = mm,
            onDismiss = { menuMsg = null },
            onReply = { menuMsg = null; editing = null; vm.setReply(mm) },
            onEdit = { menuMsg = null; vm.clearReply(); editing = mm; text = mm.body ?: "" },
            onCopy = { menuMsg = null; clipboard.setText(androidx.compose.ui.text.AnnotatedString(mm.body ?: "")); vm.showToast("Copié.") },
            onSelect = { menuMsg = null; if (mm.id != 0L) selected = setOf(mm.id) },
            onDelMe = { menuMsg = null; vm.deleteMessage(mm, false) },
            onDelAll = { menuMsg = null; vm.deleteMessage(mm, true) },
        )
    }
    if (confirmDel) {
        val chosen = state.messages.filter { it.id in selected }
        val canAll = chosen.isNotEmpty() && chosen.all { it.mine && !it.deleted }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDel = false },
            containerColor = Color(0xF2261820),
            shape = RoundedCornerShape(24.dp),
            title = { Text("Supprimer ${chosen.size} message${if (chosen.size > 1) "s" else ""} ?", color = Color(0xFFF5EEF2)) },
            text = {
                Column {
                    MenuRow(R.drawable.ic_trash, "Supprimer pour moi", Rose) { confirmDel = false; selected = emptySet(); vm.deleteMessages(chosen, false) }
                    if (canAll) MenuRow(R.drawable.ic_trash, "Supprimer pour tout le monde", Color(0xFFD32F2F)) { confirmDel = false; selected = emptySet(); vm.deleteMessages(chosen, true) }
                }
            },
            confirmButton = { TextButton(onClick = { confirmDel = false }) { Text("Annuler", color = Color.Gray) } },
        )
    }
    if (askText) SendChoiceSheet("Envoyer ce message", canOnce = true) { o -> askText = false; if (o != null) { vm.sendText(text, o); text = "" } }
    askVideo?.let { (f, mt) -> SendChoiceSheet("Envoyer cette vidéo", canOnce = true) { o -> askVideo = null; if (o != null) vm.sendVideoFile(f, mt, o) } }
    if (showSched) ScheduledListDialog { showSched = false }
    if (showHot) HotPicFlow(
        "", icon = null, allowSched = true,
        onVideoRecord = { withCam { captureVideo.launch(newCamFile(".mp4").second) } },
        onVideoPicked = { uri, mime -> vm.onVideoPicked(context.applicationContext, uri, mime) { f, mt -> askVideo = f to mt } },
        onResult = { ph, once, at -> vm.sendEdited(ph, once, at) },
        onClose = { showHot = false },
    )
    WallpaperMenu(showWall, { showWall = false }, onWall, { vm.showToast(it) })
    state.photo?.let { ph -> PhotoPreview(ph, vm, onRetake = { vm.cancelPhoto(); showHot = true }) }
    viewer?.let { m -> if (m.once) OnceViewer(m, vm) { viewer = null } else if (m.kind == "video") VideoViewer(m) { viewer = null } else PhotoViewer(m) { viewer = null } }
}

@Composable
private fun RoundButton(icon: Int, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(if (active) Rose else Color(0xFF2A1B24)).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Ico(icon, Color.White, 22.dp) }
}

@Composable
private fun DayChip(t: Long) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), Alignment.Center) {
        Text(
            dayLabel(t), color = Color(0xFFA58F9B), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BubbleRow(m: ChatMsg, partner: String, onOpenPhoto: () -> Unit, onReply: () -> Unit, onMenu: () -> Unit) {
    if (m.kind == "game") {
        SwipeToReply(!m.pending, { onReply() }, {}) { GameBotRow(m, partner) }
        return
    }
    // Bot @music : questions, propositions et choix, au centre et visibles des deux côtés.
    ChatBot.bot(m)?.let { b ->
        if (b.optString("t") != "q" && !m.deleted) { BotChatRow(m, partner, LocalChatMsgs.current); return }
    }
    SwipeToReply(!m.pending, { if (!m.deleted) onReply() }, onMenu) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
            Column(horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
                if (m.deleted) DeletedBubble(m.mine)
                else {
                    m.reply?.let {
                        QuoteChip(it, partner)
                        Spacer(Modifier.height(2.dp))
                    }
                    Bubble(m, onOpenPhoto)
                }
            }
        }
    }
}

@Composable
private fun Meta(m: ChatMsg, color: Color, modifier: Modifier = Modifier) {
    val readNow = m.mine && m.read && !m.pending
    val tick = when {
        !m.mine -> ""
        m.pending -> "  🕓"
        m.read -> "  ✓✓"
        else -> "  ✓"
    }
    val label = androidx.compose.ui.text.buildAnnotatedString {
        if (m.kind == "text" && !m.deleted && m.meta?.optBoolean("edited") == true) withStyle(androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append("modifié") }.also { append(" · ") }
        append(hhmm(m.createdAt))
        if (readNow) withStyle(androidx.compose.ui.text.SpanStyle(color = Color(0xFF3DB7FF), fontWeight = FontWeight.ExtraBold)) { append("  ✓✓") }
        else append(tick)
    }
    Text(
        label,
        fontSize = 11.sp,
        color = color,
        modifier = modifier,
        textAlign = TextAlign.End,
    )
}

@Composable
private fun Bubble(m: ChatMsg, onOpenPhoto: () -> Unit) {
    val mine = m.mine
    val bg = if (mine) Rose.copy(alpha = 0.88f) else Color(0xBC42383F)
    val fg = if (mine) Color.White else Color(0xFFF5EEF2)
    val sub = if (mine) Color(0xB3FFFFFF) else Color(0x99F5EEF2)
    val shape = RoundedCornerShape(21.dp, 21.dp, if (mine) 6.dp else 21.dp, if (mine) 21.dp else 6.dp)

    if (m.once) { OnceBubble(m, onOpenPhoto); return }
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
            Surface(color = bg, shape = shape, shadowElevation = 0.dp, modifier = Modifier.softGlow(shape, mine).widthIn(max = 290.dp)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 7.dp)) {
                    Text(m.body ?: "", color = fg, fontSize = 17.sp)
                    Meta(m, sub, Modifier.align(Alignment.End))
                }
            }
        }
        "gif" -> Surface(color = bg, shape = shape, shadowElevation = 0.dp, modifier = Modifier.softGlow(shape, mine)) {
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
        "image" -> Surface(color = bg, shape = shape, shadowElevation = 0.dp, modifier = Modifier.softGlow(shape, mine)) {
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
        "video" -> Surface(color = bg, shape = shape, shadowElevation = 0.dp, modifier = Modifier.softGlow(shape, mine)) {
            Column(Modifier.padding(3.dp)) {
                VideoTile(m, onOpenPhoto)
                Meta(m, sub, Modifier.align(Alignment.End).padding(horizontal = 6.dp, vertical = 1.dp))
            }
        }
        "voice" -> if (m.meta?.optJSONObject("song") != null) SongBubble(m, bg, fg, sub, shape) else VoiceBubble(m, bg, fg, sub, shape)
        else -> Unit
    }
}

/** Chanson envoyée avec @music : pochette, écoute et téléchargement. */
@Composable
private fun SongBubble(m: ChatMsg, bg: Color, fg: Color, sub: Color, shape: RoundedCornerShape) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val song = m.meta?.optJSONObject("song")
    val title = song?.optString("t").orEmpty().ifEmpty { "Chanson" }
    val artist = song?.optString("a").orEmpty()
    val thumb = song?.optString("th").orEmpty()
    val play by VoicePlayer.state.collectAsState()
    val source = m.localFile ?: Backend.mediaUrl(m.media ?: "")
    val active = play.key == m.key
    val playing = active && play.playing
    var saved by remember(m.key) { mutableStateOf<String?>(null) }
    Surface(color = bg, shape = shape, shadowElevation = 0.dp, modifier = Modifier.softGlow(shape, m.mine)) {
        Column(Modifier.width(250.dp).padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x22FFFFFF))) {
                    if (thumb.isNotEmpty()) AsyncImage(model = thumb, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Text("🎵", fontSize = 24.sp, modifier = Modifier.align(Alignment.Center))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("🎵 Chanson", color = sub, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(title, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (artist.isNotEmpty()) Text(artist, color = sub, fontSize = 12.sp, maxLines = 1)
                }
            }
            if (active && play.dur > 0) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { (play.pos.toFloat() / play.dur).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = if (m.mine) Color.White else Rose, trackColor = Color(0x33FFFFFF),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (m.mine) Color.White else Rose).clickable { VoicePlayer.toggle(m.key, source) }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(if (playing) "❚❚ Pause" else "▶ Écouter", color = if (m.mine) Rose else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Color(0x26FFFFFF)).clickable {
                        if (saved == null && !m.pending) {
                            saved = "…"
                            scope.launch {
                                saved = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val f = File(ctx.cacheDir, "cam/dl_${m.key}.mp3")
                                        if (!f.exists()) java.net.URL(Backend.mediaUrl(m.media ?: "")).openStream().use { i -> f.outputStream().use { i.copyTo(it) } }
                                        MbEngine.saveToPhone(ctx, File(f.parentFile, MbEngine.safeName(title) + ".mp3").also { f.copyTo(it, overwrite = true) }, title, artist)
                                        "✓ Enregistrée"
                                    }.getOrDefault("Échec")
                                }
                            }
                        }
                    }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(saved ?: "⬇ Télécharger", color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
            Meta(m, sub, Modifier.align(Alignment.End).padding(top = 4.dp))
        }
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
    val barColor = if (m.mine) Color(0x66FFFFFF) else Color(0x44FFFFFF)
    val doneColor = if (m.mine) Color.White else Color(0xFFFF6A9C)
    val seed = (m.media ?: m.key).hashCode()
    val heights = remember(seed) {
        var h = seed.toLong() and 0xFFFFFFFFL
        List(30) {
            h = (h * 1103515245L + 12345L) and 0xFFFFFFFFL
            0.22f + ((h shr 8) % 78).toFloat() / 100f
        }
    }

    Surface(color = bg, shape = shape, shadowElevation = 0.dp, modifier = Modifier.softGlow(shape, m.mine)) {
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
                        .background(if (i == cat) Color(0x44E91E63) else Color.Transparent)
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
            .filter { it.endsWith(".png") || it.endsWith(".webp") }.sorted().map { it.substringBeforeLast(".") }
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
                                .background(Color(0xFF2A1B24))
                                .clickable { onPick(g) },
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun MessagesArea(state: ChatState, partner: String, vm: ChatViewModel, modifier: Modifier, padTop: androidx.compose.ui.unit.Dp, padBottom: androidx.compose.ui.unit.Dp, onReply: (ChatMsg) -> Unit, onMenu: (ChatMsg) -> Unit, selected: Set<Long>, onToggle: (ChatMsg) -> Unit, onOpenPhoto: (ChatMsg) -> Unit) {
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
                            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = padTop + 8.dp, bottom = padBottom + 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            itemsIndexed(items, key = { _, m -> m.key }) { i, m ->
                                val older = items.getOrNull(i + 1)
                                Column(Modifier.fillMaxWidth().animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                                    if (older == null || !sameDay(older.createdAt, m.createdAt)) DayChip(m.createdAt)
                                    val canSel = selected.isNotEmpty() && m.id != 0L && !m.pending && m.kind != "game"
                                    val fresh = m.pending && System.currentTimeMillis() - m.createdAt < 1500
                                    val enter = remember(m.key) { androidx.compose.animation.core.Animatable(if (fresh) 0f else 1f) }
                                    LaunchedEffect(m.key) { if (fresh) enter.animateTo(1f, androidx.compose.animation.core.spring(0.6f, 380f)) }
                                    val dpPx = androidx.compose.ui.platform.LocalDensity.current.density
                                    Box(Modifier.fillMaxWidth().graphicsLayer {
                                        val p = enter.value
                                        scaleX = 0.35f + 0.65f * p; scaleY = scaleX
                                        translationY = (1f - p) * 64f * dpPx
                                        alpha = (0.2f + 0.8f * p).coerceIn(0f, 1f)
                                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (m.mine) 1f else 0f, 1f)
                                    }.then(if (m.id in selected) Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x40E91E63)) else Modifier)) {
                                        BubbleRow(m, partner, { onOpenPhoto(m) }, { onReply(m) }, { onMenu(m) })
                                        if (canSel) Box(Modifier.matchParentSize().clickable { onToggle(m) })
                                        if (m.id in selected) Text("✔", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                            modifier = Modifier.align(Alignment.CenterStart).padding(start = 2.dp).clip(CircleShape).background(Rose).padding(horizontal = 5.dp, vertical = 1.dp))
                                    }
                                }
                            }
                        }
                    }

                    if (state.toast != null) {
                        LaunchedEffect(state.toast) {
                            delay(3500)
                            vm.clearToast()
                        }
                        Box(Modifier.fillMaxSize().padding(bottom = padBottom + 12.dp), Alignment.BottomCenter) {
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
    flash: Int, recording: Boolean, recSecs: Int, panel: Int, text: String,
    onText: (String) -> Unit, onPanel: (Int) -> Unit,
    onCancelRec: () -> Unit, onStopRec: () -> Unit,
    onMic: () -> Unit, onPhoto: () -> Unit, onSend: () -> Unit, onSendLong: () -> Unit = {},
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val fl = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(flash) { if (flash > 0) { fl.snapTo(1f); fl.animateTo(0f, androidx.compose.animation.core.tween(500)) } }
    if (recording) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xCC26171F)).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.clickable { onCancelRec() }.padding(10.dp)) { Ico(R.drawable.ic_trash, Color(0xFFE53935), 26.dp) }
            Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFFE53935)))
            Spacer(Modifier.width(8.dp))
            Text(mmss(recSecs), fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Text("Enregistrement…", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.weight(1f))
            RoundButton(R.drawable.ic_send, true) { onStopRec() }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                Modifier.size(44.dp).softGlow(CircleShape, false).clip(CircleShape).background(Color(0xCC2A1B24))
                    .border(1.dp, Color(0x1AFFFFFF), CircleShape)
                    .clickable {
                        if (panel != 0) onPanel(0)
                        else { keyboard?.hide(); focus.clearFocus(); onPanel(2) }
                    },
                contentAlignment = Alignment.Center,
            ) { Ico(if (panel != 0) R.drawable.ic_close else R.drawable.ic_add, Rose, 28.dp) }
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier.weight(1f).heightIn(min = 44.dp).softGlow(RoundedCornerShape(24.dp), false).clip(RoundedCornerShape(24.dp))
                    .background(androidx.compose.ui.graphics.lerp(Color(0xCC2A1B24), Color(0xB3FF6A9C), fl.value))
                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(24.dp)),
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
                Box(Modifier.clickable { onPhoto() }.padding(horizontal = 8.dp, vertical = 10.dp)) { Ico(R.drawable.ic_camera, Rose, 24.dp) }
                Box(Modifier.padding(end = 4.dp, bottom = 5.dp)) {
                    if (text.isBlank()) Box(Modifier.clickable { onMic() }.padding(horizontal = 6.dp, vertical = 3.dp)) { Ico(R.drawable.ic_mic, Rose, 24.dp) }
                    else Box(
                        Modifier.size(34.dp).clip(CircleShape).background(Rose).pointerInput(Unit) { detectTapGestures(onTap = { onSend() }, onLongPress = { onSendLong() }) },
                        contentAlignment = Alignment.Center,
                    ) { Ico(R.drawable.ic_send, Color.White, 18.dp) }
                }
            }
        }
    }
}

@Composable
private fun PanelArea(panel: Int, vm: ChatViewModel, onPanel: (Int) -> Unit, onEmoji: (String) -> Unit) {
                    Column(Modifier.fillMaxWidth().height(290.dp).background(Color(0xFF1E131A))) {
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
private fun PhotoPreview(ph: PreparedPhoto, vm: ChatViewModel, onRetake: () -> Unit) {
        var caption by remember { mutableStateOf("") }
        var onceSel by remember { mutableStateOf(false) }
        var atSel by remember { mutableStateOf(0L) }
        var askTime by remember { mutableStateOf(false) }
        val opts = { SendOpts(onceSel, atSel) }
        Dialog(
            onDismissRequest = { vm.cancelPhoto() },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().imePadding()) {
                Box(Modifier.clickable { vm.cancelPhoto() }.padding(16.dp)) { Ico(R.drawable.ic_close, Color.White, 26.dp) }
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
                    RoundButton(R.drawable.ic_send, true) { vm.sendPhoto(caption, opts()) }
                }
                Row(Modifier.fillMaxWidth().background(Color(0xFF111111)).padding(start = 10.dp, end = 10.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(if (onceSel) Color(0x47FF7043) else Color.Transparent)
                            .border(1.dp, if (onceSel) Color(0xFFFF8A50) else Color(0x29FFFFFF), RoundedCornerShape(22.dp)).clickable { onceSel = !onceSel }.padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("🔥 Vue unique", color = Color(0xFFF5EEF2), fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(if (atSel > 0) Color(0x47FF7043) else Color.Transparent)
                            .border(1.dp, if (atSel > 0) Color(0xFFFF8A50) else Color(0x29FFFFFF), RoundedCornerShape(22.dp)).clickable { if (atSel > 0) atSel = 0 else askTime = true }.padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (atSel > 0) "🕐 " + whenLabel(atSel) else "🕐 Programmer", color = Color(0xFFF5EEF2), fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                }
                if (askTime) SendChoiceSheet("Programmer la photo", canOnce = false) { o -> askTime = false; if (o != null && o.at > 0) atSel = o.at }
                Row(Modifier.fillMaxWidth().background(Color(0xFF111111)).padding(start = 10.dp, end = 10.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(26.dp)).background(Color(0xFF2A1B24)).clickable { onRetake() }.padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("↻ Reprendre", color = Color(0xFFF5EEF2), fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                    Box(
                        Modifier.weight(1.4f).clip(RoundedCornerShape(26.dp)).background(Rose).clickable { vm.sendPhoto(caption, opts()) }.padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("✓ Utiliser cette photo", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
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

@Composable
private fun CameraChoice(onDismiss: () -> Unit, onPhoto: () -> Unit, onVideo: () -> Unit, onGallery: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xF2261820),
        shape = RoundedCornerShape(24.dp),
        title = { Text("Appareil photo", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                CamOption(R.drawable.ic_camera, "Prendre une photo", onPhoto)
                CamOption(R.drawable.ic_videocam, "Filmer une vidéo", onVideo)
                CamOption(R.drawable.ic_image, "Choisir dans la galerie", onGallery)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annuler", color = Color.Gray) } },
    )
}

@Composable
private fun CamOption(icon: Int, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x26E91E63)).clickable { onClick() }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(icon, Rose, 24.dp)
        Spacer(Modifier.width(14.dp))
        Text(label, color = Rose, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
private fun MessageMenu(m: ChatMsg, onDismiss: () -> Unit, onReply: () -> Unit, onEdit: () -> Unit, onCopy: () -> Unit, onSelect: () -> Unit, onDelMe: () -> Unit, onDelAll: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xF2261820),
        shape = RoundedCornerShape(24.dp),
        title = null,
        text = {
            Column {
                if (!m.deleted) MenuRow(null, "↩  Répondre", Rose, onReply)
                if (!m.deleted && m.kind == "text" && !m.body.isNullOrEmpty()) MenuRow(null, "⧉  Copier", Rose, onCopy)
                if (m.mine && !m.deleted && !m.pending && m.id != 0L && m.kind == "text" && !m.once) MenuRow(null, "✏️  Modifier", Rose, onEdit)
                MenuRow(null, "☑  Sélectionner", Rose, onSelect)
                MenuRow(R.drawable.ic_trash, "Supprimer pour moi", Rose, onDelMe)
                if (m.mine && !m.deleted) MenuRow(R.drawable.ic_trash, "Supprimer pour tout le monde", Color(0xFFD32F2F), onDelAll)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annuler", color = Color.Gray) } },
    )
}

@Composable
private fun MenuRow(icon: Int?, label: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.08f)).clickable { onClick() }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Ico(icon, color, 22.dp); Spacer(Modifier.width(12.dp)) }
        Text(label, color = color, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}


/** Très léger halo diffus autour des bulles et de la barre de saisie (effet « verre » façon iMessage). */
private fun Modifier.softGlow(shape: androidx.compose.ui.graphics.Shape, mine: Boolean): Modifier =
    if (android.os.Build.VERSION.SDK_INT >= 28) {
        val c = if (mine) Color(0x66FF4886) else Color(0x40FFB3CC)
        this.shadow(9.dp, shape, clip = false, ambientColor = c, spotColor = c)
    } else this


/** Barre du haut pendant la sélection de plusieurs messages. */
@Composable
private fun SelectionBar(count: Int, onClose: () -> Unit, onDelete: () -> Unit) {
    val glass = Color(0xE6281C24)
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(26.dp)).background(glass).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable { onClose() }, contentAlignment = Alignment.Center) { Ico(R.drawable.ic_close, Rose, 24.dp) }
        Text("$count sélectionné${if (count > 1) "s" else ""}", color = Color(0xFFF5EEF2), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        Box(Modifier.size(44.dp).clip(CircleShape).clickable { onDelete() }, contentAlignment = Alignment.Center) { Ico(R.drawable.ic_trash, Color(0xFFFF5A6E), 24.dp) }
    }
}
