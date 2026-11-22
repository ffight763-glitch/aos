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

/** Small Markdown renderer: code fences, headings, bullet lists, **bold**, *italic*, `code`. */
@Composable
fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
    val p = LocalPulse.current
    val blocks = splitBlocks(text)
    val clipboard = LocalClipboardManager.current
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
