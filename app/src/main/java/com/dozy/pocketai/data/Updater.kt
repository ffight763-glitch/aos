package com.dozy.pocketai.data

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.dozy.pocketai.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val versionName: String, val versionCode: Int, val notes: String, val apkUrl: String, val sizeMb: Long)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Ready(val info: UpdateInfo, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Over-the-air updates: reads GitHub Releases of [BuildConfig.UPDATE_REPO]
 * (tags `pocketai-v0.1.N`, N = versionCode), downloads the newer APK and
 * hands it to the system installer. Same signing key → installs on top.
 */
class Updater(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val dm = context.getSystemService(DownloadManager::class.java)
    private val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "updates").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private var dismissedCode = 0

    val currentVersion get() = BuildConfig.VERSION_NAME

    init {
        // leftovers of an installed update
        dir.listFiles()?.forEach { it.delete() }
    }

    fun check(silent: Boolean = false) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        scope.launch {
            if (!silent) _state.value = UpdateState.Checking
            val r = runCatching { fetchLatest() }
            _state.value = r.fold(
                onSuccess = { info ->
                    when {
                        info == null || info.versionCode <= BuildConfig.VERSION_CODE -> if (silent) UpdateState.Idle else UpdateState.UpToDate
                        silent && info.versionCode == dismissedCode -> UpdateState.Idle
                        else -> UpdateState.Available(info)
                    }
                },
                onFailure = { if (silent) UpdateState.Idle else UpdateState.Failed("Не удалось проверить обновления: ${it.message}") },
            )
        }
    }

    fun dismiss() {
        (_state.value as? UpdateState.Available)?.let { dismissedCode = it.info.versionCode }
        _state.value = UpdateState.Idle
    }

    private suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=30")
            .openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "PocketAI/${BuildConfig.VERSION_NAME}")
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        json.parseToJsonElement(body).jsonArray
            .map { it.jsonObject }
            .filter { !(it["draft"]?.jsonPrimitive?.boolean ?: false) }
            .mapNotNull { parse(it) }
            .maxByOrNull { it.versionCode }
    }

    private fun parse(r: JsonObject): UpdateInfo? {
        val tag = r["tag_name"]?.jsonPrimitive?.content ?: return null
        val m = Regex("^pocketai-v(\\d+)\\.(\\d+)\\.(\\d+)$").find(tag) ?: return null
        val asset = r["assets"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true } ?: return null
        val notes = r["body"]?.jsonPrimitive?.content.orEmpty()
        return UpdateInfo(
            versionName = "${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3]}",
            versionCode = m.groupValues[3].toInt(),
            notes = notes,
            apkUrl = asset["browser_download_url"]!!.jsonPrimitive.content,
            sizeMb = (asset["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0) shr 20,
        )
    }

    fun download(info: UpdateInfo) {
        val file = File(dir, "PocketAI-${info.versionName}.apk").apply { delete() }
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(info.apkUrl))
                .setTitle("PocketAI ${info.versionName}")
                .setDescription("Обновление")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(file)),
        )
        _state.value = UpdateState.Downloading(info, 0f)
        scope.launch(Dispatchers.IO) {
            while (true) {
                val c = dm.query(DownloadManager.Query().setFilterById(id)) ?: break
                val (status, done, total) = c.use {
                    if (!it.moveToFirst()) Triple(DownloadManager.STATUS_FAILED, 0L, 0L)
                    else Triple(
                        it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                    )
                }
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        _state.value = UpdateState.Ready(info, file)
                        withContext(Dispatchers.Main) { install(file) }
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED -> {
                        _state.value = UpdateState.Failed("Загрузка обновления не удалась")
                        return@launch
                    }
                    else -> _state.value = UpdateState.Downloading(info, if (total > 0) done.toFloat() / total else 0f)
                }
                delay(500)
            }
        }
    }

    /** Opens the system installer (asks once for "install unknown apps" permission). */
    fun install(file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
