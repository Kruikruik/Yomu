package com.yomu.reader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.yomu.reader.data.DownloadTask
import com.yomu.reader.data.Downloads
import com.yomu.reader.data.Library
import com.yomu.reader.data.MangaCache
import com.yomu.reader.data.Progress
import com.yomu.reader.data.downloadId
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(sourceId: String, mangaId: String, onBack: () -> Unit, onRead: (String) -> Unit) {
    val key = keyOf(sourceId, mangaId)
    val source = remember(sourceId) { SourceManager.get(sourceId) }
    var manga by remember { mutableStateOf(MangaCache.get(key) ?: Library.mangaFor(key)) }
    var chapters by remember { mutableStateOf(Library.chaptersFor(key)) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var descExpanded by remember { mutableStateOf(false) }
    var ascending by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var menu by remember { mutableStateOf(false) }
    var dlMenu by remember { mutableStateOf(false) }

    val entries by Library.entries.collectAsState()
    val progressMap by Library.progress.collectAsState()
    val downloaded by Downloads.downloaded.collectAsState()
    val queue by Downloads.queue.collectAsState()
    val inLibrary = entries.any { it.manga.key == key }
    val progress = progressMap[key] ?: Progress()
    val list = chapters.orEmpty()

    LaunchedEffect(key, reload) {
        if (source == null) {
            error = "Source introuvable"
            return@LaunchedEffect
        }
        if (reload == 0 && ChapterCache.get(key) != null && manga?.description != null) return@LaunchedEffect
        loading = true
        error = null
        try {
            val m = source.details(mangaId)
            manga = m
            Library.updateManga(m)
            val ch = source.chapters(mangaId)
            chapters = ch
            Library.updateChapters(key, ch)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        } finally {
            loading = false
        }
    }

    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    fun selectedChapters() = list.filter { it.id in selected }
    fun download(chs: List<Chapter>) {
        val m = manga ?: return
        // du plus ancien au plus récent
        Downloads.enqueue(m, chs.sortedWith(compareBy<Chapter> { it.number ?: Float.MAX_VALUE }.thenBy { it.date ?: 0L }))
    }
    fun unreadChapters() = list.filter { it.id !in progress.read }

    Scaffold(
        topBar = {
            if (selected.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${selected.size} sélectionné(s)") },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Annuler la sélection")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            Library.setRead(key, selected, true)
                            selected = emptySet()
                        }) { Icon(YomuIcons.DoneAll, contentDescription = "Marquer comme lu") }
                        IconButton(onClick = {
                            download(selectedChapters())
                            selected = emptySet()
                        }) { Icon(YomuIcons.Download, contentDescription = "Télécharger") }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Plus")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Marquer comme non lu") }, onClick = {
                                    Library.setRead(key, selected, false)
                                    selected = emptySet()
                                    menu = false
                                })
                                DropdownMenuItem(text = { Text("Marquer les précédents comme lus") }, onClick = {
                                    // La liste va du plus récent au plus ancien : « précédents » = indices plus grands.
                                    val newest = list.indexOfFirst { it.id in selected }
                                    if (newest >= 0) Library.setRead(key, list.drop(newest).map { it.id }, true)
                                    selected = emptySet()
                                    menu = false
                                })
                                DropdownMenuItem(text = { Text("Supprimer les téléchargements") }, onClick = {
                                    Downloads.delete(key, selected)
                                    selected = emptySet()
                                    menu = false
                                })
                                DropdownMenuItem(text = { Text("Tout sélectionner") }, onClick = {
                                    selected = list.map { it.id }.toSet()
                                    menu = false
                                })
                                DropdownMenuItem(text = { Text("Inverser la sélection") }, onClick = {
                                    selected = list.map { it.id }.toSet() - selected
                                    menu = false
                                })
                            }
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(manga?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { dlMenu = true }, enabled = list.isNotEmpty()) {
                                Icon(YomuIcons.Download, contentDescription = "Télécharger")
                            }
                            DropdownMenu(expanded = dlMenu, onDismissRequest = { dlMenu = false }) {
                                DropdownMenuItem(text = { Text("Le prochain chapitre") }, onClick = {
                                    Library.resumeChapter(key, list)?.let { download(listOf(it)) }
                                    dlMenu = false
                                })
                                DropdownMenuItem(text = { Text("Les 5 prochains") }, onClick = {
                                    download(unreadChapters().takeLast(5))
                                    dlMenu = false
                                })
                                DropdownMenuItem(text = { Text("Les 10 prochains") }, onClick = {
                                    download(unreadChapters().takeLast(10))
                                    dlMenu = false
                                })
                                DropdownMenuItem(text = { Text("Tous les non lus") }, onClick = {
                                    download(unreadChapters())
                                    dlMenu = false
                                })
                                DropdownMenuItem(text = { Text("Tout") }, onClick = {
                                    download(list)
                                    dlMenu = false
                                })
                            }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Plus")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Actualiser") }, onClick = {
                                    reload++
                                    menu = false
                                })
                                DropdownMenuItem(text = { Text("Tout marquer comme lu") }, onClick = {
                                    Library.setRead(key, list.map { it.id }, true)
                                    menu = false
                                })
                                DropdownMenuItem(text = { Text("Tout marquer comme non lu") }, onClick = {
                                    Library.setRead(key, list.map { it.id }, false)
                                    menu = false
                                })
                                DropdownMenuItem(text = { Text("Supprimer les téléchargements") }, onClick = {
                                    Downloads.delete(key, list.map { it.id })
                                    menu = false
                                })
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (list.isNotEmpty() && selected.isEmpty()) {
                val started = progress.lastChapterId != null || progress.read.isNotEmpty()
                ExtendedFloatingActionButton(
                    onClick = { Library.resumeChapter(key, list)?.let { onRead(it.id) } },
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
                val shown = if (ascending) list.asReversed() else list
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    item(key = "header") {
                        Header(
                            manga = m,
                            sourceLabel = source?.let { "${it.name} • ${langName(it.lang)}" }.orEmpty(),
                            inLibrary = inLibrary,
                            onToggle = { Library.toggle(m, chapters) },
                        )
                    }
                    m.description?.let { desc ->
                        item(key = "desc") {
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
                        item(key = "genres") {
                            Text(
                                m.genres.joinToString(" • "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    item(key = "chapters_header") {
                        val unread = list.count { it.id !in progress.read }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp, top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (chapters == null) "Chapitres" else "${list.size} chapitres",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                if (list.isNotEmpty()) {
                                    Text(
                                        "$unread non lu(s) • appui long pour sélectionner",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            TextButton(onClick = { ascending = !ascending }) {
                                Text(if (ascending) "Anciens ↑" else "Récents ↓")
                            }
                        }
                        HorizontalDivider()
                    }
                    if (error != null) {
                        item(key = "error") {
                            TextButton(onClick = { reload++ }, modifier = Modifier.padding(horizontal = 8.dp)) {
                                Text("Erreur : $error — réessayer")
                            }
                        }
                    }
                    if (chapters != null && list.isEmpty()) {
                        item(key = "empty") {
                            Text("Aucun chapitre disponible en anglais.", modifier = Modifier.padding(16.dp))
                        }
                    }
                    items(shown, key = { it.id }) { ch ->
                        val id = downloadId(key, ch.id)
                        ChapterRow(
                            chapter = ch,
                            read = ch.id in progress.read,
                            lastPage = if (ch.id == progress.lastChapterId) progress.lastPage else 0,
                            isSelected = ch.id in selected,
                            downloaded = id in downloaded,
                            task = queue.firstOrNull { it.id == id },
                            onClick = {
                                if (selected.isNotEmpty()) {
                                    selected = if (ch.id in selected) selected - ch.id else selected + ch.id
                                } else {
                                    onRead(ch.id)
                                }
                            },
                            onLongClick = {
                                selected = if (ch.id in selected) selected - ch.id else selected + ch.id
                            },
                            onDownload = { download(listOf(ch)) },
                            onCancel = { Downloads.cancel(id) },
                            onDelete = { Downloads.delete(key, listOf(ch.id)) },
                            onRetry = { Downloads.retry(id) },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterRow(
    chapter: Chapter,
    read: Boolean,
    lastPage: Int,
    isSelected: Boolean,
    downloaded: Boolean,
    task: DownloadTask?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
) {
    val titleColor = if (read) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) else Color.Unspecified
    ListItem(
        headlineContent = {
            Text(chapter.title, maxLines = 2, overflow = TextOverflow.Ellipsis, color = titleColor)
        },
        supportingContent = {
            val parts = listOfNotNull(
                chapter.date?.let { dateFormat.format(Date(it)) },
                chapter.scanlator,
                if (!read && lastPage > 0) "page ${lastPage + 1}" else null,
            )
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
            DownloadIndicator(
                downloaded = downloaded,
                task = task,
                onDownload = onDownload,
                onCancel = onCancel,
                onDelete = onDelete,
                onRetry = onRetry,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent,
        ),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}
