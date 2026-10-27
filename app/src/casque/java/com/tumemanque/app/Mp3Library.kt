package com.tumemanque.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Un morceau. [id] est son adresse (content://…) : elle sert aussi d'identifiant partout. */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val folder: String,
    val albumId: Long,
    val added: Long,
    val size: Long,
) {
    val uri: Uri get() = Uri.parse(id)
}

data class Playlist(val id: String, val name: String, val ids: List<String>)

data class ScanState(val running: Boolean = false, val message: String = "", val found: Int = 0)

const val FAV_ID = "fav"
const val UNKNOWN_ARTIST = "Artiste inconnu"
const val UNKNOWN_ALBUM = "Album inconnu"

private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr", "mka")

/** La bibliothèque : morceaux trouvés sur le téléphone, dossiers sources et playlists. */
object Lib {
    private lateinit var app: Context
    private var ready = false

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    private val _sources = MutableStateFlow<List<String>>(emptyList())
    val sources: StateFlow<List<String>> = _sources.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _byId = MutableStateFlow<Map<String, Track>>(emptyMap())
    val byId: StateFlow<Map<String, Track>> = _byId.asStateFlow()

    private fun prefs() = app.getSharedPreferences("casque_lib", Context.MODE_PRIVATE)
    private fun cacheFile() = File(app.filesDir, "library.json")

    fun init(ctx: Context) {
        if (ready) return
        app = ctx.applicationContext
        ready = true
        runCatching {
            val f = cacheFile()
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                val list = ArrayList<Track>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        Track(
                            o.getString("id"), o.optString("t"), o.optString("a"), o.optString("b"),
                            o.optLong("d"), o.optString("f"), o.optLong("ai"), o.optLong("ad"), o.optLong("s"),
                        )
                    )
                }
                publish(list)
            }
        }
        loadFavs()
        loadHidden()
        publish(_tracks.value)
        runCatching {
            val a = JSONArray(prefs().getString("sources", "[]"))
            _sources.value = (0 until a.length()).map { a.getString(it) }
        }
        runCatching {
            val a = JSONArray(prefs().getString("playlists", "[]"))
            _playlists.value = (0 until a.length()).map {
                val o = a.getJSONObject(it)
                val ids = o.getJSONArray("ids")
                Playlist(o.getString("id"), o.getString("name"), (0 until ids.length()).map { k -> ids.getString(k) })
            }
        }
    }

    // Morceaux et dossiers retirés par l'utilisateur : ils ne reviennent pas, même après une nouvelle analyse.
    private var hiddenTracks: Set<String> = emptySet()
    private var hiddenDirs: Set<String> = emptySet()

    private fun loadHidden() {
        runCatching {
            val a = JSONArray(prefs().getString("hidden_t", "[]")); hiddenTracks = (0 until a.length()).map { a.getString(it) }.toSet()
            val b = JSONArray(prefs().getString("hidden_d", "[]")); hiddenDirs = (0 until b.length()).map { b.getString(it) }.toSet()
        }
    }

    private fun saveHidden() {
        prefs().edit().putString("hidden_t", JSONArray(hiddenTracks.toList()).toString())
            .putString("hidden_d", JSONArray(hiddenDirs.toList()).toString()).apply()
    }

    private fun visible(list: List<Track>) = list.filter { it.id !in hiddenTracks && it.folder !in hiddenDirs }

    private fun publish(list: List<Track>) {
        val l = visible(list)
        _tracks.value = l
        _byId.value = l.associateBy { it.id }
    }

    /** Ajoute un morceau téléchargé dans Casque (il apparaît tout de suite dans les Audios). */
    // Morceaux téléchargés : on les garde même si Android ne les a pas encore analysés.
    private val pinned: MutableSet<String> by lazy {
        runCatching { val a = JSONArray(prefs().getString("pinned", "[]")); (0 until a.length()).map { a.getString(it) }.toMutableSet() }
            .getOrDefault(mutableSetOf())
    }

    fun addTrack(t: Track) {
        synchronized(pinned) { pinned.add(t.id); prefs().edit().putString("pinned", JSONArray(pinned.toList()).toString()).apply() }
        if (t.id in hiddenTracks) { hiddenTracks = hiddenTracks - t.id; saveHidden() }
        if (t.folder in hiddenDirs) { hiddenDirs = hiddenDirs - t.folder; saveHidden() }
        publish(listOf(t) + _tracks.value.filterNot { it.id == t.id })
        save()
    }

    /** Retire des morceaux de Casque (le fichier reste sur le téléphone) : bibliothèque, playlists, favoris et file d'attente. */
    fun deleteTracks(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        hiddenTracks = hiddenTracks + set
        saveHidden()
        cleanReferences(set)
        publish(_tracks.value)
        save()
    }

    /** Retire des dossiers de Casque : leurs morceaux disparaissent de la bibliothèque et des playlists. */
    fun deleteFolders(paths: Collection<String>) {
        if (paths.isEmpty()) return
        val dirs = paths.toSet()
        val gone = _tracks.value.filter { it.folder in dirs }.map { it.id }.toSet()
        hiddenDirs = hiddenDirs + dirs
        saveHidden()
        // Un dossier source choisi par l'utilisateur est aussi retiré de la liste des sources.
        _sources.value.filter { treeName(it) in dirs }.forEach { removeSource(it) }
        cleanReferences(gone)
        publish(_tracks.value)
        save()
    }

    /** Remet tout ce qui avait été retiré (il faudra relancer une analyse). */
    fun restoreHidden() {
        hiddenTracks = emptySet(); hiddenDirs = emptySet(); saveHidden()
    }

    fun hiddenCount(): Int = hiddenTracks.size + hiddenDirs.size

    private fun cleanReferences(ids: Set<String>) {
        if (ids.isEmpty()) return
        _playlists.value = _playlists.value.map { it.copy(ids = it.ids.filterNot { id -> id in ids }) }
        savePlaylists()
        _favs.value = _favs.value - ids
        prefs().edit().putString("favs", JSONArray(_favs.value.toList()).toString()).apply()
        Hub.removeIds(ids)
    }

    // ------------------------------------------------------------------ permissions
    fun permission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission()) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ scan
    private fun save() {
        runCatching {
            val arr = JSONArray()
            _tracks.value.forEach {
                arr.put(
                    JSONObject().put("id", it.id).put("t", it.title).put("a", it.artist).put("b", it.album)
                        .put("d", it.durationMs).put("f", it.folder).put("ai", it.albumId).put("ad", it.added).put("s", it.size)
                )
            }
            cacheFile().writeText(arr.toString())
        }
    }

    /** Scanne tout le téléphone : on demande d'abord à Android d'indexer les fichiers audio qu'il ne connaît pas encore. */
    suspend fun scanPhone(ctx: Context) {
        if (_scan.value.running) return
        _scan.value = ScanState(true, "Recherche des fichiers audio…")
        try {
            val knownPaths = HashSet<String>()
            val known = withContext(Dispatchers.IO) { queryStore(ctx, knownPaths) }
            _scan.update { it.copy(found = known.size, message = "Exploration du stockage…") }
            val extra = withContext(Dispatchers.IO) { walkStorage(ctx, knownPaths) }
            if (extra.isNotEmpty()) {
                _scan.update { it.copy(message = "Indexation de ${extra.size} fichier(s)…") }
                withTimeoutOrNull(40_000) { indexFiles(ctx, extra) }
            }
            val store = withContext(Dispatchers.IO) { queryStore(ctx) }
            val folderTracks = _tracks.value.filter { !it.id.startsWith("content://media/") || it.id in pinned }
            val merged = (store + folderTracks).distinctBy { it.id }
            publish(merged)
            withContext(Dispatchers.IO) { save() }
            _scan.value = ScanState(false, "${merged.size} morceau(x) trouvé(s)", merged.size)
        } catch (e: Exception) {
            _scan.value = ScanState(false, "Analyse impossible : ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Scanne les dossiers choisis comme sources (et le téléphone si l'autorisation est donnée). */
    suspend fun scanSources(ctx: Context) {
        if (_scan.value.running) return
        val trees = _sources.value
        if (trees.isEmpty()) return
        _scan.value = ScanState(true, "Lecture des dossiers…")
        try {
            val old = _tracks.value.associateBy { it.id }
            val found = ArrayList<Track>()
            for (t in trees) {
                val uri = Uri.parse(t)
                val files = withContext(Dispatchers.IO) { listTree(ctx, uri) }
                _scan.update { it.copy(message = "Lecture de ${files.size} fichier(s)…") }
                val sem = Semaphore(4)
                coroutineScope {
                    files.map { f ->
                        async(Dispatchers.IO) {
                            sem.withPermit {
                                val cached = old[f.id]
                                val tr = if (cached != null && cached.size == f.size) cached else readMeta(ctx, f)
                                synchronized(found) { found.add(tr) }
                                _scan.update { it.copy(found = found.size) }
                            }
                        }
                    }.awaitAll()
                }
            }
            // On garde les morceaux connus d'Android et ceux téléchargés dans Casque.
            val store = _tracks.value.filter { it.id.startsWith("content://media/") || it.id.startsWith("file://") }
            val merged = (store + found).distinctBy { it.id }
            publish(merged)
            withContext(Dispatchers.IO) { save() }
            _scan.value = ScanState(false, "${merged.size} morceau(x) dans la bibliothèque", merged.size)
        } catch (e: Exception) {
            _scan.value = ScanState(false, "Lecture impossible : ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Mise à jour rapide au lancement : on relit seulement ce qu'Android connaît déjà. */
    suspend fun quickRefresh(ctx: Context) {
        if (_scan.value.running || !hasPermission(ctx)) return
        val store = withContext(Dispatchers.IO) { queryStore(ctx) }
        val folderTracks = _tracks.value.filter { !it.id.startsWith("content://media/") || it.id in pinned }
        val merged = (store + folderTracks).distinctBy { it.id }
        if (visible(merged).map { it.id } != _tracks.value.map { it.id }) {
            publish(merged)
            withContext(Dispatchers.IO) { save() }
        }
    }

    suspend fun refreshAll(ctx: Context) {
        if (hasPermission(ctx)) scanPhone(ctx)
        if (_sources.value.isNotEmpty()) scanSources(ctx)
    }

    fun addSource(ctx: Context, tree: Uri) {
        val s = tree.toString()
        // Si ce dossier avait été retiré, on le remet.
        val name = treeName(s)
        hiddenDirs = hiddenDirs.filterNot { it == name || it.startsWith("$name/") }.toSet()
        saveHidden()
        if (s in _sources.value) return
        _sources.value = _sources.value + s
        saveSources()
    }

    fun removeSource(tree: String) {
        _sources.value = _sources.value - tree
        saveSources()
        // On retire les morceaux de ce dossier.
        val key = tree.substringBefore("/document/")
        publish(_tracks.value.filterNot { !it.id.startsWith("content://media/") && it.id.startsWith(key) })
        save()
    }

    private fun saveSources() {
        prefs().edit().putString("sources", JSONArray(_sources.value).toString()).apply()
    }

    fun treeName(tree: String): String = runCatching {
        Uri.decode(DocumentsContract.getTreeDocumentId(Uri.parse(tree))).substringAfter(":").ifEmpty { "Stockage interne" }
    }.getOrDefault(tree)

    // ------------------------------------------------------------------ MediaStore
    private fun queryStore(ctx: Context, paths: MutableSet<String>? = null): List<Track> {
        val out = ArrayList<Track>()
        val base = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        val sel = "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.MIME_TYPE} = 'audio/mpeg' OR " +
            "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.mp3') AND ${MediaStore.Audio.Media.DURATION} >= 10000 AND " +
            "${MediaStore.Audio.Media.IS_RINGTONE} = 0 AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 AND ${MediaStore.Audio.Media.IS_ALARM} = 0"
        try {
            ctx.contentResolver.query(base, proj, sel, null, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iT = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iA = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iB = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iAi = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val iD = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iP = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val iAd = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val iS = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val iN = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val path = c.getString(iP) ?: ""
                    paths?.add(path.substringBeforeLast('.'))
                    val name = c.getString(iN) ?: ""
                    var title = c.getString(iT)?.trim().orEmpty()
                    if (title.isEmpty() || title.startsWith("<unknown")) title = name.substringBeforeLast('.')
                    var artist = c.getString(iA)?.trim().orEmpty()
                    if (artist.isEmpty() || artist.startsWith("<unknown")) artist = UNKNOWN_ARTIST
                    var album = c.getString(iB)?.trim().orEmpty()
                    if (album.isEmpty() || album.startsWith("<unknown")) album = UNKNOWN_ALBUM
                    out.add(
                        Track(
                            ContentUris.withAppendedId(base, c.getLong(iId)).toString().let {
                                // identifiant stable, indépendant du volume
                                "content://media/external/audio/media/" + it.substringAfterLast('/')
                            },
                            title, artist, album, c.getLong(iD),
                            path.substringBeforeLast('/', "").ifEmpty { "Stockage" },
                            c.getLong(iAi), c.getLong(iAd) * 1000, c.getLong(iS),
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
        }
        return out
    }

    private fun walkStorage(ctx: Context, knownPaths: Set<String>): List<String> {
        val roots = LinkedHashSet<File>()
        roots.add(Environment.getExternalStorageDirectory())
        ctx.getExternalFilesDirs(null).forEach { d ->
            val p = d?.absolutePath ?: return@forEach
            val i = p.indexOf("/Android/")
            if (i > 0) roots.add(File(p.substring(0, i)))
        }
        val out = ArrayList<String>()
        fun walk(dir: File, depth: Int) {
            if (depth > 9 || out.size > 20000) return
            if (dir.name.startsWith(".")) return
            val p = dir.absolutePath
            if (p.contains("/Android/data") || p.contains("/Android/obb")) return
            val list = dir.listFiles() ?: return
            for (f in list) {
                if (f.isDirectory) walk(f, depth + 1)
                else if (f.extension.lowercase() in AUDIO_EXT && f.length() > 60_000) {
                    if (f.absolutePath.substringBeforeLast('.') !in knownPaths) out.add(f.absolutePath)
                }
            }
        }
        roots.forEach { runCatching { walk(it, 0) } }
        return out
    }

    private suspend fun indexFiles(ctx: Context, paths: List<String>) {
        suspendCoroutine<Unit> { cont ->
            var left = paths.size
            var done = false
            MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null) { _, _ ->
                synchronized(this) {
                    left--
                    if (left <= 0 && !done) { done = true; cont.resume(Unit) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ dossiers choisis (SAF)
    private data class DocFile(val id: String, val name: String, val size: Long, val modified: Long, val folder: String)

    private fun listTree(ctx: Context, tree: Uri): List<DocFile> {
        val out = ArrayList<DocFile>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootName = treeName(tree.toString())
        fun rec(docId: String, path: String, depth: Int) {
            if (depth > 12 || out.size > 30000) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            val dirs = ArrayList<Pair<String, String>>()
            runCatching {
                ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2) ?: ""
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (!name.startsWith(".")) dirs.add(id to name)
                        } else if (mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in AUDIO_EXT) {
                            val u = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                            out.add(DocFile(u, name, c.getLong(3), c.getLong(4), path))
                        }
                    }
                }
            }
            dirs.forEach { (id, name) -> rec(id, "$path/$name", depth + 1) }
        }
        rec(rootId, rootName, 0)
        return out
    }

    private fun readMeta(ctx: Context, f: DocFile): Track {
        var title = f.name.substringBeforeLast('.')
        var artist = UNKNOWN_ARTIST
        var album = UNKNOWN_ALBUM
        var dur = 0L
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, Uri.parse(f.id))
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.takeIf { it.isNotEmpty() }?.let { title = it }
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
                ?.trim()?.takeIf { it.isNotEmpty() }?.let { artist = it }
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()?.takeIf { it.isNotEmpty() }?.let { album = it }
            dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
        } finally {
            runCatching { r.release() }
        }
        return Track(f.id, title, artist, album, dur, f.folder, 0L, f.modified, f.size)
    }

    // ------------------------------------------------------------------ playlists
    private fun savePlaylists() {
        val arr = JSONArray()
        _playlists.value.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("ids", JSONArray(it.ids)))
        }
        prefs().edit().putString("playlists", arr.toString()).apply()
    }

    fun createPlaylist(name: String, ids: List<String> = emptyList()): String {
        val id = "pl" + System.currentTimeMillis()
        _playlists.value = _playlists.value + Playlist(id, name.trim().ifEmpty { "Playlist" }, ids.distinct())
        savePlaylists()
        return id
    }

    fun renamePlaylist(id: String, name: String) {
        _playlists.value = _playlists.value.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it }
        savePlaylists()
    }

    fun deletePlaylist(id: String) {
        _playlists.value = _playlists.value.filterNot { it.id == id }
        savePlaylists()
    }

    fun addToPlaylist(id: String, trackIds: List<String>) {
        _playlists.value = _playlists.value.map {
            if (it.id == id) it.copy(ids = (it.ids + trackIds).distinct()) else it
        }
        savePlaylists()
    }

    fun removeFromPlaylist(id: String, trackId: String) {
        _playlists.value = _playlists.value.map { if (it.id == id) it.copy(ids = it.ids - trackId) else it }
        savePlaylists()
    }

    fun movePlaylistTrack(id: String, from: Int, to: Int) {
        _playlists.value = _playlists.value.map {
            if (it.id != id || from !in it.ids.indices || to !in it.ids.indices) it
            else it.copy(ids = it.ids.toMutableList().also { l -> l.add(to, l.removeAt(from)) })
        }
        savePlaylists()
    }

    // ------------------------------------------------------------------ favoris
    private val _favs = MutableStateFlow<Set<String>>(emptySet())
    val favs: StateFlow<Set<String>> = _favs.asStateFlow()

    fun loadFavs() {
        runCatching {
            val a = JSONArray(prefs().getString("favs", "[]"))
            _favs.value = (0 until a.length()).map { a.getString(it) }.toSet()
        }
    }

    fun toggleFav(id: String) {
        _favs.value = if (id in _favs.value) _favs.value - id else _favs.value + id
        prefs().edit().putString("favs", JSONArray(_favs.value.toList()).toString()).apply()
    }
}
