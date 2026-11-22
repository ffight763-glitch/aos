package com.dozy.pocketai.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dozy.pocketai.PocketApp
import com.dozy.pocketai.data.AppSettings
import com.dozy.pocketai.data.Conversation
import com.dozy.pocketai.data.LocalModel
import com.dozy.pocketai.data.Message
import com.dozy.pocketai.llm.ChatTurn
import com.dozy.pocketai.llm.GenParams
import com.dozy.pocketai.web.WebSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Phase { Idle, LoadingModel, Searching, Thinking, Generating }

data class ChatUiState(
    val conversation: Conversation = Conversation(),
    val history: List<Conversation> = emptyList(),
    val phase: Phase = Phase.Idle,
    val status: String? = null,
    val modelName: String? = null,
    val webEnabled: Boolean = false,
    val codeMode: Boolean = false,
    val streaming: String = "",
    val error: String? = null,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val pocket = app as PocketApp
    private val engine = pocket.engine
    val models = pocket.models
    val updater = pocket.updater

    private val _settings = MutableStateFlow(pocket.settings.load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _ui = MutableStateFlow(ChatUiState(history = pocket.chats.list()))
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private var job: Job? = null

    init {
        engine.loadedPath?.let { p -> _ui.update { it.copy(modelName = java.io.File(p).nameWithoutExtension) } }
        if (!engine.isLoaded) {
            _settings.value.lastModel?.let { path ->
                val f = java.io.File(path)
                if (f.exists()) loadModel(LocalModel(f))
            }
        }
    }

    // ---------- models ----------

    fun loadModel(m: LocalModel) {
        if (_ui.value.phase != Phase.Idle) return
        job = viewModelScope.launch {
            _ui.update { it.copy(phase = Phase.LoadingModel, status = "Загрузка ${m.name}…", error = null) }
            try {
                val s = _settings.value
                engine.load(m.file.absolutePath, s.contextSize, s.threads)
                updateSettings(s.copy(lastModel = m.file.absolutePath))
                _ui.update { it.copy(modelName = m.name) }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message) }
            } finally {
                _ui.update { it.copy(phase = Phase.Idle, status = null) }
            }
        }
    }

    fun unloadModel() = viewModelScope.launch {
        engine.unload()
        _ui.update { it.copy(modelName = null) }
    }

    fun deleteModel(m: LocalModel) = viewModelScope.launch {
        if (engine.loadedPath == m.file.absolutePath) {
            engine.unload()
            _ui.update { it.copy(modelName = null) }
        }
        models.delete(m)
    }

    fun importModel(uri: Uri) = viewModelScope.launch {
        _ui.update { it.copy(status = "Копирование модели…") }
        models.import(uri).onFailure { e -> _ui.update { it.copy(error = e.message) } }
        _ui.update { it.copy(status = null) }
    }

    // ---------- settings ----------

    fun updateSettings(s: AppSettings) {
        _settings.value = s
        pocket.settings.save(s)
    }

    fun toggleWeb() = _ui.update { it.copy(webEnabled = !it.webEnabled) }
    fun toggleCode() = _ui.update { it.copy(codeMode = !it.codeMode) }
    fun dismissError() = _ui.update { it.copy(error = null) }

    // ---------- chats ----------

    fun newChat() {
        stop()
        _ui.update { it.copy(conversation = Conversation(), streaming = "") }
    }

    fun openChat(c: Conversation) {
        stop()
        _ui.update { it.copy(conversation = c, streaming = "") }
    }

    fun deleteChat(c: Conversation) {
        pocket.chats.delete(c.id)
        _ui.update {
            it.copy(
                history = pocket.chats.list(),
                conversation = if (it.conversation.id == c.id) Conversation() else it.conversation,
            )
        }
    }

    fun stop() {
        engine.stop()
        if (_ui.value.phase == Phase.Searching) job?.cancel()
    }

    fun regenerate() {
        val msgs = _ui.value.conversation.messages
        if (_ui.value.phase != Phase.Idle || msgs.isEmpty()) return
        val lastUser = msgs.indexOfLast { it.role == "user" }
        if (lastUser < 0) return
        val user = msgs[lastUser]
        setMessages(msgs.subList(0, lastUser))
        send(user.text)
    }

    fun send(text: String) {
        val input = text.trim()
        if (input.isEmpty() || _ui.value.phase != Phase.Idle) return
        if (!engine.isLoaded) {
            _ui.update { it.copy(error = "Сначала скачайте и загрузите модель (вкладка «Модели»)") }
            return
        }
        val useWeb = _ui.value.webEnabled
        val code = _ui.value.codeMode
        job = viewModelScope.launch {
            var userMsg = Message(role = "user", text = input)
            setMessages(_ui.value.conversation.messages + userMsg, titleFrom = input)
            val s = _settings.value
            try {
                if (useWeb) {
                    _ui.update { it.copy(phase = Phase.Searching, status = "Ищу в интернете…") }
                    val ctx = WebSearch.buildContext(input, s.webResults, s.webCharsPerPage)
                    if (ctx.text.isBlank()) {
                        _ui.update { it.copy(status = "Ничего не найдено — отвечаю без интернета") }
                    } else {
                        userMsg = userMsg.copy(webContext = ctx.text, sources = ctx.sources)
                        replaceLast(userMsg)
                    }
                }

                _ui.update { it.copy(phase = Phase.Thinking, status = "Читаю контекст…") }
                // codProd: precise and long enough for whole files
                val gen = if (code) GenParams(
                    maxOf(s.maxTokens, 1536), minOf(s.temperature, 0.2f), 0.9f, 40, 1.05f,
                ) else GenParams(s.maxTokens, s.temperature, s.topP, s.topK, s.repeatPenalty)
                val turns = buildTurns(s, code)
                val prompt = engine.buildPrompt(turns, reserve = gen.maxTokens + 16)

                val out = StringBuilder()
                var count = 0
                var startedAt = 0L
                engine.generate(prompt, gen)
                    .collect { chunk ->
                        if (count == 0) {
                            startedAt = System.nanoTime()
                            _ui.update { it.copy(phase = Phase.Generating, status = null) }
                        }
                        count++
                        out.append(chunk)
                        _ui.update { it.copy(streaming = out.toString()) }
                    }
                val secs = (System.nanoTime() - startedAt) / 1e9f
                val tps = if (count > 1 && secs > 0) count / secs else null
                val answer = Message(role = "assistant", text = out.toString().trim(), tokensPerSec = tps)
                setMessages(_ui.value.conversation.messages + answer)
            } catch (e: CancellationException) {
                // stopped by the user
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _ui.update { it.copy(phase = Phase.Idle, status = null, streaming = "") }
            }
        }
    }

    private fun buildTurns(s: AppSettings, code: Boolean): List<ChatTurn> {
        val msgs = _ui.value.conversation.messages
        val date = SimpleDateFormat("d MMMM yyyy, EEEE", Locale("ru")).format(Date())
        val system = (if (code) CODPROD_SYSTEM else s.systemPrompt) + "\nСегодня $date."
        val turns = mutableListOf(ChatTurn("system", system))
        msgs.forEachIndexed { i, m ->
            val isLast = i == msgs.lastIndex
            val content = if (m.role == "user" && isLast && m.webContext != null) {
                "Результаты поиска в интернете:\n\n${m.webContext}\n\n" +
                    "Используя эти результаты (ссылайся на номера источников вида [1]), ответь на вопрос: ${m.text}"
            } else if (m.role == "assistant") {
                stripThinking(m.text)
            } else m.text
            turns += ChatTurn(m.role, content)
        }
        return turns
    }

    private fun setMessages(list: List<Message>, titleFrom: String? = null) {
        _ui.update { st ->
            val c = st.conversation
            val title = if (c.messages.isEmpty() && titleFrom != null) titleFrom.take(40) else c.title
            val updated = c.copy(messages = list, title = title, updatedAt = System.currentTimeMillis())
            pocket.chats.save(updated)
            st.copy(conversation = updated, history = pocket.chats.list())
        }
    }

    private fun replaceLast(m: Message) {
        val list = _ui.value.conversation.messages.toMutableList()
        if (list.isNotEmpty()) list[list.lastIndex] = m
        setMessages(list)
    }

    companion object {
        /** codProd — the coding mode that can be switched on in any chat. */
        const val CODPROD_SYSTEM =
            "Ты — codProd, опытный программист-ассистент внутри PocketAI. " +
                "Пиши рабочий, полный код без заглушек и «...». Каждый блок кода оформляй в ```язык. " +
                "Сначала коротко: что делаем; потом код; в конце — как запустить или проверить. " +
                "Если просят исправить ошибку — назови причину и покажи исправленный фрагмент. " +
                "Объясняй на языке пользователя, комментарии в коде — кратко."

        /** Reasoning models (Qwen3, DeepSeek-R1) emit <think>…</think>; it is not sent back as history. */
        fun stripThinking(t: String): String =
            t.replace(Regex("(?s)<think>.*?</think>"), "").replace(Regex("(?s)^.*</think>"), "").trim()
    }
}
