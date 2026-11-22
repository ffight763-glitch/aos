package pro.translate.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pro.translate.app.AppViewModel
import pro.translate.app.Entry
import pro.translate.app.Languages

@Composable
fun HistoryScreen(vm: AppViewModel, onOpen: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val list = vm.history.items.filter {
        (tab == 0 || it.fav) && (q.isBlank() || it.input.contains(q, true) || it.output.contains(q, true))
    }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            Tab(tab == 0, { tab = 0 }, text = { Text("История") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Избранное") })
        }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Поиск") },
                shape = RoundedCornerShape(14.dp))
            if (tab == 0) IconButton(onClick = { confirm = true }) { Icon(Icons.Default.DeleteSweep, "Очистить") }
        }
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(if (tab == 0) "История пуста" else "Нажмите ☆ у перевода, чтобы сохранить его",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            items(list, key = { it.id }) { e ->
                HistoryRow(e, onClick = { vm.load(e); onOpen() }, onFav = { vm.history.toggleFav(e.id) },
                    onDelete = { vm.history.remove(e.id) })
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Очистить историю?") },
        text = { Text("Избранное останется.") },
        confirmButton = { TextButton({ vm.history.clearHistory(); confirm = false }) { Text("Очистить") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Отмена") } }
    )
}

@Composable
fun HistoryRow(e: Entry, onClick: () -> Unit, onFav: () -> Unit, onDelete: (() -> Unit)? = null) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${Languages.displayName(e.from)} → ${Languages.displayName(e.to)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                Text(e.input, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text(e.output, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onFav) {
                Icon(if (e.fav) Icons.Default.Star else Icons.Default.StarBorder, "Избранное",
                    tint = if (e.fav) Color(0xFFFFC94D) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onDelete != null) IconButton(onClick = onDelete) {
                Icon(Icons.Default.Close, "Удалить", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
