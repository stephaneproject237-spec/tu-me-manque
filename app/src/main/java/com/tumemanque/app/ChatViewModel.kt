package com.tumemanque.app

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Vrai tant que l'écran de chat est ouvert : les notifications reçues sont alors masquées. */
object ChatVisibility {
    @Volatile
    var open: Boolean = false
}

data class ChatState(
    val messages: List<ChatMsg> = emptyList(),
    val loading: Boolean = true,
    val error: Boolean = false,
    val toast: String? = null,
    val photo: PreparedPhoto? = null,
    val gifs: List<Gif> = emptyList(),
    val gifLoading: Boolean = false,
    val gifError: Boolean = false,
    val typing: Boolean = false,
    val replyTo: Reply? = null,
)

class ChatViewModel : ViewModel() {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    /** Appelé quand les messages reçus viennent d'être marqués comme lus. */
    var onRead: (() -> Unit)? = null

    /** Appelé quand un message contient « je t'aime » / « jtm » (envoyé ou reçu) : des cœurs s'envolent. */
    var onLove: (() -> Unit)? = null

    /** Un sticker vient d'être envoyé ou reçu : il s'envole en plusieurs exemplaires. */
    var onSticker: ((String) -> Unit)? = null
    var onGame: ((ChatMsg) -> Unit)? = null

    fun pollSoon() { viewModelScope.launch { poll() } }
    private var lastTyping = 0L

    fun setReply(m: ChatMsg) {
        if (m.pending || m.id == 0L) return
        val text = if (m.once) "🔥 Message éphémère" else when (m.kind) {
            "text" -> (m.body ?: "").replace(Regex("\\s+"), " ").take(80)
            "sticker" -> "💗 Sticker"
            "gif" -> "🎞️ GIF"
            "image" -> "📷 " + (m.body?.take(60) ?: "Photo")
            "voice" -> "🎤 Message vocal"
            "video" -> "🎥 Vidéo"
            "game" -> "🎮 " + (m.body ?: "Cupidon").removePrefix("🎮 ").take(60)
            else -> ""
        }
        _state.update { it.copy(replyTo = Reply(m.id, m.mine, m.kind, text)) }
    }

    fun clearReply() = _state.update { it.copy(replyTo = null) }

    /** Appelé à chaque modification du texte : prévient le partenaire (au plus toutes les 2,5 s). */
    fun onTextChanged(hasText: Boolean) {
        if (!hasText) return
        val now = System.currentTimeMillis()
        if (now - lastTyping < 2500) return
        lastTyping = now
        viewModelScope.launch { Backend.setTyping() }
    }

    private fun stopTyping() {
        if (lastTyping == 0L) return
        lastTyping = 0L
        viewModelScope.launch { Backend.stopTyping() }
    }

    suspend fun pollTyping() {
        val t = Backend.partnerTyping()
        if (t != _state.value.typing) _state.update { it.copy(typing = t) }
    }
    private val loveRegex = Regex("\\bje\\s*t['’ ]?\\s*aime\\b|\\bjtm\\b", RegexOption.IGNORE_CASE)
    private fun isLove(m: ChatMsg) = m.kind == "text" && m.body?.let { loveRegex.containsMatchIn(it) } == true

    private val confirmed = mutableListOf<ChatMsg>()
    private val pendings = mutableListOf<ChatMsg>()
    private val ids = HashSet<Long>()
    private val unreadOwn = HashSet<Long>()
    private var tmpCounter = 0L
    private var started = false
    private var polling = false
    private var loadingOlder = false
    private var noMore = false

    private var gifQuery = ""
    private var gifOffset = 0
    private var gifDone = false
    private var gifSeq = 0

    fun reset() {
        confirmed.clear(); pendings.clear(); ids.clear(); unreadOwn.clear()
        noMore = false; started = false; polling = false; loadingOlder = false
        gifQuery = ""; gifOffset = 0; gifDone = false; gifSeq++
        _state.value = ChatState()
    }

    private fun publish() {
        _state.update { it.copy(messages = confirmed.toList() + pendings.toList(), loading = false, error = false) }
    }

    private fun addConfirmed(m: ChatMsg): Boolean {
        if (!ids.add(m.id)) return false
        confirmed.add(m)
        if (m.mine && !m.read) unreadOwn.add(m.id)
        return true
    }

    fun ensureLoaded() {
        if (started) return
        started = true
        _state.update { it.copy(loading = true, error = false) }
        viewModelScope.launch {
            try {
                val latest = Backend.latestMessages()
                latest.forEach { addConfirmed(it) }
                noMore = latest.size < 40
                restoreOutbox()
                publish()
                markReadIfNeeded()
            } catch (e: Exception) {
                started = false
                _state.update { it.copy(loading = false, error = true) }
            }
        }
    }

    fun retry() {
        started = false
        ensureLoaded()
    }

    /** Appelé toutes les 2,5 s tant que le chat est ouvert. */
    suspend fun poll() {
        if (!started || polling || _state.value.error) return
        polling = true
        try {
            val last = confirmed.maxOfOrNull { it.id } ?: 0L
            val fresh = Backend.newMessages(last)
            var changed = false
            for (m in fresh) {
                if (addConfirmed(m)) {
                    changed = true
                    if (m.mine) dropMatchingPending(m)
                    else {
                        if (m.kind == "game") onGame?.invoke(m) else Sounds.receive()
                        if (_state.value.typing) _state.update { it.copy(typing = false) }
                        if (isLove(m)) onLove?.invoke()
                        if (m.kind == "sticker") m.body?.let { onSticker?.invoke(it) }
                    }
                }
            }
            if (syncDeleted()) changed = true
            if (syncEdited()) changed = true
            if (syncOnce()) changed = true
            tickScheduled()
            if (changed) publish()
            if (unreadOwn.isNotEmpty()) {
                val read = Backend.readAmong(unreadOwn.toList().take(100))
                if (read.isNotEmpty()) {
                    for (id in read) {
                        unreadOwn.remove(id)
                        val i = confirmed.indexOfFirst { it.id == id }
                        if (i >= 0) confirmed[i] = confirmed[i].copy(read = true)
                    }
                    publish()
                }
            }
            markReadIfNeeded()
        } catch (e: Exception) {
            // hors-ligne : on réessaie au prochain tour
        }
        polling = false
    }

    private fun markDeletedAt(i: Int) {
        val m = confirmed[i]
        confirmed[i] = m.copy(deleted = true, body = null, media = null, reply = null, localBytes = null, localFile = null)
        unreadOwn.remove(m.id)
    }

    /** L'autre personne a supprimé un message « pour tout le monde » : on le voit changer ici aussi. */
    private suspend fun syncDeleted(): Boolean {
        val min = confirmed.minOfOrNull { it.id } ?: return false
        var any = false
        for (id in Backend.deletedSince(min)) {
            val i = confirmed.indexOfFirst { it.id == id }
            if (i >= 0 && !confirmed[i].deleted) { markDeletedAt(i); any = true }
        }
        return any
    }

    private fun markEditedAt(i: Int, body: String) {
        val m = confirmed[i]
        val meta = JSONObject(m.meta?.toString() ?: "{}").put("edited", true)
        confirmed[i] = m.copy(body = body, meta = meta)
    }

    /** Une modification faite par l'autre personne (ou depuis un autre appareil) apparaît ici aussi. */
    private suspend fun syncEdited(): Boolean {
        val min = confirmed.minOfOrNull { it.id } ?: return false
        var any = false
        for ((id, body) in Backend.editedSince(min)) {
            val i = confirmed.indexOfFirst { it.id == id }
            if (i >= 0 && !confirmed[i].deleted && (confirmed[i].body != body || confirmed[i].meta?.optBoolean("edited") != true)) { markEditedAt(i, body); any = true }
        }
        return any
    }

    /** Modifie un de mes messages texte déjà envoyé. */
    fun editMessage(m: ChatMsg, text: String, done: () -> Unit) {
        val t = text.trim()
        if (m.pending || m.id == 0L || t.isEmpty()) return
        if (t == (m.body ?: "")) { done(); return }
        viewModelScope.launch {
            val ok = try { Backend.editMessage(m.id, t) } catch (e: Exception) { false }
            if (!ok) { showToast("Impossible de modifier. Vérifie ta connexion."); return@launch }
            val i = confirmed.indexOfFirst { it.id == m.id }
            if (i >= 0) { markEditedAt(i, t); publish() }
            done()
        }
    }

    fun deleteMessage(m: ChatMsg, all: Boolean) {
        if (m.pending || m.id == 0L) return
        viewModelScope.launch {
            val ok = try { Backend.deleteMessage(m.id, all) } catch (e: Exception) { false }
            if (!ok) { showToast("Impossible de supprimer. Vérifie ta connexion."); return@launch }
            if (all) {
                val i = confirmed.indexOfFirst { it.id == m.id }
                if (i >= 0) markDeletedAt(i)
            } else {
                confirmed.removeAll { it.id == m.id }
                unreadOwn.remove(m.id)
            }
            publish()
        }
    }

    /** Supprime plusieurs messages d'un coup (pour moi, ou pour tout le monde si ce sont les miens). */
    fun deleteMessages(list: List<ChatMsg>, all: Boolean) {
        val targets = list.filter { !it.pending && it.id != 0L }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            var failed = 0
            for (m in targets) {
                val ok = try { Backend.deleteMessage(m.id, all && m.mine && !m.deleted) } catch (e: Exception) { false }
                if (!ok) { failed++; continue }
                if (all && m.mine && !m.deleted) {
                    val i = confirmed.indexOfFirst { it.id == m.id }
                    if (i >= 0) markDeletedAt(i)
                } else {
                    confirmed.removeAll { it.id == m.id }
                    unreadOwn.remove(m.id)
                }
            }
            publish()
            if (failed > 0) showToast(if (failed == targets.size) "Impossible de supprimer. Vérifie ta connexion." else "$failed message(s) n'ont pas pu être supprimés.")
        }
    }

    private suspend fun markReadIfNeeded() {
        if (confirmed.none { !it.mine && !it.read }) return
        try {
            Backend.markRead()
            for (i in confirmed.indices) {
                if (!confirmed[i].mine && !confirmed[i].read) confirmed[i] = confirmed[i].copy(read = true)
            }
            publish()
            onRead?.invoke()
        } catch (e: Exception) {
            // réessayé au prochain tour
        }
    }

    fun loadOlder() {
        if (loadingOlder || noMore || !started || confirmed.isEmpty()) return
        loadingOlder = true
        viewModelScope.launch {
            try {
                val older = Backend.olderMessages(confirmed.minOf { it.id })
                if (older.size < 40) noMore = true
                older.forEach { addConfirmed(it) }
                confirmed.sortBy { it.id }
                publish()
            } catch (e: Exception) {
                // ignoré
            }
            loadingOlder = false
        }
    }

    // ---------- Envoi ----------

    private fun addPending(m: ChatMsg): ChatMsg {
        val p = m.copy(tmp = ++tmpCounter, pending = true, mine = true, createdAt = System.currentTimeMillis(), reply = _state.value.replyTo)
        _state.update { it.copy(replyTo = null) }
        pendings.add(p)
        publish()
        return p
    }

    private fun removePending(p: ChatMsg) {
        pendings.removeAll { it.tmp == p.tmp }
        publish()
    }

    private fun dropMatchingPending(m: ChatMsg) {
        val i = pendings.indexOfFirst {
            it.kind == m.kind && it.body == m.body && (it.kind != "gif" || it.media == m.media)
        }
        if (i >= 0) pendings.removeAt(i)
    }

    fun showToast(text: String) = _state.update { it.copy(toast = text) }

    fun clearToast() = _state.update { it.copy(toast = null) }

    /** Le vrai contenu d'un message éphémère en cours d'envoi (il n'apparaît jamais dans la bulle). */
    private val onceSecret = HashMap<Long, String>()

    private suspend fun deliver(p: ChatMsg, media: String?): Boolean {
        val once = p.once
        try {
            val meta = JSONObject()
            if (p.w > 0) meta.put("w", p.w)
            if (p.h > 0) meta.put("h", p.h)
            if (p.dur > 0) meta.put("d", p.dur)
            p.reply?.let {
                meta.put("reply", JSONObject().put("id", it.id).put("by_me", it.fromMe).put("kind", it.kind).put("text", it.text))
            }
            stopTyping()
            val m2 = if (meta.length() == 0) null else meta
            val r = if (once) Backend.sendOnce(p.kind, onceSecret.remove(p.tmp), media, m2) else Backend.sendMessage(p.kind, p.body, media, m2)
            if (r.status != "ok") {
                removePending(p)
                showToast(if (r.status == "no_partner") "Vous n'êtes plus partenaires." else "Message non envoyé.")
                return false
            }
            Sounds.send()
            pendings.removeAll { it.tmp == p.tmp }
            if (ids.add(r.id)) {
                confirmed.add(if (once) p.copy(id = r.id, pending = false, media = null, localBytes = null, localFile = null) else p.copy(id = r.id, pending = false, media = media))
                confirmed.sortBy { it.id }
                unreadOwn.add(r.id)
            }
            publish()
            return true
        } catch (e: Exception) {
            if (once) { removePending(p); showToast("Message non envoyé. Vérifie ta connexion."); return false }
            queueOffline(p, media)
            return false
        }
    }

    /** Pas de réseau : le message reste dans le fil (🕓) et partira tout seul dès le retour de la connexion. */
    private fun queueOffline(p: ChatMsg, media: String?, src: File? = null, ext: String = "", mime: String = "", bytes: ByteArray? = null) {
        try {
            val file = when {
                media != null -> null
                bytes != null -> Outbox.stash(bytes, ext)
                src != null -> Outbox.stashCopy(src, ext)
                else -> null
            }
            if (media == null && file == null && (p.kind == "image" || p.kind == "voice" || p.kind == "video")) {
                removePending(p); showToast("Message non envoyé."); return
            }
            val reply = p.reply?.let { JSONObject().put("id", it.id).put("by_me", it.fromMe).put("kind", it.kind).put("text", it.text).toString() }
            Outbox.add(OutMsg(java.util.UUID.randomUUID().toString(), p.kind, p.body, media, file, ext, mime, p.w, p.h, p.dur, reply, p.createdAt))
            Outbox.schedule()
            showToast("Pas de réseau : ton message partira dès que la connexion reviendra. 🕓")
        } catch (e: Exception) {
            removePending(p)
            showToast("Message non envoyé. Vérifie ta connexion.")
        }
    }

    /** Messages écrits hors connexion, pas encore partis : on les remet dans le fil. */
    private fun restoreOutbox() {
        if (pendings.isNotEmpty()) return
        try {
            for (o in Outbox.all()) {
                val reply = o.reply?.let { r -> runCatching { JSONObject(r).let { Reply(it.optLong("id"), it.optBoolean("by_me"), it.optString("kind"), it.optString("text")) } }.getOrNull() }
                val bytes = if (o.kind == "image" && o.file != null) runCatching { File(o.file).readBytes() }.getOrNull() else null
                pendings.add(ChatMsg(
                    id = 0, tmp = ++tmpCounter, mine = true, kind = o.kind, body = o.body, media = if (o.kind == "gif") o.media else null,
                    w = o.w, h = o.h, dur = o.dur, createdAt = o.createdAt, pending = true,
                    localBytes = bytes, localFile = if (o.kind == "voice" || o.kind == "video") o.file else null, reply = reply,
                ))
            }
        } catch (e: Exception) { /* ignoré */ }
    }

    private fun blank(kind: String, body: String? = null, media: String? = null, w: Int = 0, h: Int = 0, dur: Int = 0) =
        ChatMsg(id = 0, mine = true, kind = kind, body = body, media = media, w = w, h = h, dur = dur, createdAt = 0)

    private fun onceMeta() = JSONObject().put("once", true)

    fun sendText(raw: String, o: SendOpts? = null) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (o != null && o.at > 0) { schedule("text", text, null, null, o); return }
        if (o != null && o.once) {
            viewModelScope.launch {
                val p = addPending(blank("text").copy(meta = onceMeta()))
                onceSecret[p.tmp] = text
                deliver(p, null)
            }
            return
        }
        if (loveRegex.containsMatchIn(text)) onLove?.invoke()
        viewModelScope.launch { deliver(addPending(blank("text", body = text)), null) }
    }

    /** Programme un message : il part tout seul à l'heure choisie. */
    fun schedule(kind: String, body: String?, media: String?, meta: JSONObject?, o: SendOpts, done: () -> Unit = {}) {
        viewModelScope.launch {
            val st = try { Backend.scheduleMessage(kind, body, media, meta, o.once, o.at) } catch (e: Exception) { "net" }
            when (st) {
                "ok" -> showToast("🕐 Programmé pour " + SimpleDateFormat("EEE d MMM 'à' HH:mm", Locale.FRANCE).format(Date(o.at)) + ".")
                "too_many" -> showToast("Trop de messages programmés (100 maximum).")
                "bad_time" -> showToast("Choisis une heure à venir.")
                "no_partner" -> showToast("Vous n'êtes plus partenaires.")
                "net" -> showToast("Connexion impossible. Réessaie.")
                else -> showToast("Impossible de programmer ce message.")
            }
            if (st == "ok") done()
        }
    }

    // ---------- Éphémères : ouverture et état ----------

    fun markOpened(id: Long) {
        val i = confirmed.indexOfFirst { it.id == id }
        if (i < 0 || confirmed[i].opened) return
        confirmed[i] = confirmed[i].copy(meta = JSONObject(confirmed[i].meta?.toString() ?: "{}").put("opened", true))
        publish()
    }

    /** Le destinataire a ouvert un de mes messages éphémères : on le voit ici aussi. */
    private suspend fun syncOnce(): Boolean {
        val waiting = confirmed.filter { it.once && !it.opened && !it.deleted }.takeLast(100).map { it.id }
        if (waiting.isEmpty()) return false
        var any = false
        for (id in Backend.openedAmong(waiting)) {
            val i = confirmed.indexOfFirst { it.id == id }
            if (i >= 0 && !confirmed[i].opened) {
                confirmed[i] = confirmed[i].copy(meta = JSONObject(confirmed[i].meta?.toString() ?: "{}").put("opened", true)); any = true
            }
        }
        return any
    }

    private var lastDeliver = 0L
    private suspend fun tickScheduled() {
        val now = System.currentTimeMillis()
        if (now - lastDeliver < 25000) return
        lastDeliver = now
        Backend.deliverScheduled()
    }

    fun sendSticker(id: String) {
        onSticker?.invoke(id)
        viewModelScope.launch { deliver(addPending(blank("sticker", body = id)), null) }
    }

    fun sendGif(g: Gif) {
        viewModelScope.launch { deliver(addPending(blank("gif", media = g.url, w = g.w, h = g.h)), g.url) }
    }

    // ---------- Photos ----------

    fun onPhotoPicked(context: Context, uri: Uri) {
        viewModelScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                try { ImageUtil.prepare(context, uri) } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
            }
            if (prepared == null) showToast("Impossible de lire cette photo.")
            else _state.update { it.copy(photo = prepared) }
        }
    }

    fun cancelPhoto() = _state.update { it.copy(photo = null) }

    fun sendPhoto(caption: String, o: SendOpts? = null) {
        val ph = _state.value.photo ?: return
        _state.update { it.copy(photo = null) }
        val cap = caption.trim().ifEmpty { null }
        viewModelScope.launch {
            sendPhotoBytes(ph, cap, o)
        }
    }

    /** Photo retouchée (Hot pic / Now pic) : permanente ou éphémère. */
    fun sendEdited(ph: PreparedPhoto, once: Boolean) {
        viewModelScope.launch { sendPhotoBytes(ph, null, if (once) SendOpts(once = true) else null) }
    }

    /** Envoie une photo déjà prête (normale, éphémère ou programmée). */
    suspend fun sendPhotoBytes(ph: PreparedPhoto, cap: String?, o: SendOpts?, nowpic: Boolean = false) {
        if (o != null && o.at > 0) {
            showToast("Préparation…")
            try {
                val path = Backend.upload(ph.bytes, "jpg", "image/jpeg")
                schedule("image", cap, path, JSONObject().put("w", ph.w).put("h", ph.h), o)
            } catch (e: Exception) { showToast("Photo non programmée. Vérifie ta connexion.") }
            return
        }
        run {
            val once = o != null && o.once
            val p = if (once) addPending(blank("image").copy(meta = onceMeta())) else addPending(blank("image", body = cap, w = ph.w, h = ph.h).copy(localBytes = ph.bytes))
            if (once && cap != null) onceSecret[p.tmp] = cap
            try {
                val path = Backend.upload(ph.bytes, "jpg", "image/jpeg")
                deliver(p, path)
            } catch (e: Exception) {
                if (once) { removePending(p); showToast("Photo non envoyée. Vérifie ta connexion.") }
                else queueOffline(p, null, bytes = ph.bytes, ext = "jpg", mime = "image/jpeg")
            }
        }
    }

    // ---------- Vidéos ----------

    private val maxVideo = 50L * 1024 * 1024

    private fun videoInfo(file: File): Triple<Int, Int, Int> {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            var w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val d = ((r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000).toInt()
            Triple(w, h, d)
        } catch (e: Exception) {
            Triple(0, 0, 0)
        } finally {
            try { r.release() } catch (e: Exception) { /* ignoré */ }
        }
    }

    /** Envoie une vidéo déjà enregistrée dans un fichier (caméra ou galerie). */
    fun sendVideoFile(file: File, mime: String = "video/mp4", o: SendOpts? = null) {
        viewModelScope.launch {
            if (!file.exists() || file.length() == 0L) { showToast("Vidéo illisible."); return@launch }
            if (file.length() > maxVideo) { showToast("Vidéo trop lourde (50 Mo maximum)."); return@launch }
            val (w, h, d) = withContext(Dispatchers.IO) { videoInfo(file) }
            val ext = when {
                mime.contains("quicktime") -> "mov"
                mime.contains("webm") -> "webm"
                mime.contains("3gpp") -> "3gp"
                else -> "mp4"
            }
            val type = when (ext) { "mov" -> "video/quicktime"; "webm" -> "video/webm"; "3gp" -> "video/3gpp"; else -> "video/mp4" }
            if (o != null && o.at > 0) {
                showToast("Préparation…")
                try {
                    val path = Backend.uploadFile(file, ext, type)
                    val vm = JSONObject(); if (w > 0) vm.put("w", w); if (h > 0) vm.put("h", h); if (d > 0) vm.put("d", d)
                    schedule("video", null, path, if (vm.length() == 0) null else vm, o)
                } catch (e: Exception) { showToast("Vidéo non programmée. Vérifie ta connexion.") }
                return@launch
            }
            val once = o != null && o.once
            val p = if (once) addPending(blank("video", w = w, h = h, dur = d).copy(meta = onceMeta())) else addPending(blank("video", w = w, h = h, dur = d).copy(localFile = file.absolutePath))
            try {
                val path = Backend.uploadFile(file, ext, type)
                deliver(p, path)
            } catch (e: Exception) {
                if (once) { removePending(p); showToast("Vidéo non envoyée. Vérifie ta connexion.") }
                else queueOffline(p, null, src = file, ext = ext, mime = type)
            }
        }
    }

    /** Vidéo choisie dans la galerie : on la copie d'abord dans le cache de l'appli. */
    fun onVideoPicked(context: Context, uri: Uri, mime: String, ready: (File, String) -> Unit) {
        viewModelScope.launch {
            val f = withContext(Dispatchers.IO) {
                try {
                    val dir = File(context.cacheDir, "cam").apply { mkdirs() }
                    val out = File.createTempFile("vid", ".bin", dir)
                    context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                    out
                } catch (e: Exception) { null }
            }
            if (f == null) showToast("Impossible de lire cette vidéo.") else ready(f, mime)
        }
    }

    // ---------- Vocaux ----------

    fun sendVoice(file: File, seconds: Int) {
        viewModelScope.launch {
            val p = addPending(blank("voice", dur = seconds).copy(localFile = file.absolutePath))
            try {
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                val path = Backend.upload(bytes, "m4a", "audio/mp4")
                deliver(p, path)
            } catch (e: Exception) {
                queueOffline(p, null, src = file, ext = "m4a", mime = "audio/mp4")
            }
        }
    }

    // ---------- GIF ----------

    fun searchGifs(query: String) {
        gifQuery = query.trim()
        gifOffset = 0
        gifDone = false
        gifSeq++
        _state.update { it.copy(gifs = emptyList(), gifError = false, gifLoading = false) }
        moreGifs()
    }

    fun moreGifs() {
        if (gifDone || _state.value.gifLoading) return
        val seq = gifSeq
        _state.update { it.copy(gifLoading = true) }
        viewModelScope.launch {
            try {
                val page = Backend.searchGifs(gifQuery, gifOffset)
                if (seq != gifSeq) return@launch
                if (page.items.isEmpty()) gifDone = true
                gifOffset = page.next
                _state.update { it.copy(gifs = it.gifs + page.items, gifLoading = false, gifError = false) }
            } catch (e: Exception) {
                if (seq == gifSeq) _state.update { it.copy(gifLoading = false, gifError = true) }
            }
        }
    }
}
