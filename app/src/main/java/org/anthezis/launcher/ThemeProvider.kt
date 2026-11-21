package org.anthezis.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri

/**
 * The current AOS theme for other AOS apps (the Telegram client first): content://org.anthezis.ahome.theme/current gives one row
 * with the style, light/dark, the interface colours of [Palette] and four colours picked from the scene, so a chat background can
 * be a gradient of the same wallpaper. Read-only; apps read it when they come to the front.
 */
class ThemeProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        if (uri.lastPathSegment == "styles") return allStyles()
        val ctx = context!!
        val p = Palette(ctx)
        val id = AosStyles.selectedId(ctx)
        val scene = if (id == "custom") null else try { AosStyles.sceneBitmap(AosStyles.find(id), p.dark) } catch (_: Throwable) { null }
        // Four corners of a soft diagonal: top-left, top-right, bottom-right, bottom-left (the order of a Telegram gradient).
        val spots = listOf(0.25f to 0.18f, 0.75f to 0.38f, 0.75f to 0.85f, 0.25f to 0.65f)
        val colors = spots.map { (x, y) -> scene?.let { sample(it, x, y) } ?: 0 }
        return MatrixCursor(COLUMNS).apply {
            addRow(arrayOf<Any>(id, if (p.dark) 1 else 0, if (p.uiDark) 1 else 0, p.accent, p.onAccent, p.bg, p.surface, p.text, p.sub,
                colors[0], colors[1], colors[2], colors[3]))
        }
    }

    /**
     * content://org.anthezis.ahome.theme/styles: every style with its accents and four scene colours by day and at night, so an
     * app can carry the whole set (Agram builds its own theme list from it). Paints each scene once, so it takes a few seconds.
     */
    private fun allStyles(): Cursor = MatrixCursor(STYLE_COLUMNS).apply {
        val spots = listOf(0.25f to 0.18f, 0.75f to 0.38f, 0.75f to 0.85f, 0.25f to 0.65f)
        for (s in AosStyles.all) {
            val row = ArrayList<Any>()
            row += s.id; row += s.name; row += AosStyles.groupName(AosStyles.groupIndex(s.id))
            row += Color.parseColor(s.accentLight); row += Color.parseColor(s.accentDark)
            row += if (try { AosStyles.dayLuminance(s) < 0.42f } catch (_: Throwable) { false }) 1 else 0
            for (dark in listOf(false, true)) {
                val b = try { AosStyles.bitmap(s, dark) } catch (_: Throwable) { null }
                for ((x, y) in spots) row += b?.let { sample(it, x, y) } ?: 0
            }
            addRow(row.toTypedArray())
        }
    }

    /** Average colour of a small patch around (fx, fy), so a single star or a lamp does not decide the colour. */
    private fun sample(b: Bitmap, fx: Float, fy: Float): Int {
        val cx = (b.width * fx).toInt(); val cy = (b.height * fy).toInt(); val step = b.width / 40
        var r = 0; var g = 0; var bl = 0; var n = 0
        for (dy in -4..4) for (dx in -4..4) {
            val c = b.getPixel((cx + dx * step).coerceIn(0, b.width - 1), (cy + dy * step).coerceIn(0, b.height - 1))
            r += Color.red(c); g += Color.green(c); bl += Color.blue(c); n++
        }
        return Color.rgb(r / n, g / n, bl / n)
    }

    override fun getType(uri: Uri) = "vnd.android.cursor.item/vnd.org.anthezis.theme"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        val COLUMNS = arrayOf("style", "dark", "ui_dark", "accent", "on_accent", "bg", "surface", "text", "sub", "scene1", "scene2", "scene3", "scene4")
        val STYLE_COLUMNS = arrayOf("style", "name", "group", "accent_light", "accent_dark", "day_ui_dark",
            "day1", "day2", "day3", "day4", "night1", "night2", "night3", "night4")
    }
}
