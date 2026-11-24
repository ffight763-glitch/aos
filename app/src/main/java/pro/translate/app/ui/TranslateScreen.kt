package pro.translate.app.ui

import android.content.ActivityNotFoundException
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.scale
import androidx.core.content.ContextCompat
import pro.translate.app.SpeechListener
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pro.translate.app.AUTO
import pro.translate.app.AppViewModel
import pro.translate.app.Languages

@Composable
fun TranslateScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var picker by remember { mutableStateOf<String?>(null) } // "from" / "to"

    LaunchedEffect(Unit) { vm.refreshModels() }

    // ---- live voice: words appear and get translated while you speak; spoken right after the pause ----
    val listener = remember { SpeechListener(ctx) }
    var session by remember { mutableStateOf(false) }   // mic is "on" (may be waiting while TTS speaks)
    var hearing by remember { mutableStateOf(false) }   // recogniser is actually listening
    var ready by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var misses by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) { onDispose { listener.stop(); vm.speaker.stop() } }
    LaunchedEffect(vm.from, vm.to) { vm.warmUp(vm.voiceLang(), vm.to) }

    val voiceDialog = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) vm.voiceFinal(text, vm.voiceLang()) {}
    }

    fun listenOnce() {
        val lang = vm.voiceLang()
        hearing = true; ready = false
        vm.configure(listener)
        listener.start(lang,
            onReady = { ready = true },
            onLevel = { level = it },
            onPartial = { if (it.isBlank()) vm.clear() else vm.voicePartial(it, lang) },
            onFinal = { t ->
                hearing = false; level = 0f; misses = 0
                vm.voiceFinal(t, lang) { if (session && vm.vContinuous) listenOnce() else session = false }
            },
            onError = { e ->
                hearing = false; level = 0f
                val silent = e == "Ничего не расслышал"
                if (session && vm.vContinuous && silent && ++misses < 4) listenOnce()
                else { session = false; misses = 0; if (!silent) Toast.makeText(ctx, e, Toast.LENGTH_SHORT).show() }
            })
    }

    fun startSession() {
        vm.speaker.stop()
        if (!listener.available()) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, SpeechListener.speechTag(vm.voiceLang()))
            try { voiceDialog.launch(intent) } catch (_: ActivityNotFoundException) {
                Toast.makeText(ctx, "На телефоне нет сервиса распознавания речи (нужно приложение Google)", Toast.LENGTH_LONG).show()
            }
            return
        }
        session = true; misses = 0
        listenOnce()
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startSession() else Toast.makeText(ctx, "Без доступа к микрофону голос не работает", Toast.LENGTH_SHORT).show()
    }

    fun startVoice() {
        if (session) { // tap again = stop; what was heard so far is still translated and spoken
            session = false
            if (hearing) listener.finishNow() else vm.speaker.stop()
            return
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        else startSession()
    }

    fun speak(text: String, lang: String?) {
        if (lang == null) return
        if (!vm.speaker.speak(text, Languages.locale(lang)))
            Toast.makeText(ctx, "Голос для «${Languages.displayName(lang)}» не установлен в синтезе речи", Toast.LENGTH_LONG).show()
    }

    Box(Modifier.fillMaxSize()) {
    VoiceWaves(session && hearing && ready, level, Accent, Modifier.fillMaxWidth().height(220.dp).align(Alignment.BottomCenter))
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // Language bar
        Row(verticalAlignment = Alignment.CenterVertically) {
            LangButton(
                if (vm.from == AUTO && vm.detected != null) "${Languages.displayName(vm.detected!!)} · авто"
                else Languages.displayName(vm.from),
                Modifier.weight(1f)
            ) { picker = "from" }
            IconButton(onClick = vm::swap, enabled = vm.from != AUTO || vm.detected != null) {
                Icon(Icons.Default.SwapHoriz, "Поменять")
            }
            LangButton(Languages.displayName(vm.to), Modifier.weight(1f)) { picker = "to" }
        }
        Spacer(Modifier.height(12.dp))

        // Input
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            TextField(
                value = vm.input, onValueChange = vm::updateInput,
                placeholder = { Text("Введите текст", fontSize = 22.sp) },
                textStyle = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                )
            )
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                MicButton(session && hearing, ready, level, bump = vm.input, color = MaterialTheme.colorScheme.primary,
                    onClick = ::startVoice, size = 52.dp)
                Spacer(Modifier.width(4.dp))
                FilterChip(selected = vm.vContinuous, onClick = vm::toggleContinuous,
                    label = { Text("Синхрон") }, leadingIcon = { Icon(Icons.Default.Autorenew, null, Modifier.size(16.dp)) })
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { speak(vm.input, if (vm.from == AUTO) vm.detected else vm.from) }, enabled = vm.input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, "Озвучить")
                }
                IconButton(onClick = {
                    clipboard.getText()?.text?.let { vm.updateInput(it) }
                }) { Icon(Icons.Default.ContentPaste, "Вставить") }
                Spacer(Modifier.weight(1f))
                if (vm.input.isNotEmpty() || hearing) IconButton(onClick = {
                    if (hearing) listener.erase() else vm.clear()
                }) { Icon(if (hearing) Icons.AutoMirrored.Filled.Backspace else Icons.Default.Close, "Стереть") }
            }
        }

        if (session) Text(
            when {
                hearing && ready -> "Слушаю (${Languages.displayName(vm.voiceLang())})… переведу и озвучу после паузы"
                hearing -> "Включаю микрофон…"
                else -> "Озвучиваю перевод…"
            }, Modifier.padding(top = 8.dp, start = 4.dp), color = Accent, style = MaterialTheme.typography.bodySmall)
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp).height(2.dp))
        vm.status?.let {
            Text(it, Modifier.padding(top = 8.dp, start = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))

        // Output
        if (vm.output.isNotBlank()) {
            val fav = vm.history.favOf(vm.input, vm.to)
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)) {
                Text(Languages.displayName(vm.to), Modifier.padding(start = 16.dp, top = 14.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                SelectionContainer {
                    Text(vm.output, Modifier.fillMaxWidth().padding(16.dp, 8.dp),
                        style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    IconButton(onClick = { speak(vm.output, vm.to) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Озвучить") }
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(vm.output))
                        Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Default.ContentCopy, "Копировать") }
                    IconButton(onClick = {
                        ctx.startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, vm.output), null))
                    }) { Icon(Icons.Default.Share, "Поделиться") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        val e = fav ?: run {
                            val src = if (vm.from == AUTO) vm.detected ?: return@IconButton else vm.from
                            vm.history.add(src, vm.to, vm.input, vm.output)
                            vm.history.favOf(vm.input, vm.to)
                        }
                        e?.let { vm.history.toggleFav(it.id) }
                    }) {
                        Icon(if (fav?.fav == true) Icons.Default.Star else Icons.Default.StarBorder, "Избранное",
                            tint = if (fav?.fav == true) Color(0xFFFFC94D) else LocalContentColor.current)
                    }
                }
            }
        }

        // Recent
        val recent = vm.history.items.take(5)
        if (vm.input.isBlank() && recent.isNotEmpty()) {
            Text("Недавние", Modifier.padding(4.dp, 8.dp), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            recent.forEach { e -> HistoryRow(e, onClick = { vm.load(e) }, onFav = { vm.history.toggleFav(e.id) }) }
        }
    }

    }
    picker?.let { which ->
        LanguagePicker(
            selected = if (which == "from") vm.from else vm.to,
            allowAuto = which == "from",
            downloaded = vm.downloaded,
            onPick = { if (which == "from") vm.setFromLang(it) else vm.setToLang(it); picker = null },
            onDismiss = { picker = null }
        )
    }
}

@Composable
private fun LangButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.height(48.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
