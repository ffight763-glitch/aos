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
    val threads: Int = defaultThreads(),
    val webResults: Int = 3,
    val webCharsPerPage: Int = 1200,
    val lastModel: String? = null,
    val theme: Int = 0, // 0 system, 1 night, 2 day
) {
    companion object {
        const val DEFAULT_SYSTEM =
            "Ты — PocketAI, полезный ассистент, работающий прямо на телефоне пользователя. " +
                "Отвечай кратко и по делу, на языке пользователя."

        fun defaultThreads(): Int {
            val n = Runtime.getRuntime().availableProcessors()
            return (n - 2).coerceIn(2, 6)
        }
    }
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
            threads = p.getInt("threads", d.threads),
            webResults = p.getInt("web_results", d.webResults),
            webCharsPerPage = p.getInt("web_chars", d.webCharsPerPage),
            lastModel = p.getString("last_model", null),
            theme = p.getInt("theme", d.theme),
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
            .putInt("threads", s.threads)
            .putInt("web_results", s.webResults)
            .putInt("web_chars", s.webCharsPerPage)
            .putString("last_model", s.lastModel)
            .putInt("theme", s.theme)
            .apply()
    }
}
