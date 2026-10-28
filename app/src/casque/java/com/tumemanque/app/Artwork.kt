package com.tumemanque.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** Les musiques de l'appareil n'ont plus de recherche de pochette sur Internet : on garde seulement celle du fichier. */
object Artwork {
    /** Change quand une image doit être rechargée. */
    var version by mutableIntStateOf(0)
        private set
}
