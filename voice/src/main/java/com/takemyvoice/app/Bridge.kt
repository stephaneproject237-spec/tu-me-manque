package com.takemyvoice.app

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/** Fonctions appelées par la page (window.TMV.…). Les réponses lentes reviennent par tmvEvenement(nom, json). */
class Bridge(private val a: MainActivity) {
    private val c = a.applicationContext
    private var majEnAttente: Maj? = null

    private fun ok(extra: JSONObject = JSONObject()) = extra.put("ok", true).toString()
    private fun ko(msg: String) = JSONObject().put("ok", false).put("erreur", msg).toString()

    // ---------- État ----------

    @JavascriptInterface
    fun etat(): String {
        val e = Store.lire()
        val k = e.optJSONObject("kaggle") ?: JSONObject()
        val cle = k.optString("cle")
        e.put("kaggle", JSONObject().put("user", k.optString("user"))
            .put("configure", cle.isNotBlank() && k.optString("user").isNotBlank())
            .put("cleMasquee", if (cle.length > 6) cle.take(4) + "••••" + cle.takeLast(2) else "")
            .put("quota", k.optJSONObject("quota") ?: JSONObject()))
        // Enregistrements de chaque voix (durée dans le nom : enr_<date>_<ms>.<ext>)
        Store.liste(e, "voix").forEach { v ->
            val arr = JSONArray()
            var totalMs = 0L
            Store.dossierEnr(v.getString("id")).listFiles()?.sortedBy { it.name }?.forEach { f ->
                val ms = f.nameWithoutExtension.split('_').getOrNull(2)?.toLongOrNull() ?: 0L
                totalMs += ms
                arr.put(JSONObject().put("nom", f.name).put("url", Store.urlFichier(f)).put("ms", ms))
            }
            v.put("enregistrements", arr).put("totalMs", totalMs)
        }
        e.put("version", Updater.nomActuel(c))
        return e.toString()
    }

    // ---------- Compte Kaggle ----------

    @JavascriptInterface
    fun enregistrerKaggle(user: String, cle: String) {
        val u = user.trim().lowercase().removePrefix("@")
        val k = cle.trim().let { s ->
            // On accepte aussi le contenu complet de kaggle.json collé tel quel
            runCatching { JSONObject(s) }.getOrNull()?.optString("key")?.takeIf { it.isNotBlank() } ?: s
        }
        val uFinal = runCatching { JSONObject(cle.trim()).optString("username") }.getOrNull()?.takeIf { it.isNotBlank() } ?: u
        Store.modifier { it.put("kaggle", JSONObject().put("user", uFinal).put("cle", k)) }
        testerKaggle()
    }

    @JavascriptInterface
    fun testerKaggle() {
        thread {
            val k = Store.kaggle()
            if (k == null) { a.evenement("kaggle", JSONObject().put("ok", false).put("erreur", "Identifiants manquants")); return@thread }
            try {
                val q = k.quota()
                Store.modifier { it.optJSONObject("kaggle")?.put("quota", q)?.put("verifie", true) }
                a.evenement("kaggle", JSONObject().put("ok", true).put("quota", q))
            } catch (ex: Exception) {
                val msg = if (ex is KaggleException && (ex.http == 401 || ex.http == 403))
                    "Nom d'utilisateur ou clé refusés par Kaggle." else "Connexion impossible : ${ex.message}"
                a.evenement("kaggle", JSONObject().put("ok", false).put("erreur", msg))
            }
        }
    }

    @JavascriptInterface
    fun deconnecterKaggle() { Store.modifier { it.put("kaggle", JSONObject()) } }

    // ---------- Voix ----------

    @JavascriptInterface
    fun creerVoix(nom: String, genre: String): String {
        val id = Store.nouvelId()
        Store.modifier { e ->
            e.getJSONArray("voix").put(JSONObject().put("id", id).put("nom", nom.trim().ifBlank { "Nouvelle voix" }.take(40))
                .put("genre", if (genre == "femme") "femme" else "homme").put("etat", "brouillon")
                .put("cree", System.currentTimeMillis()).put("accord", false))
        }
        return ok(JSONObject().put("id", id))
    }

    @JavascriptInterface
    fun modifierVoix(id: String, champs: String) {
        val j = runCatching { JSONObject(champs) }.getOrNull() ?: return
        Store.modifier { e ->
            Store.voix(e, id)?.let { v ->
                j.optString("nom").takeIf { it.isNotBlank() }?.let { v.put("nom", it.take(40)) }
                if (j.has("genre")) v.put("genre", if (j.optString("genre") == "femme") "femme" else "homme")
                if (j.has("accord")) v.put("accord", j.optBoolean("accord"))
            }
        }
    }

    @JavascriptInterface
    fun supprimerVoix(id: String) {
        val actif = Store.liste(Store.lire(), "taches").any { it.optString("voix") == id && it.optString("etat") in setOf("envoi", "lance") }
        if (actif) { a.toast("Attendez la fin de la tâche en cours pour supprimer cette voix."); return }
        Store.modifier { e ->
            Store.retirer(e, "voix") { it.optString("id") != id }
            Store.retirer(e, "taches") { it.optString("voix") != id }
        }
        Store.dossierVoix(id).deleteRecursively()
        thread {
            Store.kaggle()?.let { k ->
                runCatching { k.supprimerScript(Moteur.scriptConversion(id)) }
                runCatching { k.supprimerScript(Moteur.scriptEntrainement(id)) }
                runCatching { k.supprimerJeu(Moteur.jeuVoix(id)) }
                runCatching { k.supprimerJeu(Moteur.jeuEntree(id)) }
            }
        }
    }

    // ---------- Enregistrements ----------

    @JavascriptInterface
    fun ajouterEnregistrement(voixId: String, b64: String, ext: String, ms: Long): String {
        return try {
            val e = ext.lowercase().filter { it.isLetterOrDigit() }.take(5).ifBlank { "webm" }
            val f = File(Store.dossierEnr(voixId), "enr_${System.currentTimeMillis()}_${ms.coerceAtLeast(0)}.$e")
            f.writeBytes(Base64.decode(b64, Base64.DEFAULT))
            Store.modifier { } // rafraîchit l'écran
            ok(JSONObject().put("nom", f.name))
        } catch (ex: Exception) { ko(ex.message ?: "Erreur") }
    }

    @JavascriptInterface
    fun supprimerEnregistrement(voixId: String, nom: String) {
        if (nom.contains('/') || nom.contains("..")) return
        File(Store.dossierEnr(voixId), nom).delete()
        Store.modifier { }
    }

    // ---------- YouTube ----------

    @JavascriptInterface
    fun ytPreparer() { YtVoice.init(c); YtVoice.chauffer { err -> a.evenement("ytPret", JSONObject().put("ok", err == null).put("erreur", err ?: "")) } }

    @JavascriptInterface
    fun ytChercher(q: String) {
        YtVoice.init(c)
        YtVoice.chercher(q.trim()) { res, err ->
            a.evenement("ytResultats", JSONObject().put("ok", res != null).put("videos", res ?: JSONArray()).put("erreur", err ?: ""))
        }
    }

    @JavascriptInterface
    fun ytExtraire(voixId: String, videoId: String, debut: Int, fin: Int) {
        YtVoice.init(c)
        YtVoice.extraire(voixId, videoId, debut, fin,
            { p -> a.evenement("ytProgression", JSONObject().put("p", p)) }) { nom, err ->
            a.evenement("ytFini", JSONObject().put("ok", nom != null).put("nom", nom ?: "").put("erreur", err ?: ""))
        }
    }

    @JavascriptInterface
    fun ytAnnuler() = YtVoice.annuler()

    // ---------- Tâches ----------

    private fun ajouterTache(voixId: String, type: String, params: JSONObject, entree: File? = null): String {
        val id = Store.nouvelId()
        Store.modifier { e ->
            val t = JSONObject().put("id", id).put("voix", voixId).put("type", type).put("etat", "attente")
                .put("etape", "En attente").put("cree", System.currentTimeMillis()).put("params", params)
            entree?.let { t.put("entree", it.absolutePath).put("entreeUrl", Store.urlFichier(it)) }
            params.optString("texte").takeIf { it.isNotBlank() }?.let { t.put("texte", it) }
            e.getJSONArray("taches").put(t)
            if (type == "train") Store.voix(e, voixId)?.put("etat", "entrainement")
        }
        Moteur.relancer(c)
        return id
    }

    @JavascriptInterface
    fun entrainer(voixId: String, epoques: Int): String {
        if (Store.kaggle() == null) return ko("Connectez d'abord votre compte Kaggle dans Réglages.")
        val v = Store.voix(Store.lire(), voixId) ?: return ko("Voix introuvable")
        if (!v.optBoolean("accord")) return ko("Confirmez d'abord que la personne est d'accord.")
        val n = Store.dossierEnr(voixId).listFiles()?.size ?: 0
        if (n == 0) return ko("Ajoutez des enregistrements d'abord.")
        return ok(JSONObject().put("id", ajouterTache(voixId, "train", JSONObject().put("epoques", epoques.coerceIn(50, 1000)))))
    }

    @JavascriptInterface
    fun transformer(voixId: String, b64: String, ext: String, hauteur: Int): String {
        if (Store.kaggle() == null) return ko("Connectez d'abord votre compte Kaggle dans Réglages.")
        return try {
            val e = ext.lowercase().filter { it.isLetterOrDigit() }.take(5).ifBlank { "webm" }
            val f = File(Store.dossierEntrees(), "entree_${System.currentTimeMillis()}.$e")
            f.writeBytes(Base64.decode(b64, Base64.DEFAULT))
            ok(JSONObject().put("id", ajouterTache(voixId, "convert",
                JSONObject().put("mode", "audio").put("hauteur", hauteur.coerceIn(-24, 24)), f)))
        } catch (ex: Exception) { ko(ex.message ?: "Erreur") }
    }

    @JavascriptInterface
    fun direTexte(voixId: String, texte: String, hauteur: Int, vitesse: Int): String {
        if (Store.kaggle() == null) return ko("Connectez d'abord votre compte Kaggle dans Réglages.")
        val t = texte.trim().take(2000)
        if (t.isBlank()) return ko("Écrivez un texte.")
        return ok(JSONObject().put("id", ajouterTache(voixId, "convert", JSONObject().put("mode", "texte")
            .put("texte", t).put("hauteur", hauteur.coerceIn(-24, 24)).put("vitesse", vitesse.coerceIn(-50, 50)))))
    }

    @JavascriptInterface
    fun reessayer(id: String) {
        Store.modifier { e ->
            Store.tache(e, id)?.let { t ->
                t.put("etat", "attente").put("etape", "En attente").put("essais", 0).remove("message")
                if (t.optString("type") == "train") Store.voix(e, t.optString("voix"))?.put("etat", "entrainement")
            }
        }
        Moteur.relancer(c)
    }

    @JavascriptInterface
    fun supprimerTache(id: String) {
        val t = Store.tache(Store.lire(), id) ?: return
        if (t.optString("etat") in setOf("envoi", "lance")) { a.toast("Cette tâche est en cours sur Kaggle."); return }
        Store.modifier { e -> Store.retirer(e, "taches") { it.optString("id") != id } }
        t.optString("entree").takeIf { it.isNotBlank() }?.let { File(it).delete() }
        t.optString("resultat").takeIf { it.isNotBlank() }?.let { Store.fichierDepuisUrl(it)?.delete() }
    }

    @JavascriptInterface
    fun actualiser() { Moteur.relancer(c) }

    // ---------- Résultats ----------

    @JavascriptInterface
    fun partager(url: String, titre: String) {
        val f = Store.fichierDepuisUrl(url.substringBefore('?')) ?: return
        val dir = File(c.cacheDir, "partage").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val copie = File(dir, nomFichier(titre, f.extension))
        f.copyTo(copie, overwrite = true)
        val uri = FileProvider.getUriForFile(c, c.packageName + ".files", copie)
        a.runOnUiThread {
            a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/mp4")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Envoyer l'audio"))
        }
    }

    @JavascriptInterface
    fun sauvegarder(url: String, titre: String) {
        val f = Store.fichierDepuisUrl(url.substringBefore('?')) ?: return
        thread {
            try {
                val nom = nomFichier(titre, f.extension)
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, nom)
                        put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                        put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Take my voice")
                    }
                    val uri = c.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv) ?: error("stockage indisponible")
                    c.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
                } else {
                    val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Take my voice").apply { mkdirs() }
                    f.copyTo(File(d, nom), overwrite = true)
                }
                a.toast("Enregistré dans Musique › Take my voice")
            } catch (ex: Exception) { a.toast("Impossible d'enregistrer : ${ex.message}") }
        }
    }

    private fun nomFichier(titre: String, ext: String): String {
        val base = titre.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(40).ifBlank { "take-my-voice" }
        return "$base ${java.text.SimpleDateFormat("dd-MM HH'h'mm", java.util.Locale.FRANCE).format(java.util.Date())}.${ext.ifBlank { "m4a" }}"
    }

    @JavascriptInterface
    fun ouvrir(url: String) {
        if (!url.startsWith("https://")) return
        a.runOnUiThread { runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    }

    @JavascriptInterface
    fun toast(msg: String) = a.toast(msg)

    // ---------- Mises à jour ----------

    @JavascriptInterface
    fun verifierMaj() {
        Updater.verifier { m, err ->
            majEnAttente = m
            val dispo = m != null && m.code > Updater.codeActuel(c)
            a.evenement("maj", JSONObject().put("ok", err == null).put("dispo", dispo)
                .put("version", m?.nom ?: "").put("erreur", err ?: ""))
        }
    }

    @JavascriptInterface
    fun installerMaj() {
        val m = majEnAttente ?: return
        Updater.telecharger(c, m, { p -> a.evenement("majProgression", JSONObject().put("p", p)) }) { f, err ->
            if (f == null) a.evenement("majProgression", JSONObject().put("erreur", err ?: "échec"))
            else {
                a.evenement("majProgression", JSONObject().put("p", 100).put("fini", true))
                a.runOnUiThread {
                    if (!Updater.installer(a, f)) a.toast("Autorisez l'installation, puis touchez de nouveau « Installer ».")
                }
            }
        }
    }
}
