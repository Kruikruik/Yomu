package com.yomu.reader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import com.yomu.reader.BuildConfig
import com.yomu.reader.data.AppRelease
import com.yomu.reader.data.AppUpdater
import com.yomu.reader.data.DownloadState
import com.yomu.reader.data.Downloads
import com.yomu.reader.data.Library
import com.yomu.reader.data.Prefs
import com.yomu.reader.data.ReaderMode
import com.yomu.reader.data.UpdateWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f Go".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f Mo".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f Ko".format(bytes / 1024.0)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoilApi::class)
@Composable
fun MoreScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val queue by Downloads.queue.collectAsState()
    val downloaded by Downloads.downloaded.collectAsState()

    var mode by remember { mutableStateOf(Prefs.defaultMode) }
    var saver by remember { mutableStateOf(Prefs.dataSaver) }
    var interval by remember { mutableStateOf(Prefs.updateInterval) }
    var intervalMenu by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var dlSize by remember { mutableLongStateOf(0L) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<AppRelease?>(null) }

    LaunchedEffect(downloaded.size) { dlSize = Downloads.sizeBytes() }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            message = try {
                val text = Library.exportJson()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                }
                "Sauvegarde exportée"
            } catch (e: Exception) {
                "Échec de l'export : ${e.message}"
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            message = try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                } ?: throw IllegalStateException("fichier illisible")
                val n = Library.importJson(text)
                "Sauvegarde restaurée ($n série(s) ajoutée(s))"
            } catch (e: Exception) {
                "Échec de la restauration : ${e.message}"
            }
        }
    }

    release?.let { AppUpdateDialog(it, onDismiss = { release = null }) }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Supprimer tous les téléchargements ?") },
            confirmButton = {
                TextButton(onClick = {
                    Downloads.deleteAll()
                    confirmDeleteAll = false
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Annuler") } },
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Plus") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            message?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp),
                )
            }

            // ------------------------------------------------ Application
            SectionTitle("Application")
            ListItem(
                leadingContent = { Icon(YomuIcons.SystemUpdate, contentDescription = null) },
                headlineContent = { Text("Mettre à jour l'appli") },
                supportingContent = {
                    Text(if (checking) "Vérification…" else "Version installée : ${BuildConfig.VERSION_NAME}")
                },
                modifier = Modifier.clickable(enabled = !checking) {
                    scope.launch {
                        checking = true
                        try {
                            val r = AppUpdater.check()
                            if (r != null) release = r else message = "Tu as déjà la dernière version 👍"
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            message = "Impossible de vérifier : ${e.message}"
                        } finally {
                            checking = false
                        }
                    }
                },
            )

            // ------------------------------------------- Téléchargements
            SectionTitle("Téléchargements")
            ListItem(
                headlineContent = { Text("${downloaded.size} chapitre(s) hors-ligne") },
                supportingContent = { Text("Espace utilisé : ${formatSize(dlSize)}") },
                trailingContent = {
                    if (downloaded.isNotEmpty()) {
                        TextButton(onClick = { confirmDeleteAll = true }) { Text("Tout supprimer") }
                    }
                },
            )
            if (queue.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "File d'attente : ${queue.size}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (queue.any { it.state == DownloadState.ERROR }) {
                        TextButton(onClick = { Downloads.retryErrors() }) { Text("Réessayer") }
                    }
                    TextButton(onClick = { Downloads.cancelAll() }) { Text("Tout annuler") }
                }
                queue.take(30).forEach { task ->
                    ListItem(
                        headlineContent = {
                            Text(task.mangaTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Column {
                                Text(
                                    when (task.state) {
                                        DownloadState.QUEUED -> "${task.chapter.title} • en attente"
                                        DownloadState.RUNNING -> "${task.chapter.title} • ${task.done}/${task.total}"
                                        DownloadState.ERROR -> "${task.chapter.title} • erreur : ${task.error}"
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (task.state == DownloadState.RUNNING && task.total > 0) {
                                    LinearProgressIndicator(
                                        progress = { task.done.toFloat() / task.total },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp),
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            Row {
                                if (task.state == DownloadState.ERROR) {
                                    IconButton(onClick = { Downloads.retry(task.id) }) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Réessayer")
                                    }
                                }
                                IconButton(onClick = { Downloads.cancel(task.id) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Annuler")
                                }
                            }
                        },
                    )
                }
                if (queue.size > 30) {
                    Text(
                        "… et ${queue.size - 30} autre(s)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            Text(
                "Les téléchargements continuent tant que l'appli est ouverte (même en arrière-plan).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // ----------------------------------------------- Bibliothèque
            SectionTitle("Bibliothèque")
            ListItem(
                headlineContent = { Text("Vérifier les nouveaux chapitres") },
                supportingContent = {
                    Text(if (interval <= 0) "Désactivé" else "Automatiquement toutes les $interval h (avec notification)")
                },
                modifier = Modifier.clickable { intervalMenu = !intervalMenu },
            )
            if (intervalMenu) {
                listOf(0, 3, 6, 12, 24).forEach { h ->
                    ListItem(
                        headlineContent = { Text(if (h == 0) "Jamais" else "Toutes les $h heures") },
                        leadingContent = { RadioButton(selected = interval == h, onClick = null) },
                        modifier = Modifier.clickable {
                            interval = h
                            Prefs.updateInterval = h
                            UpdateWorker.schedule(context, replace = true)
                            intervalMenu = false
                        },
                    )
                }
            }
            ListItem(
                headlineContent = { Text("Exporter une sauvegarde") },
                supportingContent = { Text("Bibliothèque, chapitres lus et historique (fichier .json)") },
                modifier = Modifier.clickable {
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(Date())
                    exportLauncher.launch("yomu-sauvegarde-$date.json")
                },
            )
            ListItem(
                headlineContent = { Text("Restaurer une sauvegarde") },
                supportingContent = { Text("Fusionne avec ta bibliothèque actuelle") },
                modifier = Modifier.clickable { importLauncher.launch(arrayOf("application/json", "*/*")) },
            )

            // ----------------------------------------------------- Lecteur
            SectionTitle("Lecteur — mode par défaut")
            ReaderMode.entries.forEach { m ->
                ListItem(
                    headlineContent = { Text(m.label) },
                    leadingContent = { RadioButton(selected = m == mode, onClick = null) },
                    modifier = Modifier.clickable {
                        mode = m
                        Prefs.defaultMode = m
                    },
                )
            }
            Text(
                "Tu peux aussi changer de mode pour une série depuis le lecteur (icône ⚙).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            // ------------------------------------------------------ Réseau
            SectionTitle("Réseau et stockage")
            ListItem(
                headlineContent = { Text("Économiseur de données") },
                supportingContent = { Text("Images compressées, plus légères") },
                trailingContent = { Switch(checked = saver, onCheckedChange = null) },
                modifier = Modifier.clickable {
                    saver = !saver
                    Prefs.dataSaver = saver
                },
            )
            ListItem(
                headlineContent = { Text("Vider le cache des images") },
                supportingContent = { Text("Ne touche pas aux chapitres téléchargés") },
                modifier = Modifier.clickable {
                    scope.launch {
                        withContext(Dispatchers.IO) { context.imageLoader.diskCache?.clear() }
                        context.imageLoader.memoryCache?.clear()
                        message = "Cache des images vidé"
                    }
                },
            )

            SectionTitle("À propos")
            ListItem(
                headlineContent = { Text("Yomu ${BuildConfig.VERSION_NAME}") },
                supportingContent = { Text("Lecteur de mangas et webtoons inspiré de Tachiyomi.\ngithub.com/${AppUpdater.REPO}") },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}
