package com.tumemanque.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CasqueMain(screen: String, updateVm: UpdateViewModel, hasUpdate: Boolean, onScreen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
        Text("Casque (test) — $screen")
        Button(onClick = { onScreen("help") }) { Text("Aide") }
    }
}

@Composable
fun CasqueFriends(
    state: UiState, vm: AppViewModel, chatVm: ChatViewModel,
    onBack: () -> Unit, onInvite: () -> Unit, onOpenChat: () -> Unit,
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
        Text("Amis (test)")
        Button(onClick = onBack) { Text("Retour") }
        Button(onClick = onOpenChat) { Text("Chat") }
    }
}
