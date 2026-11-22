package com.dozy.pocketai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dozy.pocketai.data.Attachment
import com.dozy.pocketai.data.GhEntry
import com.dozy.pocketai.data.GhRepo
import com.dozy.pocketai.data.GitHub

/** Bottom sheet: pick one of your GitHub repos, then the files the model should see. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoSheet(github: GitHub, current: Attachment?, onDone: (Attachment?) -> Unit, onLogin: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPulse.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var repo by remember { mutableStateOf(current?.repo) }
    var picked by remember { mutableStateOf(current?.files ?: emptySet()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = p.bar, scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 14.dp)) {
            if (!github.loggedIn) {
                Text("Репозитории", color = p.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Войди через GitHub, чтобы подключать свои репо к чату — модель будет видеть выбранные файлы.",
                    color = p.muted, fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                PillButton("Войти через GitHub", primary = true) { onLogin() }
                return@Column
            }
            val r = repo
            if (r == null) {
                RepoList(github, onPick = { repo = it; picked = if (it == current?.repo) picked else emptySet() })
            } else {
                FileBrowser(
                    github, r, picked,
                    onToggle = { path -> picked = if (path in picked) picked - path else picked + path },
                    onBack = { repo = null },
                    onDone = { onDone(Attachment(r, picked)) },
                    onDetach = { onDone(null) },
                )
            }
        }
    }
}

@Composable
private fun RepoList(github: GitHub, onPick: (GhRepo) -> Unit) {
    val p = LocalPulse.current
    var repos by remember { mutableStateOf<List<GhRepo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { github.repos() }.onSuccess { repos = it }.onFailure { error = it.message } }

    Text("Твои репозитории", color = p.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text("@${github.state.value.login}", color = p.muted, fontSize = 13.sp)
    Spacer(Modifier.height(10.dp))
    when {
        error != null -> Text(error!!, color = Red, fontSize = 14.sp)
        repos == null -> CircularProgressIndicator(color = p.ink, modifier = Modifier.padding(20.dp))
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(repos!!, key = { it.fullName }) { r ->
                ListRow(
                    badge = BadgeColors[(r.fullName.hashCode() and 0x7fffffff) % BadgeColors.size],
                    letter = r.fullName.substringAfter('/').first().uppercase(),
                    title = r.fullName.substringAfter('/'),
                    sub = r.description.ifEmpty { r.fullName.substringBefore('/') + " · " + r.branch },
                    note = r.updated,
                    onClick = { onPick(r) },
                ) {
                    if (r.private) Icon(Icons.Outlined.Lock, "private", tint = p.muted, modifier = Modifier.padding(start = 6.dp).size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun FileBrowser(
    github: GitHub, repo: GhRepo, picked: Set<String>,
    onToggle: (String) -> Unit, onBack: () -> Unit, onDone: () -> Unit, onDetach: () -> Unit,
) {
    val p = LocalPulse.current
    var all by remember(repo) { mutableStateOf<List<GhEntry>?>(null) }
    var error by remember(repo) { mutableStateOf<String?>(null) }
    var dir by remember(repo) { mutableStateOf("") }
    LaunchedEffect(repo) { runCatching { github.tree(repo) }.onSuccess { all = it }.onFailure { error = it.message } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        PillIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад", size = 38.dp) {
            if (dir.isEmpty()) onBack() else dir = dir.substringBeforeLast('/', "")
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(repo.fullName.substringAfter('/'), color = p.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("/" + dir, color = p.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PillButton(if (picked.isEmpty()) "Готово" else "Подключить (${picked.size})", primary = true, onClick = onDone)
        PillButton("Отключить", onClick = onDetach)
    }
    Spacer(Modifier.height(10.dp))
    when {
        error != null -> Text(error!!, color = Red, fontSize = 14.sp)
        all == null -> CircularProgressIndicator(color = p.ink, modifier = Modifier.padding(20.dp))
        else -> {
            val here = all!!.filter { it.parent == dir }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(here, key = { it.path }) { e ->
                    val inDir = e.isDir && picked.any { it.startsWith(e.path + "/") }
                    Row(
                        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (e.path in picked || inDir) p.selected else p.card)
                            .clickable { if (e.isDir) dir = e.path else onToggle(e.path) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (e.isDir) Icons.Outlined.Folder else Icons.Outlined.Description, null, tint = p.ink, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(e.name, color = p.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (!e.isDir) {
                            Text(if (e.size >= 1024) "${e.size / 1024} КБ" else "${e.size} Б", color = p.muted, fontSize = 11.sp)
                            Spacer(Modifier.width(8.dp))
                            Icon(if (e.path in picked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null, tint = p.ink, modifier = Modifier.size(20.dp))
                        } else if (inDir) {
                            Text("${picked.count { it.startsWith(e.path + "/") }}", color = p.muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
