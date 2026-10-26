package com.tumemanque.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
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

    private fun fetch(): UpdateInfo? {
        val conn = URL(Config.WEB_URL + "update.json?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
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

    /** Vérifie s'il existe une version plus récente que celle installée. */
    fun check(force: Boolean = true) {
        if (!force && started) return
        started = true
        if (_state.value.checking || _state.value.downloading) return
        _state.update { it.copy(checking = true, error = false) }
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { fetch() }
                val newer = if (info != null && info.code > BuildConfig.VERSION_CODE) info else null
                _state.update { it.copy(checking = false, checked = true, available = newer, error = false, ready = if (newer == null) false else it.ready) }
            } catch (e: Exception) {
                _state.update { it.copy(checking = false, checked = true, error = true) }
            }
        }
    }

    /** Télécharge la nouvelle version, puis lance l'installation. */
    fun update(context: Context) {
        val info = _state.value.available ?: return
        if (_state.value.downloading) return
        _state.update { it.copy(downloading = true, progress = 0f, error = false, ready = false, needPermission = false) }
        val app = context.applicationContext
        viewModelScope.launch {
            try {
                val f = withContext(Dispatchers.IO) { download(app, info) }
                apkFile = f
                _state.update { it.copy(downloading = false, ready = true) }
                install(app)
            } catch (e: Exception) {
                _state.update { it.copy(downloading = false, error = true) }
            }
        }
    }

    private fun download(context: Context, info: UpdateInfo): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "tu-me-manque.apk")
        val conn = URL(info.apk).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                _state.update { it.copy(progress = pct / 100f) }
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (out.length() < 1_000_000) throw IOException("fichier incomplet")
        return out
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
