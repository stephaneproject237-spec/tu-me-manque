package com.tumemanque.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SupportState(
    val messages: List<SupportMsg> = emptyList(),
    val loading: Boolean = true,
    val error: Boolean = false,
    val note: String? = null,
    val ended: Boolean = false,
)

class SupportViewModel : ViewModel() {
    private val _state = MutableStateFlow(SupportState())
    val state: StateFlow<SupportState> = _state.asStateFlow()
    var onRead: (() -> Unit)? = null

    fun load() {
        viewModelScope.launch {
            try {
                val list = Backend.supportList()
                if (list.isEmpty()) {
                    _state.update { it.copy(messages = emptyList(), loading = false, error = false, ended = true) }
                    return@launch
                }
                _state.update { it.copy(messages = list, loading = false, error = false) }
                if (list.any { it.fromAdmin }) {
                    Backend.supportMarkRead()
                    onRead?.invoke()
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = it.messages.isEmpty()) }
            }
        }
    }

    fun send(text: String, onSent: () -> Unit) {
        val body = text.trim()
        if (body.isEmpty()) return
        viewModelScope.launch {
            val st = runCatching { Backend.supportSend(body) }.getOrDefault("error")
            when (st) {
                "ok" -> { onSent(); load() }
                "no_thread" -> _state.update { it.copy(ended = true, messages = emptyList()) }
                "too_fast" -> _state.update { it.copy(note = "Doucement ! Réessaie dans une minute.") }
                else -> _state.update { it.copy(note = "Message non envoyé.") }
            }
        }
    }

    fun clearNote() = _state.update { it.copy(note = null) }

    fun reset() { _state.value = SupportState() }
}

private val RoseS = Color(0xFFE91E63)

@Composable
fun SupportScreen(vm: SupportViewModel, onBack: () -> Unit, onRead: () -> Unit, onEnded: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var text by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    BackHandler { onBack() }

    LaunchedEffect(Unit) { vm.onRead = onRead }
    LaunchedEffect(state.ended) {
        if (state.ended) { vm.reset(); onEnded() }
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.load()
                delay(3500)
            }
        }
    }
    val items = remember(state.messages) { state.messages.asReversed() }
    LaunchedEffect(items.firstOrNull()?.id) { if (items.isNotEmpty()) listState.animateScrollToItem(0) }
    LaunchedEffect(state.note) {
        if (state.note != null) { delay(3000); vm.clearNote() }
    }

    Box(Modifier.fillMaxSize().background(Brush.linearGradient(Wallpaper.defaultColors))) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFFFF6A9C), RoseS)))
                    .statusBarsPadding().padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("‹", color = Color.White, fontSize = 34.sp, modifier = Modifier.clickable { onBack() }.padding(horizontal = 12.dp))
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
                    Text("🛟", fontSize = 22.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("@admin", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("Aide et questions", color = Color(0xCCFFFFFF), fontSize = 12.sp)
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().imePadding()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                        items.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                            Text(
                                if (state.error) "Messagerie indisponible pour le moment."
                                else "Pose ta question à @admin 💗\nTu recevras sa réponse ici et par notification.",
                                color = Color.Gray,
                            )
                        }
                        else -> LazyColumn(
                            state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(items, key = { it.id }) { m -> SupportBubble(m) }
                        }
                    }
                    state.note?.let {
                        Box(Modifier.fillMaxSize().padding(bottom = 12.dp), Alignment.BottomCenter) {
                            Text(
                                it, color = Color.White, fontSize = 14.sp,
                                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xCC000000)).padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    TextField(
                        value = text, onValueChange = { text = it.take(2000) },
                        placeholder = { Text("Écris à @admin…") }, maxLines = 5,
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(if (text.isBlank()) Color(0xFFF3F3F3) else RoseS)
                            .clickable(enabled = text.isNotBlank()) { vm.send(text) { text = "" } },
                        contentAlignment = Alignment.Center,
                    ) { Text("➤", fontSize = 20.sp, color = if (text.isBlank()) Color.Gray else Color.White) }
                }
            }
        }
    }
}

@Composable
private fun SupportBubble(m: SupportMsg) {
    val mine = !m.fromAdmin
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (mine) RoseS else Color.White,
            shape = RoundedCornerShape(16.dp, 16.dp, if (mine) 5.dp else 16.dp, if (mine) 16.dp else 5.dp),
            shadowElevation = 1.dp, modifier = Modifier.widthIn(max = 290.dp),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(m.body, color = if (mine) Color.White else Color(0xFF212121), fontSize = 16.sp)
                Text(
                    SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(m.createdAt)),
                    fontSize = 11.sp, color = if (mine) Color(0xCCFFFFFF) else Color.Gray,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}
