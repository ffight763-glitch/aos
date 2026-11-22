package pro.translate.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

/**
 * OTA updates from GitHub Releases. Temporarily built and hosted in ffight763-glitch/aos
 * (branch translatepro) with tags "translatepro-v<versionName>"; that repo also has other
 * apps' releases, so we pick the newest one with our tag prefix.
 */
object Updater {
    private const val REPO = "ffight763-glitch/aos"
    private const val TAG_PREFIX = "translatepro-v"
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=50"

    val current: String get() = BuildConfig.VERSION_NAME

    /** Newer release, or null if up to date. Throws on network errors. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "translatePro/$current")
            connectTimeout = 10_000; readTimeout = 15_000
        }
        try {
            if (c.responseCode == 404) return@withContext null // no releases yet
            if (c.responseCode != 200) error("GitHub ответил ${c.responseCode}")
            val all = JSONArray(c.inputStream.bufferedReader().readText())
            var j: JSONObject? = null
            var version = current
            for (i in 0 until all.length()) {
                val r = all.getJSONObject(i)
                val tag = r.getString("tag_name")
                if (r.optBoolean("draft") || !tag.startsWith(TAG_PREFIX)) continue
                val v = tag.removePrefix(TAG_PREFIX)
                if (compare(v, version) > 0) { version = v; j = r }
            }
            if (j == null) return@withContext null
            val assets = j.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    return@withContext Release(version, j.optString("body").trim(),
                        a.getString("browser_download_url"), a.optLong("size"))
                }
            }
            null
        } finally { c.disconnect() }
    }

    /** Downloads the APK into cache; [progress] gets 0..1. */
    suspend fun download(ctx: Context, r: Release, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "translatePro-${r.version}.apk")
        var url = URL(r.apkUrl)
        var c: HttpURLConnection
        var hops = 0
        while (true) { // follow redirects (github.com -> objects.githubusercontent.com)
            c = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "translatePro/$current")
                connectTimeout = 15_000; readTimeout = 30_000
            }
            val code = c.responseCode
            if (code in 300..399 && hops++ < 5) { url = URL(url, c.getHeaderField("Location")); c.disconnect(); continue }
            if (code != 200) error("Загрузка: код $code")
            break
        }
        val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
        c.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L; var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    o.write(buf, 0, n); done += n
                    if (total > 0) withContext(Dispatchers.Main) { progress(done.toFloat() / total) }
                }
            }
        }
        c.disconnect()
        out
    }

    fun canInstall(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Compares dotted versions numerically: 1.10 > 1.9. */
    fun compare(a: String, b: String): Int {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}
