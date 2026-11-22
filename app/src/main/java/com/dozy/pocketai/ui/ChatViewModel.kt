package com.dozy.pocketai.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dozy.pocketai.PocketApp
import com.dozy.pocketai.data.AppSettings
import com.dozy.pocketai.data.AppsStore
import com.dozy.pocketai.data.Conversation
import com.dozy.pocketai.data.LocalModel
import com.dozy.pocketai.data.Message
import com.dozy.pocketai.llm.ChatTurn
import com.dozy.pocketai.llm.GenParams
import com.dozy.pocketai.llm.LlamaEngine
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
    val fastSearch: Boolean = false,
    val builderMode: Boolean = false,
    val combo: Boolean = false,
    val draft: String? = null,               // text to put back into the input (edit message)
    val preview: PreviewRequest? = null,     // Builder: open this app as soon as it is ready
    val notice: String? = null,
    val streaming: String = "",
    val error: String? = null,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val pocket = app as PocketApp
    private val engine = pocket.engine
    private val engine2 = pocket.engine2
    val models = pocket.models
    val updater = pocket.updater
    val github = pocket.github
    val terminal = pocket.terminal
    val apps = pocket.apps

    private val _settings = MutableStateFlow(pocket.settings.load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _ui = MutableStateFlow(ChatUiState(history = pocket.chats.list()))
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private var job: Job? = null

    init {
        WebSearch.engines = _settings.value.engines
        WebSearch.blockRu = _settings.value.blockRu
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
                engine.load(m.file.absolutePath, s.contextSize, s.genThreads, AppSettings.batchThreads(), s.kvQ8)
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
        WebSearch.engines = s.engines
        WebSearch.blockRu = s.blockRu
        _settings.value = s
        pocket.settings.save(s)
    }

    fun toggleWeb() = _ui.update { it.copy(webEnabled = !it.webEnabled) }
    fun setFastSearch(on: Boolean) = _ui.update { it.copy(fastSearch = on) }
    fun toggleCode() = _ui.update { it.copy(codeMode = !it.codeMode, builderMode = false) }

    /** 0 = chat, 1 = codProd, 2 = Builder */
    fun setMode(mode: Int) = _ui.update { it.copy(codeMode = mode == 1, builderMode = mode == 2) }
    fun toggleCombo() {
        val on = !_ui.value.combo
        if (on && _settings.value.comboModel == null) {
            _ui.update { it.copy(error = "Выбери вторую модель для Комбо: нажми на название модели в шапке") }
            return
        }
        _ui.update { it.copy(combo = on) }
        if (!on) viewModelScope.launch { engine2.unload() }
    }
    fun setComboModel(path: String?) = updateSettings(_settings.value.copy(comboModel = path))
    fun setComboStyle(style: Int) = updateSettings(_settings.value.copy(comboStyle = style))
    fun consumeDraft() = _ui.update { it.copy(draft = null) }
    fun consumePreview() = _ui.update { it.copy(preview = null) }
    fun dismissNotice() = _ui.update { it.copy(notice = null) }

    /** Claude-style edit: drop this message and everything after it, put its text back into the input. */
    fun editMessage(m: Message) {
        if (_ui.value.phase != Phase.Idle) return
        val msgs = _ui.value.conversation.messages
        val i = msgs.indexOfFirst { it.id == m.id }
        if (i < 0) return
        setMessages(msgs.subList(0, i))
        _ui.update { it.copy(draft = m.text) }
    }

    /** Codex-style: commit a code block from the chat into the attached repository. */
    fun commitToRepo(path: String, content: String, message: String) {
        val att = github.state.value.attachment ?: run {
            _ui.update { it.copy(error = "Сначала подключи репозиторий кнопкой 📁") }; return
        }
        viewModelScope.launch {
            _ui.update { it.copy(status = "Коммит в ${att.repo.fullName}…") }
            runCatching { github.commitFile(att.repo, path, content, message) }
                .onSuccess { url -> _ui.update { it.copy(notice = "✓ Закоммичено: $path\n$url") } }
                .onFailure { e -> _ui.update { it.copy(error = e.message) } }
            _ui.update { it.copy(status = null) }
        }
    }
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
        engine2.stop()
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
        val builder = _ui.value.builderMode
        val fast = useWeb && _ui.value.fastSearch
        job = viewModelScope.launch {
            var userMsg = Message(role = "user", text = input)
            setMessages(_ui.value.conversation.messages + userMsg, titleFrom = input)
            val s = _settings.value
            try {
                val att = github.state.value.attachment
                if (att != null && att.files.isNotEmpty()) {
                    _ui.update { it.copy(phase = Phase.Searching, status = "Читаю файлы ${att.repo.fullName}…") }
                    // ~3 chars per token; leave room for history and the answer
                    val budget = ((engine.contextSize() - s.maxTokens) * 2).coerceIn(2_000, 24_000)
                    val ctx = github.buildContext(att, budget)
                    userMsg = userMsg.copy(repoContext = ctx, repoFiles = att.files.sorted())
                    replaceLast(userMsg)
                }
                if (useWeb) {
                    _ui.update { it.copy(phase = Phase.Searching, status = if (fast) "⚡ Быстрый поиск…" else "Ищу в интернете…") }
                    val query = if (fast) input else rewriteQuery(s, input, code, builder)
                    if (query != input) _ui.update { it.copy(status = "Ищу: $query") }
                    val ctx = if (fast) WebSearch.quickContext(query)
                    else WebSearch.buildContext(query, s.webResults, s.webCharsPerPage)
                    if (ctx.text.isBlank()) {
                        _ui.update { it.copy(status = "Ничего не найдено — отвечаю без интернета") }
                    } else {
                        userMsg = userMsg.copy(webContext = ctx.text, sources = ctx.sources)
                        replaceLast(userMsg)
                    }
                }

                _ui.update { it.copy(phase = Phase.Thinking, status = "Читаю контекст…") }
                // codProd: precise and long enough for whole files
                val gen = if (builder) GenParams(
                    maxOf(s.maxTokens, 2048), minOf(s.temperature, 0.3f), 0.9f, 40, 1.05f,
                ) else if (code) GenParams(
                    maxOf(s.maxTokens, 1536), minOf(s.temperature, 0.2f), 0.9f, 40, 1.05f,
                ) else if (fast) GenParams(
                    minOf(s.maxTokens, 320), minOf(s.temperature, 0.4f), s.topP, s.topK, s.repeatPenalty,
                ) else GenParams(s.maxTokens, s.temperature, s.topP, s.topK, s.repeatPenalty)
                val turns = buildTurns(s, code, fast, builder)
                val prompt = engine.buildPrompt(turns, reserve = gen.maxTokens + 16)

                val nameA = _ui.value.modelName ?: "Модель"
                val a = stream(engine, prompt, gen)
                val combo = _ui.value.combo && s.comboModel != null && java.io.File(s.comboModel).exists()
                val final: Message
                if (!combo) {
                    final = Message(role = "assistant", text = a.text, tokensPerSec = a.tps, model = nameA)
                    setMessages(_ui.value.conversation.messages + final)
                } else {
                    val pathB = s.comboModel!!
                    val nameB = java.io.File(pathB).nameWithoutExtension
                    if (engine2.loadedPath != pathB) {
                        _ui.update { it.copy(phase = Phase.LoadingModel, status = "Комбо: загружаю $nameB…") }
                        engine2.load(pathB, minOf(s.contextSize, 4096), s.genThreads, AppSettings.batchThreads(), true)
                    }
                    _ui.update { it.copy(phase = Phase.Thinking, status = "Комбо: $nameB…") }
                    if (s.comboStyle == 0) {
                        // draft → improve
                        val last = turns.last()
                        val turnsB = turns.dropLast(1) + last.copy(
                            content = last.content + "\n\nЧерновик ответа от другой модели:\n" + stripThinking(a.text) +
                                "\n\nПроверь черновик: исправь ошибки, дополни недостающее и дай окончательный ответ. Пиши только сам ответ.",
                        )
                        val b = stream(engine2, engine2.buildPrompt(turnsB, reserve = gen.maxTokens + 16), gen)
                        final = Message(role = "assistant", text = b.text, tokensPerSec = b.tps, model = "$nameA → $nameB", draft = a.text)
                        setMessages(_ui.value.conversation.messages + final)
                    } else {
                        // both answer
                        setMessages(_ui.value.conversation.messages + Message(role = "assistant", text = a.text, model = nameA))
                        val b = stream(engine2, engine2.buildPrompt(turns, reserve = gen.maxTokens + 16), gen)
                        final = Message(role = "assistant", text = b.text, tokensPerSec = b.tps, model = nameB, secondary = true)
                        setMessages(_ui.value.conversation.messages + final)
                    }
                }
                if (builder) AppsStore.extractHtml(final.text)?.let { html ->
                    val c = _ui.value.conversation
                    val app = apps.save(c.id, c.title, html)
                    _ui.update { it.copy(preview = PreviewRequest(app.title, html)) }
                }
            } catch (e: CancellationException) {
                // stopped by the user
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _ui.update { it.copy(phase = Phase.Idle, status = null, streaming = "") }
            }
        }
    }

    /**
     * Turns a fast, typo-ridden message into a clean search query. Uses the chat so far
     * as the prompt prefix, so the model's KV cache is reused and this costs only a few tokens.
     */
    private suspend fun rewriteQuery(s: AppSettings, input: String, code: Boolean, builder: Boolean): String = runCatching {
        _ui.update { it.copy(status = "Понимаю вопрос…") }
        val turns = buildTurns(s, code, false, builder).dropLast(1) + ChatTurn(
            "user",
            "Составь один короткий поисковый запрос для Google по этому сообщению, исправив опечатки и учтя контекст диалога. " +
                "Ответь только запросом, без кавычек и пояснений.\nСообщение: $input",
        )
        val prompt = engine.buildPrompt(turns, reserve = 48)
        val out = StringBuilder()
        engine.generate(prompt, GenParams(maxTokens = 32, temperature = 0f)).collect { out.append(it) }
        stripThinking(out.toString()).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            .trim().trim('"', '«', '»', '\'').take(200)
    }.getOrNull()?.takeIf { it.length >= 2 } ?: input

    private data class Streamed(val text: String, val tps: Float?)

    /** Streams one answer into the chat bubble; returns the full text. */
    private suspend fun stream(e: LlamaEngine, prompt: ByteArray, gen: GenParams): Streamed {
        val out = StringBuilder()
        var count = 0
        var startedAt = 0L
        var lastUi = 0L
        e.generate(prompt, gen).collect { chunk ->
            if (count == 0) {
                startedAt = System.nanoTime()
                _ui.update { it.copy(phase = Phase.Generating, status = null) }
            }
            count++
            out.append(chunk)
            // redraw at most ~20×/s: recomposing per token steals CPU from the model
            val now = System.nanoTime()
            if (now - lastUi > 50_000_000L) {
                lastUi = now
                _ui.update { it.copy(streaming = out.toString()) }
            }
        }
        _ui.update { it.copy(streaming = "") }
        val secs = (System.nanoTime() - startedAt) / 1e9f
        return Streamed(out.toString().trim(), if (count > 1 && secs > 0) count / secs else null)
    }

    private fun buildTurns(s: AppSettings, code: Boolean, fast: Boolean = false, builder: Boolean = false): List<ChatTurn> {
        val msgs = _ui.value.conversation.messages
        val date = SimpleDateFormat("d MMMM yyyy, EEEE", Locale("ru")).format(Date())
        val base = when {
            builder -> BUILDER_SYSTEM
            code -> CODPROD_SYSTEM
            else -> s.systemPrompt
        }
        val memory = s.memory.trim().takeIf { it.isNotEmpty() }?.let { "\nЧто ты знаешь о пользователе:\n$it" }.orEmpty()
        val system = base + UNDERSTAND + memory + "\nСегодня $date."
        val turns = mutableListOf(ChatTurn("system", system))
        msgs.forEachIndexed { i, m ->
            if (m.secondary) return@forEachIndexed // combo's second answer stays out of the history
            val isLast = i == msgs.lastIndex
            val content = if (m.role == "user" && isLast && (m.webContext != null || m.repoContext != null)) {
                buildString {
                    m.repoContext?.let { append(it).append("\n\n") }
                    m.webContext?.let {
                        append("Результаты поиска в интернете:\n\n").append(it)
                            .append("\n\nИспользуя эти результаты (ссылайся на номера источников вида [1]), ответь.")
                            // the hint goes into the message, not the system prompt, so the KV cache stays valid
                            .append(if (fast) " Кратко, 2–4 предложения, сразу по сути.\n\n" else "\n\n")
                    }
                    append("Вопрос: ").append(m.text)
                }
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
        /** Users type fast, by voice, with typos and missing spaces: recover the meaning first. */
        const val UNDERSTAND =
            "\nПользователь часто пишет быстро: с опечатками, без пробелов и знаков, голосовым вводом, смешивая языки. " +
                "Сначала про себя восстанови, что он имел в виду (по смыслу и контексту диалога), и отвечай на это. " +
                "Не поправляй его ошибки и не переспрашивай, если смысл понятен."

        /** Builder — like Build in Google AI Studio: describe an app, get a running one. */
        const val BUILDER_SYSTEM =
            "Ты — Builder внутри PocketAI: создаёшь готовые к запуску мини-приложения. " +
                "Всегда отвечай ОДНИМ полным HTML-файлом в блоке ```html: <!DOCTYPE html>, CSS в <style>, JS в <script>, " +
                "без внешних файлов и без заглушек. Дизайн — для экрана телефона, тёмная тема, крупные кнопки. " +
                "Данные храни в localStorage. Перед кодом — одна строка, что сделано. " +
                "Если просят изменить приложение — верни весь обновлённый файл целиком."

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
