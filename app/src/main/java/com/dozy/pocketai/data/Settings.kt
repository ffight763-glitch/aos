package com.dozy.pocketai.data

import android.content.Context

data class AppSettings(
    val systemPrompt: String = DEFAULT_SYSTEM,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 768,
    val contextSize: Int = 4096,
    val threads: Int = 0, // 0 = auto
    val kvQ8: Boolean = true,
    val webResults: Int = 3,
    val webCharsPerPage: Int = 1200,
    val lastModel: String? = null,
    val theme: Int = 0, // 0 system, 1 night, 2 day
    val memory: String = "",
    val comboModel: String? = null,   // path of the second model for Combo
    val comboStyle: Int = 0,          // 0 = draft → improve, 1 = both answer
    val engines: Set<String> = setOf("google", "bing", "duckduckgo", "brave"),
    val blockRu: Boolean = true, // what the assistant should always know about the user
) {
    companion object {
        const val DEFAULT_SYSTEM =
            "Ты — PocketAI, полезный ассистент, работающий прямо на телефоне пользователя. " +
                "Отвечай кратко и по делу, на языке пользователя."

        /** Generation is memory-bound: 4 threads on 8-core phones beat 8 (little cores stall the rest). */
        fun autoThreads(): Int {
            val n = Runtime.getRuntime().availableProcessors()
            return if (n >= 8) 4 else (n / 2).coerceAtLeast(2)
        }
        /** Reading the prompt is compute-bound: use every core. */
        fun batchThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    }
    val genThreads get() = if (threads > 0) threads else autoThreads()
}

class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            systemPrompt = p.getString("system", d.systemPrompt) ?: d.systemPrompt,
            temperature = p.getFloat("temp", d.temperature),
            topP = p.getFloat("top_p", d.topP),
            topK = p.getInt("top_k", d.topK),
            repeatPenalty = p.getFloat("repeat", d.repeatPenalty),
            maxTokens = p.getInt("max_tokens", d.maxTokens),
            contextSize = p.getInt("ctx", d.contextSize),
            threads = p.getInt("threads2", d.threads),
            kvQ8 = p.getBoolean("kv_q8", d.kvQ8),
            webResults = p.getInt("web_results", d.webResults),
            webCharsPerPage = p.getInt("web_chars", d.webCharsPerPage),
            lastModel = p.getString("last_model", null),
            theme = p.getInt("theme", d.theme),
            memory = p.getString("memory", "") ?: "",
            comboModel = p.getString("combo_model", null),
            comboStyle = p.getInt("combo_style", 0),
            engines = p.getStringSet("engines", null)?.toSet() ?: d.engines,
            blockRu = p.getBoolean("block_ru", true),
        )
    }

    fun save(s: AppSettings) {
        p.edit()
            .putString("system", s.systemPrompt)
            .putFloat("temp", s.temperature)
            .putFloat("top_p", s.topP)
            .putInt("top_k", s.topK)
            .putFloat("repeat", s.repeatPenalty)
            .putInt("max_tokens", s.maxTokens)
            .putInt("ctx", s.contextSize)
            .putInt("threads2", s.threads)
            .putBoolean("kv_q8", s.kvQ8)
            .putInt("web_results", s.webResults)
            .putInt("web_chars", s.webCharsPerPage)
            .putString("last_model", s.lastModel)
            .putInt("theme", s.theme)
            .putString("memory", s.memory)
            .putString("combo_model", s.comboModel)
            .putInt("combo_style", s.comboStyle)
            .putStringSet("engines", s.engines)
            .putBoolean("block_ru", s.blockRu)
            .apply()
    }
}
