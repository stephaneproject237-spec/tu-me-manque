package com.tumemanque.app

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.view.WindowManager
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Flame = Color(0xFFFF8A50)

fun whenLabel(t: Long): String {
    val now = Calendar.getInstance()
    val d = Calendar.getInstance().apply { timeInMillis = t }
    val hm = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(t))
    val sameDay = now.get(Calendar.YEAR) == d.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == d.get(Calendar.DAY_OF_YEAR)
    now.add(Calendar.DAY_OF_YEAR, 1)
    val tomorrow = now.get(Calendar.YEAR) == d.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == d.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> "aujourd'hui à $hm"
        tomorrow -> "demain à $hm"
        else -> SimpleDateFormat("EEE d MMM", Locale.FRANCE).format(Date(t)) + " à " + hm
    }
}

private fun atHour(hour: Int, addDays: Int): Long = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_YEAR, addDays); set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
private fun SheetBtn(label: String, sub: String? = null, hot: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp))
            .background(if (hot) Rose else Color(0x26E91E63)).clickable { onClick() }.padding(14.dp),
    ) {
        Text(label, color = if (hot) Color.White else Rose, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        if (sub != null) Text(sub, color = Color(0xB3F5EEF2), fontSize = 12.sp)
    }
}

/**
 * « Comment envoyer ? » : tout de suite, en vue unique (si [canOnce]) ou programmé à une heure choisie.
 * [onResult] reçoit null si on annule.
 */
@Composable
fun SendChoiceSheet(title: String, canOnce: Boolean, startOnce: Boolean = false, onResult: (SendOpts?) -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(0) }
    var once by remember { mutableStateOf(startOnce) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { onResult(null) },
        containerColor = Color(0xF2261820),
        shape = RoundedCornerShape(24.dp),
        title = { Text(if (step == 0) title else "Quand l'envoyer ?", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (step == 0) {
                    SheetBtn("➤  Envoyer maintenant") { onResult(SendOpts(once = startOnce)) }
                    if (canOnce) SheetBtn("🔥  Éphémère · vue unique", "Il le voit une fois, puis ça disparaît.") { onResult(SendOpts(once = true)) }
                    SheetBtn("🕐  Programmer l'envoi…") { step = 1 }
                } else {
                    val now = System.currentTimeMillis()
                    SheetBtn("Dans 1 heure") { onResult(SendOpts(once, now + 3600_000)) }
                    if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 20) SheetBtn("Ce soir 20:00") { onResult(SendOpts(once, atHour(20, 0))) }
                    SheetBtn("Demain 08:00") { onResult(SendOpts(once, atHour(8, 1))) }
                    SheetBtn("Demain 20:00") { onResult(SendOpts(once, atHour(20, 1))) }
                    SheetBtn("📅  Choisir la date et l'heure…") {
                        val c = Calendar.getInstance()
                        DatePickerDialog(context, { _, y, mo, d ->
                            TimePickerDialog(context, { _, h, mi ->
                                val t = Calendar.getInstance().apply { set(y, mo, d, h, mi, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                                onResult(SendOpts(once, t))
                            }, c.get(Calendar.HOUR_OF_DAY), 0, true).show()
                        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply { datePicker.minDate = System.currentTimeMillis() - 1000 }.show()
                    }
                    if (canOnce) Row(Modifier.fillMaxWidth().clickable { once = !once }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = once, onCheckedChange = { once = it })
                        Text("🔥 En vue unique", color = Color(0xFFF5EEF2))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (step == 1) step = 0 else onResult(null) }) { Text(if (step == 1) "Retour" else "Annuler", color = Color.Gray) } },
    )
}

/** Bulle d'un message éphémère : le contenu n'y est jamais affiché. */
@Composable
fun OnceBubble(m: ChatMsg, onOpen: () -> Unit) {
    val what = when (m.kind) { "image" -> "Photo"; "video" -> "Vidéo"; else -> "Message" }
    val canOpen = !m.mine && !m.opened && !m.pending
    val sub = when {
        m.opened -> "Ouvert · disparu"
        m.mine -> "Envoyé · pas encore ouvert"
        else -> "Appuie pour voir (une seule fois)"
    }
    val shape = RoundedCornerShape(21.dp, 21.dp, if (m.mine) 6.dp else 21.dp, if (m.mine) 21.dp else 6.dp)
    Surface(
        color = Color.Transparent, shape = shape,
        modifier = Modifier.widthIn(max = 290.dp)
            .border(1.dp, Color(0x59FFA078), shape)
            .background(Brush.linearGradient(listOf(Color(0x47FF7043), Color(0x52E91E63))), shape)
            .clickable(enabled = canOpen) { onOpen() },
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp).let { if (m.opened) it.then(Modifier) else it }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔥", fontSize = 26.sp, modifier = Modifier.padding(end = 10.dp))
                Column {
                    Text("$what éphémère", color = Color(0xFFF5EEF2).copy(alpha = if (m.opened) 0.6f else 1f), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(sub, color = Color(0xB3F5EEF2), fontSize = 12.sp)
                }
            }
            Text(SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(m.createdAt)) + if (m.mine) (if (m.pending) "  🕓" else "  ✓") else "", fontSize = 11.sp, color = Color(0x99F5EEF2), modifier = Modifier.align(Alignment.End))
        }
    }
}

/** Lecteur plein écran d'un message éphémère : le contenu sort une seule fois, capture d'écran bloquée. */
@Composable
fun OnceViewer(m: ChatMsg, vm: ChatViewModel, onClose: () -> Unit) {
    var content by remember { mutableStateOf<OnceContent?>(null) }
    var failed by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(m.id) {
        val c = try { Backend.onceOpen(m.id) } catch (e: Exception) { failed = true; return@LaunchedEffect }
        if (c == null) { vm.showToast("Ce message a déjà disparu."); vm.markOpened(m.id); onClose() } else content = c
    }
    val close = {
        if (content != null) { vm.markOpened(m.id); fireDone(m.id) }
        onClose()
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val win = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { win?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🔥 Vue unique — ça disparaît après", color = Flame, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0x24FFFFFF)).clickable { close() }, Alignment.Center) { Ico(R.drawable.ic_close, Color.White, 22.dp) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                val c = content
                when {
                    failed -> Text("Connexion impossible. Réessaie.", color = Color.White)
                    c == null -> CircularProgressIndicator(color = Flame)
                    c.kind == "text" -> Text(c.body ?: "", color = Color.White, fontSize = 26.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()))
                    c.kind == "video" -> AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                val mc = MediaController(ctx); mc.setAnchorView(this); setMediaController(mc)
                                setVideoURI(android.net.Uri.parse(Backend.mediaUrl(c.media ?: "")))
                                setOnPreparedListener { it.isLooping = false; start() }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(Backend.mediaUrl(c.media ?: ""))
                            .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build(),
                        contentDescription = "Photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Text("Ne fais pas de capture d'écran : elle est bloquée sur ce message.", color = Color(0xFF8F7A86), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(10.dp))
        }
    }
}

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
private fun fireDone(id: Long) { kotlinx.coroutines.GlobalScope.launch { Backend.onceDone(id) } }

/** Liste des messages programmés (avec la possibilité d'annuler). */
@Composable
fun ScheduledListDialog(onClose: () -> Unit) {
    var items by remember { mutableStateOf<List<Sched>?>(null) }
    var err by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(reload) {
        try { items = Backend.scheduledList() } catch (e: Exception) { err = true }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        containerColor = Color(0xF2261820),
        shape = RoundedCornerShape(24.dp),
        title = { Text("🕐 Messages programmés", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val l = items
                when {
                    err -> Text("Connexion impossible.", color = Color.Gray)
                    l == null -> CircularProgressIndicator(color = Rose)
                    l.isEmpty() -> Text("Aucun message programmé. Maintiens le bouton d'envoi enfoncé pour en programmer un.", color = Color(0xB3F5EEF2), fontSize = 14.sp)
                    else -> l.forEach { s ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(when (s.kind) { "image" -> "📷"; "video" -> "🎥"; "voice" -> "🎤"; "gif" -> "🎞️"; "sticker" -> "💗"; else -> "💬" } + if (s.once) "🔥" else "", fontSize = 22.sp)
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(if (s.kind == "text") (s.body ?: "").take(60) else when (s.kind) { "image" -> "Photo"; "video" -> "Vidéo"; "voice" -> "Vocal"; "gif" -> "GIF"; else -> "Message" }, color = Color(0xFFF5EEF2), maxLines = 1, fontWeight = FontWeight.SemiBold)
                                Text(whenLabel(s.sendAt), color = Color(0xB3F5EEF2), fontSize = 12.sp)
                            }
                            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0x1AFFFFFF)).clickable {
                                scope.launch { try { Backend.scheduledCancel(s.id) } catch (e: Exception) { /* ignoré */ }; reload++ }
                            }, Alignment.Center) { Text("✕", color = Color(0xFFFF8FA8)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Fermer", color = Color.Gray) } },
    )
}
