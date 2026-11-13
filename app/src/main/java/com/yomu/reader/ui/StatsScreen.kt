package com.yomu.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yomu.reader.data.Downloads
import com.yomu.reader.data.Library
import com.yomu.reader.data.ReadingStatus
import com.yomu.reader.source.SourceManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit) {
    val entries by Library.entries.collectAsState()
    val progress by Library.progress.collectAsState()
    val downloaded by Downloads.downloaded.collectAsState()

    val stats = remember(entries, progress, downloaded) {
        val keys = entries.map { it.manga.key }.toSet()
        val chaptersRead = progress.filterKeys { it in keys }.values.sumOf { it.read.size }
        val totalRead = progress.values.sumOf { it.read.size }
        val unread = entries.sumOf { it.unread }
        val byStatus = ReadingStatus.entries.associateWith { st -> entries.count { it.status == st } }
        val bySource = entries.groupingBy { SourceManager.get(it.manga.sourceId)?.name ?: it.manga.sourceId }.eachCount()
            .entries.sortedByDescending { it.value }
        val genres = entries.flatMap { it.manga.genres }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(8)
        val rated = entries.filter { it.rating > 0 }
        val avg = if (rated.isEmpty()) null else rated.sumOf { it.rating }.toFloat() / rated.size
        val topRated = rated.sortedByDescending { it.rating }.take(5)
        Stats(entries.size, chaptersRead, totalRead, unread, byStatus, bySource, genres, avg, rated.size, topRated.map { it.manga.title to it.rating }, downloaded.size)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Statistiques") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigNumber("Séries suivies", stats.series.toString(), Modifier.weight(1f))
                BigNumber("Chapitres lus", stats.totalRead.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigNumber("Non lus", stats.unread.toString(), Modifier.weight(1f))
                BigNumber(
                    "Note moyenne",
                    stats.avgRating?.let { "%.1f/10".format(it) } ?: "—",
                    Modifier.weight(1f),
                )
            }

            Section("Par statut") {
                val max = stats.byStatus.values.maxOrNull()?.coerceAtLeast(1) ?: 1
                stats.byStatus.forEach { (st, n) -> Bar(st.label, n, max) }
            }
            if (stats.bySource.isNotEmpty()) {
                Section("Par source") {
                    val max = stats.bySource.first().value
                    stats.bySource.forEach { (name, n) -> Bar(name, n, max) }
                }
            }
            if (stats.genres.isNotEmpty()) {
                Section("Genres préférés") {
                    val max = stats.genres.first().value
                    stats.genres.forEach { (g, n) -> Bar(g, n, max) }
                }
            }
            if (stats.topRated.isNotEmpty()) {
                Section("Mieux notées (${stats.ratedCount} série(s) notée(s))") {
                    stats.topRated.forEach { (title, r) ->
                        Row {
                            Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("$r/10", color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
            Text(
                "${stats.downloads} chapitre(s) téléchargé(s) pour lire hors-ligne.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class Stats(
    val series: Int,
    val chaptersReadInLibrary: Int,
    val totalRead: Int,
    val unread: Int,
    val byStatus: Map<ReadingStatus, Int>,
    val bySource: List<Map.Entry<String, Int>>,
    val genres: List<Map.Entry<String, Int>>,
    val avgRating: Float?,
    val ratedCount: Int,
    val topRated: List<Pair<String, Int>>,
    val downloads: Int,
)

@Composable
private fun BigNumber(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Bar(label: String, value: Int, max: Int) {
    Row {
        Text(label, Modifier.width(130.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        Box(
            Modifier
                .weight(1f)
                .height(16.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(if (max > 0) value.toFloat() / max else 0f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(value.toString(), style = MaterialTheme.typography.bodySmall)
    }
}
