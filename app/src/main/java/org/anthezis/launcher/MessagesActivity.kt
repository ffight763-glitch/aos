package org.anthezis.launcher

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

/**
 * AOS Messages, built like AOS Phone: a glass header with round buttons, filter pills, rows with an initial, the name and the
 * last message, a round "new message" button and a bottom bar of icon + title tabs (Chats, Contacts). Reads the SMS store and
 * sends through SmsManager; the system keeps receiving messages as before.
 */
class MessagesActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var root: LinearLayout
    private lateinit var body: FrameLayout
    private lateinit var tabs: LinearLayout
    private var tab = 0
    private var filter = 0
    private var thread = -1L
    private var address = ""
    private val tabDefs = listOf("Chats" to R.drawable.ic_chat, "Contacts" to R.drawable.ic_person)
    /** Edit mode of the chat list (like the desktop's): hold a chat to select it, then pin, mark read or delete. */
    private val selected = LinkedHashSet<Long>()
    private fun prefs() = getSharedPreferences("aos_setup", MODE_PRIVATE)
    private fun idSet(key: String) = prefs().getString(key, "").orEmpty().split(",").mapNotNull { it.toLongOrNull() }.toMutableSet()
    private fun saveIds(key: String, ids: Set<Long>) = prefs().edit().putString(key, ids.joinToString(",")).apply()
    private fun isDefaultSms() = Telephony.Sms.getDefaultSmsPackage(this) == packageName

    private fun text(t: String, sp: Float, c: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(c); typeface = appFont(if (bold) 700 else 400)
    }

    private fun has(perm: String) = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Palette(this)
        body = FrameLayout(this)
        tabs = LinearLayout(this).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), dp(6)); background = glassShape(p, 34)
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(44), dp(16), dp(60)); setBackgroundColor(p.glassScreen)
            addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        setContentView(root)
        addAosTopBar(p)
        val need = listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS)
            .filter { !has(it) }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
        show(0)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = refresh()
    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() { if (thread < 0 && address.isEmpty()) show(tab) else showThread() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            selected.isNotEmpty() -> { selected.clear(); show(tab) }
            thread >= 0 || address.isNotEmpty() -> { thread = -1; address = ""; show(tab) }
            else -> super.onBackPressed()
        }
    }

    private class Item(val thread: Long, val address: String, val body: String, val outgoing: Boolean, val date: Long = 0, val read: Boolean = true)

    private fun load(where: String?, args: Array<String>?, order: String): List<Item> {
        if (!has(Manifest.permission.READ_SMS)) return emptyList()
        val out = mutableListOf<Item>()
        try {
            contentResolver.query(Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.TYPE, Telephony.Sms.DATE, Telephony.Sms.READ),
                where, args, order)?.use { c ->
                while (c.moveToNext()) out += Item(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(),
                    c.getInt(3) != Telephony.Sms.MESSAGE_TYPE_INBOX, c.getLong(4), c.getInt(5) != 0)
            }
        } catch (_: Exception) { }
        return out
    }

    private fun contactName(num: String): String {
        if (num.isBlank() || !has(Manifest.permission.READ_CONTACTS)) return num
        return try {
            contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: num
        } catch (_: Exception) { num }
    }

    private fun show(t: Int) {
        tab = t; thread = -1; address = ""
        // The conversation screen hides the tabs; the lists bring them back.
        tabs.visibility = View.VISIBLE
        tabs.removeAllViews()
        tabDefs.forEachIndexed { i, (name, icon) ->
            val on = i == t
            val c = if (on) p.onAccent else p.sub
            tabs.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(4), dp(6), dp(4), dp(5))
                background = if (on) shape(p.accent, 22) else null
                addView(ImageView(this@MessagesActivity).apply { setImageResource(icon); setColorFilter(c) }, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(text(name, 10f, c, on).apply { gravity = Gravity.CENTER; maxLines = 1; setPadding(0, dp(2), 0, 0) })
                setOnClickListener { show(i) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
        }
        body.removeAllViews()
        body.addView(if (t == 0) chats() else contacts())
        // Round "new message" button over the list, like the keypad button of Phone.
        body.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chat); setColorFilter(p.onAccent); setPadding(dp(14), dp(14), dp(14), dp(14))
            background = circle(p.accent); elevation = dp(6).toFloat(); contentDescription = "New message"
            setOnClickListener { newMessage() }; pressFx(p)
        }, FrameLayout.LayoutParams(dp(50), dp(50), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(2), dp(4)) })
    }

    /** Header like Phone's: a glass bar with the title and round icon buttons. */
    private fun header(title: String, col: LinearLayout) {
        fun round(icon: Int, onClick: () -> Unit) = ImageView(this).apply {
            setImageResource(icon); setColorFilter(p.text); setPadding(dp(10), dp(10), dp(10), dp(10)); background = circle(p.glassCircle); setOnClickListener { onClick() }
        }
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = glassShape(p, 30, true); setPadding(dp(20), dp(8), dp(10), dp(8))
            addView(text(title, 22f, p.text, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(round(R.drawable.ic_chat) { newMessage() }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(6) })
            addView(round(R.drawable.ic_close) { filter = 0; show(tab) }, LinearLayout.LayoutParams(dp(42), dp(42)))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    /** One row: the initial on an accent circle, the title and a line under it, an action icon on the right. */
    private fun row(col: LinearLayout, title: String, sub: String, highlight: Boolean, sel: Boolean = false, pinned: Boolean = false,
                    onLong: (() -> Unit)? = null, onClick: () -> Unit) {
        val initial = if (sel) "✓" else title.firstOrNull { it.isLetterOrDigit() || it == '+' }?.uppercaseChar()?.toString() ?: "#"
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
            background = if (sel) shape((p.accent and 0xFFFFFF) or 0x55000000, 18, p.accent, 2) else shape(p.surfaceHigh, 18)
            addView(text(initial, 16f, p.onAccent, true).apply { gravity = Gravity.CENTER; background = circle(p.accent) }, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(LinearLayout(this@MessagesActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text((if (pinned) "📌 " else "") + title, 16f, p.text, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(text(sub, 12f, if (highlight) p.accent else p.sub, highlight).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            addView(ImageView(this@MessagesActivity).apply {
                setImageResource(R.drawable.ic_chat); setColorFilter(p.accent); setPadding(dp(8), dp(8), dp(8), dp(8))
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            setOnClickListener { onClick() }; pressFx(p, round = false)
            if (onLong != null) setOnLongClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); onLong(); true }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    }

    /** The header in edit mode: how many are selected, then Pin, Read, Delete and Done. */
    private fun editBar(col: LinearLayout) {
        fun act(label: String, danger: Boolean = false, onClick: () -> Unit) = text(label, 13f, if (danger) 0xFFFFFFFF.toInt() else p.text, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0)
            background = if (danger) shape(0xFFE5484D.toInt(), 18) else glassShape(p, 18, true); setOnClickListener { onClick() }
        }
        val pins = idSet("msg_pinned")
        val allPinned = selected.all { it in pins }
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = glassShape(p, 30, true); setPadding(dp(16), dp(8), dp(8), dp(8))
            addView(text("${selected.size} selected", 17f, p.text, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(act(if (allPinned) "Unpin" else "Pin") {
                if (allPinned) pins.removeAll(selected) else pins.addAll(selected)
                saveIds("msg_pinned", pins); selected.clear(); show(tab)
            }, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(6) })
            addView(act("Read") { markRead(selected.toList()); selected.clear(); show(tab) }, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(6) })
            addView(act("Delete", danger = true) { deleteChats(selected.toList()) }, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(6) })
            addView(act("✕") { selected.clear(); show(tab) }, LinearLayout.LayoutParams(dp(36), dp(36)))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    /** Marking read writes to the SMS store, which Android allows only to the default SMS app. */
    private fun markRead(threads: List<Long>) {
        if (!isDefaultSms()) { android.widget.Toast.makeText(this, "Only the default SMS app can mark messages as read", android.widget.Toast.LENGTH_SHORT).show(); return }
        for (t in threads) runCatching {
            contentResolver.update(Telephony.Sms.CONTENT_URI, android.content.ContentValues().apply { put(Telephony.Sms.READ, 1) }, "${Telephony.Sms.THREAD_ID}=?", arrayOf(t.toString()))
        }
    }

    /**
     * Delete after a confirmation. The default SMS app deletes for real; otherwise Android forbids it, so the chats are
     * removed from AOS Messages only (they stay in the phone's SMS store and in the stock app).
     */
    private fun deleteChats(threads: List<Long>) {
        val real = isDefaultSms()
        aosDialog(p, "Delete ${threads.size} chat" + (if (threads.size > 1) "s" else "") + "?",
            if (real) "The messages will be deleted from the phone." else "They will disappear from AOS Messages. Android lets only the default SMS app delete messages from the phone.",
            null,
            DialogAction("Delete", 1) {
                if (real) for (t in threads) runCatching { contentResolver.delete(Uri.parse("content://sms/conversations/$t"), null, null) }
                else saveIds("msg_hidden", idSet("msg_hidden").apply { addAll(threads) })
                selected.clear(); show(tab)
            },
            DialogAction("Cancel"))
    }

    private fun chats(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(72)) }   // room under the last row for the button
        if (selected.isNotEmpty()) editBar(col) else header("Messages", col)
        // Filter pills: All, Unread, Sent.
        val names = listOf("All", "Unread", "Sent")
        col.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@MessagesActivity).apply {
                names.forEachIndexed { i, n ->
                    val on = i == filter
                    addView(text(n, 13f, if (on) p.onAccent else p.text, true).apply {
                        gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), 0)
                        background = if (on) shape(p.accent, 19) else glassShape(p, 19, true)
                        setOnClickListener { filter = i; show(tab) }
                    }, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(6) })
                }
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        // Newest message of each conversation; a conversation is unread when any message in it is.
        val all = load(null, null, "date DESC")
        val unreadThreads = all.filter { !it.read && !it.outgoing }.map { it.thread }.toSet()
        val seen = HashSet<Long>()
        val hidden = idSet("msg_hidden"); val pins = idSet("msg_pinned")
        val latest = all.filter { seen.add(it.thread) && it.thread !in hidden }.take(80).sortedByDescending { it.thread in pins }
        val shown = when (filter) {
            1 -> latest.filter { it.thread in unreadThreads }
            2 -> latest.filter { it.outgoing }
            else -> latest
        }
        if (shown.isEmpty()) col.addView(text("Nothing here yet, or access to SMS is not allowed.", 14f, p.sub).apply { setPadding(dp(4), dp(16), 0, 0) })
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        for (m in shown) {
            val unread = m.thread in unreadThreads
            fun toggle() { if (!selected.remove(m.thread)) selected += m.thread; show(tab) }
            row(col, contactName(m.address), (if (m.outgoing) "You: " else "") + m.body.replace('\n', ' ') + "  ·  " + fmt.format(Date(m.date)), unread,
                sel = m.thread in selected, pinned = m.thread in pins, onLong = { toggle() }) {
                if (selected.isNotEmpty()) toggle() else { thread = m.thread; address = m.address; showThread() }
            }
        }
        return ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
    }

    private fun contacts(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(72)) }
        header("Contacts", col)
        val people = mutableListOf<Pair<String, String>>()
        if (has(Manifest.permission.READ_CONTACTS)) try {
            contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC")?.use { c ->
                while (c.moveToNext()) people += c.getString(0).orEmpty() to c.getString(1).orEmpty()
            }
        } catch (_: Exception) { }
        if (people.isEmpty()) col.addView(text("Nothing here yet, or access is not allowed.", 14f, p.sub).apply { setPadding(dp(4), dp(16), 0, 0) })
        var last = ""
        for ((name, num) in people) {
            val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
            if (initial != last) { last = initial; col.addView(text(initial, 13f, p.accent, true).apply { setPadding(dp(6), dp(10), 0, dp(4)) }) }
            row(col, name, num, false) { thread = -2; address = num; showThread() }
        }
        return ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
    }

    /** New message: a glass sheet with the number field, like Phone's keypad sheet. */
    private fun newMessage() {
        val input = EditText(this).apply {
            hint = "Phone number"; inputType = android.text.InputType.TYPE_CLASS_PHONE; setTextColor(p.text); setHintTextColor(p.sub)
            background = glassShape(p, 24); setPadding(dp(18), dp(12), dp(18), dp(12)); textSize = 20f
        }
        lateinit var sheet: FrameLayout
        fun close() { body.removeView(sheet) }
        sheet = FrameLayout(this).apply {
            setOnClickListener { close() }   // tap outside the sheet closes it
            addView(LinearLayout(this@MessagesActivity).apply {
                orientation = LinearLayout.VERTICAL; background = shape(p.surface, 30, p.stroke); isClickable = true
                setPadding(dp(16), dp(16), dp(16), dp(16)); elevation = dp(10).toFloat()
                addView(text("New message", 20f, p.text, true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
                addView(input, LinearLayout.LayoutParams(-1, -2))
                addView(text("Next", 16f, p.onAccent, true).apply {
                    gravity = Gravity.CENTER; background = shape(p.accent, 24)
                    setOnClickListener {
                        val n = input.text.toString().trim()
                        if (n.isNotEmpty()) { close(); address = n; thread = -2; showThread() }
                    }; pressFx(p, round = false)
                }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
            }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        body.addView(sheet, FrameLayout.LayoutParams(-1, -1))
        input.requestFocus()
    }

    private val local = mutableListOf<Item>()

    private fun showThread() {
        tabs.visibility = View.GONE
        val items = if (thread >= 0) load("thread_id=?", arrayOf(thread.toString()), "date ASC") else emptyList()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (m in items + local.filter { it.address == address }) col.addView(text(m.body, 15f, if (m.outgoing) p.onAccent else p.text).apply {
            background = shape(if (m.outgoing) p.accent else p.surfaceHigh, 18); setPadding(dp(14), dp(9), dp(14), dp(9))
        }, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = if (m.outgoing) Gravity.END else Gravity.START; bottomMargin = dp(6)
            if (m.outgoing) marginStart = dp(56) else marginEnd = dp(56)
        })
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col); post { fullScroll(View.FOCUS_DOWN) } }
        val field = EditText(this).apply {
            hint = "Message"; setTextColor(p.text); setHintTextColor(p.sub); background = glassShape(p, 24)
            setPadding(dp(16), dp(10), dp(16), dp(10)); maxLines = 4
        }
        val send = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right); setColorFilter(p.onAccent); setPadding(dp(10), dp(10), dp(10), dp(10))
            background = circle(p.accent); contentDescription = "Send"
            setOnClickListener {
                val msg = field.text.toString().trim()
                if (msg.isEmpty()) return@setOnClickListener
                if (!has(Manifest.permission.SEND_SMS)) { requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 1); return@setOnClickListener }
                try {
                    val sms = getSystemService(SmsManager::class.java)
                    sms.sendMultipartTextMessage(address, null, sms.divideMessage(msg), null, null)
                    local += Item(thread, address, msg, true); showThread()
                } catch (e: Exception) { Toast.makeText(this@MessagesActivity, "Not sent: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
        body.removeAllViews()
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // The same glass header as the lists: back, the contact, and its initial.
            addView(LinearLayout(this@MessagesActivity).apply {
                gravity = Gravity.CENTER_VERTICAL; background = glassShape(p, 30, true); setPadding(dp(10), dp(8), dp(16), dp(8))
                addView(ImageView(this@MessagesActivity).apply {
                    setImageResource(R.drawable.ic_chevron_left); setColorFilter(p.text); setPadding(dp(10), dp(10), dp(10), dp(10))
                    background = circle(p.glassCircle); setOnClickListener { onBackPressed() }
                }, LinearLayout.LayoutParams(dp(42), dp(42)))
                addView(text(contactName(address), 20f, p.text, true).apply { setPadding(dp(12), 0, 0, 0); maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
            addView(LinearLayout(this@MessagesActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(field, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
                addView(send, LinearLayout.LayoutParams(dp(44), dp(44)))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }, FrameLayout.LayoutParams(-1, -1))
    }
}
