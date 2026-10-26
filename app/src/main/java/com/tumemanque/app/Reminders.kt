package com.tumemanque.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Une tâche à faire. `target` : me (pour moi), partner (pour l'autre) ou both (pour nous deux). */
data class Reminder(val id: Long, val title: String, val dueAt: Long, val byMe: Boolean, val byName: String, val target: String)

class ReminderViewModel : ViewModel() {
    val items = mutableStateListOf<Reminder>()
    var overlay by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)
    private var fresh = 0L

    fun refresh() {
        viewModelScope.launch {
            val l = Backend.reminderList() ?: return@launch
            if (System.currentTimeMillis() - fresh < 1500) return@launch // on vient de modifier : on attend la prochaine lecture
            items.clear(); items.addAll(l)
        }
    }

    fun create(title: String, who: String, dueMillis: Long, done: () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            val st = Backend.reminderCreate(title, who, dueMillis)
            busy = false
            when (st) {
                "ok" -> { toast = "Rappel enregistré ⏰"; done(); fresh = 0; refresh() }
                "no_partner" -> toast = "Ajoute d'abord ton partenaire."
                "too_fast" -> toast = "Doucement ! Réessaie dans une minute."
                else -> toast = "Rappel non enregistré. Le serveur n'est peut-être pas à jour."
            }
        }
    }

    fun cancel(r: Reminder) {
        items.removeAll { it.id == r.id }
        fresh = System.currentTimeMillis()
        viewModelScope.launch { Backend.reminderCancel(r.id) }
    }

    fun ack(list: List<Reminder>) {
        val ids = list.map { it.id }.toSet()
        items.removeAll { it.id in ids }
        fresh = System.currentTimeMillis()
        viewModelScope.launch { ids.forEach { Backend.reminderAck(it) } }
    }
}

private val RGreen = Color(0xFF2E9E5B)
private val RPink = Color(0xFFFF6A9C)
private val RTxt = Color(0xFFF5EEF2)

private val fmtFull = SimpleDateFormat("EEE d MMM 'à' HH:mm", Locale.FRANCE)

private fun targetText(r: Reminder, partner: String): String = when (r.target) {
    "me" -> "Pour toi"
    "both" -> "Pour vous deux"
    else -> "Pour $partner"
}

// ---------- Bouton « Rappels » à côté du jeu : vert s'il y a une tâche, sombre sinon ----------
@Composable
fun ReminderPill(vm: ReminderViewModel) {
    val on = vm.items.isNotEmpty()
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(
                if (on) Brush.linearGradient(listOf(Color(0xFF3DBE74), RGreen))
                else Brush.linearGradient(listOf(Color(0xB3281C24), Color(0xB3281C24)))
            )
            .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(50))
            .clickable { vm.overlay = true }.padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            if (on) "⏰ Rappels (${vm.items.size})" else "⏰ Rappels",
            color = if (on) Color.White else Color(0xFFB7A3AE), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

// ---------- Alarme : « pop pop » + vibrations, jusqu'à « OK » (chez les deux personnes) ----------
private fun vibrateAlarm(context: Context) {
    try {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (v == null || !v.hasVibrator()) return
        val pattern = longArrayOf(0, 180, 120, 180, 300, 180, 120, 180)
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        else @Suppress("DEPRECATION") v.vibrate(pattern, -1)
    } catch (_: Throwable) { }
}

@Composable
fun ReminderAlarmHost(vm: ReminderViewModel, active: Boolean, partner: String) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    LaunchedEffect(active, lifecycleOwner) {
        if (!active) { vm.items.clear(); return@LaunchedEffect }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.refresh()
                delay(12000)
            }
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active) { delay(1000); now = System.currentTimeMillis() }
    }
    val due = if (active) vm.items.filter { it.dueAt <= now } else emptyList()
    if (due.isEmpty()) return

    LaunchedEffect(Unit) {
        while (true) {
            Sounds.alarm()
            vibrateAlarm(context)
            delay(2400)
        }
    }
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color(0xFF281C24))
                .border(1.dp, Color(0x66FF6A9C), RoundedCornerShape(26.dp)).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("⏰", fontSize = 54.sp)
            Text("Rappel !", color = RTxt, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            due.forEach { r ->
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x33FFFFFF)).padding(12.dp)) {
                    Text(r.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(targetText(r, partner) + (if (!r.byMe) " · de $partner" else ""), color = Color(0xFFD8B8C8), fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
                    .background(Brush.linearGradient(listOf(Color(0xFFFF4F86), Color(0xFFD81B60))))
                    .clickable { vm.ack(due) }.padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("OK", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

// ---------- Écran des rappels ----------
@Composable
fun RemindersOverlay(vm: ReminderViewModel, partner: String) {
    val context = LocalContext.current
    BackHandler { vm.overlay = false }
    var title by remember { mutableStateOf("") }
    var who by remember { mutableStateOf("me") }
    var cal by remember {
        mutableStateOf(Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) })
    }
    var stamp by remember { mutableLongStateOf(cal.timeInMillis) } // force la mise à jour de l'affichage
    LaunchedEffect(vm.toast) { if (vm.toast != null) { delay(2600); vm.toast = null } }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF1A0D16), Color(0xFF26122A), Color(0xFF120F1C))))
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color(0xB3281C24)).border(1.dp, Color(0x1AFFFFFF), CircleShape).clickable { vm.overlay = false },
                    contentAlignment = Alignment.Center,
                ) { Ico(R.drawable.ic_close, RPink, 22.dp) }
                Text("⏰ Rappels", color = RTxt, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(Modifier.width(44.dp))
            }
            Column(Modifier.weight(1f).fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp)) {
                Text("Nouveau rappel", color = RPink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = title, onValueChange = { title = it.take(200) },
                    label = { Text("La tâche à faire") }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
                )
                Spacer(Modifier.height(10.dp))
                Text("Qui doit le faire ?", color = Color(0xFFB7A3AE), fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RChip("Moi", who == "me") { who = "me" }
                    RChip(partner, who == "partner") { who = "partner" }
                    RChip("Nous deux", who == "both") { who = "both" }
                }
                Spacer(Modifier.height(12.dp))
                Text("Quand ?", color = Color(0xFFB7A3AE), fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val shown = stamp
                    RBtn("📅 " + SimpleDateFormat("EEE d MMM yyyy", Locale.FRANCE).format(Date(shown)), Modifier.weight(1f)) {
                        DatePickerDialog(context, { _, y, m, d ->
                            cal.set(y, m, d); stamp = cal.timeInMillis
                        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply {
                            datePicker.minDate = System.currentTimeMillis() - 1000
                        }.show()
                    }
                    RBtn("🕐 " + SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(shown)), Modifier) {
                        TimePickerDialog(context, { _, h, mi ->
                            cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, mi); cal.set(Calendar.SECOND, 0); stamp = cal.timeInMillis
                        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
                    }
                }
                Spacer(Modifier.height(14.dp))
                val ok = title.isNotBlank() && !vm.busy
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
                        .background(if (ok) Brush.linearGradient(listOf(Color(0xFFFF4F86), Color(0xFFD81B60))) else Brush.linearGradient(listOf(Color(0xFF3A2A34), Color(0xFF3A2A34))))
                        .clickable(enabled = ok) {
                            vm.create(title.trim(), who, cal.timeInMillis) { title = "" }
                        }.padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Créer le rappel", color = if (ok) Color.White else Color.Gray, fontWeight = FontWeight.Bold, fontSize = 16.sp) }

                Spacer(Modifier.height(22.dp))
                Text("Rappels en cours", color = RPink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (vm.items.isEmpty()) {
                    Text("Aucun rappel pour le moment.", color = Color.Gray, fontSize = 14.sp)
                }
                vm.items.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xCC281C24))
                            .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(16.dp)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text("⏰ " + fmtFull.format(Date(r.dueAt)), color = Color(0xFF7BE0A0), fontSize = 13.sp)
                            Text(targetText(r, partner) + (if (!r.byMe) " · ajouté par $partner" else ""), color = Color(0xFFB7A3AE), fontSize = 12.sp)
                        }
                        Text("🗑", fontSize = 22.sp, modifier = Modifier.clickable { vm.cancel(r) }.padding(8.dp))
                    }
                }
                Spacer(Modifier.height(30.dp))
            }
        }
        vm.toast?.let { t ->
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(bottom = 24.dp), Alignment.BottomCenter) {
                Text(
                    t, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xCC000000)).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun RChip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (on) Brush.linearGradient(listOf(Color(0xFFFF4F86), Color(0xFFD81B60))) else Brush.linearGradient(listOf(Color(0xB3281C24), Color(0xB3281C24))))
            .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(50))
            .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 9.dp),
    ) { Text(text, color = if (on) Color.White else Color(0xFFD8C6D0), fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun RBtn(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xB3281C24))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(14.dp)).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = RTxt, fontSize = 15.sp) }
}
