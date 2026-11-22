package com.dozy.pocketai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Quick model switcher opened from the chat header. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(vm: ChatViewModel, onMore: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPulse.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val local by vm.models.local.collectAsStateWithLifecycle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.bar, scrimColor = Color.Black.copy(alpha = 0.55f),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            Text("Модель", color = p.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Переключение не стирает чат", color = p.muted, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(local, key = { it.file.absolutePath }) { m ->
                    val active = m.name == ui.modelName
                    ListRow(
                        badge = BadgeColors[(m.name.hashCode() and 0x7fffffff) % BadgeColors.size],
                        letter = m.name.first().uppercase(),
                        title = m.name,
                        sub = if (active) "● работает" else "${m.sizeMb} МБ",
                        selected = active,
                        onClick = {
                            if (!active) vm.loadModel(m)
                            onDismiss()
                        },
                    )
                }
            }
            PillButton("Скачать ещё модели", icon = Icons.Outlined.Download) { onDismiss(); onMore() }
            Spacer(Modifier.height(24.dp))
        }
    }
}
