package com.dozy.pocketai.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

data class ChatTurn(val role: String, val content: String)

data class GenParams(
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
)

class LlamaException(message: String) : Exception(message)

/**
 * Owns one loaded model. All native calls run on a single dedicated thread,
 * because a llama_context must not be used from several threads at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LlamaEngine {
    private val thread = Dispatchers.IO.limitedParallelism(1)
    @Volatile private var handle = 0L
    @Volatile var loadedPath: String? = null
        private set

    val isLoaded get() = handle != 0L

    suspend fun load(path: String, nCtx: Int, nThreads: Int, nThreadsBatch: Int, kvQ8: Boolean): String = withContext(thread) {
        unloadInternal()
        val h = LlamaNative.load(path, nCtx, nThreads, nThreadsBatch, kvQ8)
        if (h == 0L) throw LlamaException("Не удалось загрузить модель (файл повреждён или не хватает памяти)")
        handle = h
        loadedPath = path
        LlamaNative.description(h)
    }

    suspend fun unload() = withContext(thread) { unloadInternal() }

    private fun unloadInternal() {
        if (handle != 0L) LlamaNative.free(handle)
        handle = 0L
        loadedPath = null
    }

    fun contextSize(): Int = if (handle != 0L) LlamaNative.contextSize(handle) else 0

    fun stop() {
        val h = handle
        if (h != 0L) LlamaNative.stop(h)
    }

    suspend fun resetCache() = withContext(thread) { if (handle != 0L) LlamaNative.resetCache(handle) }

    /** Formats [turns] with the model's chat template, dropping the oldest turns until it fits. */
    suspend fun buildPrompt(turns: List<ChatTurn>, reserve: Int): ByteArray = withContext(thread) {
        val h = requireHandle()
        val limit = LlamaNative.contextSize(h) - reserve
        val work = turns.toMutableList()
        while (true) {
            val prompt = LlamaNative.applyTemplate(
                h,
                work.map { it.role }.toTypedArray(),
                work.map { it.content.toByteArray(Charsets.UTF_8) }.toTypedArray(),
                true,
            ) ?: throw LlamaException("Шаблон чата модели не поддерживается")
            val count = LlamaNative.tokenCount(h, prompt)
            if (count <= limit) return@withContext prompt
            // drop the oldest non-system turn (keep the last user message no matter what)
            val idx = work.indexOfFirst { it.role != "system" }
            if (idx < 0 || idx >= work.size - 1) {
                val last = work.last()
                val keep = (last.content.length * limit / count.coerceAtLeast(1)).coerceAtLeast(200)
                if (keep >= last.content.length) throw LlamaException("Сообщение слишком длинное для контекста")
                work[work.size - 1] = last.copy(content = last.content.takeLast(keep))
            } else {
                work.removeAt(idx)
            }
        }
        @Suppress("UNREACHABLE_CODE") ByteArray(0)
    }

    /** Streams decoded text chunks for [prompt]. */
    fun generate(prompt: ByteArray, p: GenParams): Flow<String> = channelFlow {
        val h = requireHandle()
        val decoder = Utf8Stream()
        val result = withContext(thread) {
            LlamaNative.completion(
                h, prompt, p.maxTokens, p.temperature, p.topP, p.topK, p.repeatPenalty, -1,
            ) { bytes ->
                val text = decoder.feed(bytes)
                if (text.isNotEmpty()) trySend(text)
                !isClosedForSend
            }
        }
        val tail = decoder.flush()
        if (tail.isNotEmpty()) send(tail)
        when (result) {
            -2 -> throw LlamaException("Промпт не помещается в контекст — увеличьте размер контекста")
            -3 -> throw LlamaException("Ошибка вычисления (не хватает памяти?)")
            -1 -> throw LlamaException("Модель не загружена")
        }
    }

    private fun requireHandle(): Long {
        val h = handle
        if (h == 0L) throw LlamaException("Сначала загрузите модель")
        return h
    }
}

/** Turns a stream of byte chunks into text without breaking multi-byte UTF-8 characters. */
class Utf8Stream {
    private val buf = ByteArrayOutputStream()

    fun feed(bytes: ByteArray): String {
        buf.write(bytes)
        val all = buf.toByteArray()
        val cut = completeLength(all)
        buf.reset()
        if (cut < all.size) buf.write(all, cut, all.size - cut)
        return String(all, 0, cut, Charsets.UTF_8)
    }

    fun flush(): String {
        val s = String(buf.toByteArray(), Charsets.UTF_8)
        buf.reset()
        return s
    }

    private fun completeLength(b: ByteArray): Int {
        // look at up to 3 trailing bytes for an unfinished sequence
        var i = b.size - 1
        var back = 0
        while (i >= 0 && back < 4) {
            val v = b[i].toInt() and 0xFF
            if (v and 0xC0 != 0x80) { // lead byte or ASCII
                val need = when {
                    v < 0x80 -> 1
                    v shr 5 == 0x6 -> 2
                    v shr 4 == 0xE -> 3
                    v shr 3 == 0x1E -> 4
                    else -> 1
                }
                return if (b.size - i >= need) b.size else i
            }
            i--; back++
        }
        return b.size
    }
}
