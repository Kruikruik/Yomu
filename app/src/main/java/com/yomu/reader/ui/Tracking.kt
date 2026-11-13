package com.yomu.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yomu.reader.data.Library
import com.yomu.reader.data.LibraryEntry
import com.yomu.reader.data.ReadingStatus
import com.yomu.reader.model.Manga
import com.yomu.reader.source.SourceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Suivi façon Kenmei : statut de lecture, note sur 10 et notes perso. */
@Composable
fun TrackingRow(entry: LibraryEntry) {
    val key = entry.manga.key
    var statusMenu by remember { mutableStateOf(false) }
    var notesDialog by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                AssistChip(onClick = { statusMenu = true }, label = { Text(entry.status.label) })
                DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                    ReadingStatus.entries.forEach { st ->
                        DropdownMenuItem(
                            text = { Text(st.label) },
                            onClick = {
                                Library.setStatus(key, st)
                                statusMenu = false
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            AssistChip(
                onClick = { notesDialog = true },
                label = { Text(if (entry.notes.isNullOrBlank()) "Ajouter une note perso" else "Mes notes") },
            )
        }
        // Note sur 10 : 5 étoiles, chaque étoile vaut 2 points ; retoucher la même étoile l'enlève.
        Row(verticalAlignment = Alignment.CenterVertically) {
            (1..5).forEach { i ->
                val filled = entry.rating >= i * 2
                Icon(
                    Icons.Default.Star,
                    contentDescription = "$i étoile(s)",
                    tint = if (filled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier
                        .size(32.dp)
                        .clickable { Library.setRating(key, if (entry.rating == i * 2) 0 else i * 2) }
                        .padding(2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (entry.rating > 0) "${entry.rating}/10" else "Pas de note",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        entry.notes?.takeIf { it.isNotBlank() }?.let {
            Text(
                "📝 $it",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { notesDialog = true },
            )
        }
    }

    if (notesDialog) {
        var text by remember { mutableStateOf(entry.notes.orEmpty()) }
        AlertDialog(
            onDismissRequest = { notesDialog = false },
            title = { Text("Mes notes") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    placeholder = { Text("Où j'en suis, ce que j'en pense…") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Library.setNotes(key, text)
                    notesDialog = false
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { notesDialog = false }) { Text("Annuler") } },
        )
    }
}

/**
 * Changement de source (comme le « smart grouping » de Kenmei) :
 * cherche la même série sur les autres sources et y transfère statut, note et chapitres lus.
 */
@Composable
fun MigrateDialog(manga: Manga, onDismiss: () -> Unit, onMigrated: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<List<Manga>?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(manga.key) {
        val found = mutableListOf<Manga>()
        SourceManager.all.filter { it.id != manga.sourceId && it.id != "local" }.forEach { src ->
            try {
                found += src.search(manga.title, 1).mangas.take(5)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
            }
        }
        results = found
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Changer de source") },
        text = {
            Column {
                Text(
                    "Choisis la même série sur une autre source. Statut, note, notes, catégorie et chapitres lus seront transférés.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val list = results
                when {
                    working || list == null -> Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    list.isEmpty() -> Text("Rien trouvé sur les autres sources.", modifier = Modifier.padding(top = 12.dp))
                    else -> LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(list, key = { it.key }) { m ->
                            ListItem(
                                leadingContent = { CoverThumb(m.cover) },
                                headlineContent = { Text(m.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(SourceManager.get(m.sourceId)?.name.orEmpty()) },
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        working = true
                                        error = null
                                        try {
                                            val src = SourceManager.get(m.sourceId) ?: error("source introuvable")
                                            val full = src.details(m.id)
                                            val chapters = src.chapters(m.id)
                                            Library.migrate(manga.key, full, chapters)
                                            onMigrated(full.key)
                                        } catch (ce: CancellationException) {
                                            throw ce
                                        } catch (e: Exception) {
                                            error = e.message ?: e.toString()
                                        } finally {
                                            working = false
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                error?.let { Text("Erreur : $it", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text("Fermer") } },
    )
}
