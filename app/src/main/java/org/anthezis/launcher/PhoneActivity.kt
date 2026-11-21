package org.anthezis.launcher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/**
 * AOS Phone: number field and suggestion on top, keypad with letters, big call button, and a bottom bar of
 * icon + title tabs (Keypad, Recents, Contacts, Home). Calls go out through the system telephony stack.
 */
class PhoneActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var body: FrameLayout
    private lateinit var tabs: LinearLayout
    private var tab = 0
    private val number = StringBuilder()
    private lateinit var numberView: TextView
    private lateinit var suggestView: TextView
    private val tabDefs = listOf("Home" to R.drawable.ic_home, "Contacts" to R.drawable.ic_person)

    private fun text(t: String, sp: Float, c: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(c); typeface = appFont(if (bold) 700 else 400)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Palette(this)
        body = FrameLayout(this)
        tabs = LinearLayout(this).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), dp(6)); background = glassShape(p, 34)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(44), dp(16), dp(60)); setBackgroundColor(p.glassScreen)
            addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
            // Calls / Contacts are pills under the header now (like AOS Tube); the old bottom bar stays hidden.
            addView(tabs.apply { visibility = View.GONE }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        })
        addAosTopBar(p)
        val need = listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
        // Ask once to be the call screening app, so spam numbers are rejected before the phone rings.
        val roles = getSystemService(android.app.role.RoleManager::class.java)
        if (roles.isRoleAvailable(android.app.role.RoleManager.ROLE_CALL_SCREENING) && !roles.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)
            && !askedScreening) {
            askedScreening = true
            try { startActivityForResult(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING), 8) } catch (_: Exception) {}
        }
        SpamList.refresh(this)
        // A tel: link or the dial intent pre-fills the number.
        intent?.data?.schemeSpecificPart?.let { number.append(it) }
        show(0)
        // The dialer opens on the dial pad; the recents stay right under it (swipe it down or tap outside to see them).
        openKeypad()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = show(tab)

    private fun has(perm: String) = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private companion object { var askedScreening = false }

    /** Long press on a number: add it to the block list, or take it off again. */
    private fun toggleBlock(r: Row) {
        if (r.number.isBlank()) return
        val blocked = SpamList.isUserBlocked(this, r.number)
        aosDialog(p, r.title, if (blocked) "Calls from this number will ring again." else "Calls from this number will be rejected before they ring.", null,
            DialogAction(if (blocked) "Unblock" else "Block number", if (blocked) 1 else 2) {
                if (blocked) SpamList.unblock(this, r.number) else SpamList.block(this, r.number); show(tab)
            },
            DialogAction("Cancel"))
    }

    /** Spam protection panel: the lists, the numbers you blocked, and the hard mode. */
    private fun spamPanel() {
        val blocked = SpamList.userBlocked(this).toList().sorted()
        val hard = SpamList.hardMode(this)
        aosDialog(p, "Spam protection",
            "Known spam list: ${SpamList.entryCount(this)} numbers (updates every 12 hours).\n" +
                "Your blocked numbers: ${blocked.size}.\n" +
                "Hard mode: ${if (hard) "ON, hidden numbers are rejected too" else "off"}.\n" +
                "Swipe a call to the left to block its number.",
            null,
            DialogAction(if (hard) "Turn hard mode off" else "Turn hard mode on", 2) { SpamList.setHardMode(this, !hard); toastShort(if (!hard) "Hard mode on" else "Hard mode off") },
            DialogAction("Update the spam list") { SpamList.refresh(this, true); toastShort("Updating…") },
            DialogAction(if (blocked.isEmpty()) "No blocked numbers" else "Blocked numbers (${blocked.size})") { if (blocked.isNotEmpty()) blockedList(blocked) },
            DialogAction("Close"))
    }

    private fun blockedList(numbers: List<String>) {
        aosDialog(p, "Blocked numbers", "Tap a number to unblock it.", null,
            *(numbers.take(6).map { n -> DialogAction("0$n  ·  unblock") { SpamList.unblock(this, n); show(tab) } } + DialogAction("Close")).toTypedArray())
    }

    private fun toastShort(t: String) = android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_SHORT).show()

    /** Swipe a call to the left: its number is blocked at once (calls from it are rejected before they ring). */
    private fun swipeToBlock(row: View, r: Row) {
        var x0 = 0f; var y0 = 0f; var moved = false
        row.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; moved = false; false }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    if (!moved && dx < -dp(18) && Math.abs(dx) > Math.abs(dy) * 1.5f) { moved = true; v.parent?.requestDisallowInterceptTouchEvent(true); v.cancelLongPress() }
                    if (moved) { v.translationX = dx.coerceAtMost(0f); v.alpha = (1f + dx / (v.width * 1.4f)).coerceIn(0.4f, 1f) }
                    moved
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        val far = (e.rawX - x0) < -dp(110) && e.actionMasked == android.view.MotionEvent.ACTION_UP
                        if (far && r.number.isNotBlank()) {
                            v.animate().translationX(-v.width.toFloat()).alpha(0f).setDuration(140).withEndAction {
                                SpamList.block(this, r.number); toastShort("Blocked ${r.title}"); show(tab)
                            }.start()
                        } else v.animate().translationX(0f).alpha(1f).setDuration(160).start()
                    }
                    val was = moved; moved = false; was
                }
                else -> false
            }
        }
    }

    // ---- SIM choice: the SIM cards that are in the phone; calls go out through the one picked here ----

    private fun simHandles(): List<android.telecom.PhoneAccountHandle> = try {
        if (!has(Manifest.permission.READ_PHONE_STATE)) emptyList() else getSystemService(android.telecom.TelecomManager::class.java).callCapablePhoneAccounts
    } catch (_: SecurityException) { emptyList() }

    private fun simChoice() = getSharedPreferences("aos_setup", MODE_PRIVATE).getInt("sim_choice", 0)

    /** The operator of a SIM (Kyivstar, lifecell, Telekom…), falling back to the account's own label. */
    private fun simLabel(h: android.telecom.PhoneAccountHandle): String = try {
        val tm = getSystemService(android.telephony.TelephonyManager::class.java).createForPhoneAccountHandle(h)
        tm?.networkOperatorName?.takeIf { it.isNotBlank() } ?: tm?.simOperatorName?.takeIf { it.isNotBlank() }
            ?: getSystemService(android.telecom.TelecomManager::class.java).getPhoneAccount(h)?.label?.toString() ?: "SIM"
    } catch (_: SecurityException) { "SIM" }

    /** A small pill in the header with the SIM in use; tap to choose another one. Not shown when the phone has fewer than two. */
    private fun simChip(): View? {
        val handles = simHandles()
        if (handles.size < 2) return null
        val i = simChoice().coerceIn(0, handles.lastIndex)
        return text("SIM ${i + 1} · ${simLabel(handles[i])}", 13f, p.text, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0); maxLines = 1; background = glassShape(p, 19, true)
            setOnClickListener {
                aosDialog(p, "Call with", "Choose the SIM card for outgoing calls.", null,
                    *handles.mapIndexed { k, h -> DialogAction("SIM ${k + 1} · ${simLabel(h)}", if (k == i) 1 else 0) {
                        getSharedPreferences("aos_setup", MODE_PRIVATE).edit().putInt("sim_choice", k).apply(); show(tab)
                    } }.toTypedArray(),
                    DialogAction("Cancel"))
            }
        }
    }

    /** A messenger call has no number to dial: open the messenger itself to call back. */
    private fun openApp(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { try { startActivity(it) } catch (_: Exception) {} }
    }

    private fun call(n: String, sim: Int = simChoice()) {
        if (n.isBlank()) return
        if (!has(Manifest.permission.CALL_PHONE)) { requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 1); return }
        val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", n, null))
        simHandles().getOrNull(sim)?.let { intent.putExtra(android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }   // the SIM of the pressed button
        try { startActivity(intent) } catch (_: Exception) {}
    }

    private fun show(t: Int) {
        if (t == 2) { finish(); return }
        tab = t
        tabs.removeAllViews()
        tabDefs.forEachIndexed { i, (name, icon) ->
            val on = i == t
            val c = if (on) p.onAccent else p.sub
            tabs.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(4), dp(6), dp(4), dp(5))
                background = if (on) shape(p.accent, 22) else null
                addView(ImageView(this@PhoneActivity).apply { setImageResource(icon); setColorFilter(c) }, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(text(name, 10f, c, on).apply { gravity = Gravity.CENTER; maxLines = 1; setPadding(0, dp(2), 0, 0) })
                setOnClickListener { show(i) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
        }
        body.removeAllViews(); keypadSheet = null
        body.addView(if (t == 0) list(recents()) else list(contacts(), letters = true))
        // Round keypad button over the list (a phone handset): opens the dial pad as a sheet.
        body.addView(ImageView(this).also { keypadButton = it }.apply {
            setImageResource(R.drawable.ic_phone); setColorFilter(p.onAccent); setPadding(dp(14), dp(14), dp(14), dp(14))
            background = circle(p.accent); elevation = dp(6).toFloat(); contentDescription = "Keypad"
            setOnClickListener { openKeypad() }; pressFx(p)
        }, FrameLayout.LayoutParams(dp(50), dp(50), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(2), dp(4)) })
    }

    private var keypadSheet: View? = null
    /** The round button that opens the pad; hidden while the pad is open, so it never sits on top of the keys. */
    private var keypadButton: View? = null

    private fun openKeypad() {
        if (keypadSheet != null) return
        val sheet = FrameLayout(this).apply {
            setOnClickListener { closeKeypad() }   // tap outside the pad (on the visible calls) closes it
            addView(LinearLayout(this@PhoneActivity).apply {
                orientation = LinearLayout.VERTICAL; background = shape(p.surface, 30, p.stroke); isClickable = true
                setPadding(dp(12), dp(12), dp(12), dp(10)); elevation = dp(10).toFloat()
                addView(ImageView(this@PhoneActivity).apply { setImageResource(R.drawable.ic_chevron_left); rotation = -90f; setColorFilter(p.sub); setOnClickListener { closeKeypad() } },
                    LinearLayout.LayoutParams(dp(36), dp(28)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                addView(keypad(), LinearLayout.LayoutParams(-1, -2))
            }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        keypadSheet = sheet
        body.addView(sheet, FrameLayout.LayoutParams(-1, -1))
        keypadButton?.visibility = View.GONE
    }

    private fun closeKeypad() { keypadSheet?.let { body.removeView(it) }; keypadSheet = null; keypadButton?.visibility = View.VISIBLE }

    private val letters = mapOf("2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "0" to "+")

    private var eraseView: View? = null

    private fun refreshNumber() {
        numberView.text = number
        eraseView?.visibility = if (number.isEmpty()) View.INVISIBLE else View.VISIBLE
        numberView.textSize = if (number.length > 12) 22f else 30f
        suggestView.text = if (number.length >= 2) lookupName(number.toString()).orEmpty() else ""
    }

    private fun lookupName(num: String): String? {
        if (!has(Manifest.permission.READ_CONTACTS)) return null
        return try {
            contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI.buildUpon().appendPath(num).build(),
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) { null }
    }

    private fun keypad(): View {
        numberView = text("", 36f, p.text, true).apply { gravity = Gravity.CENTER; maxLines = 1 }
        suggestView = text("", 15f, p.accent, true).apply { gravity = Gravity.CENTER; maxLines = 1 }
        // Erase: tap = one digit, hold = the whole number. Hidden while the number is empty.
        val del = text("⌫", 20f, p.text, true).apply {
            gravity = Gravity.CENTER; background = circle(p.surfaceHigh); contentDescription = "Erase"
            setOnClickListener { if (number.isNotEmpty()) number.deleteCharAt(number.length - 1); refreshNumber() }
            setOnLongClickListener { number.clear(); refreshNumber(); performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); true }
            pressFx(p)
        }
        eraseView = del
        val field = FrameLayout(this).apply {
            background = glassShape(p, 28); setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(LinearLayout(this@PhoneActivity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(numberView, LinearLayout.LayoutParams(-1, dp(38)))
                addView(suggestView, LinearLayout.LayoutParams(-1, dp(16)))
            }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { marginStart = dp(48); marginEnd = dp(48) })
            addView(del, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = dp(4) })
        }
        // Screen minus the page padding (2 x 16), the sheet padding (2 x 12) and the 4dp margins around each of the three keys.
        val size = (resources.displayMetrics.widthPixels - dp(32) - dp(24) - dp(24)) / 3
        val grid = GridLayout(this).apply {
            columnCount = 3
            for (k in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")) addView(LinearLayout(this@PhoneActivity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = shape(p.surfaceHigh, 36)
                addView(text(k, 24f, p.text, true).apply { gravity = Gravity.CENTER })
                addView(text(letters[k].orEmpty(), 10f, p.sub).apply { gravity = Gravity.CENTER; minHeight = dp(12) })
                setOnClickListener { number.append(k); refreshNumber() }
                if (k == "0") setOnLongClickListener { number.append("+"); refreshNumber(); true }
                pressFx(p, round = false)
            }, GridLayout.LayoutParams().apply { width = size; height = dp(50); setMargins(dp(4), dp(2), dp(4), dp(2)) })
        }
        // Call buttons: one per SIM (SIM 1 / SIM 2 with the operator's name), or one round button with a single SIM.
        val handles = simHandles()
        val callBtn: View = if (handles.size < 2) ImageView(this).apply {
            setImageResource(R.drawable.ic_phone); setColorFilter(p.onAccent); setPadding(dp(16), dp(16), dp(16), dp(16))
            background = circle(p.accent); elevation = dp(4).toFloat(); contentDescription = "Call"
            setOnClickListener { call(number.toString(), 0) }; pressFx(p)
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58)).apply { topMargin = dp(6) }
        } else LinearLayout(this).apply {
            handles.take(2).forEachIndexed { k, h ->
                addView(LinearLayout(this@PhoneActivity).apply {
                    gravity = Gravity.CENTER; background = shape(if (k == 0) p.accent else lift(p.accent, 0.25f), 26); elevation = dp(3).toFloat()
                    setPadding(dp(10), 0, dp(12), 0)
                    addView(ImageView(this@PhoneActivity).apply { setImageResource(R.drawable.ic_phone); setColorFilter(p.onAccent) }, LinearLayout.LayoutParams(dp(20), dp(20)))
                    addView(LinearLayout(this@PhoneActivity).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0)
                        addView(text("SIM ${k + 1}", 13f, p.onAccent, true))
                        addView(text(simLabel(h), 11f, p.onAccent).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; alpha = 0.85f })
                    })
                    contentDescription = "Call with SIM ${k + 1}"
                    setOnClickListener { call(number.toString(), k) }; pressFx(p, round = false)
                }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { if (k > 0) marginStart = dp(10) })
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); marginStart = dp(4); marginEnd = dp(4) }
        }
        refreshNumber()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            // Compact pad: the number right above the keys, no empty space, so the recent calls stay visible above the sheet.
            addView(field, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
            addView(grid)
            addView(callBtn)
            addView(View(this@PhoneActivity), LinearLayout.LayoutParams(-1, dp(6)))
        }
    }

    /** [app]: the messenger a call came through (no number to dial back: a tap opens the app). */
    private class Row(val title: String, val sub: String, val number: String, val missed: Boolean = false, val type: Int = 0, val time: Long = 0, val app: String? = null)

    private fun recents(): List<Row> {
        val out = mutableListOf<Row>()
        if (has(Manifest.permission.READ_CALL_LOG)) contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
            null, null, CallLog.Calls.DATE + " DESC")?.use { c ->
            while (c.moveToNext() && out.size < 60) {
                val type = c.getInt(2)
                val kind = when (type) { CallLog.Calls.MISSED_TYPE -> "Missed"; CallLog.Calls.OUTGOING_TYPE -> "Outgoing"; CallLog.Calls.REJECTED_TYPE -> "Rejected"; else -> "Incoming" }
                val num = c.getString(1).orEmpty()
                out += Row(c.getString(0)?.takeIf { it.isNotBlank() } ?: num.ifBlank { "Unknown" },
                    "$kind  ·  " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(c.getLong(3))),
                    num, type == CallLog.Calls.MISSED_TYPE, type, c.getLong(3))
            }
        }
        // Calls from messengers (recorded from their notifications), merged in by time.
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        for (a in AppCalls.all(this)) {
            val appName = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(a.pkg, 0)).toString() }.getOrDefault(a.pkg)
            out += Row(a.name, "$appName  ·  ${if (a.missed) "Missed" else "Incoming"}  ·  " + fmt.format(Date(a.time)), "", a.missed,
                if (a.missed) CallLog.Calls.MISSED_TYPE else CallLog.Calls.INCOMING_TYPE, a.time, a.pkg)
        }
        return out.sortedByDescending { it.time }.take(80)
    }

    private var callFilter = 0
    private var searchText = ""

    /** Numbers of starred contacts, reduced to their last 9 digits so formats can differ. */
    private fun favoriteTails(): Set<String> {
        if (!has(Manifest.permission.READ_CONTACTS)) return emptySet()
        val out = HashSet<String>()
        try {
            contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                ContactsContract.CommonDataKinds.Phone.STARRED + "=1", null, null)?.use { c -> while (c.moveToNext()) out += tail(c.getString(0).orEmpty()) }
        } catch (_: Exception) { }
        return out
    }

    private fun tail(n: String) = n.filter { it.isDigit() }.takeLast(9)

    private fun filterCalls(rows: List<Row>): List<Row> = when (callFilter) {
        1 -> rows.filter { it.type == CallLog.Calls.MISSED_TYPE }
        2 -> rows.filter { it.type == CallLog.Calls.INCOMING_TYPE }
        3 -> rows.filter { it.type == CallLog.Calls.OUTGOING_TYPE }
        4 -> rows.filter { it.type == CallLog.Calls.REJECTED_TYPE }
        5 -> { val fav = favoriteTails(); rows.filter { tail(it.number) in fav } }
        else -> rows
    }
    private fun contacts(): List<Row> {
        if (!has(Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = mutableListOf<Row>()
        contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC")?.use { c ->
            while (c.moveToNext()) out += Row(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getString(1).orEmpty())
        }
        return out
    }

    private fun list(rows: List<Row>, letters: Boolean = false): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(72)) }   // room under the last row for the keypad button
        // Header like the App Drawer: a glass bar with the title and round icon buttons.
        fun round(icon: Int, onClick: () -> Unit) = ImageView(this).apply {
            setImageResource(icon); setColorFilter(p.text); setPadding(dp(10), dp(10), dp(10), dp(10)); background = circle(p.glassCircle); setOnClickListener { onClick() }
        }
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = glassShape(p, 30, true); setPadding(dp(20), dp(8), dp(10), dp(8))
            addView(text("Phone", 22f, p.text, true), LinearLayout.LayoutParams(0, -2, 1f))
            if (!letters) addView(round(R.drawable.ic_lock) { spamPanel() }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(6) })
            addView(round(R.drawable.ic_phone) { openKeypad() }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(6) })
            addView(round(R.drawable.ic_close) { searchText = ""; callFilter = 0; show(tab) }, LinearLayout.LayoutParams(dp(42), dp(42)))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        run {
            // One row of pills under the header: the call filters, then Contacts (it replaces the old bottom tab bar).
            val names = listOf("Recents", "Missed", "Accepted", "Outgoing", "Rejected", "Favorites", "Contacts")
            var onView: View? = null
            val scroller = android.widget.HorizontalScrollView(this)
            scroller.isHorizontalScrollBarEnabled = false
            scroller.addView(LinearLayout(this@PhoneActivity).apply {
                names.forEachIndexed { i, n ->
                    val isContacts = i == names.lastIndex
                    val on = if (letters) isContacts else !isContacts && i == callFilter
                    addView(text(n, 13f, if (on) p.onAccent else p.text, true).apply {
                        gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), 0)
                        background = if (on) shape(p.accent, 19) else glassShape(p, 19, true)
                        setOnClickListener { if (isContacts) { callFilter = 0; show(1) } else { callFilter = i; show(0) } }
                        if (on) onView = this
                    }, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(6) })
                }
            })
            scroller.post { onView?.let { v -> scroller.scrollTo((v.left - dp(16)).coerceAtLeast(0), 0) } }
            col.addView(scroller, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        val shown = if (letters) rows else filterCalls(rows)
        if (shown.isEmpty()) col.addView(text("Nothing here yet, or access is not allowed.", 14f, p.sub).apply { setPadding(dp(4), dp(16), 0, 0) })
        var last = ""
        for (r in shown) {
            val spam = SpamList.lookup(this, r.number)
            val initial = if (spam != null) "!" else r.title.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
            if (letters && initial != last) {
                last = initial
                col.addView(text(initial, 13f, p.accent, true).apply { setPadding(dp(6), dp(10), 0, dp(4)) })
            }
            col.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; background = shape(p.surfaceHigh, 18); setPadding(dp(12), dp(10), dp(12), dp(10))
                if (!letters) swipeToBlock(this, r)
                val appIcon = r.app?.let { runCatching { packageManager.getApplicationIcon(it) }.getOrNull() }
                if (appIcon != null) addView(ImageView(this@PhoneActivity).apply { setImageDrawable(appIcon) }, LinearLayout.LayoutParams(dp(42), dp(42)))
                else addView(text(initial, 16f, p.onAccent, true).apply { gravity = Gravity.CENTER; background = circle(if (spam != null) 0xFFE5484D.toInt() else p.accent) },
                    LinearLayout.LayoutParams(dp(42), dp(42)))
                addView(LinearLayout(this@PhoneActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(r.title, 16f, p.text, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(text(if (spam != null) "$spam  ·  ${r.sub}" else r.sub, 12f,
                        if (r.missed || spam != null) 0xFFE5484D.toInt() else p.sub).apply { maxLines = 1 })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
                addView(ImageView(this@PhoneActivity).apply {
                    setImageResource(R.drawable.ic_phone); setColorFilter(p.accent); setPadding(dp(8), dp(8), dp(8), dp(8))
                    setOnClickListener { if (r.app != null) openApp(r.app) else call(r.number) }
                }, LinearLayout.LayoutParams(dp(40), dp(40)))
                setOnClickListener { if (r.app != null) openApp(r.app) else { number.clear(); number.append(r.number); show(0); openKeypad() } }
                setOnLongClickListener { toggleBlock(r); true }; pressFx(p, round = false)
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        return ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
    }
}
