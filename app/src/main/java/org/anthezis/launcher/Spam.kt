package org.anthezis.launcher

import android.content.Context
import android.telecom.Call
import android.telecom.CallScreeningService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Spam numbers: the user's own block list plus a list downloaded from GitHub (one entry per line:
 * "380671234567|Label", a 9-digit national number, or a prefix ending in "*" such as "38044*").
 * Lines starting with "#" are comments. The list location can be changed in the "aos_spam" preferences ("url").
 */
object SpamList {
    // Our own list first, then the public Ukrainian scammer blacklist from github.com/krlabs/ukrainian_phone_numbers_blacklist
    // (markdown lines like: ``+380443585751`` - телефонні аферисти). Both are merged into one local cache.
    private val DEFAULT_URLS = listOf(
        "https://raw.githubusercontent.com/dozyFJ/ahome-updates/main/spam.txt",
        "https://raw.githubusercontent.com/krlabs/ukrainian_phone_numbers_blacklist/main/README.md")
    private val ENTRY = Regex("""^(\+?\d[\d ]{3,19}\*?)\s*(?:[-|]\s*(.*))?$""")

    private fun prefs(c: Context) = c.getSharedPreferences("aos_spam", Context.MODE_PRIVATE)
    private fun cache(c: Context) = File(c.filesDir, "spam.txt")

    private fun digits(n: String) = n.filter { it.isDigit() }
    /** Last nine digits identify a Ukrainian number regardless of +380 / 380 / 0 prefix. */
    private fun national(n: String) = digits(n).let { if (it.length > 9) it.takeLast(9) else it }
    private fun full(n: String) = digits(n).let { if (it.length == 10 && it.startsWith("0")) "38$it" else it }

    fun userBlocked(c: Context): Set<String> = prefs(c).getStringSet("blocked", emptySet()).orEmpty()
    fun isUserBlocked(c: Context, number: String) = national(number) in userBlocked(c)
    fun block(c: Context, number: String) = prefs(c).edit().putStringSet("blocked", userBlocked(c) + national(number)).apply()
    fun unblock(c: Context, number: String) = prefs(c).edit().putStringSet("blocked", userBlocked(c) - national(number)).apply()

    /** Hard mode: calls with a hidden number (no caller ID at all) are rejected as well. */
    fun hardMode(c: Context) = prefs(c).getBoolean("hard", false)
    fun setHardMode(c: Context, on: Boolean) = prefs(c).edit().putBoolean("hard", on).apply()
    fun entryCount(c: Context): Int = cache(c).takeIf { it.exists() }?.readLines()?.count { ENTRY.matches(it.replace("`", "").replace("**", "").trim()) } ?: 0

    /** The label for a spam number, or null when the number is fine. */
    fun lookup(c: Context, number: String): String? {
        if (number.isBlank()) return null
        if (isUserBlocked(c, number)) return "Blocked"
        val nat = national(number); val fullDigits = full(number)
        val file = cache(c)
        if (!file.exists()) return null
        for (raw in file.readLines()) {
            val line = raw.replace("`", "").replace("**", "").trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val m = ENTRY.matchEntire(line) ?: continue
            val key = m.groupValues[1].replace(" ", "")
            val label = m.groupValues[2].trim().ifBlank { "Spam" }
            if (key.endsWith("*")) { if (fullDigits.startsWith(digits(key))) return label }
            else if (national(key) == nat) return label
        }
        return null
    }

    /** Downloads the list at most every 12 hours; keeps the old copy when the network is down. */
    fun refresh(c: Context, force: Boolean = false) {
        val p = prefs(c)
        if (!force && System.currentTimeMillis() - p.getLong("fetched", 0) < 12 * 3600_000L) return
        val app = c.applicationContext
        Thread {
            val urls = p.getString("url", null)?.let { listOf(it) } ?: DEFAULT_URLS
            val merged = StringBuilder()
            for (u in urls) try {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 10000
                if (conn.responseCode == 200) merged.append(conn.inputStream.bufferedReader().readText()).append('\n')
            } catch (_: Exception) { }
            if (merged.isNotEmpty()) {
                cache(app).writeText(merged.toString())
                p.edit().putLong("fetched", System.currentTimeMillis()).apply()
            }
        }.start()
    }
}

/** Rejects calls from numbers on the spam lists before the phone rings. Needs the user to pick AOS as call screening app. */
class SpamScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val number = details.handle?.schemeSpecificPart.orEmpty()
        val spam = SpamList.lookup(this, number) ?: if (number.isBlank() && SpamList.hardMode(this)) "Hidden number" else null
        val response = CallResponse.Builder()
        if (spam != null && details.callDirection == Call.Details.DIRECTION_INCOMING) {
            response.setDisallowCall(true).setRejectCall(true).setSkipNotification(true)
        }
        respondToCall(details, response.build())
    }
}
