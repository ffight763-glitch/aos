package pro.translate.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import pro.translate.app.ui.*

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            TPTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                            listOf(
                                "Перевод" to Icons.Default.Translate,
                                "Камера" to Icons.Default.PhotoCamera,
                                "История" to Icons.Default.History,
                                "Языки" to Icons.Default.Download,
                            ).forEachIndexed { i, (label, icon) ->
                                NavigationBarItem(tab == i, { tab = i }, { Icon(icon, label) }, label = { Text(label) })
                            }
                        }
                    }
                ) { pad ->
                    Box(Modifier.fillMaxSize().padding(pad)) {
                        when (tab) {
                            0 -> TranslateScreen(vm)
                            1 -> CameraScreen(vm) { tab = 0 }
                            2 -> HistoryScreen(vm) { tab = 0 }
                            else -> ModelsScreen(vm)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Text shared from other apps or chosen via the "Translate" text-selection menu. */
    private fun handleIntent(i: Intent?) {
        val text = when (i?.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        if (!text.isNullOrBlank()) vm.updateInput(text)
    }
}
