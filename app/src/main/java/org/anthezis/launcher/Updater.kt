package org.anthezis.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Prototype update channel, so the phone never has to be re-flashed or re-installed by hand: AOS Home asks the update
 * server for latest.json, compares the version code and, with the user's confirmation, installs the new APK through
 * PackageInstaller. The firmware build replaces this with the system OTA (update_engine, A/B).
 * The server is tools/serve_updates.py; over USB it is reached as 127.0.0.1:8000 via `adb reverse`.
 */
object Updater {
    // Public GitHub repo that tools/publish_github.cmd pushes to, so updates arrive over mobile internet too.
    // Can be changed in Manager > Updates (e.g. to a local server for testing).
    private const val DEFAULT_URL = "https://raw.githubusercontent.com/dozyFJ/ahome-updates/main"

    fun setServerUrl(c: Context, url: String) {
        val clean = url.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
        c.getSharedPreferences("aos_update", Context.MODE_PRIVATE).edit().putString("url", clean).apply()
    }

    fun serverUrl(c: Context): String =
        c.getSharedPreferences("aos_update", Context.MODE_PRIVATE).getString("url", DEFAULT_URL).orEmpty()

    fun currentVersion(c: Context): Pair<Long, String> {
        val info = c.packageManager.getPackageInfo(c.packageName, 0)
        return info.longVersionCode to (info.versionName ?: "?")
    }

    /** Checks the server; [manual] also reports "up to date" and errors, the automatic check stays silent. */
    fun check(act: Activity, manual: Boolean) {
        act.getSharedPreferences("aos_update", Context.MODE_PRIVATE).edit().putLong("checked_at", System.currentTimeMillis()).apply()
        Thread {
            try {
                val conn = URL(serverUrl(act) + "/latest.json").openConnection() as HttpURLConnection
                conn.connectTimeout = 4000; conn.readTimeout = 6000
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val code = json.getLong("versionCode")
                val (have, haveName) = currentVersion(act)
                act.runOnUiThread {
                    if (code > have) offer(act, json)
                    else if (manual) Toast.makeText(act, "AOS is up to date ($haveName)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (manual) act.runOnUiThread { Toast.makeText(act, "Update server unreachable: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun offer(act: Activity, json: JSONObject) {
        val notes = json.optString("notes", "")
        AlertDialog.Builder(act).setTitle("AOS update ${json.optString("versionName")}")
            .setMessage(if (notes.isBlank()) "A new build is available." else notes)
            .setPositiveButton("Install") { _, _ -> download(act, json.getString("apk")) }
            .setNegativeButton("Later", null).show()
    }

    private fun download(act: Activity, apk: String) {
        if (!act.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(act, "Allow AOS Home to install updates, then try again", Toast.LENGTH_LONG).show()
            act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
            return
        }
        Toast.makeText(act, "Downloading update…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val file = File(act.cacheDir, "aos-update.apk")
                val conn = URL(serverUrl(act) + "/" + apk).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 30000
                conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                install(act, file)
            } catch (e: Exception) {
                act.runOnUiThread { Toast.makeText(act, "Update failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun install(act: Activity, file: File) {
        val installer = act.packageManager.packageInstaller
        val id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        installer.openSession(id).use { session ->
            session.openWrite("aos", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val pending = PendingIntent.getBroadcast(act, id, Intent(act, UpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }
}

/** Receives the installer's result and shows the system confirmation when Android asks for it. */
class UpdateReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> Toast.makeText(c, "Update not installed: ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}", Toast.LENGTH_LONG).show()
        }
    }
}
