package com.dozy.pocketai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dozy.pocketai.data.DeviceCode
import com.dozy.pocketai.data.GitHub
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Settings card: sign in with GitHub (device code) or a personal token. */
@Composable
fun GitHubSection(github: GitHub) {
    val p = LocalPulse.current
    val st by github.state.collectAsState()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var code by remember { mutableStateOf<DeviceCode?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tokenMode by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf(github.clientId) }
    var job by remember { mutableStateOf<Job?>(null) }
    var secret by remember { mutableStateOf(github.clientSecret) }
    var advanced by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val loggingIn by github.loggingIn.collectAsState()
    val loginError by github.loginError.collectAsState()

    PulseCard(Modifier.fillMaxWidth()) {
        if (st.login != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(BadgeColors[2]), contentAlignment = Alignment.Center) {
                    Text(st.login!!.first().uppercase(), color = androidx.compose.ui.graphics.Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("@${st.login}", color = p.ink, fontSize = 16.sp)
                    Text("Подключено · репо доступны в чате через 📁", color = p.muted, fontSize = 12.sp)
                }
                PillButton("Выйти") { github.logout() }
            }
            return@PulseCard
        }

        val c = code
        when {
            c != null -> {
                Text("Открой github.com/login/device и введи код:", color = p.ink, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Text(c.userCode, color = p.ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Открыть GitHub", primary = true, icon = Icons.Outlined.OpenInBrowser) {
                        clipboard.setText(AnnotatedString(c.userCode))
                        runCatching { uri.openUri(c.verifyUrl) }
                    }
                    PillButton("Код", icon = Icons.Outlined.ContentCopy) { clipboard.setText(AnnotatedString(c.userCode)) }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = p.ink, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Жду подтверждения…", color = p.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    PillButton("Отмена") { job?.cancel(); code = null }
                }
            }
            tokenMode -> {
                Text("Personal access token (classic, право repo) — github.com/settings/tokens", color = p.muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Field(token, { token = it }, "ghp_…", password = true)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Войти", primary = true, enabled = token.isNotBlank() && !busy) {
                        busy = true; error = null
                        scope.launch {
                            runCatching { github.loginWithToken(token) }.onFailure { error = it.message }
                            busy = false
                        }
                    }
                    PillButton("Назад") { tokenMode = false }
                }
            }
            loggingIn -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = p.ink, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Вхожу в GitHub…", color = p.ink, fontSize = 14.sp)
            }
            else -> {
                Text("Войди, чтобы подключать свои репозитории к чату — как папки. Откроется Chrome, нажми Authorize — и ты снова в приложении.",
                    color = p.muted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Войти через GitHub", primary = true, enabled = !busy) {
                        error = null
                        if (github.canBrowserLogin) {
                            runCatching { github.startBrowserLogin(context) }.onFailure { error = it.message }
                            return@PillButton
                        }
                        busy = true
                        job = scope.launch {
                            runCatching {
                                val dc = github.startDeviceFlow()
                                code = dc
                                clipboard.setText(AnnotatedString(dc.userCode))
                                runCatching { uri.openUri(dc.verifyUrl) }
                                github.finishDeviceFlow(dc)
                            }.onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message }
                            code = null
                            busy = false
                        }
                    }
                    PillButton("Токен") { tokenMode = true }
                }
                Spacer(Modifier.height(8.dp))
                Text(if (advanced) "▾ OAuth-приложение" else "▸ OAuth-приложение", color = p.muted, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { advanced = !advanced }.padding(vertical = 4.dp))
                if (advanced) {
                    Text("github.com/settings/developers → OAuth App, Callback URL: pocketai://oauth", color = p.muted, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    Field(clientId, { clientId = it; github.clientId = it }, "Client ID")
                    Spacer(Modifier.height(6.dp))
                    Field(secret, { secret = it; github.clientSecret = it }, "Client secret", password = true)
                }
            }
        }
        (error ?: loginError)?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Red, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, password: Boolean = false) {
    val p = LocalPulse.current
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.inset)
            .border(1.dp, p.outline, RoundedCornerShape(12.dp)).padding(12.dp),
    ) {
        if (value.isEmpty()) Text(hint, color = p.muted, fontSize = 14.sp)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = TextStyle(color = p.ink, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(p.ink),
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
