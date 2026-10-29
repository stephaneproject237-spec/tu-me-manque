package com.tumemanque.app

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse

/**
 * Le moteur de téléchargement (yt-dlp) se répare tout seul : si YouTube a changé quelque chose et qu'une recherche ou un
 * téléchargement échoue, on met le moteur à jour tout de suite (version stable, puis version du jour si ça ne suffit pas)
 * et on recommence une fois, sans que la personne n'ait rien à faire.
 */
object EngineHeal {
    @Volatile var appCtx: Context? = null
    @Volatile private var lastTry = 0L

    /** Cette erreur ressemble-t-elle à un moteur dépassé (et non à un simple problème de connexion) ? */
    private fun looksBroken(e: Throwable): Boolean {
        val n = e.javaClass.simpleName
        if (e is InterruptedException || n.contains("Cancel", true)) return false
        val m = (e.message ?: "") + " " + (e.cause?.message ?: "")
        if (Regex("unknownhost|unable to resolve host|network is unreachable|timed out|no space left|connection reset|failed to connect", RegexOption.IGNORE_CASE).containsMatchIn(m)) return false
        return Regex(
            "sign in to confirm|http error 4\\d\\d|unable to extract|nsig|signature|player response|" +
                "unable to download (webpage|json)|failed to extract|\\[youtube\\]|video unavailable|ERROR:|exit code|returned error",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(m)
    }

    /** Met le moteur à jour (au plus une fois tous les 15 minutes). Renvoie true si on peut réessayer. */
    @Synchronized private fun fix(): Boolean {
        val ctx = appCtx ?: return false
        val since = System.currentTimeMillis() - lastTry
        if (since < 2 * 60_000L) return true   // un autre appel vient de le réparer
        if (since < 15 * 60_000L) return false // déjà essayé récemment
        lastTry = System.currentTimeMillis()
        val yd = YoutubeDL.getInstance()
        val r = runCatching { yd.updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE) }.getOrNull()
        if (r == null || r != YoutubeDL.UpdateStatus.DONE) {
            // Pas de version stable plus récente : on essaie la version du jour (YouTube est souvent corrigé là en premier).
            runCatching { yd.updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.NIGHTLY) }
        }
        ctx.getSharedPreferences("music_bot", Context.MODE_PRIVATE).edit().putLong("upd", System.currentTimeMillis()).apply()
        ctx.getSharedPreferences("casque_yt", Context.MODE_PRIVATE).edit().putLong("updated_at", System.currentTimeMillis()).apply()
        return true
    }

    /** Comme yt-dlp.execute, avec réparation automatique du moteur puis une nouvelle tentative. */
    fun exec(req: YoutubeDLRequest, processId: String?, callback: ((Float, Long, String) -> Unit)?): YoutubeDLResponse {
        try {
            return YoutubeDL.getInstance().execute(req, processId, callback)
        } catch (e: Exception) {
            if (!looksBroken(e) || !fix()) throw e
            return YoutubeDL.getInstance().execute(req, processId, callback)
        }
    }
}
