package org.anthezis.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * AOS Gallery: photos and videos of this phone in the AOS style, with what the big Android galleries do best:
 *  - grid: pinch to change the columns (2 to 5, Google Photos), long press to select several (share, favourite, trash)
 *  - filters: All, Favourites, Videos, Screenshots, Trash, then every album (Samsung / Xiaomi)
 *  - viewer: pinch and double-tap zoom, swipe to the next photo, swipe down to close, videos play in place,
 *    slideshow (Sony / Samsung), favourite, edit, set as wallpaper, details
 *  - trash: deleted items wait in the system trash and can be restored; "Delete forever" asks the system first
 * It reads the system media store; nothing is copied, and every change goes through the system's own confirmation.
 */
class GalleryActivity : Activity() {
    private class Item(
        val uri: Uri, val name: String, val date: Long, val album: String, val video: Boolean, val duration: Long,
        val fav: Boolean, val size: Long, val w: Int, val h: Int, val path: String, val trashed: Boolean,
    )

    private companion object {
        const val F_ALL = ""; const val F_FAV = "\u0000fav"; const val F_VIDEO = "\u0000video"; const val F_SHOTS = "\u0000shots"; const val F_TRASH = "\u0000trash"
        const val RQ_DELETE = 6; const val RQ_FAV = 7; const val RQ_TRASH = 8; const val RQ_RESTORE = 9
    }

    private lateinit var p: Palette
    private lateinit var grid: GridView
    private lateinit var albumRow: LinearLayout
    private lateinit var countText: TextView
    private lateinit var header: LinearLayout
    private lateinit var selectBar: LinearLayout
    private lateinit var selectCount: TextView
    private lateinit var viewer: FrameLayout
    private lateinit var viewerImage: ZoomImageView
    private lateinit var viewerVideo: VideoView
    private lateinit var playBadge: TextView
    private lateinit var viewerInfo: TextView
    private lateinit var viewerBar: LinearLayout
    private lateinit var viewerTop: LinearLayout
    private var all = listOf<Item>()
    private var trash = listOf<Item>()
    private var shown = listOf<Item>()
    private var filter = F_ALL
    private var current = 0
    private var columns = 3
    private val selected = LinkedHashSet<Uri>()
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(40 * 1024 * 1024) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount }

    private fun prefs() = getSharedPreferences("aos_setup", MODE_PRIVATE)
    private fun text(t: String, sp: Float, c: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(c); typeface = appFont(if (bold) 700 else 400)
    }
    private val cell get() = (resources.displayMetrics.widthPixels - dp(32) - dp(4) * (columns - 1)) / columns

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Palette(this)
        columns = prefs().getInt("gallery_cols", 3).coerceIn(2, 5)
        countText = text("", 12f, p.sub)
        albumRow = LinearLayout(this).apply { setPadding(dp(2), 0, dp(2), 0) }
        grid = buildGrid()
        header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text("Gallery", 26f, p.text, true))
            addView(countText, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10); topMargin = dp(8) })
        }
        selectCount = text("", 17f, p.text, true)
        selectBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            addView(pill("✕") { endSelect() })
            addView(selectCount, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            addView(pill("♡") { setFavourite(selected.toList(), true) })
            addView(pill("Share") { shareMany(selected.toList()) })
            addView(pill("Trash") { if (filter == F_TRASH) restore(selected.toList()) else toTrash(selected.toList()) }.also { it.tag = "trashPill" })
        }
        buildViewer()
        setContentView(FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(p.wallTop, p.wallBottom))
            addView(LinearLayout(this@GalleryActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(52), dp(16), 0)
                addView(FrameLayout(this@GalleryActivity).apply {
                    addView(header, FrameLayout.LayoutParams(-1, dp(44))); addView(selectBar, FrameLayout.LayoutParams(-1, dp(44)))
                }, LinearLayout.LayoutParams(-1, dp(44)))
                addView(HorizontalScrollView(this@GalleryActivity).apply { isHorizontalScrollBarEnabled = false; addView(albumRow) },
                    LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8); bottomMargin = dp(8) })
                addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
            })
            addView(viewer, FrameLayout.LayoutParams(-1, -1))
        })
        addAosTopBar(p)
        requestAccess()
    }

    private class AbsListViewParams(w: Int, h: Int) : android.widget.AbsListView.LayoutParams(w, h)

    private fun pill(label: String, onClick: () -> Unit) = text(label, 14f, p.text, true).apply {
        gravity = Gravity.CENTER; background = shape(p.surfaceHigh, 18); setPadding(dp(14), 0, dp(14), 0); setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, dp(36)).apply { marginStart = dp(6) }
    }

    // ---- grid ----

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun buildGrid(): GridView = GridView(this).apply {
        numColumns = columns; horizontalSpacing = dp(4); verticalSpacing = dp(4); stretchMode = GridView.NO_STRETCH; columnWidth = cell
        selector = android.graphics.drawable.ColorDrawable(0); overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false
        setPadding(0, 0, 0, dp(16)); clipToPadding = false
        adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(i: Int) = shown[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
                val item = shown[i]
                val frame = (convert as? FrameLayout) ?: FrameLayout(this@GalleryActivity).apply {
                    addView(ImageView(this@GalleryActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = shape(p.surfaceHigh, 12); clipToOutline = true }, FrameLayout.LayoutParams(-1, -1))
                    addView(text("", 11f, 0xFFFFFFFF.toInt(), true).apply {
                        background = shape(0x99000000.toInt(), 8); setPadding(dp(6), dp(2), dp(6), dp(2))
                    }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(6), dp(6)) })
                    addView(text("♥", 13f, 0xFFFFFFFF.toInt(), true).apply { setShadowLayer(4f, 0f, 1f, 0x99000000.toInt()) },
                        FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(6), 0, 0, dp(4)) })
                    addView(text("", 13f, 0xFFFFFFFF.toInt(), true).apply { gravity = Gravity.CENTER },
                        FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
                }
                frame.layoutParams = AbsListViewParams(cell, cell)
                val img = frame.getChildAt(0) as ImageView
                val badge = frame.getChildAt(1) as TextView
                val heart = frame.getChildAt(2) as TextView
                val check = frame.getChildAt(3) as TextView
                badge.visibility = if (item.video) View.VISIBLE else View.GONE
                if (item.video) badge.text = "▶ " + clock(item.duration)
                heart.visibility = if (item.fav && filter != F_TRASH) View.VISIBLE else View.GONE
                val sel = item.uri in selected
                check.visibility = if (selected.isNotEmpty()) View.VISIBLE else View.GONE
                check.text = if (sel) "✓" else ""
                check.background = if (sel) circle(p.accent) else GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x44000000); setStroke(dp(2), 0xFFFFFFFF.toInt()) }
                img.alpha = if (sel) 0.75f else 1f
                img.tag = item.uri.toString(); img.setImageDrawable(null)
                thumb(item) { bmp -> if (img.tag == item.uri.toString()) img.setImageBitmap(bmp) }
                return frame
            }
        }
        setOnItemClickListener { _, _, i, _ -> if (selected.isNotEmpty()) toggleSelect(i) else open(i) }
        setOnItemLongClickListener { _, v, i, _ -> v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); toggleSelect(i); true }
        // Pinch on the grid: more or fewer columns.
        var acc = 1f
        val pinch = ScaleGestureDetector(this@GalleryActivity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { acc = 1f; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                acc *= d.scaleFactor
                if (acc > 1.35f && columns > 2) { setColumns(columns - 1); acc = 1f }
                else if (acc < 0.74f && columns < 5) { setColumns(columns + 1); acc = 1f }
                return true
            }
        })
        setOnTouchListener { _, e -> pinch.onTouchEvent(e); pinch.isInProgress || e.pointerCount > 1 }
    }

    private fun setColumns(n: Int) {
        columns = n; prefs().edit().putInt("gallery_cols", n).apply()
        grid.numColumns = n; grid.columnWidth = cell
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun clock(ms: Long) = (ms / 1000).let { "%d:%02d".format(it / 60, it % 60) }

    // ---- selection ----

    private fun toggleSelect(i: Int) {
        val u = shown.getOrNull(i)?.uri ?: return
        if (!selected.remove(u)) selected += u
        paintSelect()
    }

    private fun endSelect() { selected.clear(); paintSelect() }

    private fun paintSelect() {
        val on = selected.isNotEmpty()
        selectBar.visibility = if (on) View.VISIBLE else View.GONE
        header.visibility = if (on) View.INVISIBLE else View.VISIBLE
        selectCount.text = "${selected.size} selected"
        (selectBar.findViewWithTag<TextView>("trashPill"))?.text = if (filter == F_TRASH) "Restore" else "Trash"
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    // ---- data ----

    private fun needed() = if (android.os.Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun requestAccess() {
        val missing = needed().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) load() else requestPermissions(missing.toTypedArray(), 5)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) load()
        else countText.text = "Allow access to photos to see them here"
    }

    private fun scan(base: Uri, video: Boolean, trashed: Boolean, out: MutableList<Item>) {
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.IS_FAVORITE,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.RELATIVE_PATH)
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, MediaStore.MediaColumns.DATE_ADDED + " DESC")
            if (trashed) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        runCatching {
            contentResolver.query(base, cols, args, null)?.use { c ->
                while (c.moveToNext()) out += Item(ContentUris.withAppendedId(base, c.getLong(0)), c.getString(1).orEmpty(), c.getLong(2) * 1000,
                    c.getString(3) ?: "Other", video, if (video) c.getLong(4) else 0, c.getInt(5) == 1, c.getLong(6), c.getInt(7), c.getInt(8),
                    c.getString(9).orEmpty(), trashed)
            }
        }
    }

    private fun load() {
        pool.execute {
            val live = mutableListOf<Item>(); val bin = mutableListOf<Item>()
            scan(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, false, live)
            scan(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, false, live)
            scan(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, true, bin)
            scan(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, true, bin)
            val a = live.sortedByDescending { it.date }; val t = bin.sortedByDescending { it.date }
            runOnUiThread { all = a; trash = t; selected.clear(); paintSelect(); paintAlbums(); applyFilter() }
        }
    }

    private fun isShot(i: Item) = i.album.contains("Screenshot", true) || i.path.contains("Screenshot", true)

    private fun paintAlbums() {
        albumRow.removeAllViews()
        val smart = listOf(F_ALL to "All", F_FAV to "♥ Favourites", F_VIDEO to "▶ Videos", F_SHOTS to "Screenshots", F_TRASH to "Trash")
        val albums = all.groupingBy { it.album }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.key }
        for ((key, name) in smart + albums) {
            val count = itemsFor(key).size
            if (count == 0 && key != F_ALL && key != filter) continue
            val on = key == filter
            albumRow.addView(text("$name  $count", 13f, if (on) p.onAccent else p.text, true).apply {
                gravity = Gravity.CENTER; background = if (on) shape(p.accent, 19) else shape(p.surfaceHigh, 19); setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener { filter = key; selected.clear(); paintSelect(); paintAlbums(); applyFilter() }
            }, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(6) })
        }
    }

    private fun itemsFor(key: String) = when (key) {
        F_ALL -> all
        F_FAV -> all.filter { it.fav }
        F_VIDEO -> all.filter { it.video }
        F_SHOTS -> all.filter { isShot(it) }
        F_TRASH -> trash
        else -> all.filter { it.album == key }
    }

    private fun applyFilter() {
        shown = itemsFor(filter)
        countText.text = if (filter == F_TRASH) "${shown.size} in trash · kept 30 days" else "${shown.size} items"
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun thumb(item: Item, done: (Bitmap) -> Unit) {
        val key = item.uri.toString()
        cache.get(key)?.let { done(it); return }
        pool.execute {
            try {
                val bmp = contentResolver.loadThumbnail(item.uri, Size(320, 320), null)
                cache.put(key, bmp)
                runOnUiThread { done(bmp) }
            } catch (_: Exception) { }
        }
    }

    // ---- viewer ----

    private fun bar() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; background = shape(0x99000000.toInt(), 28); setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    private fun act(label: String, onClick: () -> Unit) = text(label, 13f, 0xFFFFFFFF.toInt(), true).apply {
        gravity = Gravity.CENTER; background = shape(0x33FFFFFF, 18); setPadding(dp(12), dp(8), dp(12), dp(8)); setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) }
    }

    private fun buildViewer() {
        viewerImage = ZoomImageView(this).apply {
            onSwipe = { step(it) }
            onDismiss = { close() }
            onTap = { if (shown.getOrNull(current)?.video == true) playVideo() else toggleChrome() }
        }
        viewerVideo = VideoView(this).apply { visibility = View.GONE }
        playBadge = text("▶", 34f, 0xFFFFFFFF.toInt(), true).apply {
            gravity = Gravity.CENTER; background = circle(0x77000000); visibility = View.GONE; setOnClickListener { playVideo() }
        }
        viewerInfo = text("", 12f, 0xFFFFFFFF.toInt())
        viewerTop = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@GalleryActivity).apply {
                setImageResource(R.drawable.ic_close); setColorFilter(0xFFFFFFFF.toInt()); setPadding(dp(9), dp(9), dp(9), dp(9)); background = circle(0x66000000)
                setOnClickListener { close() }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(viewerInfo, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            addView(act("Slideshow") { toggleSlideshow() })
        }
        viewerBar = bar()
        viewer = FrameLayout(this).apply {
            visibility = View.GONE; setBackgroundColor(0xFF000000.toInt()); isClickable = true
            addView(viewerImage, FrameLayout.LayoutParams(-1, -1))
            addView(viewerVideo, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            addView(playBadge, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
            addView(viewerTop, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(14), dp(48), dp(14), 0) })
            addView(viewerBar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(dp(12), 0, dp(12), dp(28)) })
        }
    }

    /** The bottom actions depend on the item: favourite / edit / share / details / trash, or restore / delete for trashed ones. */
    private fun paintViewerBar(item: Item) {
        viewerBar.removeAllViews()
        if (item.trashed) {
            viewerBar.addView(act("Restore") { restore(listOf(item.uri)) })
            viewerBar.addView(act("Delete forever") { deleteForever(listOf(item.uri)) })
            viewerBar.addView(act("Details") { details(item) })
            return
        }
        viewerBar.addView(act(if (item.fav) "♥" else "♡") { setFavourite(listOf(item.uri), !item.fav) })
        if (!item.video) viewerBar.addView(act("Edit") { edit(item) })
        viewerBar.addView(act("Share") { shareMany(listOf(item.uri)) })
        if (!item.video) viewerBar.addView(act("Set as") { setAs(item) })
        viewerBar.addView(act("Details") { details(item) })
        viewerBar.addView(act("Trash") { toTrash(listOf(item.uri)) })
    }

    private var chrome = true
    private fun toggleChrome() {
        chrome = !chrome
        for (v in listOf(viewerTop, viewerBar)) v.animate().alpha(if (chrome) 1f else 0f).setDuration(150)
            .withEndAction { v.visibility = if (chrome) View.VISIBLE else View.INVISIBLE }.withStartAction { if (chrome) v.visibility = View.VISIBLE }.start()
    }

    private fun open(i: Int) {
        current = i; viewer.visibility = View.VISIBLE; chrome = false; toggleChrome(); showCurrent()
    }

    private fun close() {
        stopSlideshow(); stopVideo()
        viewer.visibility = View.GONE; viewerImage.setImageDrawable(null)
    }

    private fun step(d: Int) {
        val n = current + d
        if (n !in shown.indices) { if (slideshow) stopSlideshow(); return }
        current = n; showCurrent()
    }

    private fun showCurrent() {
        val item = shown.getOrNull(current) ?: return
        stopVideo()
        viewerInfo.text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.date)) + "\n${current + 1} / ${shown.size}"
        paintViewerBar(item)
        playBadge.visibility = if (item.video) View.VISIBLE else View.GONE
        cache.get(item.uri.toString())?.let { viewerImage.setImageBitmap(it) } ?: viewerImage.setImageDrawable(null)
        val screen = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        pool.execute {
            try {
                val bmp = if (item.video) contentResolver.loadThumbnail(item.uri, Size(screen, screen), null)
                else ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, item.uri)) { dec, info, _ ->
                    // Sharp enough to zoom in (about twice the screen), never the full 50-megapixel bitmap.
                    var s = 1
                    while (maxOf(info.size.width, info.size.height) / (s * 2) >= screen * 2) s *= 2
                    dec.setTargetSampleSize(s)
                }
                runOnUiThread { if (shown.getOrNull(current) === item) viewerImage.setImageBitmap(bmp) }
            } catch (_: Exception) { }
        }
    }

    private fun playVideo() {
        val item = shown.getOrNull(current) ?: return
        if (!item.video) return
        if (viewerVideo.isPlaying) { viewerVideo.pause(); playBadge.visibility = View.VISIBLE; return }
        playBadge.visibility = View.GONE
        viewerVideo.visibility = View.VISIBLE
        if (viewerVideo.tag != item.uri) {
            viewerVideo.tag = item.uri
            viewerVideo.setMediaController(MediaController(this).also { it.setAnchorView(viewerVideo) })
            viewerVideo.setVideoURI(item.uri)
            viewerVideo.setOnCompletionListener { playBadge.visibility = View.VISIBLE }
        }
        viewerVideo.start()
    }

    private fun stopVideo() {
        runCatching { viewerVideo.stopPlayback() }
        viewerVideo.tag = null; viewerVideo.visibility = View.GONE
    }

    // Slideshow: a new photo every 3 seconds with a soft fade; any tap on the close button or the end stops it.
    private var slideshow = false
    private val slide = object : Runnable {
        override fun run() {
            if (!slideshow) return
            viewerImage.animate().alpha(0f).setDuration(250).withEndAction {
                step(1); viewerImage.animate().alpha(1f).setDuration(250).start()
            }.start()
            main.postDelayed(this, 3000)
        }
    }
    private fun toggleSlideshow() {
        if (slideshow) { stopSlideshow(); return }
        slideshow = true; if (chrome) toggleChrome(); main.postDelayed(slide, 2500)
        Toast.makeText(this, "Slideshow: tap to show controls", Toast.LENGTH_SHORT).show()
    }
    private fun stopSlideshow() { slideshow = false; main.removeCallbacks(slide); viewerImage.alpha = 1f }

    // ---- actions (each change is confirmed by the system) ----

    private fun request(rq: Int, pi: android.app.PendingIntent?) {
        if (pi == null) return
        try { startIntentSenderForResult(pi.intentSender, rq, null, 0, 0, 0) }
        catch (e: Exception) { Toast.makeText(this, "Not possible: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun setFavourite(uris: List<Uri>, on: Boolean) { if (uris.isNotEmpty()) request(RQ_FAV, MediaStore.createFavoriteRequest(contentResolver, uris, on)) }
    private fun toTrash(uris: List<Uri>) { if (uris.isNotEmpty()) request(RQ_TRASH, MediaStore.createTrashRequest(contentResolver, uris, true)) }
    private fun restore(uris: List<Uri>) { if (uris.isNotEmpty()) request(RQ_RESTORE, MediaStore.createTrashRequest(contentResolver, uris, false)) }
    private fun deleteForever(uris: List<Uri>) { if (uris.isNotEmpty()) request(RQ_DELETE, MediaStore.createDeleteRequest(contentResolver, uris)) }

    private fun shareMany(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val items = (all + trash).filter { it.uri in uris }
        val type = if (items.all { it.video }) "video/*" else if (items.none { it.video }) "image/*" else "*/*"
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        try { startActivity(Intent.createChooser(i.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share")) } catch (_: Exception) {}
    }

    private fun edit(item: Item) {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_EDIT).setDataAndType(item.uri, "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION), "Edit with"))
        } catch (_: Exception) { Toast.makeText(this, "No photo editor on this phone", Toast.LENGTH_SHORT).show() }
    }

    private fun setAs(item: Item) {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(item.uri, "image/*")
                .putExtra("mimeType", "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Set as"))
        } catch (_: Exception) {}
    }

    private fun details(item: Item) {
        val rows = listOfNotNull(
            "Name" to item.name,
            "Date" to DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(item.date)),
            if (item.w > 0) "Resolution" to "${item.w} × ${item.h}  (${"%.1f".format(item.w * item.h / 1_000_000f)} MP)" else null,
            "Size" to Formatter.formatFileSize(this, item.size),
            if (item.video) "Length" to clock(item.duration) else null,
            "Album" to item.album,
            "Folder" to item.path.ifEmpty { "-" },
        )
        AlertDialog.Builder(this).setTitle("Details").setMessage(rows.joinToString("\n\n") { "${it.first}\n${it.second}" }).setPositiveButton("OK", null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            RQ_TRASH, RQ_DELETE, RQ_RESTORE -> { close(); Toast.makeText(this, when (requestCode) {
                RQ_TRASH -> "Moved to Trash"; RQ_RESTORE -> "Restored"; else -> "Deleted" }, Toast.LENGTH_SHORT).show() }
        }
        val keep = current
        load()
        // A favourite changed in the viewer: show the same photo again with the new heart.
        if (requestCode == RQ_FAV && viewer.visibility == View.VISIBLE) main.postDelayed({ current = keep.coerceIn(0, maxOf(0, shown.lastIndex)); showCurrent() }, 400)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            viewer.visibility == View.VISIBLE -> close()
            selected.isNotEmpty() -> endSelect()
            else -> super.onBackPressed()
        }
    }

    override fun onPause() { super.onPause(); stopSlideshow(); if (viewerVideo.isPlaying) viewerVideo.pause() }
    override fun onDestroy() { stopSlideshow(); pool.shutdownNow(); super.onDestroy() }
}
