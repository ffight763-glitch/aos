package org.anthezis.launcher

import android.service.notification.NotificationListenerService

/** Gives the Control Center read access to the phone's notifications, so the stock shade is not needed. */
class NotifService : NotificationListenerService() {
    // The listener runs whenever notification access is on, so it also brings up the AOS lock screen.
    override fun onListenerConnected() { instance = this; LockScreen.register(this); onChange?.invoke() }
    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        LockScreen.unregister(this)
        onChange?.invoke()
        requestRebind(android.content.ComponentName(this, NotifService::class.java))
    }
    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        sbn?.let { runCatching { AppCalls.record(this, it) } }
        onChange?.invoke(); onLockChange?.invoke()
    }
    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) { onChange?.invoke(); onLockChange?.invoke() }

    fun visibleNotifications(): List<android.service.notification.StatusBarNotification> {
        val active = runCatching { activeNotifications.toList() }.getOrDefault(emptyList())
        val childGroups = active.filter { it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0 }.map { it.groupKey }.toSet()
        return active.filter {
            it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0 || it.groupKey !in childGroups
        }.sortedByDescending { it.postTime }
    }

    companion object {
        @Volatile var instance: NotifService? = null
        /** Set by the home screen while it is visible, so the top bar can show the notification icons. */
        @Volatile var onChange: (() -> Unit)? = null
        /** Set by the lock screen while it is shown. */
        @Volatile var onLockChange: (() -> Unit)? = null
    }
}

/**
 * Calls from messengers (Telegram, WhatsApp, Viber...) never reach the call log of the phone. Their call notifications are
 * recorded here, so the AOS dialer can list them next to the normal calls.
 */
object AppCalls {
    private val messengers = setOf(
        "org.telegram.messenger", "org.telegram.messenger.web", "org.anthezis.atg", "org.anthezis.atg.beta", "org.thunderdog.challegram",
        "com.whatsapp", "com.whatsapp.w4b", "com.viber.voip", "com.facebook.orca", "com.skype.raider", "com.discord", "org.thoughtcrime.securesms",
        "com.google.android.apps.tachyon", "jp.naver.line.android", "com.microsoft.teams", "us.zoom.videomeetings", "com.instagram.android",
        "com.imo.android.imoim", "com.kakao.talk", "com.wire", "im.vector.app")
    private val phoneApps = setOf("com.android.server.telecom", "com.android.dialer", "com.google.android.dialer", "com.android.incallui", "com.samsung.android.incallui")
    private val missedWords = Regex("(?i)missed|пропущ|непринят|не прийнят")
    private val callWords = Regex("(?i)call|звон|дзвін|виклик")

    class Call(val pkg: String, val name: String, val time: Long, val missed: Boolean)

    private fun prefs(c: android.content.Context) = c.getSharedPreferences("aos_app_calls", android.content.Context.MODE_PRIVATE)

    fun all(c: android.content.Context): List<Call> {
        val a = runCatching { org.json.JSONArray(prefs(c).getString("calls", "[]")) }.getOrDefault(org.json.JSONArray())
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Call(it.getString("p"), it.getString("n"), it.getLong("t"), it.optBoolean("m")) }
    }

    fun record(c: android.content.Context, sbn: android.service.notification.StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == c.packageName || pkg in phoneApps) return
        val n = sbn.notification
        val ex = n.extras
        val title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
        val missedCat = n.category == android.app.Notification.CATEGORY_MISSED_CALL
        val callCat = n.category == android.app.Notification.CATEGORY_CALL
        val missedText = pkg in messengers && (missedWords.containsMatchIn(title) || missedWords.containsMatchIn(text)) && callWords.containsMatchIn("$title $text")
        if (!callCat && !missedCat && !missedText) return
        val missed = missedCat || missedText
        // A missed-call notification is often titled "Missed call" with the caller in the text; a ringing one has the caller as title.
        val name = (if (missed && missedWords.containsMatchIn(title)) text else title).ifBlank {
            runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }.take(60)
        val list = all(c).toMutableList()
        // A ringing call updates its notification again and again: one entry per caller per three minutes.
        val i = list.indexOfFirst { it.pkg == pkg && it.name == name && sbn.postTime - it.time < 180_000 }
        if (i >= 0) { if (missed && !list[i].missed) list[i] = Call(pkg, name, list[i].time, true) else return }
        else list.add(0, Call(pkg, name, sbn.postTime, missed))
        val a = org.json.JSONArray()
        list.take(80).forEach { a.put(org.json.JSONObject().put("p", it.pkg).put("n", it.name).put("t", it.time).put("m", it.missed)) }
        prefs(c).edit().putString("calls", a.toString()).apply()
    }
}
