package com.yomu.reader.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yomu.reader.data.Bookmark
import com.yomu.reader.data.Bookmarks
import com.yomu.reader.data.hostOf
import com.yomu.reader.data.parseUserUrl
import com.yomu.reader.net.WebCookies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Favoris et liens personnalisés de l'utilisateur + accès au navigateur intégré. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(onBack: () -> Unit, onOpen: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by Bookmarks.items.collectAsState()
    val lastUrl by Bookmarks.lastUrl.collectAsState()

    var address by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    // null = fermé ; Bookmark sans id connu = création.
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    var creating by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(Bookmarks.exportText().toByteArray()) }
                }.isSuccess
            }
            Toast.makeText(context, if (ok) "Favoris exportés" else "Échec de l'export", Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }.orEmpty()
                    Bookmarks.importText(text)
                }.getOrNull()
            }
            Toast.makeText(
                context,
                if (added == null) "Fichier illisible" else "$added lien(s) ajouté(s)",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun go() {
        val url = parseUserUrl(address)
        if (url == null) {
            Toast.makeText(context, "Adresse invalide", Toast.LENGTH_SHORT).show()
        } else {
            address = ""
            onOpen(url)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Navigateur") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Exporter les favoris") },
                            onClick = { menuOpen = false; exportLauncher.launch("yomu-favoris.json") },
                        )
                        DropdownMenuItem(
                            text = { Text("Importer des favoris") },
                            onClick = { menuOpen = false; importLauncher.launch(arrayOf("application/json", "text/plain")) },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Effacer toutes les sessions") },
                            onClick = { menuOpen = false; confirmClear = true },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Default.Add, contentDescription = "Ajouter un lien") }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    placeholder = { Text("Ouvrir une adresse…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go() }),
                    leadingIcon = { Icon(YomuIcons.Public, contentDescription = null) },
                )
            }
            lastUrl?.let { url ->
                item(key = "resume") {
                    ListItem(
                        headlineContent = { Text("Reprendre la navigation") },
                        supportingContent = { Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(YomuIcons.History, contentDescription = null) },
                        modifier = Modifier.clickable { onOpen(url) },
                    )
                    HorizontalDivider()
                }
            }
            if (items.isEmpty()) {
                item {
                    Text(
                        "Aucun favori. Ajoute tes liens avec le bouton +, ou l'étoile dans le navigateur.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            // Rangés par dossier (les liens sans dossier en premier).
            items.groupBy { it.folder }.toSortedMap(compareBy<String>({ it.isNotEmpty() }, { it.lowercase() }, { it })).forEach { (folder, list) ->
                if (folder.isNotEmpty()) {
                    item(key = "folder_$folder") {
                        Text(
                            folder,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                }
                items(list.sortedBy { it.title.lowercase() }, key = { it.id }) { b ->
                    ListItem(
                        headlineContent = { Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(hostOf(b.url), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            IconButton(onClick = { editing = b }) { Icon(Icons.Default.Edit, contentDescription = "Modifier") }
                        },
                        modifier = Modifier.clickable { onOpen(b.url) },
                    )
                }
            }
        }
    }

    if (creating || editing != null) {
        BookmarkEditor(
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { title, url, folder ->
                val current = editing
                val ok = if (current == null) Bookmarks.add(title, url, folder) != null else Bookmarks.update(current.id, title, url, folder)
                if (ok) {
                    creating = false
                    editing = null
                }
                ok
            },
            onDelete = editing?.let { b -> { Bookmarks.remove(b.id); editing = null } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Effacer toutes les sessions ?") },
            text = { Text("Tu seras déconnecté de tous les sites ouverts dans le navigateur. Tes favoris sont conservés.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    WebCookies.clearAll { ok ->
                        Toast.makeText(context, if (ok) "Sessions effacées" else "Rien à effacer", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Effacer") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun BookmarkEditor(
    initial: Bookmark?,
    onDismiss: () -> Unit,
    onSave: (title: String, url: String, folder: String) -> Boolean,
    onDelete: (() -> Unit)?,
) {
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var url by rememberSaveable { mutableStateOf(initial?.url.orEmpty()) }
    var folder by rememberSaveable { mutableStateOf(initial?.folder.orEmpty()) }
    var error by remember { mutableStateOf(false) }
    val folders = remember { Bookmarks.folders() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nouveau lien" else "Modifier le lien") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = false },
                    label = { Text("Adresse") },
                    singleLine = true,
                    isError = error,
                    supportingText = { if (error) Text("Adresse invalide") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Nom (facultatif)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    label = { Text("Dossier (facultatif)") },
                    singleLine = true,
                )
                if (folders.isNotEmpty()) {
                    Text(
                        "Dossiers : " + folders.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (!onSave(title, url, folder)) error = true }) { Text("Enregistrer") }
        },
        dismissButton = {
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            } else {
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        },
    )
}
