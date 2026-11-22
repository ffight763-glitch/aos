package com.dozy.pocketai.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Multi-connection downloader: splits the file into [parts] byte ranges that
 * download in parallel (HuggingFace's CDN limits speed per connection, so this
 * is several times faster than one stream). Progress of each part is saved next
 * to the file, so an interrupted download resumes where it stopped.
 */
class FastDownloader(private val scope: CoroutineScope) {
    private val jobs = HashMap<String, Job>()

    data class Progress(val done: Long, val total: Long, val bytesPerSec: Long)

    fun start(
        url: String,
        dest: File,
        parts: Int = 6,
        onProgress: (Progress) -> Unit,
        onDone: (Result<File>) -> Unit,
    ) {
        val key = dest.absolutePath
        if (jobs[key]?.isActive == true) return
        jobs[key] = scope.launch(Dispatchers.IO) {
            val r = runCatching { download(url, dest, parts, onProgress) }
            jobs.remove(key)
            if (r.exceptionOrNull() is kotlinx.coroutines.CancellationException) return@launch
            onDone(r)
        }
    }

    fun cancel(dest: File, deleteFiles: Boolean = true) {
        jobs.remove(dest.absolutePath)?.cancel()
        if (deleteFiles) {
            partFile(dest).delete()
            metaFile(dest).delete()
        }
    }

    fun isRunning(dest: File) = jobs[dest.absolutePath]?.isActive == true

    private fun partFile(dest: File) = File(dest.parentFile, dest.name + ".part")
    private fun metaFile(dest: File) = File(dest.parentFile, dest.name + ".meta")

    private data class Probe(val url: String, val total: Long, val ranges: Boolean)

    /** Follows redirects to the CDN and learns the size / range support. */
    private fun probe(url: String): Probe {
        var current = url
        repeat(8) {
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                setRequestProperty("Range", "bytes=0-0")
                setRequestProperty("User-Agent", "PocketAI")
                connectTimeout = 15_000
                readTimeout = 20_000
            }
            val code = c.responseCode
            if (code in 300..399) {
                current = URL(URL(current), c.getHeaderField("Location")).toString()
                c.disconnect()
                return@repeat
            }
            if (code !in 200..299) error("HTTP $code")
            val cr = c.getHeaderField("Content-Range")
            val total = cr?.substringAfter('/')?.toLongOrNull() ?: c.contentLengthLong
            c.disconnect()
            return Probe(current, total, code == 206 && total > 0)
        }
        error("Слишком много перенаправлений")
    }

    private suspend fun download(url: String, dest: File, parts: Int, onProgress: (Progress) -> Unit): File = coroutineScope {
        val probe = probe(url)
        val part = partFile(dest)
        val meta = metaFile(dest)

        if (!probe.ranges || probe.total < 8L shl 20) {
            single(probe.url, part, probe.total, onProgress)
        } else {
            val n = parts.coerceIn(1, 8)
            val chunk = probe.total / n
            val starts = LongArray(n) { it * chunk }
            val ends = LongArray(n) { if (it == n - 1) probe.total - 1 else (it + 1) * chunk - 1 }
            // resume: meta = "total|done0,done1,..."
            val done = AtomicLongArray(n)
            meta.takeIf { it.exists() && part.exists() }?.readText()?.split('|')?.takeIf { it.size == 2 }?.let { f ->
                if (f[0].toLongOrNull() == probe.total) f[1].split(',').mapNotNull { it.toLongOrNull() }
                    .forEachIndexed { i, v -> if (i < n) done.set(i, v.coerceIn(0, ends[i] - starts[i] + 1)) }
            }
            RandomAccessFile(part, "rw").use { it.setLength(probe.total) }

            val ticker = launch {
                var last = (0 until n).sumOf { done.get(it) }
                var lastT = System.nanoTime()
                var speed = 0L
                while (isActive) {
                    delay(700)
                    val now = (0 until n).sumOf { done.get(it) }
                    val t = System.nanoTime()
                    val inst = ((now - last) * 1_000_000_000L / (t - lastT).coerceAtLeast(1))
                    speed = if (speed == 0L) inst else (speed * 2 + inst) / 3
                    last = now; lastT = t
                    onProgress(Progress(now, probe.total, speed))
                    meta.writeText(probe.total.toString() + "|" + (0 until n).joinToString(",") { done.get(it).toString() })
                }
            }
            (0 until n).map { i ->
                async(Dispatchers.IO) {
                    var attempt = 0
                    var target = probe.url
                    while (starts[i] + done.get(i) <= ends[i]) {
                        try {
                            rangePart(target, part, starts[i] + done.get(i), ends[i]) { bytes -> done.addAndGet(i, bytes.toLong()) }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            if (++attempt > 6) throw e
                            delay(1500L * attempt)
                            // signed CDN links expire — get a fresh one
                            runCatching { target = probe(url).url }
                        }
                    }
                }
            }.awaitAll()
            ticker.cancel()
            onProgress(Progress(probe.total, probe.total, 0))
        }
        meta.delete()
        dest.delete()
        if (!part.renameTo(dest)) error("Не удалось сохранить файл")
        dest
    }

    private suspend fun rangePart(url: String, file: File, from: Long, to: Long, onBytes: (Int) -> Unit) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Range", "bytes=$from-$to")
            setRequestProperty("User-Agent", "PocketAI")
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        if (c.responseCode != 206) { c.disconnect(); error("HTTP ${c.responseCode}") }
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(from)
            c.inputStream.use { input ->
                val buf = ByteArray(256 * 1024)
                var pos = from
                while (pos <= to) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), to - pos + 1).toInt())
                    if (n < 0) error("Обрыв соединения")
                    raf.write(buf, 0, n)
                    pos += n
                    onBytes(n)
                }
            }
        }
        c.disconnect()
    }

    private suspend fun single(url: String, part: File, total: Long, onProgress: (Progress) -> Unit) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "PocketAI")
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        var done = 0L
        var lastT = System.nanoTime()
        var lastDone = 0L
        part.outputStream().use { out ->
            c.inputStream.use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val t = System.nanoTime()
                    if (t - lastT > 700_000_000L) {
                        onProgress(Progress(done, total, (done - lastDone) * 1_000_000_000L / (t - lastT)))
                        lastT = t; lastDone = done
                    }
                }
            }
        }
    }
}
