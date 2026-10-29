package com.takemyvoice.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Le moteur : une seule tâche Kaggle à la fois (le GPU gratuit est limité),
 * les autres attendent leur tour. Tout tourne en arrière-plan, appli fermée ou non.
 */
object Moteur {
    const val CANAL = "taches"
    private const val TRAVAIL = "tmv-travail"
    private const val SUIVI = "tmv-suivi"
    private val reseau = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun canal(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CANAL, "Voix et transformations", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    /** Démarre la prochaine tâche en attente, ou relance le suivi de celle en cours. */
    fun relancer(c: Context) {
        Store.init(c)
        val etat = Store.lire()
        val taches = Store.liste(etat, "taches")
        val wm = WorkManager.getInstance(c)
        if (taches.any { it.optString("etat") == "lance" }) {
            wm.enqueueUniqueWork(SUIVI, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SuiviWorker>().setConstraints(reseau).build())
            return
        }
        if (taches.any { it.optString("etat") == "envoi" }) {
            wm.enqueueUniqueWork(TRAVAIL, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<TravailWorker>().setConstraints(reseau).build())
            return
        }
        if (taches.any { it.optString("etat") == "attente" }) {
            wm.enqueueUniqueWork(TRAVAIL, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<TravailWorker>().setConstraints(reseau).build())
        }
    }

    fun suiviPlusTard(c: Context, secondes: Long) {
        WorkManager.getInstance(c).enqueueUniqueWork(SUIVI, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SuiviWorker>().setConstraints(reseau)
                .setInitialDelay(secondes, TimeUnit.SECONDS).build())
    }

    fun majTache(id: String, bloc: (JSONObject) -> Unit) {
        Store.modifier { e -> Store.tache(e, id)?.let(bloc) }
    }

    fun notifier(c: Context, id: Int, titre: String, texte: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val pi = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, CANAL).setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(titre).setContentText(texte)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texte))
            .setContentIntent(pi).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(c).notify(id, n) }
    }

    // ---------- Slugs Kaggle ----------
    fun jeuVoix(v: String) = "tmv-voix-$v"
    fun scriptEntrainement(v: String) = "tmv-train-$v"
    fun scriptConversion(v: String) = "tmv-conv-$v"
    fun jeuEntree(v: String) = "tmv-in-$v"

    /** Texte du script envoyé à Kaggle : paramètres + outils communs + tâche. */
    fun script(c: Context, nom: String, params: JSONObject, audioB64: String? = null): String {
        val p = Base64.encodeToString(params.toString().toByteArray(), Base64.NO_WRAP)
        val sb = StringBuilder()
        sb.append("import base64, json\n")
        sb.append("PARAMS = json.loads(base64.b64decode(\"").append(p).append("\").decode(\"utf-8\"))\n")
        if (audioB64 != null) sb.append("PARAMS[\"audio\"] = \"").append(audioB64).append("\"\n")
        sb.append(c.assets.open("kaggle/commun.py").bufferedReader().use { it.readText() }).append("\n")
        sb.append(c.assets.open("kaggle/$nom.py").bufferedReader().use { it.readText() })
        return sb.toString()
    }

    fun messageErreur(code: String, detail: String): String = when (code) {
        "NO_GPU" -> "Kaggle refuse le GPU gratuit. Vérifiez votre numéro de téléphone sur kaggle.com (Settings › Phone verification), puis réessayez."
        "NO_AUDIO" -> "Pas assez d'audio utilisable. Ajoutez des enregistrements plus longs et plus propres (au moins 5 minutes conseillées)."
        "NO_MODEL" -> "La voix n'est pas encore disponible sur Kaggle. Réessayez dans un instant ou réentraînez-la."
        "INSTALL" -> "L'installation sur Kaggle a échoué (souvent passager). Réessayez dans quelques minutes."
        "TRAIN" -> "L'entraînement a échoué. Essayez avec des enregistrements plus propres, sans musique ni bruit."
        "CONVERT" -> "La transformation a échoué. Réessayez avec un enregistrement plus clair."
        "QUOTA" -> "Votre temps de GPU gratuit de la semaine est épuisé. Il se recharge chaque semaine."
        else -> detail.ifBlank { "Erreur inconnue sur Kaggle." }
    }
}

/** Envoie les fichiers et lance le script sur Kaggle (premier plan pendant l'envoi). */
class TravailWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {

    override suspend fun doWork(): Result {
        val c = applicationContext
        Store.init(c)
        val k = Store.kaggleVerifie() ?: return Result.success()
        val e = Store.lire()
        val taches = Store.liste(e, "taches")
        val t = taches.firstOrNull { it.optString("etat") == "envoi" }
            ?: taches.filter { it.optString("etat") == "attente" }.minByOrNull { it.optLong("cree") }
            ?: return Result.success()
        val id = t.getString("id")
        val voixId = t.getString("voix")
        Moteur.majTache(id) { it.put("etat", "envoi").put("etape", "Préparation").put("progression", 0) }
        runCatching { setForeground(infoPremierPlan("Envoi vers Kaggle…")) }

        try {
            when (t.getString("type")) {
                "train" -> entrainer(c, k, id, voixId, t)
                else -> convertir(c, k, id, voixId, t)
            }
            Moteur.majTache(id) {
                it.put("etat", "lance").put("etape", "En file d'attente sur Kaggle")
                    .put("lance", System.currentTimeMillis()).put("vuActif", false)
            }
            Moteur.relancer(c)
            return Result.success()
        } catch (ex: Exception) {
            val essais = t.optInt("essais", 0)
            val reseauCoupe = ex is java.net.UnknownHostException || ex is java.net.SocketTimeoutException || ex is java.net.ConnectException
            if (reseauCoupe && essais < 5) {
                Moteur.majTache(id) { it.put("etat", "attente").put("essais", essais + 1).put("etape", "Connexion perdue, nouvel essai bientôt") }
                return Result.retry()
            }
            val msg = traduire(ex)
            Moteur.majTache(id) { it.put("etat", "erreur").put("message", msg).put("fin", System.currentTimeMillis()) }
            if (t.getString("type") == "train") Store.modifier { st -> Store.voix(st, voixId)?.put("etat", "brouillon") }
            Moteur.notifier(c, id.hashCode(), "Échec de l'envoi", msg)
            Moteur.relancer(c)
            return Result.success()
        }
    }

    private fun traduire(ex: Exception): String {
        val m = ex.message ?: ex.javaClass.simpleName
        return when {
            ex is KaggleException && (ex.http == 401 || ex.http == 403) ->
                "Kaggle refuse vos identifiants. Vérifiez votre nom d'utilisateur et votre clé dans Réglages."
            m.contains("phone", true) || m.contains("verif", true) ->
                "Kaggle demande la vérification de votre numéro de téléphone pour utiliser le GPU (kaggle.com › Settings)."
            m.contains("quota", true) -> Moteur.messageErreur("QUOTA", m)
            m.contains("Owner", true) && m.contains("Invalid", true) ->
                "Nom d'utilisateur Kaggle incorrect. Dans Réglages, touchez « Changer de compte » et collez exactement le nom visible dans l'adresse de votre profil (kaggle.com/votre-nom)."
            else -> "Erreur : $m"
        }
    }

    private fun entrainer(c: Context, k: Kaggle, id: String, voixId: String, t: JSONObject) {
        val fichiers = Store.dossierEnr(voixId).listFiles()?.filter { it.length() > 1000 }?.sortedBy { it.name } ?: emptyList()
        if (fichiers.isEmpty()) throw IllegalStateException("Aucun enregistrement pour cette voix.")
        val total = fichiers.sumOf { it.length() }.coerceAtLeast(1)
        var avant = 0L
        val jetons = mutableListOf<String>()
        fichiers.forEachIndexed { i, f ->
            Moteur.majTache(id) { it.put("etape", "Envoi des enregistrements (${i + 1}/${fichiers.size})") }
            var dernier = -1
            jetons += k.envoyerFichier(f, typeAudio(f)) { n ->
                val p = ((avant + n) * 100 / total).toInt()
                if (p != dernier && p % 2 == 0) { dernier = p; Moteur.majTache(id) { it.put("progression", p) } }
            }
            avant += f.length()
        }
        Moteur.majTache(id) { it.put("etape", "Kaggle range les fichiers").put("progression", 100) }
        k.publierJeu(Moteur.jeuVoix(voixId), "tmv voix $voixId", jetons)
        attendreJeu(k, Moteur.jeuVoix(voixId))
        Store.modifier { st -> Store.voix(st, voixId)?.put("jeuCree", true) }

        val v = Store.voix(Store.lire(), voixId)
        val femme = v?.optString("genre") == "femme"
        val params = JSONObject()
            .put("epoques", t.optJSONObject("params")?.optInt("epoques", 300) ?: 300)
            .put("voix_tts", if (femme) "fr-FR-DeniseNeural" else "fr-FR-HenriNeural")
            .put("phrase", "Bonjour ! Voici ma nouvelle voix, créée avec Take my voice.")
        Moteur.majTache(id) { it.put("etape", "Lancement sur le GPU") }
        k.lancerScript(Moteur.scriptEntrainement(voixId), "tmv train $voixId",
            Moteur.script(c, "entrainement", params), listOf(Moteur.jeuVoix(voixId)), emptyList(), 6 * 3600)
    }

    private fun convertir(c: Context, k: Kaggle, id: String, voixId: String, t: JSONObject) {
        val params = JSONObject(t.optJSONObject("params")?.toString() ?: "{}")
        val v = Store.voix(Store.lire(), voixId)
        if (!params.has("voix_tts")) params.put("voix_tts", if (v?.optString("genre") == "femme") "fr-FR-DeniseNeural" else "fr-FR-HenriNeural")
        val slug = Moteur.scriptConversion(voixId)
        val sources = listOf(Moteur.scriptEntrainement(voixId))
        if (params.optString("mode") == "texte") {
            Moteur.majTache(id) { it.put("etape", "Lancement sur le GPU") }
            k.lancerScript(slug, "tmv conv $voixId", Moteur.script(c, "conversion", params), emptyList(), sources, 3600)
            return
        }
        val f = File(t.getString("entree"))
        if (!f.exists()) throw IllegalStateException("Enregistrement introuvable.")
        if (f.length() <= 900_000) {
            try {
                Moteur.majTache(id) { it.put("etape", "Envoi de l'enregistrement").put("progression", 50) }
                val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
                k.lancerScript(slug, "tmv conv $voixId", Moteur.script(c, "conversion", params, b64), emptyList(), sources, 3600)
                return
            } catch (ex: KaggleException) {
                val m = ex.message ?: ""
                if (!(ex.http == 413 || m.contains("large", true) || m.contains("size", true) || m.contains("long", true))) throw ex
            }
        }
        // Gros fichier : on l'envoie comme petit jeu de données
        Moteur.majTache(id) { it.put("etape", "Envoi de l'enregistrement") }
        val copie = File(c.cacheDir, "entree_tmv." + f.extension.ifBlank { "m4a" })
        f.copyTo(copie, overwrite = true)
        try {
            val jeton = k.envoyerFichier(copie, typeAudio(copie)) { n ->
                Moteur.majTache(id) { it.put("progression", (n * 100 / copie.length().coerceAtLeast(1)).toInt()) }
            }
            k.publierJeu(Moteur.jeuEntree(voixId), "tmv entree $voixId", listOf(jeton))
            attendreJeu(k, Moteur.jeuEntree(voixId))
        } finally { copie.delete() }
        Moteur.majTache(id) { it.put("etape", "Lancement sur le GPU") }
        k.lancerScript(slug, "tmv conv $voixId", Moteur.script(c, "conversion", params), listOf(Moteur.jeuEntree(voixId)), sources, 3600)
    }

    private fun attendreJeu(k: Kaggle, slug: String) {
        val fin = System.currentTimeMillis() + 15 * 60_000
        while (System.currentTimeMillis() < fin) {
            val s = runCatching { k.etatJeu(slug) }.getOrDefault("")
            if (s == "READY") return
            if (s == "FAILED") throw IllegalStateException("Kaggle n'a pas accepté les fichiers.")
            Thread.sleep(8000)
        }
        throw IllegalStateException("Kaggle met trop de temps à préparer les fichiers. Réessayez.")
    }

    private fun typeAudio(f: File) = when (f.extension.lowercase()) {
        "webm" -> "audio/webm"; "ogg", "oga", "opus" -> "audio/ogg"; "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"; "m4a", "mp4", "aac" -> "audio/mp4"; "flac" -> "audio/flac"
        "3gp" -> "audio/3gpp"; "amr" -> "audio/amr"; else -> "application/octet-stream"
    }

    private fun infoPremierPlan(texte: String): ForegroundInfo {
        Moteur.canal(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, Moteur.CANAL).setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Take my voice").setContentText(texte).setOngoing(true).setSilent(true).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(4242, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(4242, n)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = infoPremierPlan("Envoi vers Kaggle…")
}

/** Interroge Kaggle régulièrement jusqu'à la fin de la tâche, puis récupère le résultat. */
class SuiviWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {

    override suspend fun doWork(): Result {
        val c = applicationContext
        Store.init(c)
        val k = Store.kaggle() ?: return Result.success()
        val t = Store.liste(Store.lire(), "taches").firstOrNull { it.optString("etat") == "lance" }
            ?: run { Moteur.relancer(c); return Result.success() }
        val id = t.getString("id")
        val voixId = t.getString("voix")
        val train = t.getString("type") == "train"
        val slug = if (train) Moteur.scriptEntrainement(voixId) else Moteur.scriptConversion(voixId)
        val attente = if (train) 90L else 25L

        val (statut, echec) = try { k.etatScript(slug) } catch (ex: Exception) {
            Moteur.suiviPlusTard(c, attente); return Result.success()
        }
        val depuis = System.currentTimeMillis() - t.optLong("lance")
        // Juste après l'envoi, Kaggle peut encore montrer l'état de l'exécution précédente.
        val vuActif = t.optBoolean("vuActif") || statut == "QUEUED" || statut == "RUNNING"
        if (vuActif && !t.optBoolean("vuActif")) Moteur.majTache(id) { it.put("vuActif", true) }
        val termine = (statut == "COMPLETE" || statut == "ERROR" || statut.startsWith("CANCEL")) && (vuActif || depuis > 150_000)

        if (!termine) {
            val etape = when (statut) {
                "RUNNING" -> if (train) "Entraînement en cours sur Kaggle" else "Transformation en cours sur Kaggle"
                else -> "En file d'attente sur Kaggle"
            }
            Moteur.majTache(id) { it.put("etape", etape).put("statut", statut) }
            if (depuis > (if (train) 8L * 3600_000 else 2L * 3600_000)) {
                Moteur.majTache(id) { it.put("etat", "erreur").put("message", "Kaggle ne répond plus pour cette tâche.").put("fin", System.currentTimeMillis()) }
                Moteur.relancer(c)
            } else Moteur.suiviPlusTard(c, attente)
            return Result.success()
        }

        val sorties = try { k.sorties(slug) } catch (_: Exception) { emptyMap() }
        if (statut == "COMPLETE" && !sorties.containsKey("erreur.json")) {
            try {
                if (train) finEntrainement(k, id, voixId, sorties) else finConversion(k, id, sorties)
            } catch (ex: Exception) {
                Moteur.majTache(id) { it.put("etat", "erreur").put("message", "Résultat introuvable : ${ex.message}").put("fin", System.currentTimeMillis()) }
            }
        } else {
            var code = ""; var detail = echec
            sorties["erreur.json"]?.let { u ->
                runCatching { JSONObject(k.texte(u)) }.getOrNull()?.let { code = it.optString("code"); detail = it.optString("detail") }
            }
            if (statut.startsWith("CANCEL")) detail = "Tâche annulée."
            if (echec.contains("quota", true)) code = "QUOTA"
            val msg = Moteur.messageErreur(code, detail)
            Moteur.majTache(id) { it.put("etat", "erreur").put("message", msg).put("fin", System.currentTimeMillis()) }
            if (train) Store.modifier { st -> Store.voix(st, voixId)?.let { v -> v.put("etat", if (v.optBoolean("entrainee")) "prete" else "brouillon") } }
            Moteur.notifier(c, id.hashCode(), if (train) "Entraînement échoué" else "Transformation échouée", msg)
        }
        Moteur.relancer(c)
        return Result.success()
    }

    private fun finEntrainement(k: Kaggle, id: String, voixId: String, s: Map<String, String>) {
        if (!s.containsKey("model.pth")) throw IllegalStateException("modèle absent")
        var apercu: String? = null
        s["apercu.m4a"]?.let { u ->
            val f = File(Store.dossierVoix(voixId), "apercu.m4a")
            runCatching { k.telecharger(u, f); apercu = Store.urlFichier(f) + "?v=" + System.currentTimeMillis() }
        }
        val info = s["info.json"]?.let { runCatching { JSONObject(k.texte(it)) }.getOrNull() }
        Store.modifier { st ->
            Store.voix(st, voixId)?.apply {
                put("etat", "prete").put("entrainee", true).put("entraineeLe", System.currentTimeMillis())
                apercu?.let { put("apercu", it) }
                info?.let { put("minutesEntrainees", it.optDouble("minutes")) }
            }
            Store.tache(st, id)?.put("etat", "fini")?.put("fin", System.currentTimeMillis())?.put("etape", "Terminé")
        }
        val nom = Store.voix(Store.lire(), voixId)?.optString("nom") ?: "Votre voix"
        Moteur.notifier(applicationContext, id.hashCode(), "La voix « $nom » est prête 🎉", "Vous pouvez maintenant transformer votre voix.")
    }

    private fun finConversion(k: Kaggle, id: String, s: Map<String, String>) {
        val u = s["resultat.m4a"] ?: throw IllegalStateException("fichier absent")
        val f = File(Store.dossierResultats(), "$id.m4a")
        k.telecharger(u, f)
        Store.modifier { st ->
            Store.tache(st, id)?.put("etat", "fini")?.put("fin", System.currentTimeMillis())
                ?.put("etape", "Terminé")?.put("resultat", Store.urlFichier(f))
        }
        Moteur.notifier(applicationContext, id.hashCode(), "Transformation terminée 🎧", "Votre audio est prêt à écouter et à partager.")
    }
}
