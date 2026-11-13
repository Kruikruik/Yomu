package com.yomu.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.yomu.reader.data.MangaCache
import com.yomu.reader.model.Manga
import com.yomu.reader.source.Source
import com.yomu.reader.source.SourceManager
import com.yomu.reader.source.langName
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(onOpen: (Source) -> Unit, onGlobalSearch: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    Scaffold(topBar = { TopAppBar(title = { Text("Explorer") }) }) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    placeholder = { Text("Rechercher dans toutes les sources…") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        if (query.isNotBlank()) {
                            focus.clearFocus()
                            onGlobalSearch(query.trim())
                        }
                    }),
                )
            }
            items(SourceManager.all, key = { it.id }) { source ->
                ListItem(
                    headlineContent = { Text(source.name) },
                    supportingContent = { Text(langName(source.lang)) },
                    leadingContent = { SourceBadge(source) },
                    modifier = Modifier.clickable { onOpen(source) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SourceBadge(source: Source) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            source.name.take(2).uppercase(),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private sealed interface SearchResult {
    data object Loading : SearchResult
    data class Done(val mangas: List<Manga>) : SearchResult
    data class Failed(val message: String) : SearchResult
}

/** Recherche dans toutes les sources à la fois (une ligne par source). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSearchScreen(query: String, onBack: () -> Unit, onOpen: (Manga) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("« $query »") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            items(SourceManager.all, key = { it.id }) { source ->
                SourceResults(source, query, onOpen)
            }
        }
    }
}

@Composable
private fun SourceResults(source: Source, query: String, onOpen: (Manga) -> Unit) {
    var result by remember(source.id, query) { mutableStateOf<SearchResult>(SearchResult.Loading) }
    LaunchedEffect(source.id, query) {
        result = try {
            val r = source.search(query, 1).mangas
            r.forEach { MangaCache.put(it) }
            SearchResult.Done(r)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            SearchResult.Failed(e.message ?: e.toString())
        }
    }
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${source.name} • ${langName(source.lang)}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (result is SearchResult.Loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        when (val r = result) {
            is SearchResult.Loading -> Box(Modifier.height(8.dp))
            is SearchResult.Failed -> Text(
                "Erreur : ${r.message}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            is SearchResult.Done -> if (r.mangas.isEmpty()) {
                Text(
                    "Aucun résultat",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            } else {
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                    items(r.mangas, key = { it.id }) { m ->
                        Box(Modifier.width(110.dp)) {
                            MangaCard(manga = m, onClick = { onOpen(m) })
                        }
                    }
                }
            }
        }
    }
}
