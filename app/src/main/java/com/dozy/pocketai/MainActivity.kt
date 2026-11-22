package com.dozy.pocketai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dozy.pocketai.ui.ChatScreen
import com.dozy.pocketai.ui.ChatViewModel
import com.dozy.pocketai.ui.DockItem
import com.dozy.pocketai.ui.LocalPulse
import com.dozy.pocketai.ui.LocalRunInTerminal
import com.dozy.pocketai.ui.TerminalScreen
import com.dozy.pocketai.ui.ModelsScreen
import com.dozy.pocketai.ui.PocketTheme
import com.dozy.pocketai.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            PocketTheme(settings.theme) { Shell(vm) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Shell(vm: ChatViewModel) {
    val p = LocalPulse.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = tab != 0) { tab = 0 }
    Column(Modifier.fillMaxSize().background(p.bg).imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CompositionLocalProvider(LocalRunInTerminal provides { cmd -> vm.terminal.run(cmd); tab = 2 }) {
                when (tab) {
                    1 -> ModelsScreen(vm)
                    2 -> TerminalScreen(vm.terminal)
                    3 -> SettingsScreen(vm)
                    else -> ChatScreen(vm, openModels = { tab = 1 }, openSettings = { tab = 3 })
                }
            }
        }
        // bottom dock (Pulse style), hidden while the keyboard is open
        if (!WindowInsets.isImeVisible) Row(
            Modifier
                .fillMaxWidth()
                .background(p.bar)
                .drawBehind { drawLine(p.outline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            DockItem(Icons.Outlined.ChatBubbleOutline, "Чат", tab == 0, Modifier.weight(1f)) { tab = 0 }
            DockItem(Icons.Outlined.Memory, "Модели", tab == 1, Modifier.weight(1f)) { tab = 1 }
            DockItem(Icons.Outlined.Terminal, "Терминал", tab == 2, Modifier.weight(1f)) { tab = 2 }
            DockItem(Icons.Outlined.Tune, "Настройки", tab == 3, Modifier.weight(1f)) { tab = 3 }
        }
    }
}
