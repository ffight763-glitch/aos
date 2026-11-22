package pro.translate.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import android.content.res.Configuration
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import pro.translate.app.AppViewModel
import pro.translate.app.Languages
import pro.translate.app.SpeechListener

private val ThemColor = Color(0xFF2E7D5B)

@Composable
fun DialogueScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val listener = remember { SpeechListener(ctx) }
    var listening by remember { mutableStateOf<Boolean?>(null) } // side being heard: true = me
    var ready by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }
    var pendingSide by remember { mutableStateOf<Boolean?>(null) }
    var picker by remember { mutableStateOf<Boolean?>(null) }

    DisposableEffect(Unit) { onDispose { listener.stop() } }
    LaunchedEffect(Unit) { vm.refreshModels() }

    // Fallback: the system voice dialog when SpeechRecognizer isn't available.
    val dialogVoice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        val side = pendingSide; pendingSide = null
        if (!t.isNullOrBlank() && side != null) vm.dSend(side, t)
    }

    fun listen(mine: Boolean) {
        vm.speaker.stop()
        val lang = if (mine) vm.dMe else vm.dThem
        if (!listener.available()) {
            pendingSide = mine
            try {
                dialogVoice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, SpeechListener.speechTag(lang)))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(ctx, "Нет сервиса распознавания речи — установите приложение Google", Toast.LENGTH_LONG).show()
            }
            return
        }
        listening = mine; ready = false; partial = ""
        listener.start(lang,
            onReady = { ready = true },
            onLevel = { level = it },
            onPartial = { partial = it },
            onFinal = { t -> listening = null; partial = ""; level = 0f; vm.dSend(mine, t) },
            onError = { e -> listening = null; partial = ""; level = 0f
                Toast.makeText(ctx, e, Toast.LENGTH_SHORT).show() })
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val side = pendingSide; pendingSide = null
        if (ok && side != null) listen(side)
        else if (!ok) Toast.makeText(ctx, "Без микрофона можно писать текстом", Toast.LENGTH_SHORT).show()
    }

    fun onMic(mine: Boolean) {
        if (listening == mine) { listener.finishNow(); return } // second tap = done
        if (listening != null) { listener.stop(); listening = null; partial = "" }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingSide = mine; micPermission.launch(Manifest.permission.RECORD_AUDIO); return
        }
        listen(mine)
    }

    @Composable
    fun Side(mine: Boolean, modifier: Modifier) = DialoguePanel(
        vm = vm, mine = mine, modifier = modifier,
        listening = listening == mine, ready = ready, partial = if (listening == mine) partial else "",
        level = if (listening == mine) level else 0f,
        onMic = { onMic(mine) }, onPickLang = { picker = mine }
    )

    // Auto-rotate: portrait = top/bottom, landscape = left/right. The button overrides it
    // until the phone is turned again.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var lastLandscape by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(landscape) }
    LaunchedEffect(landscape) { if (landscape != lastLandscape) { lastLandscape = landscape; vm.resetSplitToAuto() } }
    val split = vm.dManual ?: if (landscape) "horizontal" else "vertical"
    val vertical = split == "vertical"
    val flip = if (vertical) vm.dFlipV else vm.dFlipH
    val themMod = Modifier.graphicsLayer { if (flip) rotationZ = 180f }
    if (vertical) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Side(false, themMod.weight(1f).fillMaxWidth())
            ControlBar(vm, horizontal = true, split = split, flip = flip)
            Side(true, Modifier.weight(1f).fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxSize().padding(8.dp)) {
            Side(true, Modifier.weight(1f).fillMaxHeight())
            ControlBar(vm, horizontal = false, split = split, flip = flip)
            Side(false, themMod.weight(1f).fillMaxHeight())
        }
    }

    picker?.let { mine ->
        LanguagePicker(if (mine) vm.dMe else vm.dThem, false, vm.downloaded,
            { vm.setDLang(mine, it); picker = null }, { picker = null })
    }
}

@Composable
private fun ControlBar(vm: AppViewModel, horizontal: Boolean, split: String, flip: Boolean) {
    val items: List<Triple<androidx.compose.ui.graphics.vector.ImageVector, String, () -> Unit>> = listOf(
        Triple(if (split == "vertical") Icons.Default.ViewColumn else Icons.Default.ViewAgenda,
            if (split == "vertical") "Лево / право" else "Верх / низ") { vm.toggleSplit(split) },
        Triple(Icons.Default.Flip, "Развернуть собеседника") { vm.toggleFlip(split == "vertical") },
        Triple(Icons.Default.SwapHoriz, "Поменять языки", vm::swapDLangs),
        Triple(if (vm.dAutoSpeak) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
            "Автоозвучка", vm::toggleAutoSpeak),
        Triple(Icons.Default.DeleteSweep, "Очистить", vm::clearDialogue),
    )
    val content: @Composable () -> Unit = {
        items.forEach { (icon, desc, action) ->
            IconButton(onClick = action) {
                Icon(icon, desc, tint = if (desc == "Развернуть собеседника" && flip) Accent
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (horizontal) Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), Arrangement.SpaceEvenly, Alignment.CenterVertically) { content() }
    else Column(Modifier.fillMaxHeight().padding(horizontal = 2.dp), Arrangement.SpaceEvenly, Alignment.CenterHorizontally) { content() }
}

@Composable
private fun DialoguePanel(
    vm: AppViewModel, mine: Boolean, modifier: Modifier,
    listening: Boolean, ready: Boolean, partial: String, level: Float,
    onMic: () -> Unit, onPickLang: () -> Unit,
) {
    val color = if (mine) Accent else ThemColor
    val lang = if (mine) vm.dMe else vm.dThem
    val otherLang = if (mine) vm.dThem else vm.dMe
    var text by remember { mutableStateOf("") }
    val state = rememberLazyListState()
    val msgs = vm.dMsgs
    LaunchedEffect(msgs.size, msgs.lastOrNull()?.translated, partial) {
        val n = msgs.size + (if (partial.isNotEmpty()) 1 else 0)
        if (n > 0) state.animateScrollToItem(n - 1)
    }

    Card(modifier, shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(8.dp))
                Text(if (mine) "Вы" else "Собеседник", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                AssistChip(onClick = onPickLang, label = { Text(Languages.displayName(lang), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) })
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state, contentPadding = PaddingValues(10.dp)) {
                if (msgs.isEmpty() && partial.isEmpty()) item {
                    Text(if (mine) "Нажмите микрофон и говорите на языке «${Languages.displayName(lang)}»"
                        else "Нажмите микрофон — собеседник говорит на «${Languages.displayName(lang)}»",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(8.dp))
                }
                items(msgs, key = { it.id }) { m ->
                    val own = m.mine == mine
                    // Own phrase: what I said. The other side's phrase: its translation into my language.
                    val main = if (own) m.original else (m.translated ?: if (m.failed) "⚠ не удалось перевести" else "…")
                    val sub = if (own) (m.translated?.let { "→ $it" } ?: if (m.failed) "⚠ нет перевода — нажмите, чтобы повторить" else "перевожу…") else m.original
                    Bubble(main, sub, own, if (m.mine) Accent else ThemColor) {
                        when {
                            m.failed -> vm.dRetry(m)
                            own -> m.translated?.let { vm.speaker.speak(it, Languages.locale(otherLang)) }
                            else -> m.translated?.let { vm.speaker.speak(it, Languages.locale(lang)) }
                        }
                    }
                }
                if (partial.isNotEmpty()) item("partial") {
                    Text(partial, Modifier.fillMaxWidth().padding(8.dp), fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
                }
            }

            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("Текст…") }, shape = RoundedCornerShape(16.dp),
                    trailingIcon = {
                        if (text.isNotBlank()) IconButton({ vm.dSend(mine, text); text = "" }) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Отправить", tint = color)
                        }
                    })
                Spacer(Modifier.width(8.dp))
                val pulse by animateFloatAsState(if (listening) 1f + level * 0.25f else 1f, label = "pulse")
                FloatingActionButton(onClick = onMic, containerColor = if (listening) Color(0xFFE5484D) else color,
                    contentColor = Color.White, shape = CircleShape, modifier = Modifier.size(60.dp).scale(pulse)) {
                    if (listening && !ready) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    else Icon(if (listening) Icons.Default.Stop else Icons.Default.Mic, if (listening) "Готово" else "Говорить")
                }
            }
        }
    }
}

@Composable
private fun Bubble(main: String, sub: String, own: Boolean, color: Color, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = if (own) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(Modifier.fillMaxWidth(0.88f).clip(RoundedCornerShape(18.dp))
            .background(if (own) color.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(main, style = if (own) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
