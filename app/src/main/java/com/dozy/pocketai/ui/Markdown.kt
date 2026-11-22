package com.dozy.pocketai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Sends a command to the Terminal tab (provided by the app shell). */
val LocalRunInTerminal = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }

/** Codex-style "commit this block" (provided when a repo is attached): (path, content, message). */
data class RepoCommit(val defaultPath: String?, val commit: (String, String, String) -> Unit)
val LocalCommitToRepo = androidx.compose.runtime.staticCompositionLocalOf<RepoCommit?> { null }

/** Guesses the file a code block belongs to: ```kotlin:app/Main.kt or a first-line "// path: …" comment. */
private fun guessPath(lang: String, code: String): String? {
    lang.substringAfter(':', "").trim().takeIf { '.' in it }?.let { return it }
    val first = code.lineSequence().firstOrNull()?.trim().orEmpty()
    Regex("^(?://|#|<!--|/\\*)\\s*(?:path|file|файл)?:?\\s*([\\w./-]+\\.[\\w]+)").find(first)?.let { return it.groupValues[1] }
    return null
}

private val shellLangs = setOf("bash", "sh", "shell", "zsh", "console", "terminal")

/** Small Markdown renderer: code fences, headings, bullet lists, **bold**, *italic*, `code`. */
@Composable
fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
    val p = LocalPulse.current
    val blocks = splitBlocks(text)
    val clipboard = LocalClipboardManager.current
    val runInTerminal = LocalRunInTerminal.current
    val openPreview = LocalOpenPreview.current
    val repoCommit = LocalCommitToRepo.current
    var commitFor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Block.Code?>(null) }
    commitFor?.let { b ->
        CommitDialog(
            path = guessPath(b.lang, b.code) ?: repoCommit?.defaultPath.orEmpty(),
            onCommit = { path, msg -> repoCommit?.commit?.invoke(path, b.code, msg); commitFor = null },
            onDismiss = { commitFor = null },
        )
    }
    SelectionContainer(modifier) {
        Column {
            blocks.forEachIndexed { i, b ->
                if (i > 0) Spacer(Modifier.height(6.dp))
                when (b) {
                    is Block.Code -> Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.inset),
                    ) {
                        // header: language + copy, like a code block in a desktop chat
                        Row(
                            Modifier.fillMaxWidth().background(p.bar).padding(start = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(b.lang.ifEmpty { "код" }, color = p.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            val isHtml = b.lang.lowercase().substringBefore(':') in setOf("html", "htm") ||
                                b.code.trimStart().startsWith("<!DOCTYPE", true) || b.code.trimStart().startsWith("<html", true)
                            if (openPreview != null && isHtml) Row(
                                Modifier.clip(RoundedCornerShape(50)).clickable { openPreview(PreviewRequest("Превью", b.code)) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) { Text("▶ Запустить", color = p.ink, fontSize = 11.sp) }
                            if (repoCommit != null) Row(
                                Modifier.clip(RoundedCornerShape(50)).clickable { commitFor = b }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) { Text("⬆ В репо", color = p.ink, fontSize = 11.sp) }
                            if (runInTerminal != null && b.lang.lowercase().substringBefore(':') in shellLangs) Row(
                                Modifier.clip(RoundedCornerShape(50)).clickable {
                                    runInTerminal(b.code.lines().joinToString("\n") { it.removePrefix("$ ") })
                                }.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) { Text("▶ В терминал", color = p.ink, fontSize = 11.sp) }
                            Row(
                                Modifier.clip(RoundedCornerShape(50)).clickable { clipboard.setText(AnnotatedString(b.code)) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.ContentCopy, null, tint = p.muted, modifier = Modifier.size(14.dp))
                                Text(" Копировать", color = p.muted, fontSize = 11.sp)
                            }
                        }
                        Text(
                            b.code,
                            color = p.ink,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
                            softWrap = false,
                        )
                    }
                    is Block.Para -> Text(inline(b.text, p), color = color, fontSize = 15.sp, lineHeight = 21.sp)
                }
            }
        }
    }
}

@Composable
private fun CommitDialog(path: String, onCommit: (String, String) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPulse.current
    var file by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(path) }
    var msg by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.bar,
        title = { Text("Коммит в репозиторий", color = p.ink) },
        text = {
            Column {
                Text("Путь к файлу", color = p.muted, fontSize = 12.sp)
                DialogField(file, { file = it }, "app/src/Main.kt")
                Spacer(Modifier.height(10.dp))
                Text("Сообщение коммита", color = p.muted, fontSize = 12.sp)
                DialogField(msg, { msg = it }, "Update from PocketAI")
            }
        },
        confirmButton = { PillButton("Закоммитить", primary = true, enabled = file.isNotBlank()) { onCommit(file, msg) } },
        dismissButton = { PillButton("Отмена", onClick = onDismiss) },
    )
}

@Composable
private fun DialogField(value: String, onChange: (String) -> Unit, hint: String) {
    val p = LocalPulse.current
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).background(p.inset).padding(12.dp),
    ) {
        if (value.isEmpty()) Text(hint, color = p.muted, fontSize = 14.sp)
        androidx.compose.foundation.text.BasicTextField(
            value, onChange, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = p.ink, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(p.ink),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private sealed interface Block {
    data class Code(val lang: String, val code: String) : Block
    data class Para(val text: String) : Block
}

private fun splitBlocks(text: String): List<Block> {
    val out = mutableListOf<Block>()
    val parts = text.split("```")
    parts.forEachIndexed { i, part ->
        if (i % 2 == 1) {
            // inside a fence: drop the language tag on the first line
            val first = part.substringBefore('\n')
            val hasLang = part.contains('\n') && first.isNotBlank() && !first.trim().contains(' ')
            val code = (if (hasLang) part.substringAfter('\n') else part.removePrefix("\n")).trimEnd()
            if (code.isNotEmpty()) out += Block.Code(if (hasLang) first.trim() else "", code)
        } else {
            part.split(Regex("\n{2,}")).map { it.trim('\n') }.filter { it.isNotBlank() }.forEach { out += Block.Para(it) }
        }
    }
    return out
}

private fun inline(text: String, p: Pulse): AnnotatedString = buildAnnotatedString {
    val lines = text.lines()
    lines.forEachIndexed { li, raw ->
        var line = raw
        var heading = false
        val h = Regex("^#{1,6}\\s+").find(line)
        if (h != null) { heading = true; line = line.substring(h.value.length) }
        val bullet = Regex("^\\s*[-*+]\\s+").find(line)
        if (bullet != null) line = "  •  " + line.substring(bullet.value.length)
        if (heading) withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp)) { spans(line, p) } else spans(line, p)
        if (li < lines.lastIndex) append('\n')
    }
}

private val token = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\w)")

private fun AnnotatedString.Builder.spans(line: String, p: Pulse) {
    var pos = 0
    for (m in token.findAll(line)) {
        append(line.substring(pos, m.range.first))
        when {
            m.groupValues[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
            m.groupValues[2].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = p.inset)) { append(m.groupValues[2]) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groupValues[3]) }
        }
        pos = m.range.last + 1
    }
    append(line.substring(pos))
}
