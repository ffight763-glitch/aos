package org.anthezis.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import android.widget.EditText
import android.widget.LinearLayout
import java.util.Locale

/**
 * AOS keyboard: Russian (ЙЦУКЕН), English (QWERTY), two symbol pages and emoji, painted in the launcher's theme.
 * Light theme -> light keyboard, dark theme -> dark keyboard; pref "kb_invert" swaps them. Keys take the AOS accent
 * (Enter, active Shift, pressed key). Pref "kb_round": letters on round keys (default), off = rounded squares.
 *  - strip above the keys: while a word is typed, three suggestions (T9); otherwise emoji, paste, cursor ◀ ▶, hide
 *  - autocorrect (pref "kb_autocorrect"): a typo is fixed on Space or punctuation; Backspace right after puts it back
 *    (and the keyboard remembers that word). Words come from the phone's spell checker, a built-in list and the user's own words.
 *  - Shift: tap = one capital, double tap = Caps Lock; sentences start with a capital by themselves
 *  - hold a top-row letter: its digit; hold е -> ё, ь -> ъ, hold . -> …
 *  - hold Backspace: repeats; swipe left from Backspace: deletes the previous word
 *  - swipe along Space: moves the cursor; hold Space: keyboard picker; globe: RU <-> EN
 *  - several fingers at once: every key is typed (fast two-thumb typing loses nothing)
 */
class AosKeyboardService : InputMethodService() {
    private var view: KeyboardView? = null

    override fun onCreateInputView(): View = KeyboardView(this).also { view = it }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        view?.start(info)
    }

    /** Themes and the light/dark mode can change while the keyboard is hidden: repaint every time it opens. */
    override fun onWindowShown() { super.onWindowShown(); view?.retheme() }

    /** The cursor moved: if the user did it (tapped elsewhere in the text), the word under the cursor is read again. */
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        view?.selectionChanged(newSelStart, newSelEnd)
    }

    /** A key held while the keyboard goes away must not keep deleting / moving the cursor. */
    override fun onFinishInputView(finishingInput: Boolean) { view?.reset(); super.onFinishInputView(finishingInput) }
    override fun onWindowHidden() { view?.reset(); view?.save(); super.onWindowHidden() }
    override fun onDestroy() { view?.release(); view = null; super.onDestroy() }

    fun prefs() = getSharedPreferences("aos_setup", Context.MODE_PRIVATE)
}

/**
 * A plain text field that opens the keyboard by itself. For checking the keyboard from a computer:
 *   adb shell am start -n org.anthezis.ahome/org.anthezis.launcher.KeyboardTestActivity
 */
class KeyboardTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Palette(this)
        val d = resources.displayMetrics.density
        val field = EditText(this).apply {
            hint = "AOS Keyboard"; setTextColor(p.text); setHintTextColor(p.sub); typeface = appFont(400)
            gravity = Gravity.TOP or Gravity.START; minLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg)
            val m = (16 * d).toInt(); setPadding(m, m * 3, m, m)
            addView(field, LinearLayout.LayoutParams(-1, -2))
        })
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        field.requestFocus()
        this.field = field
    }

    private var field: EditText? = null

    /** The keyboard can be asked for only once the window has focus (started from adb it gets it a moment later). */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val f = field ?: return
        if (!hasFocus) return
        f.requestFocus()
        window.insetsController?.show(WindowInsets.Type.ime())
        getSystemService(InputMethodManager::class.java).showSoftInput(f, 0)
    }
}

@SuppressLint("ViewConstructor")
class KeyboardView(private val ime: AosKeyboardService) : View(ime) {
    private val d = resources.displayMetrics.density

    // ---- keys ----
    private class Key(val label: String, val code: Int = 0, val weight: Float = 1f, val alt: String? = null) {
        val rect = RectF()
        val isChar get() = code == 0
        /** Keys that do something again and again while held. */
        val repeats get() = code == DEL || code == LEFT || code == RIGHT
    }

    private companion object {
        const val SHIFT = -1; const val DEL = -2; const val ENTER = -3; const val SYM = -4; const val LANG = -5; const val SPACE = -6
        const val EMOJI = -7; const val PASTE = -8; const val LEFT = -9; const val RIGHT = -10; const val HIDE = -11; const val SYM2 = -12
        const val CAT = -20          // emoji categories: CAT - index
        const val SUGG = -40         // suggestion slots: SUGG - index
        const val DIGITS = "1234567890"
        const val SEPARATORS = " .,!?;:"
        val CATS = listOf(
            "🕘" to "",
            "😀" to "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😋 😜 🤪 😎 🤔 🤨 😐 😴 😭 😡 🥺 😱 😳 🤗 🤫 🙄 😬 🤯 🥳",
            "👍" to "👍 👎 👌 ✌️ 🤞 🤟 🤘 👋 🤙 👏 🙌 🙏 💪 🤝 👀 🧠 👶 👦 👧 👨 👩 👴 👵 🙋 🤷 🤦 💃 🕺 👑 💋 👊 ✊",
            "❤️" to "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 ❣️ 💕 💯 ✨ ⭐ 🔥 💥 💫 ✅ ❌ ❗ ❓ ⚠️ 🚫 💤 🎉 🎁 🏆 🎈 💐 🌈 ☀️ 🌙",
            "🐶" to "🐶 🐱 🐭 🐰 🦊 🐻 🐼 🐸 🐵 🐔 🐧 🐝 🦋 🐍 🐢 🐬 🌸 🌹 🌲 🍀 🍎 🍌 🍉 🍓 🍕 🍔 🍟 🍩 🍫 🍰 ☕ 🍺",
            "⚽" to "⚽ 🏀 🎮 🎧 🎵 🎬 📷 📱 💻 ⌚ 💡 📌 📎 ✏️ 📚 💰 💳 🚗 🚕 🚌 ✈️ 🚀 🏠 🌍 ☔ ⏰ 🔑 🔒 📦 🛒 💊 🎂",
        )
    }

    private fun letters(rows: List<String>, alts: Map<Char, String> = emptyMap()): List<List<Key>> {
        val r = rows.mapIndexed { i, row ->
            row.mapIndexed { j, c -> Key(c.toString(), alt = alts[c] ?: if (i == 0 && j < 10) DIGITS[j].toString() else null) }.toMutableList()
        }
        r[2].add(0, Key("⇧", SHIFT, 1.5f)); r[2].add(Key("⌫", DEL, 1.5f))
        return r + listOf(bottomRow())
    }

    private fun bottomRow(sym: String = "?123") = listOf(Key(sym, SYM, 1.5f), Key("🌐", LANG), Key(","), Key(" ", SPACE, 4.5f),
        Key(".", alt = "…"), Key("⏎", ENTER, 1.5f))

    private fun chars(s: String, alts: Map<Char, String> = emptyMap()) = s.map { Key(it.toString(), alt = alts[it]) }

    private val ru = letters(listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю"), mapOf('е' to "ё", 'ь' to "ъ"))
    private val en = letters(listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"))
    private val sym = listOf(
        chars("1234567890", mapOf('1' to "¹", '2' to "²", '3' to "³", '0' to "°")),
        chars("@#₽_&-+()/", mapOf('₽' to "₴", '-' to "—", '/' to "\\", '(' to "[", ')' to "]", '&' to "§")),
        listOf(Key("=\\<", SYM2, 1.5f)) + chars("*\"':;!?", mapOf('"' to "«", '\'' to "»", '!' to "¡", '?' to "¿", '*' to "★")) +
            listOf(Key("⌫", DEL, 1.5f)),
        bottomRow("ABC"),
    )
    private val sym2 = listOf(
        chars("~`|•√π÷×¶∆"),
        chars("£€$¢^°={}\\", mapOf('$' to "¥", '=' to "≠", '°' to "℃")),
        listOf(Key("?123", SYM2, 1.5f)) + chars("%©®™✓[]", mapOf('%' to "‰", '✓' to "✗")) + listOf(Key("⌫", DEL, 1.5f)),
        bottomRow("ABC"),
    )

    /** Emoji: a category of 3 rows × 9 (more emoji than fit are dropped), category bar at the bottom. 🕘 = recently used. */
    private var emojiCat = 1
    private var emojiRows: List<List<Key>>? = null
    private var emojiRowsCat = -1
    private fun recent() = ime.prefs().getString("kb_recent_emoji", "").orEmpty().split(' ').filter { it.isNotBlank() }
    private fun emojiPage(): List<List<Key>> {
        emojiRows?.let { if (emojiRowsCat == emojiCat) return it }
        val list = (if (emojiCat == 0) recent() else CATS[emojiCat].second.split(' ')).take(27)
        val grid = list.chunked(9).map { r -> r.map { Key(it) } }.toMutableList()
        while (grid.size < 3) grid += listOf<Key>()
        val bottom = listOf(Key("ABC", SYM, 1.5f)) + CATS.mapIndexed { i, c -> Key(c.first, CAT - i) } + listOf(Key("⌫", DEL, 1.5f))
        return (grid + listOf(bottom)).also { emojiRows = it; emojiRowsCat = emojiCat }
    }
    private fun rememberEmoji(e: String) {
        val r = (listOf(e) + recent().filter { it != e }).take(27)
        ime.prefs().edit().putString("kb_recent_emoji", r.joinToString(" ")).apply()
        if (emojiCat == 0) emojiRows = null
    }

    /** Toolbar above the keys (like Gboard's strip). */
    private val tools = listOf(Key("😊", EMOJI), Key("📋", PASTE), Key("◀", LEFT), Key("▶", RIGHT), Key("⌄", HIDE))
    /** The same strip while a word is typed: three suggestions, the best one in the middle. */
    private var sugg: List<Key> = emptyList()
    private val bar get() = if (sugg.isNotEmpty() && !symbols && !emojis) sugg else tools

    private var russian = ime.prefs().getBoolean("kb_ru", true)
    private var symbols = false
    private var symPage2 = false
    private var emojis = false
    private var shift = false
    private var caps = false
    private var lastShiftTap = 0L
    private var info: EditorInfo? = null
    private val rows get() = if (emojis) emojiPage() else if (symbols) (if (symPage2) sym2 else sym) else if (russian) ru else en

    // ---- colours (follow the AOS theme; "kb_invert" gives the opposite tone) ----
    private var dark = true
    private var round = true
    private var bg = 0; private var keyFill = 0; private var modFill = 0; private var text = 0; private var sub = 0; private var accent = 0; private var onAccent = 0

    fun retheme() {
        val p = Palette(ime)
        dark = p.uiDark != ime.prefs().getBoolean("kb_invert", false)
        round = ime.prefs().getBoolean("kb_round", true)
        accent = p.accent; onAccent = p.onAccent
        bg = Color.parseColor(if (dark) "#121417" else "#E9ECEE")
        keyFill = Color.parseColor(if (dark) "#2A2F35" else "#FFFFFF")
        modFill = Color.parseColor(if (dark) "#1E2227" else "#D3D8DD")
        text = Color.parseColor(if (dark) "#E8EAED" else "#14181B")
        sub = Color.parseColor(if (dark) "#8A9097" else "#6B7178")
        setBackgroundColor(bg)
        invalidate()
    }

    private val h = Handler(Looper.getMainLooper())

    init {
        retheme()
        // Stay above the system navigation (and the AOS buttons drawn over it).
        // The IME window does not always get the navigation inset, so reserve the system bar height up front.
        val navRes = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val navH = if (navRes != 0) resources.getDimensionPixelSize(navRes) else (24 * d).toInt()
        setPadding(0, 0, 0, navH)
        setOnApplyWindowInsetsListener { v, ins ->
            val nav = maxOf(navH, ins.getInsets(WindowInsets.Type.navigationBars()).bottom)
            if (v.paddingBottom != nav) { v.setPadding(0, 0, 0, nav); requestLayout() }
            ins
        }
    }

    fun start(e: EditorInfo) {
        reset()
        info = e
        val cls = e.inputType and InputType.TYPE_MASK_CLASS
        symbols = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME
        symPage2 = false; emojis = false; caps = false; shift = false; lastChar = ' '
        russian = ime.prefs().getBoolean("kb_ru", true)
        suggestOn = suggestionsAllowed(e)
        clearWord()
        if (suggestOn) h.post { resyncWord() }
        autoShift()
        retheme()
    }

    /** Drops every pending press, repeat and long press (keyboard hidden, field changed). */
    fun reset() {
        h.removeCallbacks(longPress); h.removeCallbacks(repeatKey); h.removeCallbacks(askCaps)
        pressed = null; pointerId = -1; longDone = false; swiping = false
        invalidate()
    }

    fun save() { wordsRu?.save(); wordsEn?.save() }
    fun release() { reset(); save(); runCatching { spell?.close() }; spell = null }

    /**
     * Capital at the start of a sentence, when the field asks for it. Asking the app is a blocking call into its process,
     * slow on a weak phone, so it runs only on open, Enter and Backspace, posted after the key is handled. While typing, the
     * keyboard follows the sentence itself (see [afterChar]).
     */
    private fun autoShift() {
        h.removeCallbacks(askCaps); h.post(askCaps)
    }
    private val askCaps = Runnable {
        val e = info ?: return@Runnable
        if (caps) return@Runnable
        val ic = ime.currentInputConnection
        val want = ic != null && e.inputType != InputType.TYPE_NULL && ic.getCursorCapsMode(e.inputType) != 0
        if (want != shift) { shift = want; invalidate() }
    }

    /** Sentence-ends seen while typing: ". " / "! " / "? " turns the next letter capital, without asking the app. */
    private var lastChar = ' '
    private fun capsWanted() = info?.let { (it.inputType and (InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_CAP_WORDS)) != 0 } ?: false
    private fun afterChar(s: String) {
        val c = s.lastOrNull() ?: return
        if (!caps && c == ' ' && lastChar in ".!?" && capsWanted()) shift = true
        lastChar = c
    }

    // ---- suggestions and autocorrect (T9) ----
    private var suggestOn = false
    private val word = StringBuilder()
    /** Last autocorrection, undone by a Backspace right after it: (what was typed, what it became, the separator typed). */
    private var undo: Triple<String, String, String>? = null
    private var lastEdit = 0L
    private var wordsRu: KbWords? = null
    private var wordsEn: KbWords? = null
    private fun words(): KbWords = if (russian) (wordsRu ?: KbWords(ime, "ru").also { wordsRu = it }) else (wordsEn ?: KbWords(ime, "en").also { wordsEn = it })

    private fun suggestionsAllowed(e: EditorInfo): Boolean {
        if (!ime.prefs().getBoolean("kb_autocorrect", true)) return false
        if ((e.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        if ((e.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return false
        return when (e.inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI -> false
            else -> true
        }
    }

    private fun isWordChar(c: Char) = c.isLetter() || c == '\'' || c == '-'

    private fun clearWord() { word.setLength(0); spellWord = ""; spellSugg = emptyList(); spellTypo = false; updateSugg() }

    /** Reads the word right before the cursor from the app (only after a jump of the cursor, never per key). */
    private fun resyncWord() {
        word.setLength(0); undo = null
        val ic = ime.currentInputConnection
        if (suggestOn && ic != null && ic.getSelectedText(0).isNullOrEmpty()) {
            val after = ic.getTextAfterCursor(1, 0)?.firstOrNull()
            if (after == null || !isWordChar(after)) {
                val before = ic.getTextBeforeCursor(48, 0)?.toString().orEmpty()
                word.append(before.takeLastWhile { isWordChar(it) })
            }
        }
        wordChanged()
    }

    fun selectionChanged(start: Int, end: Int) {
        if (!suggestOn) return
        if (start != end) { word.setLength(0); undo = null; wordChanged(); return }
        // Our own edits also move the cursor; only a move well after the last key press comes from the user.
        if (SystemClock.uptimeMillis() - lastEdit > 250) { h.removeCallbacks(resync); h.postDelayed(resync, 30) }
    }
    private val resync = Runnable { resyncWord() }

    private fun wordChanged() {
        if (word.length >= 2) askSpell(word.toString()) else { spellWord = ""; spellSugg = emptyList(); spellTypo = false }
        updateSugg()
    }

    // The phone's spell checker (Gboard's on this phone): corrections for a misspelled word, asked in the background.
    private var spell: SpellCheckerSession? = null
    private var spellLang = ""
    private var spellSeq = 0
    private val spellAsked = HashMap<Int, String>()
    private var spellWord = ""
    private var spellSugg: List<String> = emptyList()
    private var spellTypo = false

    private val spellListener = object : SpellCheckerSession.SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) { results?.forEach { take(it) } }
        override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
            results?.forEach { s -> for (i in 0 until s.suggestionsCount) s.getSuggestionsInfoAt(i)?.let { take(it) } }
        }
        private fun take(si: SuggestionsInfo) {
            val w = spellAsked.remove(si.sequence) ?: return
            if (!w.equals(word.toString(), true)) return       // the word has changed since
            val attrs = si.suggestionsAttributes
            spellWord = w
            spellTypo = (attrs and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY) == 0 &&
                (attrs and (SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO or SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS)) != 0
            spellSugg = (0 until maxOf(0, si.suggestionsCount)).mapNotNull { si.getSuggestionAt(it) }.filter { it.isNotBlank() }
            updateSugg()
        }
    }

    private fun askSpell(w: String) {
        val lang = if (russian) "ru" else "en"
        if (spell == null || spellLang != lang) {
            runCatching { spell?.close() }
            spellLang = lang
            @Suppress("DEPRECATION")
            spell = runCatching {
                ime.getSystemService(TextServicesManager::class.java)
                    ?.newSpellCheckerSession(null, if (russian) Locale("ru", "RU") else Locale.US, spellListener, false)
            }.getOrNull()
        }
        val s = spell ?: return
        val seq = ++spellSeq
        spellAsked.clear(); spellAsked[seq] = w
        runCatching { s.getSentenceSuggestions(arrayOf(TextInfo(w, 0, w.length, 0, seq)), 4) }
    }

    /** The spelling of a suggestion follows the typed word: Привет / ПРИВЕТ / привет. */
    private fun matchCase(s: String): String {
        val w = word.toString()
        val low = s.lowercase()
        return when {
            w.length > 1 && w.all { !it.isLetter() || it.isUpperCase() } -> low.uppercase()
            w.firstOrNull()?.isUpperCase() == true -> low.replaceFirstChar { it.uppercase() }
            s.any { it.isUpperCase() } && w.firstOrNull()?.isLowerCase() == true && s.first().isUpperCase() -> s   // a name stays a name
            else -> low
        }
    }

    /** The correction autocorrect would make right now, or null. */
    private fun correction(): String? {
        val w = word.toString()
        if (w.length < 2 || !spellTypo || !spellWord.equals(w, true) || words().known(w)) return null
        val fix = spellSugg.firstOrNull() ?: return null
        return matchCase(fix).takeIf { !it.equals(w, true) }
    }

    private fun updateSugg() {
        val w = word.toString()
        val list = ArrayList<String>()
        if (suggestOn && w.isNotEmpty()) {
            val fix = correction()
            fun add(s: String) { val m = matchCase(s); if (list.none { it.equals(m, true) }) list += m }
            if (fix != null) { list += fix; list += "«$w»" }
            else if (w.length >= 2 && !spellTypo) list += w
            for (c in words().complete(w, 3)) add(c)
            for (s in spellSugg) add(s)
            if (list.isEmpty()) list += w
        }
        val top = list.take(3)
        // Slots left, middle, right; the best suggestion goes in the middle.
        val order = when (top.size) { 0 -> emptyList(); 1 -> listOf(-1, 0, -1); 2 -> listOf(1, 0, -1); else -> listOf(1, 0, 2) }
        sugg = order.mapIndexed { slot, i -> Key(if (i >= 0) top[i] else "", SUGG - slot, alt = if (i == 0) "best" else null) }
        invalidate()
    }

    /** A suggestion was tapped: it replaces the word and a space follows, as in Gboard. */
    private fun pickSuggestion(k: Key) {
        if (k.label.isEmpty()) return
        val ic = ime.currentInputConnection ?: return
        val typed = k.label.startsWith("«")
        val chosen = if (typed) k.label.removePrefix("«").removeSuffix("»") else k.label
        if (word.isNotEmpty()) ic.deleteSurroundingText(word.length, 0)
        ic.commitText("$chosen ", 1)
        words().learn(chosen, if (typed) 2 else 1)
        lastChar = ' '; undo = null
        word.setLength(0); wordChanged()
        if (shift && !caps) shift = false
    }

    /** A separator ends the word: autocorrect it when it looks like a typo, remember it, then type the separator. */
    private fun endWord(sep: String) {
        val w = word.toString()
        val ic = ime.currentInputConnection
        val fix = if (suggestOn && sep.isNotEmpty() && sep[0] in SEPARATORS) correction() else null
        if (fix != null && ic != null) {
            ic.deleteSurroundingText(w.length, 0); ic.commitText(fix, 1)
            undo = Triple(w, fix, sep)
            words().learn(fix)
        } else {
            undo = null
            if (w.length >= 2) words().learn(w)
        }
        word.setLength(0); wordChanged()
    }

    // ---- layout ----
    /** Key height: pull the handle at the top of the keyboard up / down to make the keys bigger or smaller (kept in prefs). */
    private var scale = ime.prefs().getFloat("kb_scale3", 1f).coerceIn(0.9f, 2f)   // 1 = Gboard size
    private val rowH get() = 54 * d * scale
    private val pad get() = 4 * d
    private val handleH get() = 14 * d
    private val barH get() = handleH + 40 * d

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (barH + rowH * 4 + pad * 3).toInt() + paddingBottom)
    }

    /** Key rectangles are computed once per layout and width, not on every frame. */
    private var laidOut: Any? = null
    private var laidW = 0
    private var laidH = 0f
    private fun layoutKeys() {
        val w = width.toFloat()
        // Strip: toolbar icons (emoji + paste on the left, cursor arrows in the middle, hide on the right) or three suggestion slots.
        val bw = 48 * d
        val slots = listOf(pad, pad + bw, w / 2 - bw, w / 2, w - pad - bw)
        tools.forEachIndexed { i, k -> k.rect.set(slots[i], handleH, slots[i] + bw, barH - pad) }
        sugg.forEachIndexed { i, k -> k.rect.set(w * i / 3f + pad, handleH, w * (i + 1) / 3f - pad, barH - pad) }
        if (laidOut === rows && laidW == width && laidH == rowH) return
        laidOut = rows; laidW = width; laidH = rowH
        var y = barH + pad * 0.5f
        val gridPage = !symbols || emojis
        val ref = if (emojis) 9f else rows[0].size.toFloat()
        for (row in rows) {
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            // Short rows (the 9-letter middle row of QWERTY, the last row of emoji) keep the key width of the top row.
            val unit = (w - pad) / maxOf(total, ref.takeIf { gridPage && row.all { k -> k.isChar } } ?: total)
            var x = (w - unit * total) / 2f
            for (k in row) {
                k.rect.set(x + pad / 2, y, x + unit * k.weight - pad / 2, y + rowH - pad)
                x += unit * k.weight
            }
            y += rowH
        }
    }

    // ---- painting ----
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = appFont(500) }
    private val bold = appFont(700)
    private val normal = appFont(500)
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = appFont(500) }
    private val bubble = RectF()
    private var pressed: Key? = null

    /** Enter shows what it will do in this field (search, send, go, next, done). */
    private fun enterLabel(): String {
        val e = info ?: return "⏎"
        if ((e.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 || (e.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "⏎"
        return when (e.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> "⌕"
            EditorInfo.IME_ACTION_SEND -> "➤"
            EditorInfo.IME_ACTION_GO -> "→"
            EditorInfo.IME_ACTION_NEXT -> "⇥"
            EditorInfo.IME_ACTION_PREVIOUS -> "⇤"
            EditorInfo.IME_ACTION_DONE -> "✓"
            else -> "⏎"
        }
    }

    private fun shown(k: Key) = when {
        k.code == SPACE -> if (symbols || emojis) "" else if (russian) "Русский" else "English"
        k.code == SHIFT && caps -> "⇪"
        k.code == ENTER -> enterLabel()
        k.code == EMOJI && emojis -> "АБВ"
        k.isChar && (shift || caps) -> k.label.uppercase()
        else -> k.label
    }

    private fun drawLabel(c: Canvas, s: String, r: RectF) = c.drawText(s, r.centerX(), r.centerY() - (label.descent() + label.ascent()) / 2, label)

    /** Fits a suggestion into its slot: smaller text first, then "…". */
    private fun fit(s: String, maxW: Float): String {
        if (label.measureText(s) <= maxW) return s
        label.textSize = 13 * d
        var t = s
        while (t.length > 2 && label.measureText("$t…") > maxW) t = t.dropLast(1)
        return if (t == s) s else "$t…"
    }

    override fun onDraw(c: Canvas) {
        layoutKeys()
        val r = 10 * d
        // Resize handle: a pill at the top centre, accent while it is being pulled.
        fill.color = if (resizing) accent else (sub and 0xFFFFFF) or 0x99000000.toInt()
        c.drawRoundRect(width / 2f - 22 * d, 5 * d, width / 2f + 22 * d, 9 * d, 2 * d, 2 * d, fill)
        val strip = bar
        if (strip === sugg) {
            // Suggestions: thin dividers, the best one in the middle in bold accent.
            fill.color = (sub and 0xFFFFFF) or 0x40000000
            for (i in 1..2) c.drawRect(width * i / 3f - d / 2, handleH + (barH - handleH) * 0.3f, width * i / 3f + d / 2, handleH + (barH - handleH) * 0.7f, fill)
            for (k in sugg) {
                if (k.label.isEmpty()) continue
                if (k === pressed) { fill.color = accentTint(); c.drawRoundRect(k.rect, r, r, fill) }
                val best = k.alt != null
                label.typeface = if (best) bold else normal
                label.color = if (best) accent else text
                label.textSize = 16 * d
                drawLabel(c, fit(k.label, k.rect.width() - 8 * d), k.rect)
            }
            label.typeface = normal
        } else for (k in tools) {
            if (k === pressed) { fill.color = accentTint(); c.drawRoundRect(k.rect, r, r, fill) }
            label.color = if (k.code == EMOJI && emojis) accent else sub
            label.textSize = (if (k.code == EMOJI && emojis) 13 else 18) * d
            drawLabel(c, shown(k), k.rect)
        }
        for (row in rows) for (k in row) {
            val active = (k.code == SHIFT && (shift || caps)) || k.code == ENTER
            val catOn = k.code == CAT - emojiCat && emojis
            val emojiKey = emojis && k.isChar
            fill.color = when {
                k === pressed && active -> lift(accent, 0.2f)
                k === pressed -> accentTint()
                active -> accent
                catOn -> accentTint()
                k.isChar || k.code == SPACE -> keyFill
                else -> modFill
            }
            // Emoji sit without plates (like Gboard); letters on circles; the wide keys are pills.
            if (!emojiKey || k === pressed) {
                // Round mode: every key is a pill over its whole cell (a big target for the finger), fully rounded ends.
                val rr = if (round) minOf(k.rect.width(), k.rect.height()) / 2f else r
                c.drawRoundRect(k.rect, rr, rr, fill)
            }
            label.color = if (active) onAccent else if (k.code == SPACE) sub else text
            label.textSize = (if (emojiKey) 26 else if (k.isChar) 22 else if (k.code == SPACE) 13 else if (k.code <= CAT) 18 else 16) * d * scale.coerceAtMost(1.5f)
            // Never wider than the key: a letter fits in about 60% of its width.
            if (k.isChar) label.textSize = minOf(label.textSize, k.rect.width() * 0.62f)
            drawLabel(c, shown(k), k.rect)
            k.alt?.let { a ->
                hint.color = sub; hint.textSize = 10 * d
                // On a round key the hint sits on the circle's upper right, inside it.
                val hx = k.rect.right - 7 * d
                c.drawText(a, hx, k.rect.top + 13 * d, hint)
            }
        }
        // Key preview above the finger (letters, digits, symbols, emoji), like Gboard.
        pressed?.takeIf { it.isChar && !longDone }?.let { k ->
            val bw = maxOf(k.rect.width() * 1.25f, 40 * d); val bh = rowH * 0.95f
            val top = maxOf(0f, k.rect.top - bh - 2 * d)
            val left = (k.rect.centerX() - bw / 2).coerceIn(0f, width - bw)
            bubble.set(left, top, left + bw, top + bh)
            fill.color = lift(keyFill, if (dark) 0.08f else 0f); fill.setShadowLayer(6 * d, 0f, 2 * d, 0x55000000)
            c.drawRoundRect(bubble, r, r, fill); fill.clearShadowLayer()
            label.color = text; label.textSize = 28 * d
            drawLabel(c, shown(k), bubble)
        }
    }

    private fun accentTint() = Color.argb(if (dark) 120 else 90, Color.red(accent), Color.green(accent), Color.blue(accent))

    // ---- touch ----
    private var longDone = false
    private var pointerId = -1
    private var downX = 0f
    private var swiping = false
    private var swipeSteps = 0
    private val repeatKey = object : Runnable {
        override fun run() { val k = pressed ?: return; act(k); h.postDelayed(this, if (k.code == DEL) 55 else 70) }
    }
    private val longPress = Runnable {
        val k = pressed ?: return@Runnable
        when {
            k.repeats -> { longDone = true; h.post(repeatKey) }
            k.code == SPACE && !swiping -> { longDone = true; ime.getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
            k.alt != null && k.isChar -> { longDone = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); typeChar(k.alt); invalidate() }
        }
    }

    private fun keyAt(x: Float, y: Float): Key? {
        var best: Key? = null; var dist = Float.MAX_VALUE
        if (y < barH) return bar.firstOrNull { it.rect.contains(x, y) && (it.code > SUGG || it.label.isNotEmpty()) }
        for (row in rows) for (k in row) {
            if (k.rect.contains(x, y)) return k
            // Gaps between keys belong to the nearest key, so a tap never falls through.
            val dx = maxOf(k.rect.left - x, 0f, x - k.rect.right); val dy = maxOf(k.rect.top - y, 0f, y - k.rect.bottom)
            val dd = dx * dx + dy * dy
            if (dd < dist) { dist = dd; best = k }
        }
        return if (dist < (pad * 3) * (pad * 3)) best else null
    }

    /** Finger down on a key: haptics, the immediate action of repeat keys, the long-press timer. */
    private fun press(k: Key, x: Float) {
        pressed = k; longDone = false; swiping = false; swipeSteps = 0; downX = x
        if (ime.prefs().getBoolean("kb_vibe", true)) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (k.repeats) act(k)
        h.postDelayed(longPress, if (k.repeats) 400L else 380L)
    }

    /** Finger up: a normal tap types the key; repeats, long presses and swipes have already done their work. */
    private fun release(typeIt: Boolean) {
        h.removeCallbacks(longPress); h.removeCallbacks(repeatKey)
        val k = pressed
        pressed = null
        if (typeIt && k != null && !longDone && !swiping && !k.repeats) tap(k)
        if (k?.code == DEL || swiping) autoShift()
        if (swiping && k?.code == SPACE && suggestOn) h.post { resyncWord() }
        swiping = false
    }

    private var resizing = false
    private var resizeY = 0f
    private var resizeFrom = 1f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (resizing) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val next = (resizeFrom + (resizeY - e.rawY) / (4 * 54 * d)).coerceIn(0.9f, 2f)
                    if (kotlin.math.abs(next - scale) > 0.01f) { scale = next; requestLayout(); invalidate() }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    resizing = false; ime.prefs().edit().putFloat("kb_scale3", scale).apply()
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); invalidate()
                }
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = e.getPointerId(0)
                // The top strip around the handle: drag up = bigger keys, down = smaller.
                if (e.y < handleH + 4 * d && (e.x - width / 2f).let { it > -90 * d && it < 90 * d }) {
                    resizing = true; resizeY = e.rawY; resizeFrom = scale; invalidate(); return true
                }
                keyAt(e.x, e.y)?.let { press(it, e.x) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger lands before the first lifts (fast typing): the first key counts now, the new one takes over.
                release(true)
                val i = e.actionIndex
                pointerId = e.getPointerId(i)
                keyAt(e.getX(i), e.getY(i))?.let { press(it, e.getX(i)) }
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(pointerId)
                if (i >= 0) move(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == pointerId) { release(true); pointerId = -1 }
            }
            MotionEvent.ACTION_UP -> { release(e.getPointerId(e.actionIndex) == pointerId); pointerId = -1 }
            MotionEvent.ACTION_CANCEL -> { release(false); pointerId = -1 }
        }
        invalidate()
        return true
    }

    private fun move(x: Float, y: Float) {
        val k = pressed ?: return
        val dx = x - downX
        when {
            // Swipe along Space moves the cursor, one character per step.
            k.code == SPACE && !longDone -> {
                val step = 11 * d
                val steps = (dx / step).toInt()
                if (!swiping && kotlin.math.abs(dx) > 16 * d) { swiping = true; h.removeCallbacks(longPress) }
                if (swiping && steps != swipeSteps) {
                    val key = if (steps > swipeSteps) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                    repeat(kotlin.math.abs(steps - swipeSteps)) { ime.sendDownUpKeyEvents(key) }
                    swipeSteps = steps; lastEdit = SystemClock.uptimeMillis()
                }
            }
            // Swipe left from Backspace deletes the previous word (once per swipe).
            k.code == DEL && !swiping && dx < -36 * d -> {
                swiping = true; h.removeCallbacks(longPress); h.removeCallbacks(repeatKey); deleteWord()
            }
            k.repeats || k.code == SPACE || k.code <= SUGG -> Unit
            // Sliding onto another key moves the press there (no long press carried over).
            !longDone -> {
                val n = keyAt(x, y)
                if (n != null && n !== k && !n.repeats && n.code != SPACE && n.code > SUGG) {
                    h.removeCallbacks(longPress); pressed = n; downX = x; h.postDelayed(longPress, 380)
                }
            }
        }
    }

    /** One step of a key that also repeats while held. */
    private fun act(k: Key) {
        when (k.code) {
            DEL -> delete()
            LEFT -> { ime.sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT); cursorMoved() }
            RIGHT -> { ime.sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT); cursorMoved() }
        }
    }

    private fun cursorMoved() {
        lastEdit = SystemClock.uptimeMillis()
        if (suggestOn) { h.removeCallbacks(resync); h.postDelayed(resync, 120) }
    }

    /** Types one character: letters grow the word, anything else ends it (autocorrect may fix it first). */
    private fun typeChar(raw: String) {
        val s = if (shift || caps) raw.uppercase() else raw
        lastEdit = SystemClock.uptimeMillis()
        if (emojis) { endWord(""); commit(s); rememberEmoji(raw); afterChar(s); return }
        val c = s.singleOrNull()
        if (c != null && isWordChar(c) && !(c == '-' && word.isEmpty())) {
            undo = null
            commit(s); word.append(c); wordChanged()
        } else {
            if (word.isNotEmpty()) endWord(s) else undo = null
            commit(s)
        }
        if (shift && !caps) shift = false
        afterChar(s)
    }

    private fun tap(k: Key) {
        when {
            k.code <= SUGG -> pickSuggestion(k)
            k.code <= CAT -> { emojiCat = CAT - k.code; emojiRows = null }
            k.code == 0 -> typeChar(k.label)
            k.code == SPACE -> { lastEdit = SystemClock.uptimeMillis(); if (word.isNotEmpty()) endWord(" ") else undo = null; commit(" "); afterChar(" ") }
            k.code == SHIFT -> {
                val now = System.currentTimeMillis()
                when {
                    caps -> { caps = false; shift = false }
                    shift && now - lastShiftTap < 350 -> caps = true
                    else -> shift = !shift
                }
                lastShiftTap = now
            }
            k.code == SYM -> { if (emojis) { emojis = false; symbols = false } else symbols = !symbols; symPage2 = false; if (!symbols) autoShift() }
            k.code == SYM2 -> symPage2 = !symPage2
            k.code == LANG -> {
                russian = !russian; symbols = false; emojis = false; ime.prefs().edit().putBoolean("kb_ru", russian).apply()
                word.setLength(0); wordChanged(); autoShift()
            }
            k.code == ENTER -> { if (word.isNotEmpty()) endWord(""); enter() }
            k.code == EMOJI -> { emojis = !emojis; if (emojis && emojiCat == 0 && recent().isEmpty()) emojiCat = 1; if (!emojis) autoShift() }
            k.code == PASTE -> { ime.currentInputConnection?.performContextMenuAction(android.R.id.paste); cursorMoved() }
            k.code == HIDE -> ime.requestHideSelf(0)
        }
    }

    private fun commit(s: String) { ime.currentInputConnection?.commitText(s, 1) }

    private fun delete() {
        lastEdit = SystemClock.uptimeMillis()
        val ic = ime.currentInputConnection
        // Backspace right after an autocorrection puts back what was typed, and the keyboard remembers that word.
        undo?.let { (typed, fixed, sep) ->
            undo = null
            if (ic != null) {
                ic.deleteSurroundingText(fixed.length + sep.length, 0); ic.commitText(typed, 1)
                words().learn(typed, 2)
                word.setLength(0); word.append(typed); wordChanged()
                lastChar = typed.lastOrNull() ?: ' '
                return
            }
        }
        // A plain Backspace key event also deletes a selection; no extra call into the app per key.
        ime.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        lastChar = ' '
        if (word.isNotEmpty()) { word.setLength(word.length - 1); wordChanged() }
        else if (suggestOn) { h.removeCallbacks(resync); h.postDelayed(resync, 150) }   // stepping back into the previous word
    }

    /** Deletes the word before the cursor together with the spaces after it (or the selection, if there is one). */
    private fun deleteWord() {
        val ic = ime.currentInputConnection ?: return
        undo = null; lastEdit = SystemClock.uptimeMillis()
        if (!ic.getSelectedText(0).isNullOrEmpty()) { delete(); return }
        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: return
        var n = before.length
        while (n > 0 && before[n - 1].isWhitespace()) n--
        while (n > 0 && !before[n - 1].isWhitespace()) n--
        val count = before.length - n
        if (count > 0) ic.deleteSurroundingText(count, 0)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        lastChar = ' '
        word.setLength(0); wordChanged()
    }

    private fun enter() {
        val e = info
        val ic = ime.currentInputConnection ?: return
        lastEdit = SystemClock.uptimeMillis()
        val action = (e?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val multiLine = ((e?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (e == null || multiLine || (e.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 ||
            action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) ime.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        else ic.performEditorAction(action)
        autoShift()
    }
}
