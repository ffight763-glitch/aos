package pro.translate.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pro.translate.app.AUTO
import pro.translate.app.Lang
import pro.translate.app.Languages

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePicker(
    selected: String,
    allowAuto: Boolean,
    downloaded: Set<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var q by remember { mutableStateOf("") }
    val list = remember(q) {
        val base = (if (allowAuto) listOf(Lang(AUTO, Languages.displayName(AUTO))) else emptyList()) + Languages.all
        if (q.isBlank()) base else base.filter { it.name.contains(q, true) || it.code.startsWith(q, true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(true)) {
        OutlinedTextField(
            value = q, onValueChange = { q = it },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Поиск языка") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxHeight(0.8f)) {
            items(list, key = { it.code }) { l ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(l.code) }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(l.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (l.code in downloaded) Icon(
                        Icons.Default.DownloadDone, "офлайн", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    if (l.code == selected) {
                        Spacer(Modifier.width(12.dp)); Icon(Icons.Default.Check, null, tint = Accent)
                    }
                }
            }
        }
    }
}
