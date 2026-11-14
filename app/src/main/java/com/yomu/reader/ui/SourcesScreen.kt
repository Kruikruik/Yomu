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
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.runtime.collectAsState
import com.yomu.reader.data.Library
import com.yomu.reader.extension.Extensions
import com.yomu.reader.data.MangaCache
import com.yomu.reader.data.ReadingStatus
import com.yomu.reader.source.FilterState
import com.yomu.reader.model.Manga
import com.yomu.reader.source.Source
import com.yomu.reader.source.SourceManager
import com.yomu.reader.source.langName
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    onOpen: (Source) -> Unit,
    onGlobalSearch: (String) -> Unit,
    onOpenManga: (Manga) -> Unit = {},
    onOpenBrowser: () -> Unit = {},
    onOpenExtensions: () -> Unit = {},
) {
    // Recompose quand l'utilisateur ajoute / active / désactive une extension.
    val extensions by Extensions.installed.collectAsState()
    val sources = remember(extensions) { SourceManager.all }
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
            item(key = "browser") {
                ListItem(
                    headlineContent = { Text("Navigateur") },
                    supportingContent = { Text("Ouvrir n'importe quelle adresse, tes favoris") },
                    leadingContent = { Icon(YomuIcons.Public, contentDescription = null) },
                    modifier = Modifier.clickable { onOpenBrowser() },
                )
                HorizontalDivider()
            }
            item(key = "extensions") {
                ListItem(
                    headlineContent = { Text("Extensions") },
                    supportingContent = {
                        Text(if (extensions.isEmpty()) "Importer tes fichiers JSON" else "${extensions.count { it.enabled }} active(s) sur ${extensions.size}")
                    },
                    leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                    modifier = Modifier.clickable { onOpenExtensions() },
                )
                HorizontalDivider()
            }
            item(key = "reco") { Recommendations(onOpenManga) }
            items(sources, key = { it.id }) { source ->
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

/**
 * « Pour toi » : séries MangaDex bien notées dans tes genres préférés,
 * hors de ce que tu suis déjà (comme les suggestions de Kenmei).
 */
@Composable
private fun Recommendations(onOpen: (Manga) -> Unit) {
    val entries by Library.entries.collectAsState()
    val topGenres = remember(entries) {
        entries.filter { it.status != ReadingStatus.DROPPED }
            .flatMap { e -> e.manga.genres.map { it to (if (e.rating > 0) e.rating else 5) } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.sum() }
            .entries.sortedByDescending { it.value }
            .map { it.key }
            .take(2)
    }
    var result by remember(topGenres) { mutableStateOf<List<Manga>?>(null) }
    LaunchedEffect(topGenres) {
        if (topGenres.isEmpty()) {
            result = emptyList()
            return@LaunchedEffect
        }
        val source = SourceManager.get("mangadex-en") ?: return@LaunchedEffect
        result = try {
            val groups = source.filters()
            val ids = groups.filter { it.key.startsWith("tags_") }.flatMap { it.options }
                .filter { o -> topGenres.any { it.equals(o.label, ignoreCase = true) } }
            val state: FilterState = if (ids.isEmpty()) emptyMap() else mapOf("tags_genre" to ids.associate { it.value to 1 })
            val sorted = state + ("sort" to mapOf("rating" to 1))
            val known = Library.entries.value.map { it.manga.key }.toSet()
            source.browse(1, "", sorted).mangas.filter { it.key !in known }.onEach { MangaCache.put(it) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            emptyList()
        }
    }
    val list = result
    if (topGenres.isEmpty() || (list != null && list.isEmpty())) return
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(
            "Pour toi • ${topGenres.joinToString(", ")}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (list == null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                items(list, key = { it.key }) { m ->
                    Box(Modifier.width(110.dp)) { MangaCard(manga = m, onClick = { onOpen(m) }) }
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
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
