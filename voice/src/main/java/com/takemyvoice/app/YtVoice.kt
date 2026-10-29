package com.takemyvoice.app

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * YouTube comme source d'entraînement : recherche, puis extraction de l'audio d'un passage
 * (yt-dlp + FFmpeg embarqués, comme dans Free tune). Le fichier obtenu rejoint les enregistrements de la voix.
 */
object YtVoice {
    private lateinit var app: Context
    private val verrou = Mutex()
    private var pret = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seq = AtomicInteger(0)
    @Volatile private var enCours: String? = null
    /** Recherches et extractions lisent le moteur ; sa mise à jour (ou sa réparation) le remplace : jamais en même temps. */
    private val moteur = ReentrantReadWriteLock()

    private fun prefs() = app.getSharedPreferences("yt", Context.MODE_PRIVATE)

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
    }

    private suspend fun preparer() {
        if (pret) return
        verrou.withLock {
            if (pret) return
            YoutubeDL.init(app)
            FFmpeg.init(app)
            val abime = !moteurIntact()
            if (abime) moteur.write { reparer() }
            pret = true
        }
        // YouTube change souvent : on met yt-dlp à jour une fois par jour (sans gêner une recherche en cours).
        if (System.currentTimeMillis() - prefs().getLong("maj", 0) > 20 * 3600_000L) {
            prefs().edit().putLong("maj", System.currentTimeMillis()).apply()
            scope.launch { mettreAJour() }
        }
    }

    @Volatile private var dernierEssai = 0L

    /** Met yt-dlp à jour : version stable, puis version du jour si la stable n'apporte rien (YouTube y est corrigé en premier). */
    private fun mettreAJour(nuit: Boolean = false): Boolean = moteur.write {
        val r = runCatching { YoutubeDL.updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE) }.getOrNull()
        var ok = r == YoutubeDL.UpdateStatus.DONE
        if (nuit && !ok) ok = runCatching { YoutubeDL.updateYoutubeDL(app, YoutubeDL.UpdateChannel.NIGHTLY) }.getOrNull() == YoutubeDL.UpdateStatus.DONE
        if (!moteurIntact()) { reparer(); ok = false }
        else prefs().edit().putLong("maj", System.currentTimeMillis()).apply()
        ok
    }

    /** L'erreur ressemble-t-elle à un moteur dépassé (et pas à un simple problème de connexion) ? */
    private fun moteurDepasse(m: String): Boolean {
        if (Regex("unknownhost|unable to resolve host|network is unreachable|timed out|no space left|connection reset|failed to connect", RegexOption.IGNORE_CASE).containsMatchIn(m)) return false
        return Regex("sign in to confirm|not a bot|http error 4\\d\\d|unable to extract|nsig|signature|player response|" +
            "unable to download (webpage|json)|failed to extract|requested format is not available|ERROR:", RegexOption.IGNORE_CASE).containsMatchIn(m)
    }

    private fun dossierMoteur() = File(File(app.noBackupFilesDir, YoutubeDL.baseName), YoutubeDL.ytdlpDirName)

    /** Le moteur est un fichier zip : on vérifie qu'il est complet. */
    private fun moteurIntact(): Boolean = runCatching {
        java.util.zip.ZipFile(File(dossierMoteur(), YoutubeDL.ytdlpBin)).use { it.size() > 0 }
    }.getOrDefault(false)

    /** Remet la version livrée avec l'appli, et oublie la version enregistrée pour que la prochaine mise à jour soit retentée. */
    private fun reparer() {
        val d = dossierMoteur()
        d.deleteRecursively()
        YoutubeDL.init_ytdlp(app, d)
        app.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE).edit().remove("dlpVersion").remove("dlpVersionName").apply()
        prefs().edit().putLong("maj", 0).apply()
    }

    private fun moteurAbime(m: String?) = m != null && (m.contains("bad local file header", true) ||
        m.contains("zipimport", true) || m.contains("BadZipFile", true) || m.contains("ImportError", true) || m.contains("No module named", true))

    /**
     * Lance une commande yt-dlp. Si le moteur est abîmé, il est réparé puis mis à jour ; s'il est dépassé
     * (YouTube a changé), il est mis à jour tout de suite. Puis la commande est relancée une fois.
     */
    private fun executer(req: () -> YoutubeDLRequest, pid: String?, cb: ((Float, Long, String) -> Unit)?): String {
        val m: String
        try {
            return moteur.read { YoutubeDL.execute(req(), pid, cb).out }
        } catch (ex: Exception) {
            m = (ex.message ?: "") + " " + (ex.cause?.message ?: "")
            if (ex is YoutubeDL.CanceledException || m.contains("destroy", true)) throw ex
            val abime = moteurAbime(m)
            if (!abime && !moteurDepasse(m)) throw ex
            if (!abime && System.currentTimeMillis() - dernierEssai < 10 * 60_000L) throw ex   // déjà mis à jour il y a peu
            dernierEssai = System.currentTimeMillis()
            if (abime) moteur.write { reparer() }
            mettreAJour(nuit = !abime)
        }
        return moteur.read { YoutubeDL.execute(req(), pid, cb).out }
    }

    /** Prépare le moteur dès l'ouverture de la fenêtre YouTube (premier lancement : ~15 s). */
    fun chauffer(fini: (String?) -> Unit) {
        scope.launch { fini(runCatching { preparer() }.exceptionOrNull()?.let { it.message ?: "Moteur indisponible" }) }
    }

    private fun base(r: YoutubeDLRequest) = r.addOption("--no-warnings").addOption("--socket-timeout", "15").addOption("-R", "2")

    fun chercher(q: String, fini: (JSONArray?, String?) -> Unit) {
        scope.launch {
            try {
                preparer()
                val n = seq.incrementAndGet()
                runCatching { YoutubeDL.destroyProcessById("cherche${n - 1}") }
                // Un lien collé : on ouvre directement cette vidéo.
                val id = Regex("(?:v=|youtu\\.be/|shorts/|live/)([\\w-]{11})").find(q)?.groupValues?.get(1)
                val cible = if (id != null) "https://www.youtube.com/watch?v=$id" else "ytsearch20:$q"
                val out = executer({
                    base(YoutubeDLRequest(cible))
                        .addOption(if (id != null) "--no-playlist" else "--flat-playlist")
                        .addOption("--dump-single-json")
                }, "cherche$n", null)
                val o = JSONObject(out)
                val entrees = o.optJSONArray("entries") ?: JSONArray().put(o)
                val res = JSONArray()
                for (i in 0 until entrees.length()) {
                    val e = entrees.optJSONObject(i) ?: continue
                    val vid = e.optString("id")
                    if (vid.length != 11 || e.optString("live_status") == "is_live") continue
                    res.put(JSONObject().put("id", vid)
                        .put("titre", e.optString("title").ifBlank { "Sans titre" })
                        .put("chaine", e.optString("channel").ifBlank { e.optString("uploader") })
                        .put("duree", e.optDouble("duration", 0.0).toInt())
                        .put("miniature", "https://i.ytimg.com/vi/$vid/mqdefault.jpg"))
                }
                fini(res, null)
            } catch (ex: Exception) {
                if (ex.message?.contains("destroy", true) == true) return@launch
                val m = ex.message ?: ""
                fini(null, when {
                    moteurAbime(m) -> "Le moteur YouTube a été réparé. Relancez la recherche."
                    m.contains("Sign in to confirm", true) || m.contains("not a bot", true) ->
                        "YouTube demande une vérification. Réessayez dans quelques minutes, ou changez de réseau (Wi-Fi / données)."
                    m.contains("resolve", true) || m.contains("network", true) || m.contains("timed out", true) -> "Pas de connexion à YouTube. Vérifiez internet."
                    else -> "Recherche impossible : " + detail(m)
                })
            }
        }
    }

    /**
     * Extrait l'audio de [debut, fin] (secondes) de la vidéo, en Opus mono, dans les enregistrements de la voix.
     * Le nom contient « _yt » : sur Kaggle, la voix y sera séparée de la musique et du bruit.
     */
    fun extraire(voixId: String, videoId: String, debut: Int, fin: Int,
                 progression: (Int) -> Unit, fini: (String?, String?) -> Unit) {
        if (enCours != null) { fini(null, "Une extraction est déjà en cours."); return }
        enCours = videoId
        scope.launch {
            val tmp = File(app.cacheDir, "yt").apply { deleteRecursively(); mkdirs() }
            try {
                preparer()
                executer({
                    // Comme Free tune : on télécharge la piste audio entière (méthode la plus fiable avec YouTube),
                    // puis FFmpeg ne garde que le passage choisi, en mono.
                    val coupe = if (fin > debut) "-ss $debut -t ${fin - debut} " else ""
                    base(YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId"))
                        .addOption("--no-playlist")
                        .addOption("--no-mtime")
                        .addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
                        .addOption("-x").addOption("--audio-format", "mp3").addOption("--audio-quality", "128K")
                        .addOption("--postprocessor-args", "ExtractAudio:${coupe}-ac 1")
                        .addOption("-o", tmp.absolutePath + "/audio.%(ext)s")
                }, "extrait") { p, _, _ -> progression(p.toInt().coerceIn(0, 99)) }
                val f = tmp.listFiles()?.filter { it.extension == "mp3" && it.length() > 2000 }?.maxByOrNull { it.length() }
                    ?: tmp.listFiles()?.filter { it.length() > 2000 && !it.name.endsWith(".part") }?.maxByOrNull { it.length() }
                    ?: throw IllegalStateException("aucun audio reçu")
                val ms = (fin - debut).coerceAtLeast(0) * 1000L
                val dest = File(Store.dossierEnr(voixId), "enr_${System.currentTimeMillis()}_${ms}_yt.${f.extension}")
                f.copyTo(dest, overwrite = true)
                Store.modifier { }
                progression(100)
                fini(dest.name, null)
            } catch (ex: Exception) {
                val m = ex.message ?: ""
                fini(null, when {
                    m.contains("destroy", true) -> "Extraction annulée."
                    m.contains("Sign in to confirm", true) || m.contains("not a bot", true) ->
                        "YouTube demande une vérification. Réessayez dans quelques minutes, ou passez du Wi-Fi aux données mobiles (ou l'inverse)."
                    m.contains("Private video", true) || m.contains("Video unavailable", true) -> "Cette vidéo n'est pas disponible."
                    m.contains("confirm your age", true) -> "Vidéo réservée aux adultes : YouTube refuse de la donner sans compte."
                    m.contains("resolve", true) || m.contains("timed out", true) || m.contains("network", true) -> "Pas de connexion à YouTube. Vérifiez internet."
                    else -> "Extraction impossible : " + detail(m)
                })
            } finally {
                tmp.deleteRecursively()
                enCours = null
            }
        }
    }

    /** La ligne d'erreur utile de yt-dlp (pour comprendre ce qui bloque). */
    private fun detail(m: String): String =
        (m.lines().lastOrNull { it.contains("ERROR", true) } ?: m.lines().lastOrNull { it.isNotBlank() } ?: "erreur")
            .removePrefix("ERROR:").trim().take(220)

    fun annuler() { runCatching { YoutubeDL.destroyProcessById("extrait") } }
}
