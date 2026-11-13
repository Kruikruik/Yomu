package com.yomu.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yomu.reader.data.Library
import com.yomu.reader.data.Progress
import com.yomu.reader.model.Manga

private data class HistoryRow(val key: String, val manga: Manga, val progress: Progress)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onOpenManga: (String) -> Unit, onRead: (key: String, chapterId: String) -> Unit) {
    val progress by Library.progress.collectAsState()
    val entries by Library.entries.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    val rows = remember(progress, entries) {
        progress.entries
            .filter { it.value.lastReadAt > 0 && it.value.lastChapterId != null }
            .sortedByDescending { it.value.lastReadAt }
            .mapNotNull { (key, p) -> Library.mangaFor(key)?.let { HistoryRow(key, it, p) } }
    }
    val grouped = remember(rows) { rows.groupBy { dayLabel(it.progress.lastReadAt) } }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Effacer l'historique ?") },
            text = { Text("Ta progression (chapitres lus) est conservée.") },
            confirmButton = {
                TextButton(onClick = {
                    Library.clearHistory()
                    confirmClear = false
                }) { Text("Effacer") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historique") },
                actions = {
                    if (rows.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Effacer l'historique")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (rows.isEmpty()) {
                CenterMessage("Rien lu pour l'instant.")
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
                        items(items, key = { it.key }) { row ->
                            val chapters = Library.chaptersFor(row.key)
                            val last = chapters?.firstOrNull { it.id == row.progress.lastChapterId }
                            val next = Library.resumeChapter(row.key, chapters)
                            val target = next?.id ?: row.progress.lastChapterId.orEmpty()
                            val finished = row.progress.lastChapterId in row.progress.read
                            val detail = buildString {
                                append(last?.title ?: "Chapitre")
                                if (!finished && row.progress.lastPage > 0) append(" • page ${row.progress.lastPage + 1}")
                                append(" • ${timeLabel(row.progress.lastReadAt)}")
                            }
                            ListItem(
                                leadingContent = {
                                    Box(Modifier.clickable { onOpenManga(row.key) }) { CoverThumb(row.manga.cover) }
                                },
                                headlineContent = {
                                    Text(row.manga.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Text(
                                        detail,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                trailingContent = {
                                    IconButton(onClick = { Library.removeFromHistory(row.key) }) {
                                        Icon(Icons.Default.Close, contentDescription = "Retirer de l'historique")
                                    }
                                },
                                modifier = Modifier.clickable { if (target.isNotEmpty()) onRead(row.key, target) },
                            )
                        }
                    }
                }
            }
        }
    }
}
