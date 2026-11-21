package org.anthezis.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.FrameLayout
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * AOS Files: the phone's storage in the AOS style (the same glass cards and pills as Messages and Gallery).
 *  - home: storage card with the used space, categories (Images, Videos, Audio, Documents, Downloads, APKs), recent files
 *  - browse: folders with a path bar, sort (name / date / size), search in the open folder
 *  - a file: open, share, rename, details, delete (after a confirmation); in a folder: new folder
 * Categories come from the media store; browsing every folder needs "All files access", which the user turns on in Settings.
 */
class FilesActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var body: LinearLayout
    private lateinit var header: TextView
    private lateinit var pathRow: LinearLayout
    private val pool = Executors.newFixedThreadPool(2)
    private val root: File = Environment.getExternalStorageDirectory()
    private var dir: File? = null            // null = Files home
    private var category: String? = null     // a category list instead of a folder
    private var sort = 0                     // 0 name, 1 newest, 2 largest
    private var query = ""
    private lateinit var search: EditText
    private lateinit var createButton: ImageView
    private var syncingSearch = false
    private var gridMode = false
    private val prefs by lazy { getSharedPreferences("aos_setup", MODE_PRIVATE) }
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val searchRefresh = Runnable { if (!isDestroyed) show() }
    private val thumbnailCache = android.util.LruCache<String, android.graphics.Bitmap>(32)

    private fun text(t: String, sp: Float, c: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(c); typeface = appFont(if (bold) 700 else 400)
    }

    private fun allFiles() = Environment.isExternalStorageManager()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Palette(this)
        gridMode = prefs.getBoolean("files_grid", false)
        header = text("Files", 26f, p.text, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(8), 0, 0, 0)
        }
        pathRow = LinearLayout(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(40)) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(52), dp(16), 0); setBackgroundColor(p.glassScreen)
            addView(LinearLayout(this@FilesActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(iconButton(R.drawable.ic_chevron_left) { onBackPressed() }.apply { contentDescription = "Back" })
                addView(header, LinearLayout.LayoutParams(0, -2, 1f))
                addView(iconButton(R.drawable.ic_chevron_right) { goUp() }.apply {
                    rotation = -90f; contentDescription = "Up one folder"
                })
                addView(iconButton(R.drawable.ic_start) {
                    gridMode = !gridMode; prefs.edit().putBoolean("files_grid", gridMode).apply(); show()
                }.apply { contentDescription = "Switch list or grid" })
                addView(iconButton(R.drawable.ic_list) {
                    AlertDialog.Builder(this@FilesActivity).setTitle("Sort files")
                        .setSingleChoiceItems(arrayOf("Name", "Newest first", "Largest first"), sort) { dialog, which ->
                            sort = which; dialog.dismiss(); show()
                        }.setNegativeButton("Cancel", null).show()
                }.apply { contentDescription = "Sort files" })
                createButton = iconButton(R.drawable.ic_folder) { newFolder() }.apply { contentDescription = "New folder" }
                addView(createButton)
            }, LinearLayout.LayoutParams(-1, dp(48)))
            search = EditText(this@FilesActivity).apply {
                hint = "Search files"; setTextColor(p.text); setHintTextColor(p.sub); textSize = 15f
                typeface = appFont(400); setSingleLine(); setPadding(dp(16), 0, dp(16), 0)
                background = shape(p.surfaceHigh, 26)
                val glyph = getDrawable(R.drawable.ic_search)!!.mutate().apply { setTint(p.accent); setBounds(0, 0, dp(22), dp(22)) }
                setCompoundDrawables(glyph, null, null, null); compoundDrawablePadding = dp(12)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        if (syncingSearch) return
                        query = s?.toString().orEmpty().trim()
                        searchHandler.removeCallbacks(searchRefresh); searchHandler.postDelayed(searchRefresh, 220)
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            }
            addView(search, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12); bottomMargin = dp(8) })
            addView(HorizontalScrollView(this@FilesActivity).apply { isHorizontalScrollBarEnabled = false; addView(pathRow) },
                LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
            addView(ScrollView(this@FilesActivity).apply { isVerticalScrollBarEnabled = false; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        addAosTopBar(p)
        val need = (if (android.os.Build.VERSION.SDK_INT >= 33)
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
            else listOf(Manifest.permission.READ_EXTERNAL_STORAGE))
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty() && !allFiles()) requestPermissions(need.toTypedArray(), 3)
        show()
    }

    override fun onResume() { super.onResume(); show() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = show()
    override fun onDestroy() { searchHandler.removeCallbacks(searchRefresh); pool.shutdownNow(); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            query.isNotEmpty() -> { query = ""; show() }
            category != null -> { category = null; show() }
            dir != null && dir != root -> { dir = dir?.parentFile; show() }
            dir != null -> { dir = null; show() }
            else -> super.onBackPressed()
        }
    }

    /** Explorer's "up" button: parent folder, then the storage home. */
    private fun goUp() {
        when {
            category != null -> { category = null }
            dir != null && dir != root -> dir = dir?.parentFile
            else -> dir = null
        }
        query = ""; show()
    }

    /** A drive row like This PC in Windows: icon, name, a long usage bar, and "free of total" on the right. Tap: open the storage. */
    private fun driveTile(): View {
        val st = StatFs(root.absolutePath)
        val total = st.totalBytes; val free = st.availableBytes; val used = total - free
        val frac = used.toFloat() / total.coerceAtLeast(1)
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = glassShape(p, 14); setPadding(dp(12), dp(12), dp(14), dp(12))
            addView(ImageView(this@FilesActivity).apply {
                setImageResource(R.drawable.ic_folder); setColorFilter(p.accent); setPadding(dp(4), dp(4), dp(4), dp(4))
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(LinearLayout(this@FilesActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), 0)
                addView(text("Internal storage", 14f, p.text, true))
                addView(object : View(this@FilesActivity) {
                    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    override fun onDraw(c: android.graphics.Canvas) {
                        // Square-cornered bar like the Windows one; it turns red when the drive is almost full.
                        paint.color = p.surfaceHigh; c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                        paint.color = if (frac > 0.9f) 0xFFE5484D.toInt() else p.accent
                        c.drawRect(0f, 0f, width * frac, height.toFloat(), paint)
                    }
                }, LinearLayout.LayoutParams(-1, dp(14)).apply { topMargin = dp(6) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(LinearLayout(this@FilesActivity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.END
                addView(text(if (allFiles()) "Ext4" else "No access", 11f, p.sub))
                addView(text("${Formatter.formatFileSize(this@FilesActivity, free)} free of ${Formatter.formatFileSize(this@FilesActivity, total)}", 11f, p.text))
            })
            setOnClickListener {
                if (allFiles()) { dir = root; category = null; query = ""; show() }
                else try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
            pressFx(p, round = false)
        }
    }

    private fun iconButton(res: Int, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(res); setColorFilter(p.text); setPadding(dp(10), dp(10), dp(10), dp(10)); background = glassShape(p, 20, true)
        setOnClickListener { onClick() }; pressFx(p)
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) }
    }

    private fun pill(t: String, on: Boolean, onClick: () -> Unit) = text(t, 13f, if (on) p.onAccent else p.text, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0); background = if (on) shape(p.accent, 18) else glassShape(p, 18, true)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(6) }
    }

    // ---- screens ----

    private fun show() {
        body.tag = Any()
        body.removeAllViews(); pathRow.removeAllViews()
        if (search.text.toString().trim() != query) {
            syncingSearch = true; search.setText(query); search.setSelection(search.length()); syncingSearch = false
        }
        createButton.visibility = if (dir != null && category == null) View.VISIBLE else View.GONE
        (pathRow.parent as? View)?.visibility = if (dir == null && category == null) View.GONE else View.VISIBLE
        pathRow.addView(pill("Home", dir == null && category == null) { dir = null; category = null; query = ""; show() })
        when {
            category == "Favourites" -> { header.text = "Favourites"; pathRow.addView(pill("Favourites", true) {}); showFavourites() }
            category != null -> { header.text = category; pathRow.addView(pill(category!!, true) {}); showCategory(category!!) }
            dir != null -> {
                header.text = dir!!.name.ifEmpty { "Storage" }; paintPath()
                clip?.let { c ->
                    val target = dir!!
                    pathRow.addView(pill("Paste “${c.name}”", true) { paste(target) })
                    pathRow.addView(pill("✕", false) { clip = null; show() })
                }
                showFolder(dir!!)
            }
            query.isNotEmpty() -> {
                header.text = "Search"
                loadEntries({ sorted(media("${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", 500, arrayOf("%$query%"))) }) { entries ->
                    showEntries(entries)
                }
            }
            else -> { header.text = "Files"; showHome() }
        }
    }

    private fun paintPath() {
        val parts = mutableListOf<File>(); var f: File? = dir
        while (f != null && f.absolutePath.startsWith(root.absolutePath)) { parts.add(0, f); if (f == root) break; f = f.parentFile }
        for (part in parts) pathRow.addView(pill(if (part == root) "Storage" else part.name, part == dir) { dir = part; query = ""; show() })
    }

    private fun card(v: View, top: Int = 12) = body.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })

    private fun showHome() {
        val recent = LinearLayout(this)
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), dp(12), 0, dp(10))
            addView(text("Recent files", 18f, p.text, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("Show all", 13f, p.accent, true).apply {
                setPadding(dp(12), dp(8), dp(4), dp(8))
                setOnClickListener { category = "Recent"; query = ""; show() }
            })
        })
        body.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(recent)
        }, LinearLayout.LayoutParams(-1, dp(154)))
        // Storage: used and free space with a bar.
        val st = StatFs(root.absolutePath)
        val total = st.totalBytes; val free = st.availableBytes; val used = total - free
        val storage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = shape(p.accent, 24); setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(text("Internal storage", 15f, p.onAccent, true))
            addView(text("${Formatter.formatFileSize(this@FilesActivity, used)} used of ${Formatter.formatFileSize(this@FilesActivity, total)}", 13f, p.onAccent).apply { alpha = 0.85f })
            addView(object : View(this@FilesActivity) {
                private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(c: android.graphics.Canvas) {
                    val r = height / 2f
                    paint.color = (p.onAccent and 0xFFFFFF) or 0x44000000; c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
                    paint.color = p.onAccent; c.drawRoundRect(0f, 0f, width * (used.toFloat() / total.coerceAtLeast(1)), height.toFloat(), r, r, paint)
                }
            }, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(12) })
            addView(text(if (allFiles()) "Browse all folders" else "Allow access to all files to browse folders", 14f, p.accent, true).apply {
                gravity = Gravity.CENTER; background = shape(p.onAccent, 18); setPadding(0, dp(10), 0, dp(10))
                setOnClickListener {
                    if (allFiles()) { dir = root; show() }
                    else try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                    catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        }
        // Categories, two per row.
        body.addView(text("Categories", 17f, p.text, true).apply { setPadding(dp(4), dp(18), 0, dp(4)) })
        val cats = listOf("Downloads" to R.drawable.ic_history, "Images" to R.drawable.ic_camera,
            "Videos" to R.drawable.ic_play, "Audio" to R.drawable.ic_volume, "Documents" to R.drawable.ic_list, "APKs" to R.drawable.ic_boost)
        val hints = mapOf("Downloads" to "Saved from apps", "Images" to "Photos & pictures", "Videos" to "Clips & movies",
            "Audio" to "Music & recordings", "Documents" to "PDFs & text", "APKs" to "App packages")
        for (row in cats.chunked(2)) body.addView(LinearLayout(this).apply {
            for ((i, c) in row.withIndex()) addView(LinearLayout(this@FilesActivity).apply {
                gravity = Gravity.CENTER_VERTICAL; background = glassShape(p, 22); setPadding(dp(12), dp(12), dp(12), dp(12))
                addView(ImageView(this@FilesActivity).apply {
                    setImageResource(c.second); setColorFilter(p.accent); setPadding(dp(7), dp(7), dp(7), dp(7))
                    background = shape((p.accent and 0xFFFFFF) or 0x22000000, 12)
                }, LinearLayout.LayoutParams(dp(34), dp(34)))
                addView(LinearLayout(this@FilesActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0)
                    addView(text(c.first, 14f, p.text, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(text(hints[c.first].orEmpty(), 10f, p.sub).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                setOnClickListener { category = c.first; query = ""; show() }; pressFx(p, round = false)
            }, LinearLayout.LayoutParams(0, dp(80), 1f).apply { if (i > 0) marginStart = dp(8) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        body.addView(text("Collections", 17f, p.text, true).apply { setPadding(dp(4), dp(20), 0, dp(8)) })
        card(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = glassShape(p, 22); setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(ImageView(this@FilesActivity).apply { setImageResource(R.drawable.ic_star); setColorFilter(p.accent) }, LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(LinearLayout(this@FilesActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
                addView(text("Favourites", 15f, p.text, true))
                addView(text("Files you keep close", 12f, p.sub))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("›", 26f, p.sub))
            setOnClickListener { category = "Favourites"; query = ""; show() }; pressFx(p, round = false)
        }, 0)
        card(driveTile(), 16)
        loadEntries({ media(null, 8) }) { list ->
            if (list.isEmpty()) recent.addView(text("Recent files will appear here once access is allowed.", 13f, p.sub).apply {
                setPadding(dp(8), dp(18), dp(8), 0); maxWidth = resources.displayMetrics.widthPixels - dp(48)
            })
            else list.forEach { recent.addView(fileCard(it), LinearLayout.LayoutParams(dp(144), dp(148)).apply { marginEnd = dp(10) }) }
        }
    }

    // ---- data ----

    private class Entry(val name: String, val size: Long, val date: Long, val folder: Boolean, val file: File?, val uri: Uri?, val mime: String?)

    private fun loadEntries(work: () -> List<Entry>, done: (List<Entry>) -> Unit) {
        val token = Any(); body.tag = token
        pool.execute {
            val list = runCatching { work() }.getOrDefault(emptyList())
            runOnUiThread { if (!isDestroyed && !isFinishing && body.tag === token) done(list) }
        }
    }

    private fun media(where: String?, limit: Int, args: Array<String>? = null): List<Entry> {
        val uri = MediaStore.Files.getContentUri("external")
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATA)
        val qa = Bundle().apply {
            if (where != null) { putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, where); putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args) }
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val out = mutableListOf<Entry>()
        contentResolver.query(uri, cols, qa, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (c.getString(4) == null && c.getLong(2) == 0L) continue   // folders
                out += Entry(name, c.getLong(2), c.getLong(3) * 1000, false, c.getString(5)?.let { File(it) }, android.content.ContentUris.withAppendedId(uri, c.getLong(0)), c.getString(4))
            }
        }
        return out
    }

    private fun showCategory(cat: String) {
        if (cat == "Recent") {
            loadEntries({ sorted(media(null, 500)) }) { list -> showEntries(list.filter { query.isEmpty() || it.name.contains(query, true) }) }
            return
        }
        val (where, args) = when (cat) {
            "Images" -> "${MediaStore.MediaColumns.MIME_TYPE} LIKE ?" to arrayOf("image/%")
            "Videos" -> "${MediaStore.MediaColumns.MIME_TYPE} LIKE ?" to arrayOf("video/%")
            "Audio" -> "${MediaStore.MediaColumns.MIME_TYPE} LIKE ?" to arrayOf("audio/%")
            "Documents" -> "(${MediaStore.MediaColumns.MIME_TYPE} LIKE ? OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE ? OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE ?)" to arrayOf("application/pdf", "text/%", "application/vnd%")
            "Downloads" -> "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" to arrayOf("Download%")
            else -> "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" to arrayOf("%.apk")
        }
        body.addView(text("Loading…", 13f, p.sub).apply { setPadding(dp(4), dp(8), 0, 0) })
        loadEntries({ sorted(media(where, 500, args)) }) { list ->
            body.removeAllViews()
            val shownList = list.filter { query.isEmpty() || it.name.contains(query, true) }
            showEntries(shownList)
        }
    }

    private fun showFolder(f: File) {
        if (!allFiles()) { body.addView(text("Allow access to all files on the Files home to browse folders.", 14f, p.sub)); return }
        loadEntries({
            sorted((f.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".") }.map {
                Entry(it.name, if (it.isDirectory) (it.list()?.size ?: 0).toLong() else it.length(), it.lastModified(), it.isDirectory, it, null, null)
            })
        }) { list ->
            val shownList = list.filter { query.isEmpty() || it.name.contains(query, true) }
            if (shownList.isEmpty()) body.addView(text(if (query.isEmpty()) "Empty folder" else "Nothing found", 14f, p.sub).apply { setPadding(dp(4), dp(10), 0, 0) })
            showEntries(shownList)
        }
    }

    private fun sorted(l: List<Entry>) = when (sort) {
        1 -> l.sortedWith(compareByDescending<Entry> { it.folder }.thenByDescending { it.date })
        2 -> l.sortedWith(compareByDescending<Entry> { it.folder }.thenByDescending { it.size })
        else -> l.sortedWith(compareByDescending<Entry> { it.folder }.thenBy { it.name.lowercase() })
    }

    private fun showEntries(entries: List<Entry>) {
        body.removeAllViews()
        if (dir != null && category == null && query.isEmpty()) body.addView(driveTile(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        body.addView(text("${entries.size} items · ${Formatter.formatFileSize(this, entries.filter { !it.folder }.sumOf { it.size })}", 12f, p.sub).apply {
            setPadding(dp(4), dp(8), 0, dp(8))
        })
        if (entries.isEmpty()) {
            body.addView(text(if (query.isEmpty()) "No files here yet" else "No matching files", 15f, p.sub).apply {
                gravity = Gravity.CENTER; setPadding(dp(12), dp(32), dp(12), dp(32))
            })
        } else if (!gridMode) entries.forEach { body.addView(row(it)) }
        else for (pair in entries.chunked(2)) body.addView(LinearLayout(this).apply {
            pair.forEachIndexed { index, entry ->
                addView(fileCard(entry), LinearLayout.LayoutParams(0, dp(148), 1f).apply { if (index > 0) marginStart = dp(10) })
            }
            if (pair.size == 1) addView(View(this@FilesActivity), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(10) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun thumbnail(entry: Entry): ImageView = ImageView(this).apply {
        setImageResource(iconFor(entry)); setColorFilter(p.accent); setPadding(dp(26), dp(20), dp(26), dp(20))
        background = shape(p.surfaceHigh, 16); clipToOutline = true
        val mediaType = entry.mime ?: entry.file?.let { mimeOf(it) }.orEmpty()
        val uri = entry.uri
        if (uri != null && (mediaType.startsWith("image/") || mediaType.startsWith("video/"))) {
            val key = uri.toString()
            fun applyBitmap(bitmap: android.graphics.Bitmap) {
                clearColorFilter(); setPadding(0, 0, 0, 0); scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(bitmap)
            }
            val cached = thumbnailCache.get(key)
            if (cached != null) applyBitmap(cached)
            else pool.execute {
                val bitmap = runCatching { contentResolver.loadThumbnail(uri, android.util.Size(dp(160), dp(120)), null) }.getOrNull()
                if (bitmap != null) {
                    thumbnailCache.put(key, bitmap)
                    post { if (!isDestroyed && isAttachedToWindow) applyBitmap(bitmap) }
                }
            }
        }
    }

    private fun fileCard(entry: Entry): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = glassShape(p, 20); setPadding(dp(8), dp(8), dp(8), dp(8))
        addView(FrameLayout(this@FilesActivity).apply {
            addView(thumbnail(entry), FrameLayout.LayoutParams(-1, -1))
            addView(ImageView(this@FilesActivity).apply {
                setImageResource(R.drawable.ic_more); setColorFilter(p.text); setPadding(dp(8), dp(8), dp(8), dp(8))
                background = shape(p.surface, 8); contentDescription = "Actions for ${entry.name}"
                setOnClickListener { actions(entry) }
            }, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP or Gravity.END).apply { topMargin = dp(4); marginEnd = dp(4) })
        }, LinearLayout.LayoutParams(-1, dp(88)))
        addView(text(entry.name, 13f, p.text, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(dp(2), dp(6), dp(2), 0)
        }, LinearLayout.LayoutParams(-1, -2))
        addView(text(if (entry.folder) "${entry.size} items" else Formatter.formatFileSize(this@FilesActivity, entry.size), 10f, p.sub).apply {
            maxLines = 1; setPadding(dp(2), 0, 0, 0)
        })
        setOnClickListener { if (entry.folder) { dir = entry.file; category = null; query = ""; show() } else open(entry) }
        setOnLongClickListener { actions(entry); true }
    }

    private fun favouriteEntries(): List<Entry> = runCatching {
        val array = org.json.JSONArray(prefs.getString("files_favourites", "[]"))
        (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            Entry(item.getString("name"), item.optLong("size"), item.optLong("date"), false,
                item.optString("file").takeIf { it.isNotEmpty() }?.let { File(it) },
                item.optString("uri").takeIf { it.isNotEmpty() }?.let { Uri.parse(it) },
                item.optString("mime").takeIf { it.isNotEmpty() })
        }
    }.getOrDefault(emptyList())

    private fun entryKey(entry: Entry) = entry.uri?.toString() ?: entry.file?.absolutePath ?: entry.name
    private fun toggleFavourite(entry: Entry) {
        val entries = favouriteEntries().toMutableList()
        if (!entries.removeAll { entryKey(it) == entryKey(entry) }) entries.add(entry)
        val array = org.json.JSONArray()
        entries.forEach { e -> array.put(org.json.JSONObject().apply {
            put("name", e.name); put("size", e.size); put("date", e.date)
            put("file", e.file?.absolutePath.orEmpty()); put("uri", e.uri?.toString().orEmpty()); put("mime", e.mime.orEmpty())
        }) }
        prefs.edit().putString("files_favourites", array.toString()).apply()
        if (category == "Favourites") show()
    }

    private fun showFavourites() = showEntries(sorted(favouriteEntries()).filter { query.isEmpty() || it.name.contains(query, true) })

    private fun iconFor(e: Entry): Int {
        val m = e.mime ?: e.file?.let { mimeOf(it) } ?: ""
        return when {
            e.folder -> R.drawable.ic_folder
            m.startsWith("image/") -> R.drawable.ic_camera
            m.startsWith("video/") -> R.drawable.ic_play
            m.startsWith("audio/") -> R.drawable.ic_volume
            e.name.endsWith(".apk", true) -> R.drawable.ic_boost
            else -> R.drawable.ic_list
        }
    }

    private fun row(e: Entry) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; background = glassShape(p, 18); setPadding(dp(12), dp(10), dp(12), dp(10))
        addView(ImageView(this@FilesActivity).apply {
            setImageResource(iconFor(e)); setColorFilter(if (e.folder) p.onAccent else p.accent); setPadding(dp(9), dp(9), dp(9), dp(9))
            background = shape(if (e.folder) p.accent else p.surfaceHigh, 12)
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(LinearLayout(this@FilesActivity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
            addView(text(e.name, 14f, p.text, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            addView(text((if (e.folder) "${e.size} items" else Formatter.formatFileSize(this@FilesActivity, e.size)) + "  ·  " +
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(e.date)), 12f, p.sub))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        setOnClickListener { if (e.folder) { dir = e.file; query = ""; show() } else open(e) }
        setOnLongClickListener { actions(e); true }
        pressFx(p, round = false)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }

    // ---- actions ----

    private fun mimeOf(f: File) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
    private fun uriOf(e: Entry): Uri? = e.uri ?: e.file?.let { AosFileProvider.uri(it) }

    private fun open(e: Entry) {
        val u = uriOf(e) ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, e.mime ?: e.file?.let { mimeOf(it) } ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) { Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show() }
    }

    private fun actions(e: Entry) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (!e.folder) { items += "Open" to { open(e) }; items += "Share" to { share(e) } }
        if (e.file != null && allFiles()) {
            items += "Copy" to { clip = e.file; clipMove = false; hint("Open a folder and tap Paste") }
            items += "Move" to { clip = e.file; clipMove = true; hint("Open a folder and tap Paste") }
            items += "Rename" to { rename(e) }; items += "Delete" to { delete(e) }
        }
        items += "Details" to { details(e) }
        if (!e.folder) {
            val favourite = favouriteEntries().any { entryKey(it) == entryKey(e) }
            items.add(0, (if (favourite) "Remove from favourites" else "Add to favourites") to { toggleFavourite(e) })
        }
        AlertDialog.Builder(this).setTitle(e.name).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    private fun share(e: Entry) {
        val u = uriOf(e) ?: return
        try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(e.mime ?: e.file?.let { mimeOf(it) } ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share")) } catch (_: Exception) {}
    }

    private fun ask(title: String, value: String, ok: (String) -> Unit) {
        val field = EditText(this).apply { setText(value); setSelectAllOnFocus(true); setSingleLine() }
        AlertDialog.Builder(this).setTitle(title).setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("OK") { _, _ -> field.text.toString().trim().takeIf { it.isNotEmpty() }?.let(ok) }.setNegativeButton("Cancel", null).show()
    }

    private fun rename(e: Entry) {
        val f = e.file ?: return
        ask("Rename", f.name) { n ->
            if (n.contains('/')) return@ask
            if (f.renameTo(File(f.parentFile, n))) show() else Toast.makeText(this, "Cannot rename", Toast.LENGTH_SHORT).show()
        }
    }

    private fun delete(e: Entry) {
        val f = e.file ?: return
        AlertDialog.Builder(this).setTitle("Delete ${f.name}?").setMessage(if (f.isDirectory) "The folder and everything in it will be deleted." else "The file will be deleted.")
            .setPositiveButton("Delete") { _, _ -> if (f.deleteRecursively()) show() else Toast.makeText(this, "Cannot delete", Toast.LENGTH_SHORT).show() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun newFolder() {
        val d = dir
        if (d == null || !allFiles()) { Toast.makeText(this, "Open a folder first", Toast.LENGTH_SHORT).show(); return }
        ask("New folder", "New folder") { n -> if (File(d, n).mkdirs()) show() else Toast.makeText(this, "Cannot create", Toast.LENGTH_SHORT).show() }
    }

    // ---- copy / move: the file waits on the clipboard until Paste in the path bar of a folder ----

    private var clip: File? = null
    private var clipMove = false

    private fun hint(t: String) { Toast.makeText(this, t, Toast.LENGTH_SHORT).show(); show() }

    private fun freeName(d: File, name: String): File {
        var f = File(d, name); var n = 1
        val base = name.substringBeforeLast('.', name); val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        while (f.exists()) { f = File(d, "$base ($n)$ext"); n++ }
        return f
    }

    private fun paste(target: File) {
        val src = clip ?: return
        if (src.isDirectory && (target.canonicalPath + "/").startsWith(src.canonicalPath + "/")) {
            Toast.makeText(this, "Cannot put a folder inside itself", Toast.LENGTH_SHORT).show(); return
        }
        val move = clipMove
        if (move && src.parentFile?.canonicalPath == target.canonicalPath) { clip = null; show(); return }
        Toast.makeText(this, if (move) "Moving…" else "Copying…", Toast.LENGTH_SHORT).show()
        pool.execute {
            val dest = freeName(target, src.name)
            val ok = runCatching {
                if (move && src.renameTo(dest)) true
                else src.copyRecursively(dest, overwrite = false) && (!move || src.deleteRecursively())
            }.getOrDefault(false)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (ok) clip = null
                Toast.makeText(this, if (ok) (if (move) "Moved" else "Copied") else "Could not finish", Toast.LENGTH_SHORT).show()
                show()
            }
        }
    }

    private fun details(e: Entry) {
        val rows = listOfNotNull("Name" to e.name, if (e.folder) "Items" to "${e.size}" else "Size" to Formatter.formatFileSize(this, e.size),
            "Modified" to DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(e.date)),
            e.file?.let { "Path" to it.absolutePath }, e.mime?.let { "Type" to it })
        AlertDialog.Builder(this).setTitle("Details").setMessage(rows.joinToString("\n\n") { "${it.first}\n${it.second}" }).setPositiveButton("OK", null).show()
    }
}

/** Hands one file of the storage to another app for viewing or sharing (read-only, content:// instead of a raw path). */
class AosFileProvider : ContentProvider() {
    companion object {
        private const val AUTH = "org.anthezis.ahome.files"
        fun uri(f: File): Uri = Uri.Builder().scheme("content").authority(AUTH).encodedPath(Uri.encode(f.absolutePath, "/")).build()
    }
    private fun file(u: Uri) = File(u.path ?: "").canonicalFile.takeIf { it.path.startsWith(Environment.getExternalStorageDirectory().canonicalPath) }
    override fun onCreate() = true
    override fun getType(uri: Uri) = file(uri)?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.extension.lowercase()) } ?: "application/octet-stream"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        file(uri)?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = file(uri) ?: return null
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
