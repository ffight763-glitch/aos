package pro.translate.app.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import pro.translate.app.AppViewModel
import pro.translate.app.Languages
import pro.translate.app.loadBitmap
import java.io.File

@Composable
fun CameraScreen(vm: AppViewModel, onOpenTranslator: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var picker by remember { mutableStateOf(false) }

    fun handle(uri: Uri) {
        val bmp = loadBitmap(ctx, uri)
        if (bmp == null) Toast.makeText(ctx, "Не удалось открыть изображение", Toast.LENGTH_SHORT).show()
        else vm.recognize(bmp)
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) pendingUri?.let(::handle)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(::handle)
    }

    fun shoot() {
        val dir = File(ctx.cacheDir, "photos").apply { mkdirs() }
        val f = File(dir, "shot.jpg")
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        pendingUri = uri
        try { camera.launch(uri) } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, "Нет приложения камеры", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Перевести на:", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = { picker = true }, shape = RoundedCornerShape(14.dp)) { Text(Languages.displayName(vm.to)) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = ::shoot, Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text("Снять")
            }
            FilledTonalButton(
                onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(16.dp)
            ) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(8.dp)); Text("Галерея") }
        }

        vm.photo?.let {
            Spacer(Modifier.height(16.dp))
            Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Fit)
        } ?: run {
            Spacer(Modifier.height(32.dp))
            Text("Сфотографируйте вывеску, меню или документ — текст распознается и переведётся прямо на телефоне.\n\n" +
                    "Распознавание работает для латиницы (английский, немецкий, польский, испанский и т.д.).",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (vm.photoBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
        vm.photoStatus?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }

        if (vm.photoTranslation.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)) {
                SelectionContainer { Text(vm.photoTranslation, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.titleMedium) }
                Row(Modifier.padding(horizontal = 8.dp)) {
                    IconButton(onClick = { vm.speaker.speak(vm.photoTranslation, Languages.locale(vm.to)) }) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, "Озвучить")
                    }
                    IconButton(onClick = { clipboard.setText(AnnotatedString(vm.photoTranslation))
                        Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show() }) { Icon(Icons.Default.ContentCopy, "Копировать") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.sendPhotoToTranslator(); onOpenTranslator() }) { Text("Редактировать") }
                }
            }
        }
        if (vm.photoText.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text("Распознанный текст", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer { Text(vm.photoText, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium) }
        }
    }

    if (picker) LanguagePicker(vm.to, false, vm.downloaded, { vm.setToLang(it); picker = false; vm.photo?.let(vm::recognize) }, { picker = false })
}
