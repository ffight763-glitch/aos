package pro.translate.app.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pro.translate.app.Release
import pro.translate.app.Updater
import java.io.File

/** Shared OTA state for the whole app. */
class UpdateState {
    var release by mutableStateOf<Release?>(null)
    var checking by mutableStateOf(false)
    var progress by mutableStateOf<Float?>(null)
    var showDialog by mutableStateOf(false)
    var apk: File? = null
}

private const val PREFS = "ota"

/** Checks on launch at most every 6 hours; [manual] always checks and reports the result. */
suspend fun checkForUpdate(ctx: Context, s: UpdateState, manual: Boolean) {
    val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val now = System.currentTimeMillis()
    if (!manual && now - p.getLong("last", 0) < 6 * 3600_000L) return
    s.checking = true
    try {
        val r = Updater.check()
        p.edit().putLong("last", now).apply()
        s.release = r
        if (r != null && (manual || p.getString("skipped", null) != r.version)) s.showDialog = true
        else if (manual) Toast.makeText(ctx, "У вас последняя версия ${Updater.current}", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        if (manual) Toast.makeText(ctx, "Не удалось проверить обновления: ${e.message}", Toast.LENGTH_LONG).show()
    } finally { s.checking = false }
}

@Composable
fun UpdateDialog(s: UpdateState) {
    val r = s.release ?: return
    if (!s.showDialog) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (s.progress == null) s.showDialog = false },
        icon = { Icon(Icons.Default.SystemUpdate, null) },
        title = { Text("Обновление ${r.version}") },
        text = {
            Column {
                Text("Сейчас установлена ${Updater.current}" + if (r.size > 0) " · ${r.size / 1_048_576} МБ" else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                if (r.notes.isNotBlank()) Text(r.notes, Modifier.padding(top = 8.dp).heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()))
                s.progress?.let {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth())
                    Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = s.progress == null, onClick = {
                val ready = s.apk
                if (ready != null && ready.exists()) { install(ctx, ready); return@TextButton }
                scope.launch {
                    s.progress = 0f
                    try {
                        val f = Updater.download(ctx, r) { s.progress = it }
                        s.apk = f
                        install(ctx, f)
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Ошибка загрузки: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally { s.progress = null }
                }
            }) { Text(if (s.apk?.exists() == true) "Установить" else "Обновить") }
        },
        dismissButton = {
            TextButton(enabled = s.progress == null, onClick = {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("skipped", r.version).apply()
                s.showDialog = false
            }) { Text("Позже") }
        }
    )
}

private fun install(ctx: Context, f: File) {
    if (!Updater.canInstall(ctx)) {
        Toast.makeText(ctx, "Разрешите translatePro устанавливать приложения и нажмите «Установить» ещё раз", Toast.LENGTH_LONG).show()
        Updater.openInstallPermission(ctx)
    } else Updater.install(ctx, f)
}

@Composable
fun UpdateCard(s: UpdateState, onCheck: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 16.dp), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("translatePro ${Updater.current}", style = MaterialTheme.typography.titleMedium)
                Text(s.release?.let { "Доступна версия ${it.version}" } ?: "Обновления приходят автоматически",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.release != null) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (s.checking) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else FilledTonalButton(onClick = { if (s.release != null) s.showDialog = true else onCheck() }) {
                Text(if (s.release != null) "Обновить" else "Проверить")
            }
        }
    }
}
