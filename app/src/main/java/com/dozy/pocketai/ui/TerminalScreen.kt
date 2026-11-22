package com.dozy.pocketai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dozy.pocketai.data.Terminal

@Composable
fun TerminalScreen(terminal: Terminal) {
    val p = LocalPulse.current
    val out by terminal.output.collectAsStateWithLifecycle()
    val history by terminal.history.collectAsStateWithLifecycle()
    var cmd by rememberSaveable { mutableStateOf("") }
    var histIdx by rememberSaveable { mutableStateOf(-1) }
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) { terminal.start() }
    LaunchedEffect(out.length) { scroll.animateScrollTo(scroll.maxValue) }

    fun send() {
        terminal.run(cmd)
        cmd = ""
        histIdx = -1
    }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        PulseHeader("Терминал", subtitle = "sh · " + terminal.home.absolutePath) {
            PillIcon(Icons.Outlined.History, "Прошлая команда", size = 38.dp, enabled = history.isNotEmpty()) {
                histIdx = if (histIdx <= 0) history.lastIndex else histIdx - 1
                cmd = history.getOrElse(histIdx) { "" }
            }
            PillIcon(Icons.Outlined.DeleteSweep, "Очистить", size = 38.dp) { terminal.run("clear") }
            PillIcon(Icons.Outlined.StopCircle, "Прервать", size = 38.dp) { terminal.restart() }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(14.dp))
                .background(p.inset).border(1.dp, p.gridLine, RoundedCornerShape(14.dp)),
        ) {
            SelectionContainer {
                Text(
                    out.ifEmpty { "" },
                    color = p.ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
                    softWrap = false,
                    modifier = Modifier.fillMaxSize().verticalScroll(scroll).horizontalScroll(rememberScrollState()).padding(10.dp),
                )
            }
        }
        // quick commands
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("ls -la", "pwd", "df -h", "getprop ro.product.model", "uname -a", "top -n 1 -b | head -20").forEach { q ->
                PillButton(q) { terminal.run(q) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(p.inset)
                    .border(1.dp, p.outline, RoundedCornerShape(22.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("$ ", color = p.muted, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                BasicTextField(
                    cmd, { cmd = it },
                    singleLine = true,
                    textStyle = TextStyle(color = p.ink, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    cursorBrush = SolidColor(p.ink),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            PillIcon(Icons.AutoMirrored.Outlined.Send, "Выполнить", primary = true, enabled = cmd.isNotBlank()) { send() }
        }
    }
}
