package com.tumemanque.app

import android.content.Context
import androidx.compose.runtime.Composable

/** Notification silencieuse « un ami attend un titre » : seul Free tune s'en occupe. */
fun onRelayPush(ctx: Context) {}
fun onPingPush(ctx: Context) {}

/** « Tu me manques » n'a pas de lecteur audio : ces écrans n'existent que dans « Casque ». */
@Composable
fun CasqueMain(screen: String, updateVm: UpdateViewModel, hasUpdate: Boolean, username: String?, onScreen: (String) -> Unit) {}

@Composable
fun CasqueFriends(
    state: UiState, vm: AppViewModel, chatVm: ChatViewModel,
    onBack: () -> Unit, onInvite: () -> Unit, onOpenChat: () -> Unit,
) {}
