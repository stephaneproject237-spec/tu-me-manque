package com.takemyvoice.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tout l'état de l'appli dans un seul fichier JSON (voix, tâches, identifiants Kaggle).
 * Les enregistrements et résultats audio sont des fichiers à côté.
 */
object Store {
    private lateinit var dir: File
    private lateinit var etatFile: File
    private var etat = JSONObject()
    private val ecouteurs = CopyOnWriteArrayList<() -> Unit>()

    @Synchronized
    fun init(c: Context) {
        if (::dir.isInitialized) return
        dir = c.applicationContext.filesDir
        etatFile = File(dir, "etat.json")
        etat = try { JSONObject(etatFile.readText()) } catch (_: Exception) { JSONObject() }
        if (!etat.has("voix")) etat.put("voix", JSONArray())
        if (!etat.has("taches")) etat.put("taches", JSONArray())
        if (!etat.has("kaggle")) etat.put("kaggle", JSONObject())
    }

    fun ecouter(f: () -> Unit) { ecouteurs.add(f) }
    fun oublier(f: () -> Unit) { ecouteurs.remove(f) }

    @Synchronized
    private fun sauver() {
        val tmp = File(dir, "etat.json.tmp")
        tmp.writeText(etat.toString())
        tmp.renameTo(etatFile)
    }

    /** Toute modification passe par ici : sauvegarde + prévient l'écran. */
    fun modifier(bloc: (JSONObject) -> Unit) {
        synchronized(this) {
            bloc(etat)
            sauver()
        }
        ecouteurs.forEach { runCatching { it() } }
    }

    @Synchronized
    fun lire(): JSONObject = JSONObject(etat.toString())

    // ---------- Kaggle ----------

    @Synchronized
    fun kaggle(): Kaggle? {
        val k = etat.optJSONObject("kaggle") ?: return null
        val u = k.optString("user"); val key = k.optString("cle")
        return if (u.isBlank() || key.isBlank()) null else Kaggle(u, key)
    }

    /** Corrige le nom d'utilisateur enregistré avec celui que Kaggle donne pour ce jeton (s'il est différent). Renvoie le client à jour. */
    fun kaggleVerifie(): Kaggle? {
        val k = kaggle() ?: return null
        val nom = runCatching { k.vraiNom() }.getOrNull() ?: return k
        if (nom == k.user) return k
        modifier { it.optJSONObject("kaggle")?.put("user", nom) }
        return kaggle()
    }

    // ---------- Fichiers ----------

    fun dossierVoix(id: String) = File(dir, "voix/$id").apply { mkdirs() }
    fun dossierEnr(id: String) = File(dossierVoix(id), "enr").apply { mkdirs() }
    fun dossierResultats() = File(dir, "resultats").apply { mkdirs() }
    fun dossierEntrees() = File(dir, "entrees").apply { mkdirs() }

    /** Chemin servi à la page (https://appassets.androidplatform.net/files/…). */
    fun urlFichier(f: File): String = "/files/" + f.relativeTo(dir).path

    fun fichierDepuisUrl(u: String): File? {
        val rel = u.substringAfter("/files/", "")
        if (rel.isBlank() || rel.contains("..")) return null
        return File(dir, rel).takeIf { it.exists() }
    }

    // ---------- Aides JSON ----------

    fun liste(o: JSONObject, cle: String): List<JSONObject> {
        val a = o.optJSONArray(cle) ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    fun voix(o: JSONObject, id: String): JSONObject? = liste(o, "voix").firstOrNull { it.optString("id") == id }
    fun tache(o: JSONObject, id: String): JSONObject? = liste(o, "taches").firstOrNull { it.optString("id") == id }

    fun retirer(o: JSONObject, cle: String, garder: (JSONObject) -> Boolean) {
        val a = JSONArray()
        liste(o, cle).filter(garder).forEach { a.put(it) }
        o.put(cle, a)
    }

    fun nouvelId(): String {
        val alpha = "abcdefghijkmnpqrstuvwxyz23456789"
        return (1..8).map { alpha.random() }.joinToString("")
    }
}
