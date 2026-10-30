package com.tumemanque.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties

/** Fenêtre qui s'affiche quand la mise à jour est téléchargée : une seule option, « Installer la mise à jour ». */
@Composable
fun UpdatePrompt(vm: UpdateViewModel, st: UpdateState) {
    val ctx = LocalContext.current
    val info = st.available
    if (!st.ready || info == null) return
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Mise à jour prête") },
        text = {
            Text(
                "La version ${info.name} est téléchargée." + (if (info.notes.isNotBlank()) "\n\n" + info.notes else "") +
                    (if (st.needPermission) "\n\nAutorise l'installation une seule fois dans l'écran qui vient de s'ouvrir, puis reviens ici et touche à nouveau le bouton." else "")
            )
        },
        confirmButton = { Button(onClick = { vm.install(ctx) }) { Text("Installer la mise à jour") } },
    )
}
