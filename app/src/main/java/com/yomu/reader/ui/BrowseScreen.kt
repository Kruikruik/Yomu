package com.yomu.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.yomu.reader.data.Library
import com.yomu.reader.data.MangaCache
import com.yomu.reader.model.Manga
import com.yomu.reader.source.FilterGroup
import com.yomu.reader.source.FilterState
import com.yomu.reader.source.SourceManager
import com.yomu.reader.source.activeCount
import kotlinx.coroutines.CancellationException

private const val POPULAR = 0
private const val LATEST = 1
private const val SEARCH = 2
private const val FILTERED = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(sourceId: String, onBack: () -> Unit, onOpen: (Manga) -> Unit) {
    val source = remember(sourceId) { SourceManager.get(sourceId) }
    if (source == null) {
        CenterMessage("Source introuvable", "Retour", onBack)
        return
    }

    var listing by rememberSaveable { mutableIntStateOf(POPULAR) }
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    val results = remember { mutableStateListOf<Manga>() }
    var page by remember { mutableIntStateOf(0) }
    var hasNext by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var applied by remember { mutableStateOf<FilterState>(emptyMap()) }
    var groups by remember { mutableStateOf<List<FilterGroup>?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    val activeFilters = applied.activeCount()

    LaunchedEffect(sourceId) {
        groups = try {
            source.filters()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            emptyList()
        }
    }

    if (showFilters) {
        val g = groups
        if (g.isNullOrEmpty()) {
            showFilters = false
        } else {
            FilterSheet(
                groups = g,
                initial = applied,
                onApply = { f ->
                    applied = f
                    showFilters = false
                    if (listing != SEARCH) listing = FILTERED
                    reloadKey++
                },
                onDismiss = { showFilters = false },
            )
        }
    }
    val gridState = rememberLazyGridState()
    val focus = LocalFocusManager.current
    val library by Library.entries.collectAsState()
    val libraryKeys = remember(library) { library.map { it.manga.key }.toSet() }

    suspend fun fetch(p: Int) {
        loading = true
        error = null
        try {
            val res = when (listing) {
                LATEST -> source.latest(p)
                SEARCH -> if (applied.isEmpty()) source.search(submitted, p) else source.browse(p, submitted, applied)
                FILTERED -> source.browse(p, "", applied)
                else -> source.popular(p)
            }
            res.mangas.forEach { MangaCache.put(it) }
            val known = results.map { it.id }.toSet()
            results.addAll(res.mangas.filter { it.id !in known })
            hasNext = res.hasNextPage && res.mangas.isNotEmpty()
            page = p
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        } finally {
            loading = false
        }
    }

    LaunchedEffect(listing, submitted, reloadKey) {
        results.clear()
        page = 0
        hasNext = true
        fetch(1)
    }

    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            results.isNotEmpty() && last >= results.size - 6
        }
    }
    LaunchedEffect(nearEnd, loading, hasNext, error, listing, submitted) {
        if (nearEnd && !loading && hasNext && error == null && page > 0) fetch(page + 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${source.name} (${source.lang.uppercase()})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    if (!groups.isNullOrEmpty()) {
                        IconButton(onClick = { showFilters = true }) {
                            BadgedBox(badge = { if (activeFilters > 0) Badge { Text("$activeFilters") } }) {
                                Icon(YomuIcons.FilterList, contentDescription = "Filtres")
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                placeholder = { Text("Rechercher un titre…") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            if (listing == SEARCH) listing = POPULAR
                        }) { Icon(Icons.Default.Close, contentDescription = "Effacer") }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (query.isNotBlank()) {
                        submitted = query.trim()
                        listing = SEARCH
                        focus.clearFocus()
                    }
                }),
            )
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = listing == POPULAR, onClick = { listing = POPULAR }, label = { Text("Populaires") })
                FilterChip(selected = listing == LATEST, onClick = { listing = LATEST }, label = { Text("Récents") })
                if (!groups.isNullOrEmpty()) {
                    FilterChip(
                        selected = listing == FILTERED,
                        onClick = { if (activeFilters > 0 && listing != FILTERED) listing = FILTERED else showFilters = true },
                        label = { Text(if (activeFilters > 0) "Catalogue filtré ($activeFilters)" else "Catalogue + filtres") },
                        leadingIcon = { Icon(YomuIcons.FilterList, contentDescription = null) },
                    )
                }
                if (submitted.isNotEmpty()) {
                    FilterChip(selected = listing == SEARCH, onClick = { listing = SEARCH }, label = { Text("« $submitted »") })
                }
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    results.isEmpty() && loading -> Loading()
                    results.isEmpty() && error != null ->
                        CenterMessage("Erreur : $error", "Réessayer") { reloadKey++ }
                    results.isEmpty() -> CenterMessage("Aucun résultat")
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(110.dp),
                        state = gridState,
                        contentPadding = PaddingValues(8.dp),
                    ) {
                        items(results, key = { it.id }) { manga ->
                            MangaCard(
                                manga = manga,
                                badge = if (manga.key in libraryKeys) "♥" else null,
                                onClick = { onOpen(manga) },
                            )
                        }
                        if (loading || error != null) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (error != null) {
                                        TextButton(onClick = { error = null }) { Text("Erreur — réessayer") }
                                    } else {
                                        CircularProgressIndicator()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
