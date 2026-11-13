package com.yomu.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yomu.reader.data.Downloads
import com.yomu.reader.data.Library
import com.yomu.reader.data.downloadId
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(onOpenManga: (String) -> Unit, onRead: (key: String, chapterId: String) -> Unit) {
    val updates by Library.updates.collectAsState()
    val progress by Library.progress.collectAsState()
    val downloaded by Downloads.downloaded.collectAsState()
    val queue by Downloads.queue.collectAsState()
    val refreshing by Library.refreshing.collectAsState()
    val entries by Library.entries.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val grouped = remember(updates) { updates.groupBy { dayLabel(it.foundAt) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nouveautés") },
                actions = {
                    if (updates.isNotEmpty()) {
                        IconButton(onClick = { Library.clearUpdates() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Vider la liste")
                        }
                    }
                    if (refreshing) {
                        Box(Modifier.padding(12.dp)) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        IconButton(onClick = {
                            scope.launch {
                                val r = Library.refreshAll()
                                if (r.newChapters == 0) snackbar.showSnackbar("Pas de nouveaux chapitres")
                            }
                        }) { Icon(Icons.Default.Refresh, contentDescription = "Chercher les nouveaux chapitres") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (updates.isEmpty()) {
                CenterMessage(
                    if (entries.isEmpty()) "Ajoute des séries à ta bibliothèque :\nleurs nouveaux chapitres apparaîtront ici."
                    else "Aucun nouveau chapitre pour l'instant.\nTire ⟳ pour vérifier."
                )
            } else {
                LazyColumn {
                    grouped.forEach { (day, items) ->
                        item(key = "h_$day") {
                            Text(
                                day,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(items, key = { it.key + "|" + it.chapterId }) { u ->
                            val manga = Library.mangaFor(u.key)
                            val read = u.chapterId in (progress[u.key]?.read ?: emptySet())
                            val faded = Color.Unspecified.takeIf { !read } ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            ListItem(
                                leadingContent = {
                                    Box(Modifier.clickable { onOpenManga(u.key) }) { CoverThumb(manga?.cover) }
                                },
                                headlineContent = {
                                    Text(manga?.title ?: "?", maxLines = 1, overflow = TextOverflow.Ellipsis, color = faded)
                                },
                                supportingContent = {
                                    Text(
                                        "${u.chapterTitle} • ${timeLabel(u.foundAt)}",
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = faded,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                trailingContent = {
                                    val chapter = Library.chaptersFor(u.key)?.firstOrNull { it.id == u.chapterId }
                                    if (manga != null && chapter != null) {
                                        val id = downloadId(u.key, u.chapterId)
                                        DownloadIndicator(
                                            downloaded = id in downloaded,
                                            task = queue.firstOrNull { it.id == id },
                                            onDownload = { Downloads.enqueue(manga, listOf(chapter)) },
                                            onCancel = { Downloads.cancel(id) },
                                            onDelete = { Downloads.delete(u.key, listOf(u.chapterId)) },
                                            onRetry = { Downloads.retry(id) },
                                        )
                                    }
                                },
                                modifier = Modifier.clickable { onRead(u.key, u.chapterId) },
                            )
                        }
                    }
                }
            }
        }
    }
}
