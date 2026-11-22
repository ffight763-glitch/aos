package com.dozy.pocketai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dozy.pocketai.data.Message
import com.dozy.pocketai.data.WebSource
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(vm: ChatViewModel, openModels: () -> Unit) {
    val p = LocalPulse.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawer,
        scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
        drawerContent = {
            ChatDrawer(
                ui = ui,
                onNew = { vm.newChat(); scope.launch { drawer.close() } },
                onOpen = { vm.openChat(it); scope.launch { drawer.close() } },
                onDelete = { vm.deleteChat(it) },
            )
        },
    ) {
        Column(Modifier.fillMaxSize().background(p.bg)) {
            PulseHeader(
                title = if (ui.conversation.messages.isEmpty()) "PocketAI" else ui.conversation.title,
                subtitle = (if (ui.codeMode) "codProd · " else "") + (ui.modelName ?: "Модель не загружена"),
                leading = { PillIcon(Icons.Outlined.Menu, "Чаты", size = 38.dp) { scope.launch { drawer.open() } } },
            ) {
                PillIcon(Icons.Outlined.Code, "codProd", active = ui.codeMode, size = 38.dp) { vm.toggleCode() }
                PillIcon(Icons.Outlined.Language, "Интернет", active = ui.webEnabled, size = 38.dp) { vm.toggleWeb() }
                PillIcon(Icons.Outlined.Add, "Новый чат", primary = true) { vm.newChat() }
            }

            ui.error?.let { err ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Red.copy(alpha = 0.18f))
                        .border(1.dp, Red.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                        .clickable { vm.dismissError() }
                        .padding(12.dp),
                ) { Text(err, color = p.ink, fontSize = 14.sp) }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (ui.conversation.messages.isEmpty() && ui.streaming.isEmpty() && ui.phase == Phase.Idle) {
                    EmptyState(loaded = ui.modelName != null, web = ui.webEnabled, code = ui.codeMode, openModels = openModels, onPrompt = vm::send)
                } else {
                    Messages(ui, onRegenerate = vm::regenerate)
                }
            }

            StatusLine(ui)
            InputBar(
                busy = ui.phase != Phase.Idle,
                web = ui.webEnabled,
                code = ui.codeMode,
                onSend = vm::send,
                onStop = vm::stop,
            )
        }
    }
}

@Composable
private fun ChatDrawer(
    ui: ChatUiState,
    onNew: () -> Unit,
    onOpen: (com.dozy.pocketai.data.Conversation) -> Unit,
    onDelete: (com.dozy.pocketai.data.Conversation) -> Unit,
) {
    val p = LocalPulse.current
    Column(
        Modifier
            .fillMaxHeight()
            .widthIn(max = 320.dp)
            .fillMaxWidth(0.8f)
            .clip(RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
            .background(p.bar)
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 18.dp),
    ) {
        Text("PocketAI", color = p.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
        Text(ui.modelName ?: "модель не загружена", color = p.muted, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
        Spacer(Modifier.size(14.dp))
        DrawerRow(Icons.Outlined.Add, "Новый чат", onClick = onNew)
        SectionHeading("История", Modifier.padding(start = 8.dp, top = 8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(ui.history, key = { it.id }) { c ->
                DrawerRow(Icons.Outlined.ChatBubbleOutline, c.title, active = c.id == ui.conversation.id, onClick = { onOpen(c) }) {
                    Icon(
                        Icons.Outlined.DeleteOutline, "Удалить", tint = p.muted,
                        modifier = Modifier.size(36.dp).clip(CircleShape).clickable { onDelete(c) }.padding(8.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(loaded: Boolean, web: Boolean, code: Boolean, openModels: () -> Unit, onPrompt: (String) -> Unit) {
    val p = LocalPulse.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(76.dp).clip(CircleShape).background(p.card).border(1.dp, p.gridLine, CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (code) Icons.Outlined.Code else Icons.Outlined.AutoAwesome, null, tint = p.ink, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.size(16.dp))
        Text(if (code) "codProd" else "ИИ прямо в телефоне", color = p.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(6.dp))
        Text(
            if (code) "Режим программиста: пишет, объясняет и чинит код. Лучше всего — с моделью Qwen2.5 Coder."
            else if (loaded) "Модель работает офлайн. Включи 🌐, чтобы она искала ответы в интернете."
            else "Скачай модель — она будет работать без интернета и без серверов.",
            color = p.muted, fontSize = 14.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(20.dp))
        if (!loaded) {
            PillButton("Выбрать модель", primary = true, icon = Icons.Outlined.Memory, onClick = openModels)
        } else {
            val prompts = if (code) listOf("Kotlin: функция проверки email", "Найди ошибку в моём коде", "Bash-скрипт бэкапа папки")
            else if (web) listOf("Какие новости сегодня?", "Курс доллара к гривне", "Погода в Киеве на завтра")
            else listOf("Объясни, как работает нейросеть", "Напиши короткий стих про зиму", "Придумай идею для приложения")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                prompts.forEach { PillButton(it) { onPrompt(it) } }
            }
        }
    }
}

@Composable
private fun Messages(ui: ChatUiState, onRegenerate: () -> Unit) {
    val list = rememberLazyListState()
    val msgs = ui.conversation.messages
    val total = msgs.size + if (ui.streaming.isNotEmpty()) 1 else 0
    LaunchedEffect(total, ui.streaming.length / 40) {
        if (total > 0) list.animateScrollToItem(total - 1)
    }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(msgs, key = { _, m -> m.id }) { i, m ->
            if (m.role == "user") UserBubble(m)
            else BotBubble(
                m.text, m,
                // sources live on the user message that triggered the search
                sources = msgs.getOrNull(i - 1)?.takeIf { it.role == "user" }?.sources.orEmpty(),
                isLast = i == msgs.lastIndex && ui.phase == Phase.Idle,
                onRegenerate = onRegenerate,
            )
        }
        if (ui.streaming.isNotEmpty()) item("streaming") { BotBubble(ui.streaming, null, emptyList(), isLast = false, onRegenerate = {}) }
    }
}

@Composable
private fun UserBubble(m: Message) {
    val p = LocalPulse.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Text(
            m.text,
            color = p.ink,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp))
                .background(p.selected)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
        if (m.sources.isNotEmpty()) {
            Text("🌐 найдено источников: ${m.sources.size}", color = p.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp, end = 6.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BotBubble(text: String, m: Message?, sources: List<WebSource>, isLast: Boolean, onRegenerate: () -> Unit) {
    val p = LocalPulse.current
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    var showThinking by rememberSaveable(m?.id) { mutableStateOf(false) }
    val (thinking, answer) = remember(text) { splitThinking(text) }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp))
                .background(p.card)
                .border(1.dp, p.gridLine, RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (thinking != null) {
                Text(
                    if (showThinking) "▾ Размышления" else "▸ Размышления…",
                    color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { showThinking = !showThinking }.padding(bottom = 4.dp),
                )
                AnimatedVisibility(showThinking) {
                    Text(thinking, color = p.muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            if (answer.isNotBlank()) MarkdownText(answer, p.ink) else if (m == null) Text("…", color = p.muted)
        }

        if (sources.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                sources.forEachIndexed { i, s ->
                    Text(
                        "[${i + 1}] " + s.url.substringAfter("://").substringBefore('/').removePrefix("www."),
                        color = p.ink, fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(p.bar)
                            .border(1.dp, p.gridLine, RoundedCornerShape(50))
                            .clickable { runCatching { uri.openUri(s.url) } }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }

        if (m != null) {
            Row(Modifier.padding(top = 2.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallAction(Icons.Outlined.ContentCopy, "Копировать") { clipboard.setText(AnnotatedString(answer)) }
                if (isLast) SmallAction(Icons.Outlined.Refresh, "Заново", onRegenerate)
                m.tokensPerSec?.let { Text("%.1f ток/с".format(it), color = p.muted, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp)) }
            }
        }
    }
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val p = LocalPulse.current
    Icon(icon, label, tint = p.muted, modifier = Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onClick).padding(8.dp))
}

@Composable
private fun StatusLine(ui: ChatUiState) {
    val p = LocalPulse.current
    val text = ui.status ?: when (ui.phase) {
        Phase.Generating -> "Печатает…"
        else -> null
    } ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), color = p.ink, strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = p.muted, fontSize = 12.sp)
    }
}

@Composable
private fun InputBar(busy: Boolean, web: Boolean, code: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    val p = LocalPulse.current
    var text by rememberSaveable { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(p.inset)
                .border(1.dp, p.outline, RoundedCornerShape(22.dp))
                .padding(horizontal = 16.dp, vertical = 11.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) Text(when {
                code -> "codProd: задача или код…"
                web -> "Спросить с поиском в интернете…"
                else -> "Сообщение…"
            }, color = p.muted, fontSize = 15.sp)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = p.ink, fontSize = 15.sp, lineHeight = 21.sp),
                cursorBrush = SolidColor(p.ink),
                maxLines = if (code) 12 else 6,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(8.dp))
        if (busy) {
            PillIcon(Icons.Outlined.Stop, "Стоп", primary = true, onClick = onStop)
        } else {
            PillIcon(Icons.AutoMirrored.Outlined.Send, "Отправить", primary = true, enabled = text.isNotBlank()) {
                onSend(text)
                text = ""
            }
        }
    }
}

private fun splitThinking(t: String): Pair<String?, String> {
    val open = t.indexOf("<think>")
    if (open < 0) return null to t
    val close = t.indexOf("</think>")
    return if (close < 0) t.substring(open + 7).trim() to ""
    else t.substring(open + 7, close).trim() to (t.substring(0, open) + t.substring(close + 8)).trim()
}
