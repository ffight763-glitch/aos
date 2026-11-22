package com.dozy.pocketai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dozy.pocketai.data.UpdateState
import com.dozy.pocketai.data.Updater

/** Banner shown in the chat when a newer build is on GitHub. */
@Composable
fun UpdateBanner(updater: Updater) {
    val st by updater.state.collectAsStateWithLifecycle()
    when (val s = st) {
        is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Ready ->
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { UpdateCard(updater, s) }
        else -> {}
    }
}

/** Updates section of the settings screen. */
@Composable
fun UpdateSection(updater: Updater) {
    val p = LocalPulse.current
    val st by updater.state.collectAsStateWithLifecycle()
    PulseCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Версия ${updater.currentVersion}", color = p.ink, fontSize = 15.sp)
                Text(
                    when (val s = st) {
                        UpdateState.Checking -> "Проверяю…"
                        UpdateState.UpToDate -> "Последняя версия ✓"
                        is UpdateState.Failed -> s.message
                        is UpdateState.Available -> "Доступна ${s.info.versionName}"
                        is UpdateState.Downloading -> "Скачиваю ${s.info.versionName}…"
                        is UpdateState.Ready -> "Готово к установке"
                        UpdateState.Idle -> "Обновления по воздуху с GitHub"
                    },
                    color = p.muted, fontSize = 12.sp,
                )
            }
            PillButton("Проверить", enabled = st !is UpdateState.Checking && st !is UpdateState.Downloading) { updater.check() }
        }
    }
    when (val s = st) {
        is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Ready -> UpdateCard(updater, s)
        else -> {}
    }
}

@Composable
private fun UpdateCard(updater: Updater, s: UpdateState) {
    val p = LocalPulse.current
    PulseCard(Modifier.fillMaxWidth(), selected = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.SystemUpdate, null, tint = p.ink)
            Spacer(Modifier.padding(start = 10.dp))
            val info = when (s) {
                is UpdateState.Available -> s.info
                is UpdateState.Downloading -> s.info
                is UpdateState.Ready -> s.info
                else -> return@Row
            }
            Column(Modifier.weight(1f)) {
                Text("Обновление ${info.versionName}", color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("${info.sizeMb} МБ", color = p.muted, fontSize = 12.sp)
            }
        }
        val notes = when (s) {
            is UpdateState.Available -> s.info.notes
            else -> ""
        }.lines().filter { it.isNotBlank() && !it.startsWith("Co-Authored") && !it.startsWith("Claude-Session") }
            .take(6).joinToString("\n")
        if (notes.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(notes, color = p.muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.height(10.dp))
        when (s) {
            is UpdateState.Available -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Обновить", primary = true) { updater.download(s.info) }
                PillButton("Позже") { updater.dismiss() }
            }
            is UpdateState.Downloading -> {
                LinearProgressIndicator(
                    progress = { s.progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                    color = p.accent, trackColor = p.inset,
                )
                Text("${(s.progress * 100).toInt()}%", color = p.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
            is UpdateState.Ready -> PillButton("Установить", primary = true) { updater.install(s.file) }
            else -> {}
        }
    }
}
