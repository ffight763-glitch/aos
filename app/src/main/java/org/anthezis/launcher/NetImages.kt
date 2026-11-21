package org.anthezis.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Pictures from the network (news thumbnails, AOS Tube previews): a few shared worker threads instead of one thread per
 * picture, timeouts, connections always closed, pictures scaled down to the size they are shown at, and a memory cache
 * with a limit (16 MB) so a long feed cannot fill the memory.
 */
object NetImages {
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** Shows the picture at [url] in [into], about [targetPx] wide. A view reused for another url keeps the newer one. */
    fun load(url: String, into: ImageView, targetPx: Int) {
        if (url.isBlank()) return
        into.tag = url
        cache.get(url)?.let { into.setImageBitmap(it); return }
        pool.execute {
            val bmp = runCatching { fetch(url, targetPx) }.getOrNull() ?: return@execute
            cache.put(url, bmp)
            main.post { if (into.tag == url) into.setImageBitmap(bmp) }
        }
    }

    private fun fetch(url: String, targetPx: Int): Bitmap? {
        val bytes = (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 8000; readTimeout = 10000; instanceFollowRedirects = true
            try { if (responseCode !in 200..299) return null; inputStream.use { it.readBytes() } } finally { disconnect() }
        }
        // Measure first, then decode only as many pixels as the view needs.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx / 2) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
