package com.dozy.pocketai.ui

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

data class PreviewRequest(val title: String, val html: String)

/** Opens a live preview of an HTML document (provided by the app shell). */
val LocalOpenPreview = staticCompositionLocalOf<((PreviewRequest) -> Unit)?> { null }

/** Full-screen live preview — like artifacts in Claude / Build in AI Studio. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewDialog(req: PreviewRequest, onClose: () -> Unit) {
    val p = LocalPulse.current
    var reload by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(p.bg)) {
            PulseHeader(req.title, subtitle = "Живое превью", leading = {
                PillIcon(Icons.Outlined.Close, "Закрыть", size = 38.dp, onClick = onClose)
            }) {
                PillIcon(Icons.Outlined.Refresh, "Перезапустить", size = 38.dp) { reload++ }
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.White).navigationBarsPadding()) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            webViewClient = WebViewClient()
                            webChromeClient = WebChromeClient()
                        }
                    },
                    update = { wv ->
                        if (wv.tag != reload) {
                            wv.tag = reload
                            wv.loadDataWithBaseURL("https://pocketai.local/", req.html, "text/html", "utf-8", null)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
