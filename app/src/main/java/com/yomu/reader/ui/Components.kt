package com.yomu.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.yomu.reader.model.Manga

@Composable
fun MangaCard(
    manga: Manga,
    badge: String? = null,
    secondBadge: String? = null,
    onContinue: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = manga.cover,
                contentDescription = manga.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (badge != null) {
                Text(
                    badge,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (secondBadge != null) {
                Text(
                    secondBadge,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.Black,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (onContinue != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.92f))
                        .clickable(onClick = onContinue),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Continuer",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            manga.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
fun CenterMessage(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Une page de scan, avec chargement, erreur et bouton « réessayer ». */
@Composable
fun PageImage(
    url: String,
    headers: Map<String, String>,
    modifier: Modifier = Modifier,
    scale: ContentScale = ContentScale.FillWidth,
    placeholderHeight: Dp = 480.dp,
) {
    var attempt by remember(url) { mutableIntStateOf(0) }
    val context = LocalContext.current
    val request = remember(url, attempt) {
        ImageRequest.Builder(context)
            .data(url)
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .setParameter("attempt", attempt, memoryCacheKey = null)
            .build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier,
        contentScale = scale,
        loading = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(placeholderHeight),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        },
        error = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(placeholderHeight)
                    .clickable { attempt++ },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Image non chargée\nToucher pour réessayer",
                    textAlign = TextAlign.Center,
                    color = Color.White,
                )
            }
        },
    )
}

/** Petite couverture pour les listes (nouveautés, historique). */
@Composable
fun CoverThumb(url: String?, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .width(48.dp)
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** Icône d'état de téléchargement d'un chapitre (télécharger / progression / fait / erreur). */
@Composable
fun DownloadIndicator(
    downloaded: Boolean,
    task: com.yomu.reader.data.DownloadTask?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        when {
            downloaded -> androidx.compose.material3.IconButton(onClick = { menu = true }) {
                Icon(YomuIcons.DownloadDone, contentDescription = "Téléchargé", tint = MaterialTheme.colorScheme.primary)
            }
            task != null && task.state == com.yomu.reader.data.DownloadState.ERROR ->
                androidx.compose.material3.IconButton(onClick = onRetry) {
                    Icon(YomuIcons.Warning, contentDescription = "Réessayer", tint = MaterialTheme.colorScheme.error)
                }
            task != null -> androidx.compose.material3.IconButton(onClick = onCancel) {
                if (task.state == com.yomu.reader.data.DownloadState.RUNNING && task.total > 0) {
                    CircularProgressIndicator(
                        progress = { task.done.toFloat() / task.total },
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
            else -> androidx.compose.material3.IconButton(onClick = onDownload) {
                Icon(
                    YomuIcons.Download,
                    contentDescription = "Télécharger",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Supprimer le téléchargement") },
                onClick = {
                    menu = false
                    onDelete()
                },
            )
        }
    }
}

private val dayFormat = java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.FRANCE)
private val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE)

fun dayLabel(time: Long): String {
    val cal = java.util.Calendar.getInstance()
    val today = cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
    cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
    val yesterday = cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
    cal.timeInMillis = time
    val day = cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
    return when (day) {
        today -> "Aujourd'hui"
        yesterday -> "Hier"
        else -> dayFormat.format(java.util.Date(time)).replaceFirstChar { it.uppercase() }
    }
}

fun timeLabel(time: Long): String = timeFormat.format(java.util.Date(time))
