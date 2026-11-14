package com.yomu.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yomu.reader.data.Downloads
import com.yomu.reader.data.Library
import com.yomu.reader.data.LibraryEntry
import com.yomu.reader.data.LibrarySort
import com.yomu.reader.data.Prefs
import com.yomu.reader.data.ReadingStatus
import com.yomu.reader.model.Manga
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpen: (Manga) -> Unit,
    onRead: (key: String, chapterId: String) -> Unit,
    onSearch: (String) -> Unit = {},
) {
    val pending by Library.pending.collectAsState()
    var showPending by remember { mutableStateOf(false) }
    if (showPending) PendingDialog(pending, onSearch = { showPending = false; onSearch(it) }, onDismiss = { showPending = false })
    val entries by Library.entries.collectAsState()
    val progress by Library.progress.collectAsState()
    val downloaded by Downloads.downloaded.collectAsState()
    val refreshing by Library.refreshing.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(Prefs.librarySort) }
    var onlyUnread by remember { mutableStateOf(Prefs.filterUnread) }
    var onlyDownloaded by remember { mutableStateOf(Prefs.filterDownloaded) }

    val downloadCounts = remember(downloaded) {
        downloaded.groupingBy { it.substringBeforeLast('|') }.eachCount()
    }

    var statusFilter by rememberSaveable { mutableStateOf<String?>(null) }

    // Catégories : null = toutes, "" = sans catégorie.
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val categories = remember(entries) { entries.mapNotNull { it.category }.distinct().sortedBy { it.lowercase() } }
    if (category != null && category != "" && category !in categories) category = null

    val shown = remember(entries, progress, downloadCounts, sort, onlyUnread, onlyDownloaded, query, category, statusFilter) {
        entries
            .asSequence()
            .filter { statusFilter == null || it.status.name == statusFilter }
            .filter { category == null || (it.category ?: "") == category }
            .filter { query.isBlank() || it.manga.title.contains(query.trim(), ignoreCase = true) }
            .filter { !onlyUnread || it.unread > 0 }
            .filter { !onlyDownloaded || (downloadCounts[it.manga.key] ?: 0) > 0 }
            .sortedWith(
                when (sort) {
                    LibrarySort.TITLE -> compareBy<LibraryEntry> { it.manga.title.lowercase() }
                    LibrarySort.UNREAD -> compareByDescending<LibraryEntry> { it.unread }.thenBy { it.manga.title.lowercase() }
                    LibrarySort.LAST_READ -> compareByDescending<LibraryEntry> { progress[it.manga.key]?.lastReadAt ?: 0L }
                    LibrarySort.ADDED -> compareByDescending<LibraryEntry> { it.addedAt }
                    LibrarySort.RATING -> compareByDescending<LibraryEntry> { it.rating }.thenBy { it.manga.title.lowercase() }
                }
            )
            .toList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Rechercher dans la bibliothèque") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                        )
                    } else {
                        Text(if (entries.isEmpty()) "Bibliothèque" else "Bibliothèque (${entries.size})")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (searching) query = ""
                        searching = !searching
                    }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, contentDescription = "Rechercher")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(YomuIcons.FilterList, contentDescription = "Trier et filtrer")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            Text(
                                "Trier par",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            LibrarySort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.label) },
                                    onClick = {
                                        sort = s
                                        Prefs.librarySort = s
                                    },
                                    trailingIcon = { if (s == sort) Icon(Icons.Default.Check, contentDescription = null) },
                                )
                            }
                            HorizontalDivider()
                            Text(
                                "Filtrer",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            DropdownMenuItem(
                                text = { Text("Non lus seulement") },
                                onClick = {
                                    onlyUnread = !onlyUnread
                                    Prefs.filterUnread = onlyUnread
                                },
                                leadingIcon = { Checkbox(checked = onlyUnread, onCheckedChange = null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Téléchargés seulement") },
                                onClick = {
                                    onlyDownloaded = !onlyDownloaded
                                    Prefs.filterDownloaded = onlyDownloaded
                                },
                                leadingIcon = { Checkbox(checked = onlyDownloaded, onCheckedChange = null) },
                            )
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
                                snackbar.showSnackbar(
                                    when (r.newChapters) {
                                        0 -> "Pas de nouveaux chapitres"
                                        1 -> "1 nouveau chapitre"
                                        else -> "${r.newChapters} nouveaux chapitres"
                                    }
                                )
                            }
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Chercher les nouveaux chapitres")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (entries.isNotEmpty() || pending.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (pending.isNotEmpty()) {
                        item(key = "pending") {
                            FilterChip(
                                selected = false,
                                onClick = { showPending = true },
                                label = { Text("À retrouver (${pending.size})") },
                            )
                        }
                    }
                    item(key = "s_all") {
                        FilterChip(selected = statusFilter == null, onClick = { statusFilter = null }, label = { Text("Tout") })
                    }
                    ReadingStatus.entries.forEach { st ->
                        val count = entries.count { it.status == st }
                        if (count > 0) {
                            item(key = "s_${st.name}") {
                                FilterChip(
                                    selected = statusFilter == st.name,
                                    onClick = { statusFilter = if (statusFilter == st.name) null else st.name },
                                    label = { Text("${st.label} ($count)") },
                                )
                            }
                        }
                    }
                }
            }
            if (categories.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "all") {
                        FilterChip(selected = category == null, onClick = { category = null }, label = { Text("Toutes") })
                    }
                    rowItems(categories, key = { "c_$it" }) { c ->
                        FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                    }
                    if (entries.any { it.category == null }) {
                        item(key = "none") {
                            FilterChip(selected = category == "", onClick = { category = "" }, label = { Text("Sans catégorie") })
                        }
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
            when {
                entries.isEmpty() ->
                    CenterMessage("Ta bibliothèque est vide.\nVa dans Explorer et ajoute des séries avec ♥.")
                shown.isEmpty() -> CenterMessage("Aucune série ne correspond.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    items(shown, key = { it.manga.key }) { entry ->
                        val key = entry.manga.key
                        val dl = downloadCounts[key] ?: 0
                        val resume = remember(key, entry.unread, progress[key]) {
                            if (entry.unread > 0) Library.resumeChapter(key) else null
                        }
                        MangaCard(
                            manga = entry.manga,
                            badge = if (entry.unread > 0) entry.unread.toString() else null,
                            secondBadge = if (dl > 0) "⬇$dl" else null,
                            onContinue = resume?.let { ch -> { onRead(key, ch.id) } },
                            onClick = { onOpen(entry.manga) },
                        )
                    }
                }
            }
            }
        }
    }
}

/** Séries importées mais introuvables pour l'instant sur les sources. */
@Composable
private fun PendingDialog(
    pending: List<com.yomu.reader.data.PendingEntry>,
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var editLink by remember { mutableStateOf<com.yomu.reader.data.PendingEntry?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    editLink?.let { p ->
        LinkDialog(
            initial = p.link,
            onSave = { Library.setPendingLink(p.title, it) },
            onDismiss = { editLink = null },
        )
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("À retrouver (${pending.size})") },
        text = {
            Column {
                Text(
                    "Introuvables pour l'instant sur MangaDex et Webtoon. Yomu les recherche à chaque vérification. " +
                        "Appuie sur un titre pour le chercher toi-même (il existe parfois sous un autre nom).",
                    style = MaterialTheme.typography.bodySmall,
                )
                result?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
                androidx.compose.foundation.lazy.LazyColumn(Modifier.padding(top = 8.dp).heightIn(max = 380.dp)) {
                    rowItems(pending, key = { it.title }) { p ->
                        androidx.compose.material3.ListItem(
                            headlineContent = { Text(p.title, maxLines = 2) },
                            supportingContent = {
                                Column {
                                    p.maxRead?.let { Text("Lu jusqu'au ch. ${if (it % 1f == 0f) it.toInt().toString() else it.toString()}") }
                                    Row {
                                        androidx.compose.material3.TextButton(onClick = { editLink = p }) {
                                            Text(if (p.link == null) "+ Mon lien" else "Modifier le lien")
                                        }
                                        p.link?.let { l ->
                                            androidx.compose.material3.TextButton(onClick = { openLink(context, l) }) { Text("Ouvrir ↗") }
                                        }
                                    }
                                }
                            },
                            trailingContent = {
                                IconButton(onClick = { Library.removePending(p.title) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Retirer")
                                }
                            },
                            modifier = Modifier.clickable { onSearch(p.title) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = !working, onClick = {
                scope.launch {
                    working = true
                    val found = try {
                        com.yomu.reader.data.TachiImport.retryPending()
                    } catch (e: Exception) {
                        emptyList()
                    }
                    working = false
                    result = if (found.isEmpty()) "Toujours rien de nouveau." else "Retrouvées : ${found.joinToString(", ")}"
                }
            }) { Text(if (working) "Recherche…" else "Rechercher maintenant") }
        },
        dismissButton = { androidx.compose.material3.TextButton(enabled = !working, onClick = onDismiss) { Text("Fermer") } },
    )
}
