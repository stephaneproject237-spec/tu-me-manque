package com.tumemanque.app

import androidx.compose.runtime.Composable

/** « Tu me manques » n'a pas de lecteur audio : ces écrans n'existent que dans « Casque ». */
@Composable
fun CasqueMain(screen: String, updateVm: UpdateViewModel, hasUpdate: Boolean, username: String?, onScreen: (String) -> Unit) {}

@Composable
fun CasqueFriends(
    state: UiState, vm: AppViewModel, chatVm: ChatViewModel,
    onBack: () -> Unit, onInvite: () -> Unit, onOpenChat: () -> Unit,
) {}
