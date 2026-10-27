package com.tumemanque.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.delay

// =============================================================================================
//  « Verre liquide » façon iOS 26
// =============================================================================================

/** Hauteur à laisser libre en bas des listes (barre d'onglets + mini-lecteur flottants). */
val LocalBottomInset = compositionLocalOf { 0.dp }

private val rim = Brush.verticalGradient(listOf(Color(0x47FFFFFF), Color(0x0AFFFFFF), Color(0x1FFFFFFF)))

/** Surface de verre : légèrement claire, reflet en haut, liseré fin. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(24.dp), alpha: Float = 0.07f, tint: Color = Color.White): Modifier =
    this.clip(shape)
        .background(Brush.verticalGradient(listOf(tint.copy(alpha = alpha + 0.05f), tint.copy(alpha = alpha))))
        .border(0.7.dp, rim, shape)

/** Verre flou (vrai flou d'arrière-plan sur Android 12+, verre teinté avant). */
fun Modifier.liquid(state: HazeState, shape: Shape): Modifier =
    this.clip(shape)
        .hazeChild(
            state,
            HazeStyle(
                backgroundColor = P.bg,
                tints = listOf(HazeTint(Color(0xD60F1217))),
                blurRadius = 26.dp,
                noiseFactor = 0.035f,
                fallbackTint = HazeTint(Color(0xF5161A21)),
            ),
        )
        .border(0.7.dp, rim, shape)

/** Effet « pression » doux. */
@Composable
fun Modifier.pressable(onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), label = "press")
    return this.scale(s).clickable(interactionSource = src, indication = null, onClick = onClick)
}

/** Grand titre à gauche, comme sur iPhone. */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = P.text, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, modifier = Modifier.weight(1f))
        trailing()
    }
}

// =============================================================================================
//  Navigation de l'appli : Audios · Vidéos · Téléchargements + Rechercher
// =============================================================================================

object Shell {
    var tab by mutableStateOf("audios")
    /** Le lecteur plein écran (Audios) est ouvert : on cache la barre. */
    var nowOpen by mutableStateOf(false)
    /** Demande d'ouverture du lecteur depuis un autre onglet. */
    var openNow by mutableIntStateOf(0)
}

private data class TabDef(val key: String, val label: String, val icon: ImageVector)

private val TABS_MAIN = listOf(
    TabDef("audios", "Audios", Ic.Note),
    TabDef("videos", "Vidéos", Ic.VideoLib),
    TabDef("downloads", "Téléchargements", Ic.Download),
)

@Composable
fun CasqueShell(onSettings: () -> Unit, hasUpdate: Boolean) {
    val ctx = LocalContext.current
    remember { Yt.warmUp(ctx); Dl.init(ctx); Vids.init(ctx); true }
    val haze = remember { HazeState() }
    val byId by Lib.byId.collectAsState()
    val current = Hub.currentId?.let { byId[it] }
    val holder = rememberSaveableStateHolder()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bars = !Shell.nowOpen && !Vp.fullscreen
    val inset = navBottom + 10.dp + 62.dp + (if (current != null) 66.dp else 0.dp) + 12.dp

    BackHandler(enabled = Shell.tab != "audios" && !Vp.fullscreen && !Shell.nowOpen) { Shell.tab = "audios" }
    // La vidéo de la recherche s'arrête quand on change d'onglet.
    // En quittant la recherche, la vidéo qui joue continue dans la petite fenêtre flottante.
    LaunchedEffect(Shell.tab) { if (Shell.tab != "search" && !Vp.fullscreen && Vp.key != null && Vp.playing) Vp.mini = true }

    Box(Modifier.fillMaxSize().background(P.bg)) {
        Box(Modifier.fillMaxSize().haze(haze)) {
            CompositionLocalProvider(LocalBottomInset provides inset) {
                holder.SaveableStateProvider(Shell.tab) {
                    when (Shell.tab) {
                        "videos" -> VideosScreen(onSettings)
                        "downloads" -> DownloadsScreen()
                        "search" -> SearchScreen()
                        else -> PlayerHome(onSettings = onSettings, hasUpdate = hasUpdate)
                    }
                }
            }
        }
        AnimatedVisibility(
            bars, Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 10.dp)) {
                if (current != null) GlassMini(current, haze) {
                    Shell.tab = "audios"; Shell.openNow++
                }
                GlassTabBar(haze)
            }
        }
        if (Vp.mini && Vp.key != null && !Vp.fullscreen && !Vp.inPip) MiniVideo(if (bars) inset - navBottom else 24.dp)
        if (Vp.fullscreen) VideoFullscreen()
    }
}

@Composable
private fun GlassTabBar(haze: HazeState) {
    val tasks by Dl.tasks.collectAsState()
    val active = tasks.count { it.active }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).height(62.dp).liquid(haze, RoundedCornerShape(31.dp)).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TABS_MAIN.forEach { t ->
                val sel = Shell.tab == t.key
                val bg by animateColorAsState(if (sel) Color(0x26FFFFFF) else Color.Transparent, label = "tabbg")
                val fg by animateColorAsState(if (sel) P.blueSoft else P.text, label = "tabfg")
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(27.dp)).background(bg).pressable { Shell.tab = t.key },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box {
                            CIcon(t.icon, fg, 24.dp)
                            if (t.key == "downloads" && active > 0) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(start = 14.dp).size(16.dp).clip(CircleShape).background(P.blue),
                                    contentAlignment = Alignment.Center,
                                ) { Text("$active", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                        Text(t.label, color = fg, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        val sel = Shell.tab == "search"
        Box(
            Modifier.size(62.dp).liquid(haze, CircleShape).background(if (sel) Color(0x332F7BFF) else Color.Transparent)
                .pressable { Shell.tab = "search" },
            contentAlignment = Alignment.Center,
        ) { CIcon(Ic.Search, if (sel) P.blueSoft else P.text, 26.dp) }
    }
}

/** Mini-lecteur en verre au-dessus de la barre d'onglets. */
@Composable
private fun GlassMini(t: Track, haze: HazeState, onOpen: () -> Unit) {
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Hub.currentId, Hub.playing) {
        while (true) { pos = Hub.player.currentPosition; delay(500) }
    }
    val dur = Hub.durationMs.takeIf { it > 0 } ?: t.durationMs
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 8.dp)) {
        Column(Modifier.fillMaxWidth().liquid(haze, RoundedCornerShape(24.dp)).pressable(onOpen)) {
            Row(Modifier.padding(start = 8.dp, end = 6.dp, top = 7.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(t, 42.dp, 12.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = P.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, color = P.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                RoundBtn(if (Hub.playing) Ic.Pause else Ic.Play, { Hub.toggle() }, size = 42.dp, iconSize = 26.dp, fill = Color.Transparent)
                RoundBtn(Ic.Next, { Hub.next() }, size = 42.dp, iconSize = 24.dp, fill = Color.Transparent)
            }
            LinearProgressIndicator(
                progress = { if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 6.dp).height(2.dp).clip(RoundedCornerShape(1.dp)),
                color = P.blueSoft, trackColor = Color(0x1AFFFFFF), drawStopIndicator = {},
            )
        }
    }
}

/** Petit bouton de verre avec une icône et un texte. */
@Composable
fun GlassChip(label: String, icon: ImageVector? = null, tint: Color = P.text, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.glass(RoundedCornerShape(50), 0.08f).pressable(onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { CIcon(icon, tint, 16.dp); Spacer(Modifier.width(6.dp)) }
        Text(label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Zone vide avec une grande icône, un titre et une explication. */
@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(96.dp).glass(CircleShape, 0.06f, P.blue),
            contentAlignment = Alignment.Center,
        ) { CIcon(icon, P.blueSoft, 44.dp) }
        Spacer(Modifier.height(18.dp))
        Text(title, color = P.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(text, color = P.sub, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            BlueButton(action, Modifier.fillMaxWidth(0.8f), Ic.Search) { onAction() }
        }
    }
}

fun Dp.orZero(): Dp = if (this < 0.dp) 0.dp else this
