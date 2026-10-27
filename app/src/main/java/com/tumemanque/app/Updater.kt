package com.tumemanque.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.DownloadManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Une version proposée par le fichier update.json (publié seulement quand Claude « rend la mise à jour disponible »). */
data class UpdateInfo(val code: Int, val name: String, val notes: String, val apk: String)

data class UpdateState(
    val checking: Boolean = false,
    val checked: Boolean = false,
    val available: UpdateInfo? = null,
    val error: Boolean = false,
    val errorDetail: String = "",
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val ready: Boolean = false,
    val needPermission: Boolean = false,
)

class UpdateViewModel : ViewModel() {
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var apkFile: File? = null
    private var started = false

    private fun fetchOne(url: String): UpdateInfo? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.useCaches = false
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val code = j.optInt("versionCode", 0)
            val apk = j.optString("apk", "")
            if (code <= 0 || !apk.startsWith("https://github.com/stephaneproject237-spec/tu-me-manque/")) return null
            return UpdateInfo(code, j.optString("versionName", "1.$code"), j.optString("notes", ""), apk)
        } finally {
            conn.disconnect()
        }
    }

    /** Interroge plusieurs adresses en même temps (au cas où l'une serait bloquée sur ce réseau) : la première qui répond gagne. */
    private suspend fun fetch(): UpdateInfo? = coroutineScope {
        val t = System.currentTimeMillis()
        val sources = listOf(
            Config.WEB_URL + "update.json?t=$t",
            "https://raw.githubusercontent.com/stephaneproject237-spec/tu-me-manque/main/web/update.json?t=$t",
        )
        val result = CompletableDeferred<UpdateInfo?>()
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val jobs = sources.map { url ->
            launch(Dispatchers.IO) {
                try {
                    result.complete(fetchOne(url))
                } catch (e: Exception) {
                    val host = url.substringAfter("://").substringBefore("/")
                    errors.add("$host : ${e.javaClass.simpleName}${e.message?.let { m -> " ($m)".take(60) } ?: ""}")
                    if (errors.size == sources.size) result.completeExceptionally(IOException(errors.joinToString("\n")))
                }
            }
        }
        try { result.await() } finally { jobs.forEach { it.cancel() } }
    }

    /** Vérifie s'il existe une version plus récente que celle installée. */
    fun check(force: Boolean = true, context: Context? = null) {
        if (!force && started) return
        started = true
        if (_state.value.checking || _state.value.downloading) return
        _state.update { it.copy(checking = true, error = false) }
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { fetch() }
                val newer = if (info != null && info.code > BuildConfig.VERSION_CODE) info else null
                _state.update { it.copy(checking = false, checked = true, available = newer, error = false, errorDetail = "", ready = if (newer == null) false else it.ready) }
                if (newer != null && context != null && !_state.value.downloading && !_state.value.ready) resume(context.applicationContext, newer)
            } catch (e: Exception) {
                _state.update { it.copy(checking = false, checked = true, error = true, errorDetail = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun prefs(c: Context) = c.getSharedPreferences("upd", Context.MODE_PRIVATE)
    private fun dlDir(c: Context): File = (c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(c.filesDir, "dl")).apply { mkdirs() }

    /** Reprend une mise à jour déjà (en cours de) téléchargée, même si l'appli a été fermée entre-temps. */
    private fun resume(app: Context, newer: UpdateInfo) {
        val pr = prefs(app)
        if (pr.getInt("code", 0) != newer.code) return
        val f = File(pr.getString("file", "") ?: "")
        val id = pr.getLong("id", -1)
        if (f.exists() && f.length() > 1_000_000 && statusOf(app, id) != DownloadManager.STATUS_RUNNING) {
            apkFile = f; _state.update { it.copy(downloading = false, ready = true) }
        } else if (id >= 0 && statusOf(app, id).let { it == DownloadManager.STATUS_RUNNING || it == DownloadManager.STATUS_PENDING || it == DownloadManager.STATUS_PAUSED }) {
            apkFile = f; _state.update { it.copy(downloading = true) }; track(app, id)
        }
    }

    private fun statusOf(app: Context, id: Long): Int {
        if (id < 0) return -1
        val dm = app.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c -> if (c.moveToFirst()) return c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) }
        return -1
    }

    /** Suit le téléchargement du système (il continue même si l'appli est fermée). */
    private fun track(app: Context, id: Long) {
        viewModelScope.launch {
            val dm = app.getSystemService(DownloadManager::class.java)
            while (true) {
                var st = -1; var done = 0L; var total = 0L
                dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                    if (c.moveToFirst()) {
                        st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    }
                }
                when (st) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        _state.update { it.copy(downloading = false, ready = true, progress = 1f) }
                        runCatching { install(app) }
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED, -1 -> {
                        _state.update { it.copy(downloading = false, error = true, errorDetail = "Téléchargement interrompu. Réessaie.") }
                        return@launch
                    }
                    else -> if (total > 0) _state.update { it.copy(progress = (done.toFloat() / total).coerceIn(0f, 1f)) }
                }
                kotlinx.coroutines.delay(500)
            }
        }
    }

    /** Télécharge la nouvelle version avec le gestionnaire de téléchargements d'Android : ça continue en arrière-plan. */
    fun update(context: Context) {
        val info = _state.value.available ?: return
        if (_state.value.downloading) return
        val app = context.applicationContext
        try {
            val dm = app.getSystemService(DownloadManager::class.java)
            val old = prefs(app).getLong("id", -1)
            if (old >= 0) runCatching { dm.remove(old) }
            val dir = dlDir(app)
            dir.listFiles()?.forEach { it.delete() }
            val f = File(dir, "tu-me-manque-${info.code}.apk")
            val req = DownloadManager.Request(Uri.parse(info.apk))
                .setTitle("Tu me manques ${info.name}")
                .setDescription("Téléchargement de la mise à jour")
                .setDestinationUri(Uri.fromFile(f))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(true).setAllowedOverRoaming(true)
            val id = dm.enqueue(req)
            prefs(app).edit().putLong("id", id).putString("file", f.path).putInt("code", info.code).apply()
            apkFile = f
            _state.update { it.copy(downloading = true, progress = 0f, error = false, ready = false, needPermission = false) }
            track(app, id)
        } catch (e: Exception) {
            _state.update { it.copy(downloading = false, error = true, errorDetail = "Téléchargement : " + (e.message ?: e.javaClass.simpleName)) }
        }
    }

    /** Ouvre l'installateur d'Android. Si l'autorisation manque, ouvre le réglage correspondant. */
    fun install(context: Context) {
        val f = apkFile ?: return
        if (!f.exists()) { _state.update { it.copy(ready = false) }; return }
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            _state.update { it.copy(needPermission = true, ready = true) }
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        _state.update { it.copy(needPermission = false) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
