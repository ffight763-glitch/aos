package com.dozy.pocketai.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStreamWriter

/**
 * A persistent /system/bin/sh session inside the app sandbox (no root needed).
 * `cd`, variables and functions persist between commands like in a real terminal.
 */
class Terminal(private val context: Context, private val scope: CoroutineScope) {
    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()
    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    val home: File = File(context.filesDir, "home").apply { mkdirs() }
    private var process: Process? = null
    private var stdin: OutputStreamWriter? = null
    private val lock = Any()
    private val maxChars = 200_000

    private fun ensure() {
        if (process?.isAlive == true) return
        val pb = ProcessBuilder("/system/bin/sh").directory(home).redirectErrorStream(true)
        pb.environment().apply {
            put("HOME", home.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
            put("PS1", "")
            put("TERM", "dumb")
        }
        val p = pb.start()
        process = p
        stdin = OutputStreamWriter(p.outputStream)
        scope.launch(Dispatchers.IO) {
            val buf = CharArray(4096)
            val reader = p.inputStream.reader()
            while (true) {
                val n = runCatching { reader.read(buf) }.getOrDefault(-1)
                if (n < 0) break
                append(String(buf, 0, n))
            }
            append("\n[shell завершён]\n")
        }
        append("PocketAI shell · ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\n" +
            "Папка: ${home.absolutePath}\n\n")
    }

    private fun append(s: String) = synchronized(lock) {
        val v = _output.value + s
        _output.value = if (v.length > maxChars) v.takeLast(maxChars) else v
    }

    fun run(command: String) {
        val cmd = command.trimEnd()
        if (cmd.isBlank()) return
        if (cmd == "clear") { _output.value = ""; return }
        _history.value = (_history.value.filter { it != cmd } + cmd).takeLast(50)
        scope.launch(Dispatchers.IO) {
            runCatching {
                ensure()
                append("$ $cmd\n")
                stdin!!.apply { write(cmd + "\n"); flush() }
            }.onFailure { append("ошибка: ${it.message}\n") }
        }
    }

    /** Kills whatever is running and starts a fresh shell (there is no Ctrl+C on a pipe). */
    fun restart() {
        process?.destroy()
        process = null
        append("\n^C — новый shell\n")
        scope.launch(Dispatchers.IO) { runCatching { ensure() } }
    }

    fun start() = scope.launch(Dispatchers.IO) { runCatching { ensure() } }
}
