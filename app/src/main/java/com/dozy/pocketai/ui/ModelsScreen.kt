package com.dozy.pocketai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dozy.pocketai.data.Catalog
import com.dozy.pocketai.data.DownloadState
import java.io.File

@Composable
fun ModelsScreen(vm: ChatViewModel) {
    val p = LocalPulse.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val local by vm.models.local.collectAsStateWithLifecycle()
    val downloads by vm.models.downloads.collectAsStateWithLifecycle()
    val dlError by vm.models.errors.collectAsStateWithLifecycle()
    val loadedPath = (ui.modelName)?.let { name -> local.firstOrNull { it.name == name }?.file?.absolutePath }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importModel) }
    var customUrl by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        PulseHeader("Модели", subtitle = ui.modelName?.let { "Загружена: $it" } ?: "Ничего не загружено") {
            PillIcon(Icons.Outlined.FileOpen, "Импорт .gguf", size = 38.dp) { picker.launch(arrayOf("*/*")) }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (ui.status ?: dlError)?.let { s ->
                item {
                    PulseCard(Modifier.fillMaxWidth(), onClick = { vm.models.clearError() }) { Text(s, color = p.ink, fontSize = 14.sp) }
                }
            }

            item { SectionHeading("На телефоне") }
            if (local.isEmpty()) item {
                PulseCard(Modifier.fillMaxWidth()) {
                    Text("Пока нет моделей. Скачай одну из списка ниже или импортируй свой .gguf файл.", color = p.muted, fontSize = 14.sp)
                }
            }
            items(local, key = { it.file.absolutePath }) { m ->
                val isLoaded = m.file.absolutePath == loadedPath
                ListRow(
                    badge = BadgeColors[(m.name.hashCode() and 0x7fffffff) % BadgeColors.size],
                    letter = m.name.first().uppercase(),
                    title = m.name,
                    sub = if (isLoaded) "● работает" else "${m.sizeMb} МБ",
                    selected = isLoaded,
                    onClick = { if (!isLoaded) vm.loadModel(m) },
                ) {
                    if (!isLoaded) RowIcon(Icons.Outlined.PlayArrow, "Загрузить") { vm.loadModel(m) }
                    RowIcon(Icons.Outlined.DeleteOutline, "Удалить") { vm.deleteModel(m) }
                }
            }
            if (loadedPath != null) item {
                PillButton("Выгрузить из памяти", modifier = Modifier.padding(top = 4.dp)) { vm.unloadModel() }
            }

            item { SectionHeading("Скачать с HuggingFace") }
            items(Catalog.models, key = { it.id }) { c ->
                val have = local.any { it.file.name == c.fileName }
                val dl = downloads[c.fileName]
                CatalogCard(c.title, c.sizeLabel, c.note, have, dl,
                    onDownload = { vm.models.download(c.url) },
                    onCancel = { vm.models.cancel(c.fileName) })
            }

            // downloads started from a custom URL
            val custom = downloads.values.filter { d -> Catalog.models.none { it.fileName == d.fileName } }
            items(custom, key = { it.fileName }) { d ->
                CatalogCard(d.fileName, "", "Своя ссылка", false, d, onDownload = {}, onCancel = { vm.models.cancel(d.fileName) })
            }

            item { SectionHeading("Своя ссылка на .gguf") }
            item {
                PulseCard(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.inset)
                            .border(1.dp, p.outline, RoundedCornerShape(12.dp)).padding(12.dp),
                    ) {
                        if (customUrl.isEmpty()) Text("https://huggingface.co/…/model.gguf", color = p.muted, fontSize = 14.sp)
                        BasicTextField(
                            customUrl, { customUrl = it.trim() }, singleLine = true,
                            textStyle = TextStyle(color = p.ink, fontSize = 14.sp), cursorBrush = SolidColor(p.ink),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    PillButton("Скачать", primary = true, icon = Icons.Outlined.Download,
                        enabled = customUrl.startsWith("http")) {
                        vm.models.download(customUrl.replace("/blob/", "/resolve/"))
                        customUrl = ""
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Подсказка: на телефон с 4–6 ГБ ОЗУ подходят модели до 3B в квантовании Q4_K_M.",
                        color = p.muted, fontSize = 12.sp)
                }
            }
            item {
                Text("Папка моделей: " + File(vm.models.dir.absolutePath).path, color = p.muted, fontSize = 11.sp,
                    modifier = Modifier.padding(4.dp))
            }
        }
    }
}

@Composable
private fun CatalogCard(
    title: String, size: String, note: String, have: Boolean, dl: DownloadState?,
    onDownload: () -> Unit, onCancel: () -> Unit,
) {
    val p = LocalPulse.current
    PulseCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(listOf(size, note).filter { it.isNotEmpty() }.joinToString(" · "), color = p.muted, fontSize = 12.sp)
            }
            when {
                have -> Text("✓ скачана", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                dl != null -> RowIcon(Icons.Outlined.Close, "Отмена", onCancel)
                else -> PillIcon(Icons.Outlined.Download, "Скачать", primary = true, size = 38.dp, onClick = onDownload)
            }
        }
        if (dl != null) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { dl.progress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                color = p.accent, trackColor = p.inset,
            )
            Text(
                if (dl.total > 0) "%d / %d МБ".format(dl.bytes shr 20, dl.total shr 20) else "Подключение…",
                color = p.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun RowIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val p = LocalPulse.current
    Icon(icon, label, tint = p.ink, modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick).padding(9.dp))
}
