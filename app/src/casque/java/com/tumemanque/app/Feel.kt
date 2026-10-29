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
import androidx.compose.runtime.getValue
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState

/** Petits « clics » d'engrenage pendant qu'on fait défiler une liste (activable dans les paramètres). */
object Feel {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("casque_feel", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("ticks", true)
    fun setEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("ticks", on).apply() }

    private var appCtx: Context? = null
    fun init(c: Context) { if (appCtx == null) appCtx = c.applicationContext }

    /** Petite vibration quand on lance une lecture. */
    fun tap() {
        val c = appCtx ?: return
        if (!enabled(c)) return
        runCatching {
            @Suppress("DEPRECATION")
            val v = if (android.os.Build.VERSION.SDK_INT >= 31) (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            else c.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= 29) v.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK))
            else if (android.os.Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createOneShot(14, 90))
        }
    }

    /** Petite vibration quand on touche une chanson pour la lire. */
    fun tap(v: View) {
        val k = if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
        v.performHapticFeedback(k)
    }

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

/** Toucher une chanson : petite vibration + légère surbrillance qui s'estompe. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun androidx.compose.ui.Modifier.tapGlow(onLong: (() -> Unit)? = null, onClick: () -> Unit): androidx.compose.ui.Modifier = composed {
    val view = LocalView.current
    val src = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    var flash by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(flash) { if (flash) { kotlinx.coroutines.delay(160); flash = false } }
    val glow by androidx.compose.animation.core.animateFloatAsState(if (pressed || flash) 1f else 0f, androidx.compose.animation.core.tween(if (pressed || flash) 70 else 420), label = "glow")
    this
        .drawBehind { drawRect(Color(0xFF7FB0FF).copy(alpha = 0.20f * glow)) }
        .combinedClickable(interactionSource = src, indication = null, onLongClick = onLong, onClick = { Feel.tap(view); flash = true; onClick() })
}
