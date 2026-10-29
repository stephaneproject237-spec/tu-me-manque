package com.tumemanque.app

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.snapshotFlow

/** Petits « clics » d'engrenage pendant qu'on fait défiler une liste (activable dans les paramètres). */
object Feel {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("casque_feel", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("ticks", true)
    fun setEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("ticks", on).apply() }

    fun tick(v: View) {
        if (!enabled(v.context)) return
        val k = if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.KEYBOARD_TAP
        v.performHapticFeedback(k)
    }
}

/** À poser dans l'écran qui contient la liste. */
@Composable
fun ScrollTicks(state: LazyListState, stepPx: Int = 140) {
    val view = LocalView.current
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex * 100000 + state.firstVisibleItemScrollOffset / stepPx }
            .map { it }.distinctUntilChanged().drop(1)
            .collect { if (state.isScrollInProgress) Feel.tick(view) }
    }
}
