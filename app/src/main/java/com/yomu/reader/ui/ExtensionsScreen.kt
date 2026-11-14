package com.yomu.reader.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yomu.reader.data.hostOf
import com.yomu.reader.extension.Extensions
import com.yomu.reader.extension.ImportResult
import com.yomu.reader.extension.InstalledExtension
import com.yomu.reader.source.langName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Import, activation, test et suppression des extensions JSON de l'utilisateur. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(onBack: () -> Unit, onOpenWebsite: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed by Extensions.installed.collectAsState()
    val broken by Extensions.broken.collectAsState()

    var results by remember { mutableStateOf<List<ImportResult>?>(null) }
    var pasteOpen by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<InstalledExtension?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { results = Extensions.importUris(context, uris) }
    }

    fun test(ext: InstalledExtension) {
        message = ext.config.name to "Test en cours…"
        scope.launch {
            val text = try {
                val src = ext.source
                val page = if (ext.config.popular != null || ext.config.latest != null) src.popular(1) else src.search("a", 1)
                val first = page.mangas.firstOrNull()
                if (first == null) {
                    "La page s'est chargée mais aucune série n'a été trouvée : vérifie « item », « title » et « link »."
                } else {
                    val chapters = src.chapters(first.id)
                    "${page.mangas.size} série(s) trouvée(s).\nPremière : ${first.title}\n${chapters.size} chapitre(s) sur sa fiche." +
                        if (chapters.isNotEmpty()) "\nImages du dernier : ${src.pages(chapters.first().id).size}" else ""
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                "Erreur : ${e.message ?: e.toString()}"
            }
            message = ext.config.name to text
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Extensions") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Coller un JSON") }, onClick = { menuOpen = false; pasteOpen = true })
                        DropdownMenuItem(text = { Text("Recharger les fichiers") }, onClick = { menuOpen = false; Extensions.reload() })
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Importer") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            if (installed.isEmpty() && broken.isEmpty()) {
                item {
                    Text(
                        "Aucune extension. Importe tes fichiers JSON avec le bouton « Importer ».",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(installed, key = { it.config.id }) { ext ->
                ExtensionRow(
                    ext = ext,
                    onToggle = { Extensions.setEnabled(ext.config.id, it) },
                    onTest = { test(ext) },
                    onOpenWebsite = { onOpenWebsite(ext.config.baseUrl) },
                    onShare = {
                        val text = Extensions.exportText(ext.config.id) ?: return@ExtensionRow
                        val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, text)
                        context.startActivity(Intent.createChooser(send, ext.config.name))
                    },
                    onDelete = { toDelete = ext },
                )
                HorizontalDivider()
            }
            if (broken.isNotEmpty()) {
                item {
                    Text(
                        "Fichiers en erreur",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp),
                    )
                }
                items(broken, key = { "broken_" + it.fileName }) { b ->
                    ListItem(
                        headlineContent = { Text(b.fileName) },
                        supportingContent = { Text(b.error, color = MaterialTheme.colorScheme.error) },
                        trailingContent = {
                            IconButton(onClick = { Extensions.deleteBroken(b.fileName) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Supprimer")
                            }
                        },
                    )
                }
            }
        }
    }

    results?.let { list ->
        AlertDialog(
            onDismissRequest = { results = null },
            title = { Text("Import") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    list.forEach { r ->
                        val line = when (r) {
                            is ImportResult.Installed -> "✓ ${r.name} : installée"
                            is ImportResult.Updated -> "✓ ${r.name} : mise à jour (v${r.from} → v${r.to})"
                            is ImportResult.Failed -> "✗ ${r.name} : ${r.error}"
                        }
                        Text(
                            line,
                            color = if (r is ImportResult.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { results = null }) { Text("OK") } },
        )
    }

    if (pasteOpen) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasteOpen = false },
            title = { Text("Coller un JSON") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pasteOpen = false
                    results = Extensions.importText(text, "texte collé")
                }) { Text("Importer") }
            },
            dismissButton = { TextButton(onClick = { pasteOpen = false }) { Text("Annuler") } },
        )
    }

    toDelete?.let { ext ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Supprimer ${ext.config.name} ?") },
            text = { Text("Les séries de cette source dans ta bibliothèque ne pourront plus être mises à jour ni lues (sauf chapitres téléchargés).") },
            confirmButton = {
                TextButton(onClick = { Extensions.delete(ext.config.id); toDelete = null }) {
                    Text("Supprimer", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } },
        )
    }

    message?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun ExtensionRow(
    ext: InstalledExtension,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onOpenWebsite: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val c = ext.config
    ListItem(
        headlineContent = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull("v${c.version}", hostOf(c.baseUrl), langName(c.lang), c.author).joinToString(" • "),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            androidx.compose.foundation.layout.Row {
                Switch(checked = ext.enabled, onCheckedChange = onToggle)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Tester") }, onClick = { menu = false; onTest() })
                        DropdownMenuItem(text = { Text("Ouvrir le site (connexion)") }, onClick = { menu = false; onOpenWebsite() })
                        DropdownMenuItem(text = { Text("Partager le fichier") }, onClick = { menu = false; onShare() })
                        DropdownMenuItem(text = { Text("Supprimer") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        },
    )
}
