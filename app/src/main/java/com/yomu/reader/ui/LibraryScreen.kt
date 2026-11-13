package com.yomu.reader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yomu.reader.data.Library
import com.yomu.reader.data.LibraryEntry
import com.yomu.reader.model.Manga
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpen: (Manga) -> Unit) {
    val entries by Library.entries.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var refreshing by remember { mutableStateOf(false) }

    val sorted = remember(entries) {
        entries.sortedWith(
            compareByDescending<LibraryEntry> { it.unread > 0 }.thenBy { it.manga.title.lowercase() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bibliothèque") },
                actions = {
                    if (refreshing) {
                        Box(Modifier.padding(12.dp)) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        IconButton(onClick = {
                            scope.launch {
                                refreshing = true
                                val n = Library.refreshAll()
                                refreshing = false
                                snackbar.showSnackbar(
                                    if (n > 0) "$n série(s) avec de nouveaux chapitres" else "Pas de nouveaux chapitres"
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
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (sorted.isEmpty()) {
                CenterMessage("Ta bibliothèque est vide.\nVa dans Explorer et ajoute des séries avec ♥.")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    items(sorted, key = { it.manga.key }) { entry ->
                        MangaCard(
                            manga = entry.manga,
                            badge = if (entry.unread > 0) entry.unread.toString() else null,
                            onClick = { onOpen(entry.manga) },
                        )
                    }
                }
            }
        }
    }
}
