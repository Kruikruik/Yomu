package com.yomu.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yomu.reader.data.ChapterCache
import com.yomu.reader.data.Library
import com.yomu.reader.data.MangaCache
import com.yomu.reader.data.Progress
import com.yomu.reader.model.Chapter
import com.yomu.reader.model.Manga
import com.yomu.reader.model.keyOf
import com.yomu.reader.source.SourceManager
import com.yomu.reader.source.langName
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)

/** Chapitre à ouvrir avec le bouton « Continuer ». La liste va du plus récent au plus ancien. */
private fun resumeTarget(list: List<Chapter>, p: Progress): Chapter {
    val idx = list.indexOfFirst { it.id == p.lastChapterId }
    if (idx >= 0) {
        if (list[idx].id !in p.read) return list[idx]
        if (idx > 0) return list[idx - 1]
        return list[idx]
    }
    return list.lastOrNull { it.id !in p.read } ?: list.first()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(sourceId: String, mangaId: String, onBack: () -> Unit, onRead: (String) -> Unit) {
    val key = keyOf(sourceId, mangaId)
    val source = remember(sourceId) { SourceManager.get(sourceId) }
    var manga by remember { mutableStateOf(MangaCache.get(key) ?: Library.get(key)?.manga) }
    var chapters by remember { mutableStateOf(ChapterCache.get(key)) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var descExpanded by remember { mutableStateOf(false) }
    var ascending by rememberSaveable { mutableStateOf(false) }

    val entries by Library.entries.collectAsState()
    val progressMap by Library.progress.collectAsState()
    val inLibrary = entries.any { it.manga.key == key }
    val progress = progressMap[key] ?: Progress()

    LaunchedEffect(key, reload) {
        if (source == null) {
            error = "Source introuvable"
            return@LaunchedEffect
        }
        // Déjà chargé pendant cette session : pas besoin de refaire les requêtes.
        if (reload == 0 && chapters != null && manga?.description != null) return@LaunchedEffect
        loading = true
        error = null
        try {
            val m = source.details(mangaId)
            manga = m
            MangaCache.put(m)
            Library.updateManga(m)
            val ch = source.chapters(mangaId)
            chapters = ch
            ChapterCache.put(key, ch)
            Library.updateChapters(key, ch)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        } finally {
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(manga?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { reload++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualiser")
                    }
                },
            )
        },
        floatingActionButton = {
            val list = chapters
            if (!list.isNullOrEmpty()) {
                val started = progress.lastChapterId != null || progress.read.isNotEmpty()
                ExtendedFloatingActionButton(
                    onClick = { onRead(resumeTarget(list, progress).id) },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    text = { Text(if (started) "Continuer" else "Commencer") },
                )
            }
        },
    ) { padding ->
        val m = manga
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (m == null) {
                if (error != null) CenterMessage("Erreur : $error", "Réessayer") { reload++ } else Loading()
            } else {
                val list = chapters.orEmpty()
                val shown = if (ascending) list.asReversed() else list
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    item {
                        Header(
                            manga = m,
                            sourceLabel = source?.let { "${it.name} • ${langName(it.lang)}" }.orEmpty(),
                            inLibrary = inLibrary,
                            onToggle = { Library.toggle(m, chapters) },
                        )
                    }
                    m.description?.let { desc ->
                        item {
                            Text(
                                desc,
                                maxLines = if (descExpanded) Int.MAX_VALUE else 4,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { descExpanded = !descExpanded }
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (m.genres.isNotEmpty()) {
                        item {
                            Text(
                                m.genres.joinToString(" • "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp, top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (chapters == null) "Chapitres" else "${list.size} chapitres",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            TextButton(onClick = { ascending = !ascending }) {
                                Text(if (ascending) "Plus anciens d'abord" else "Plus récents d'abord")
                            }
                        }
                    }
                    if (error != null) {
                        item {
                            TextButton(onClick = { reload++ }, modifier = Modifier.padding(horizontal = 8.dp)) {
                                Text("Erreur : $error — réessayer")
                            }
                        }
                    }
                    if (chapters != null && list.isEmpty()) {
                        item {
                            Text(
                                "Aucun chapitre disponible dans cette langue.",
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    items(shown, key = { it.id }) { ch ->
                        val read = ch.id in progress.read
                        ChapterRow(
                            chapter = ch,
                            read = read,
                            current = ch.id == progress.lastChapterId && !read,
                            onClick = { onRead(ch.id) },
                            onToggleRead = { Library.setRead(key, listOf(ch.id), !read) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(manga: Manga, sourceLabel: String, inLibrary: Boolean, onToggle: () -> Unit) {
    Row(Modifier.padding(16.dp)) {
        AsyncImage(
            model = manga.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(110.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(manga.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            manga.author?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            manga.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (sourceLabel.isNotEmpty()) {
                Text(sourceLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onToggle) {
                Icon(
                    if (inLibrary) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (inLibrary) "Dans la bibliothèque" else "Ajouter")
            }
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: Chapter,
    read: Boolean,
    current: Boolean,
    onClick: () -> Unit,
    onToggleRead: () -> Unit,
) {
    val titleColor = when {
        read -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        current -> MaterialTheme.colorScheme.primary
        else -> Color.Unspecified
    }
    ListItem(
        headlineContent = {
            Text(chapter.title, maxLines = 2, overflow = TextOverflow.Ellipsis, color = titleColor)
        },
        supportingContent = {
            val parts = listOfNotNull(chapter.date?.let { dateFormat.format(Date(it)) }, chapter.scanlator)
            if (parts.isNotEmpty()) {
                Text(
                    parts.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (read) 0.4f else 1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = {
            IconButton(onClick = onToggleRead) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = if (read) "Marquer comme non lu" else "Marquer comme lu",
                    tint = if (read) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
