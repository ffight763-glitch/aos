package com.dozy.pocketai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dozy.pocketai.data.AppSettings
import java.io.File
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: ChatViewModel) {
    val p = LocalPulse.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val set: (AppSettings) -> Unit = vm::updateSettings

    Column(Modifier.fillMaxSize().background(p.bg)) {
        PulseHeader("Настройки", subtitle = "PocketAI ${com.dozy.pocketai.BuildConfig.VERSION_NAME}")
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeading("GitHub")
            GitHubSection(vm.github)

            SectionHeading("Обновления")
            UpdateSection(vm.updater)

            SectionHeading("Оформление")
            PulseCard(Modifier.fillMaxWidth()) {
                Segmented(listOf("Система", "Ночь", "День"), s.theme) { set(s.copy(theme = it)) }
            }

            SectionHeading("Личность ассистента")
            PulseCard(Modifier.fillMaxWidth()) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(12.dp)).background(p.inset)
                        .border(1.dp, p.outline, RoundedCornerShape(12.dp)).padding(12.dp),
                ) {
                    BasicTextField(
                        s.systemPrompt, { set(s.copy(systemPrompt = it)) },
                        textStyle = TextStyle(color = p.ink, fontSize = 14.sp, lineHeight = 20.sp),
                        cursorBrush = SolidColor(p.ink), modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                PillButton("Сбросить") { set(s.copy(systemPrompt = AppSettings.DEFAULT_SYSTEM)) }
            }

            SectionHeading("Генерация")
            PulseCard(Modifier.fillMaxWidth()) {
                SliderRow("Креативность (temperature)", "%.2f".format(s.temperature), s.temperature, 0f..1.5f) {
                    set(s.copy(temperature = (it * 20).roundToInt() / 20f))
                }
                SliderRow("Макс. длина ответа", "${s.maxTokens} ток.", s.maxTokens.toFloat(), 128f..2048f) {
                    set(s.copy(maxTokens = ((it / 64).roundToInt() * 64).coerceAtLeast(128)))
                }
                SliderRow("Штраф за повторы", "%.2f".format(s.repeatPenalty), s.repeatPenalty, 1f..1.5f) {
                    set(s.copy(repeatPenalty = (it * 20).roundToInt() / 20f))
                }
            }

            SectionHeading("Производительность")
            PulseCard(Modifier.fillMaxWidth()) {
                Text("Размер контекста (память диалога)", color = p.ink, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                val ctxs = listOf(2048, 4096, 8192, 16384)
                Segmented(listOf("2K", "4K", "8K", "16K"), ctxs.indexOf(s.contextSize).coerceAtLeast(0)) {
                    set(s.copy(contextSize = ctxs[it]))
                }
                Spacer(Modifier.height(10.dp))
                val cores = Runtime.getRuntime().availableProcessors()
                SliderRow(
                    "Потоки генерации", if (s.threads == 0) "авто (${s.genThreads}) из $cores" else "${s.threads} из $cores",
                    s.threads.toFloat(), 0f..cores.toFloat(),
                ) { set(s.copy(threads = it.roundToInt().coerceIn(0, cores))) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Сжатый контекст (KV 8-бит)", color = p.ink, fontSize = 14.sp)
                        Text("В 2 раза меньше памяти на диалог, быстрее длинные чаты", color = p.muted, fontSize = 12.sp)
                    }
                    androidx.compose.material3.Switch(
                        checked = s.kvQ8, onCheckedChange = { set(s.copy(kvQ8 = it)) },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = p.onAccent, checkedTrackColor = p.accent,
                            uncheckedThumbColor = p.muted, uncheckedTrackColor = p.inset,
                        ),
                    )
                }
                Text("Больше контекста — больше ОЗУ. Изменения применяются после перезагрузки модели.",
                    color = p.muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                val path = s.lastModel
                PillButton("Перезагрузить модель", primary = true, enabled = ui.modelName != null && path != null && ui.phase == Phase.Idle) {
                    path?.let { vm.loadModel(com.dozy.pocketai.data.LocalModel(File(it))) }
                }
            }

            SectionHeading("Интернет (🌐 в чате)")
            PulseCard(Modifier.fillMaxWidth()) {
                SliderRow("Страниц читать", "${s.webResults}", s.webResults.toFloat(), 1f..5f) {
                    set(s.copy(webResults = it.roundToInt()))
                }
                SliderRow("Текста с каждой", "${s.webCharsPerPage} симв.", s.webCharsPerPage.toFloat(), 400f..3000f) {
                    set(s.copy(webCharsPerPage = (it / 100).roundToInt() * 100))
                }
                Text("Поиск идёт через DuckDuckGo (запасной — Википедия). Найденный текст подаётся модели, сама модель работает локально.",
                    color = p.muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SliderRow(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val p = LocalPulse.current
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = p.ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(value, color = p.muted, fontSize = 13.sp)
        }
        Slider(
            value = v.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.inset,
            ),
        )
    }
}
