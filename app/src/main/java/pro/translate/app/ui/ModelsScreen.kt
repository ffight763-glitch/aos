package pro.translate.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pro.translate.app.AppViewModel
import pro.translate.app.Languages

@Composable
fun ModelsScreen(vm: AppViewModel, update: UpdateState, onCheckUpdate: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshModels() }
    val sorted = Languages.all.sortedBy { it.code !in vm.downloaded }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item { UpdateCard(update, onCheckUpdate) }
        item { VoiceSettings(vm) }
        item {
            Text("Офлайн-языки", style = MaterialTheme.typography.titleLarge)
            Text("Скачайте языки заранее по Wi‑Fi — потом перевод работает без интернета. Одна модель ≈ 30 МБ.",
                Modifier.padding(top = 4.dp, bottom = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
        }
        items(sorted, key = { it.code }) { l ->
            val has = l.code in vm.downloaded
            val loading = l.code in vm.downloading
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l.name, style = MaterialTheme.typography.bodyLarge)
                    Text(if (has) "Скачан" else "Не скачан", style = MaterialTheme.typography.bodySmall,
                        color = if (has) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    loading -> CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
                    has && l.code != "en" -> IconButton({ vm.deleteModel(l.code) }) { Icon(Icons.Default.Delete, "Удалить") }
                    !has -> IconButton({ vm.downloadModel(l.code) }) { Icon(Icons.Default.CloudDownload, "Скачать") }
                }
            }
        }
    }
}

@Composable
private fun VoiceSettings(vm: AppViewModel) {
    Card(Modifier.fillMaxWidth().padding(bottom = 16.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp)) {
            Text("Голос", style = MaterialTheme.typography.titleMedium)
            Text("Сколько ждать тишины, прежде чем переводить фразу", Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1500L to "1,5 с", 2500L to "2,5 с", 4000L to "4 с", 6000L to "6 с").forEach { (ms, label) ->
                    FilterChip(selected = vm.vWait == ms, onClick = { vm.setWait(ms) }, label = { Text(label) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Усиливать тихую речь")
                    Text("Ловит даже тихий голос (Android 13+)", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = vm.vBoost, onCheckedChange = { vm.toggleBoost() })
            }
        }
    }
}
