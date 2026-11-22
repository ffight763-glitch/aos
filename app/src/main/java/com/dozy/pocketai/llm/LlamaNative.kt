package com.dozy.pocketai.llm

/** Thin JNI surface over llama.cpp (see app/src/main/cpp/pocketai.cpp). */
object LlamaNative {
    init {
        System.loadLibrary("pocketai")
        backendInit()
    }

    fun interface TokenCallback {
        /** Raw UTF-8 bytes of one token piece. Return false to stop generation. */
        fun onToken(bytes: ByteArray): Boolean
    }

    private external fun backendInit()
    external fun load(path: String, nCtx: Int, nThreads: Int, nThreadsBatch: Int, kvQ8: Boolean): Long
    external fun free(handle: Long)
    external fun contextSize(handle: Long): Int
    external fun description(handle: Long): String
    external fun tokenCount(handle: Long, text: ByteArray): Int
    external fun applyTemplate(
        handle: Long,
        roles: Array<String>,
        contents: Array<ByteArray>,
        addAssistant: Boolean,
    ): ByteArray?
    external fun stop(handle: Long)
    external fun resetCache(handle: Long)
    external fun completion(
        handle: Long,
        prompt: ByteArray,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        seed: Int,
        callback: TokenCallback,
    ): Int
}
