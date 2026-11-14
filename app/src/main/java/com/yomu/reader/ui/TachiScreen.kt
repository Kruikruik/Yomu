package com.yomu.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yomu.reader.source.langName
import com.yomu.reader.tachi.AvailableExtension
import com.yomu.reader.tachi.InstallResult
import com.yomu.reader.tachi.InstalledTachiExtension
import com.yomu.reader.tachi.TachiExtensions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Onglet « Dépôts & Extensions » : dépôts Tachiyomi/Mihon, extensions disponibles et installées. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TachiScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val repos by TachiExtensions.repos.collectAsState()
    val installed by TachiExtensions.installed.collectAsState()

    var tab by rememberSaveable { mutableStateOf(if (repos.isEmpty()) 2 else 0) }
    var available by remember { mutableStateOf<List<AvailableExtension>?>(null) }
    var repoErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val installing = remember { mutableStateMapOf<String, Boolean>() }
    var message by remember { mutableStateOf<String?>(null) }
    var trustRequest by remember { mutableStateOf<Pair<AvailableExtension, InstallResult.NeedsTrust>?>(null) }
    var toUninstall by remember { mutableStateOf<InstalledTachiExtension?>(null) }

    fun refresh() {
        if (repos.isEmpty()) {
            available = emptyList()
            return
        }
        loading = true
        scope.launch {
            try {
                val (list, errors) = TachiExtensions.fetchAvailable()
                available = list
                repoErrors = errors
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                message = e.message ?: e.toString()
            } finally {
                loading = false
            }
        }
    }

    fun install(ext: AvailableExtension) {
        installing[ext.pkgName] = true
        scope.launch {
            val result = try {
                TachiExtensions.install(ext)
            } finally {
                installing.remove(ext.pkgName)
            }
            when (result) {
                InstallResult.Done -> Unit
                is InstallResult.NeedsTrust -> trustRequest = ext to result
                is InstallResult.Failed -> message = "${ext.name} : ${result.message}"
            }
        }
    }

    LaunchedEffect(repos) { refresh() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Dépôts & Extensions") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                    },
                    actions = {
                        IconButton(onClick = { refresh() }, enabled = !loading) {
                            Icon(Icons.Default.Refresh, contentDescription = "Actualiser")
                        }
                    },
                )
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Installées (${installed.size})") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Disponibles") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Dépôts (${repos.size})") })
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when (tab) {
                0 -> InstalledTab(
                    installed = installed,
                    available = available.orEmpty(),
                    installing = installing,
                    onUpdate = ::install,
                    onUninstall = { toUninstall = it },
                )
                1 -> AvailableTab(
                    available = available,
                    installed = installed,
                    loading = loading,
                    errors = repoErrors,
                    installing = installing,
                    hasRepos = repos.isNotEmpty(),
                    onInstall = ::install,
                    onGoToRepos = { tab = 2 },
                )
                else -> ReposTab(onAdded = { tab = 1 }, onMessage = { message = it })
            }
        }
    }

    trustRequest?.let { (ext, req) ->
        AlertDialog(
            onDismissRequest = { trustRequest = null },
            title = { Text("Signature inconnue") },
            text = {
                Text(
                    "${ext.name} n'est pas signée par la clé annoncée par son dépôt.\n\n" +
                        "Une extension exécute du code dans Yomu : n'approuve que si tu fais confiance à sa source.\n\n" +
                        "Empreinte : ${req.signature.take(16)}…",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    TachiExtensions.trust(req.pkgName, req.signature)
                    trustRequest = null
                    install(ext)
                }) { Text("Approuver et installer") }
            },
            dismissButton = { TextButton(onClick = { trustRequest = null }) { Text("Annuler") } },
        )
    }

    toUninstall?.let { ext ->
        AlertDialog(
            onDismissRequest = { toUninstall = null },
            title = { Text("Désinstaller ${ext.name} ?") },
            text = { Text("Les séries de ses sources dans ta bibliothèque ne pourront plus être mises à jour (les chapitres téléchargés restent lisibles).") },
            confirmButton = {
                TextButton(onClick = {
                    TachiExtensions.uninstall(ext.pkgName)
                    toUninstall = null
                }) { Text("Désinstaller", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { toUninstall = null }) { Text("Annuler") } },
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }
}

// ============================================================================ Installées

@Composable
private fun InstalledTab(
    installed: List<InstalledTachiExtension>,
    available: List<AvailableExtension>,
    installing: Map<String, Boolean>,
    onUpdate: (AvailableExtension) -> Unit,
    onUninstall: (InstalledTachiExtension) -> Unit,
) {
    if (installed.isEmpty()) {
        EmptyText("Aucune extension installée. Ajoute un dépôt, puis installe des extensions depuis l'onglet « Disponibles ».")
        return
    }
    LazyColumn {
        items(installed, key = { it.pkgName }) { ext ->
            val update = available.firstOrNull { it.pkgName == ext.pkgName && it.versionCode > ext.versionCode }
            ListItem(
                headlineContent = { Text(ext.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Column {
                        Text(
                            listOfNotNull(
                                "v${ext.versionName}",
                                langName(ext.lang).takeIf { ext.lang.isNotBlank() },
                                "18+".takeIf { ext.nsfw },
                                "${ext.sources.size} source(s)".takeIf { ext.sources.size > 1 },
                            ).joinToString(" • "),
                        )
                        ext.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                leadingContent = { ExtIcon(available.firstOrNull { it.pkgName == ext.pkgName }?.iconUrl) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when {
                            installing[ext.pkgName] == true -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            update != null -> Button(onClick = { onUpdate(update) }) { Text("Mettre à jour") }
                        }
                        IconButton(onClick = { onUninstall(ext) }) { Icon(Icons.Default.Delete, contentDescription = "Désinstaller") }
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

// ============================================================================ Disponibles

@Composable
private fun AvailableTab(
    available: List<AvailableExtension>?,
    installed: List<InstalledTachiExtension>,
    loading: Boolean,
    errors: List<String>,
    installing: Map<String, Boolean>,
    hasRepos: Boolean,
    onInstall: (AvailableExtension) -> Unit,
    onGoToRepos: () -> Unit,
) {
    if (!hasRepos) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Aucun dépôt. Ajoute l'adresse d'un dépôt (lien index.min.json) pour voir ses extensions.")
            Button(onClick = onGoToRepos) { Text("Ajouter un dépôt") }
        }
        return
    }
    if (available == null || (loading && available.isEmpty())) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    var query by rememberSaveable { mutableStateOf("") }
    var lang by rememberSaveable { mutableStateOf("") }
    var showNsfw by rememberSaveable { mutableStateOf(false) }
    var langMenu by remember { mutableStateOf(false) }
    val langs = remember(available) { available.map { it.lang }.distinct().sorted() }
    val filtered = remember(available, query, lang, showNsfw) {
        available.filter { e ->
            (showNsfw || !e.nsfw) &&
                (lang.isEmpty() || e.lang == lang) &&
                (query.isBlank() || e.name.contains(query, true) || e.sourceNames.any { it.contains(query, true) })
        }
    }

    LazyColumn {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Chercher une extension") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Row(
                Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
                    FilterChip(
                        selected = lang.isNotEmpty(),
                        onClick = { langMenu = true },
                        label = { Text(if (lang.isEmpty()) "Toutes les langues" else langName(lang)) },
                    )
                    DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
                        DropdownMenuItem(text = { Text("Toutes les langues") }, onClick = { lang = ""; langMenu = false })
                        langs.forEach { l ->
                            DropdownMenuItem(text = { Text(langName(l)) }, onClick = { lang = l; langMenu = false })
                        }
                    }
                }
                FilterChip(selected = showNsfw, onClick = { showNsfw = !showNsfw }, label = { Text("18+") })
            }
            errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            Text(
                "${filtered.size} extension(s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        items(filtered, key = { it.repo.baseUrl + "/" + it.pkgName }) { ext ->
            val current = installed.firstOrNull { it.pkgName == ext.pkgName }
            val compatible = ext.libVersion != null && ext.libVersion in TachiExtensions.SUPPORTED_LIB_VERSIONS
            ListItem(
                headlineContent = { Text(ext.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        listOfNotNull("v${ext.versionName}", langName(ext.lang), "18+".takeIf { ext.nsfw }, ext.repo.name)
                            .joinToString(" • "),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = { ExtIcon(ext.iconUrl) },
                trailingContent = {
                    when {
                        installing[ext.pkgName] == true -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        !compatible -> Text("Non compatible", style = MaterialTheme.typography.bodySmall)
                        current == null -> Button(onClick = { onInstall(ext) }) { Text("Installer") }
                        ext.versionCode > current.versionCode -> Button(onClick = { onInstall(ext) }) { Text("Mettre à jour") }
                        else -> OutlinedButton(onClick = {}, enabled = false) { Text("Installée") }
                    }
                },
            )
        }
    }
}

// ============================================================================ Dépôts

@Composable
private fun ReposTab(onAdded: () -> Unit, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val repos by TachiExtensions.repos.collectAsState()
    var url by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var toRemove by remember { mutableStateOf<String?>(null) }

    fun add() {
        if (url.isBlank() || adding) return
        adding = true
        scope.launch {
            try {
                val repo = TachiExtensions.addRepo(url)
                url = ""
                onMessage("Dépôt « ${repo.name} » ajouté.")
                onAdded()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                onMessage("Impossible d'ajouter le dépôt : ${e.message ?: e}")
            } finally {
                adding = false
            }
        }
    }

    LazyColumn {
        item {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Adresse du dépôt (…/index.min.json)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { add() }, enabled = url.isNotBlank() && !adding, modifier = Modifier.fillMaxWidth()) {
                    if (adding) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Ajouter le dépôt")
                }
                Text(
                    "Les extensions signées par la clé d'un dépôt ajouté sont approuvées automatiquement. " +
                        "N'ajoute que des dépôts en qui tu as confiance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
        items(repos, key = { it.baseUrl }) { repo ->
            ListItem(
                headlineContent = { Text(repo.name) },
                supportingContent = {
                    Text(
                        repo.baseUrl + (repo.fingerprint?.let { "\nClé : ${it.take(16)}…" } ?: "\nAucune clé publiée"),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    IconButton(onClick = { toRemove = repo.baseUrl }) { Icon(Icons.Default.Delete, contentDescription = "Retirer") }
                },
            )
            HorizontalDivider()
        }
    }

    toRemove?.let { base ->
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text("Retirer ce dépôt ?") },
            text = { Text("Ses extensions déjà installées restent, mais celles approuvées grâce à sa clé ne se chargeront plus.") },
            confirmButton = {
                TextButton(onClick = { TachiExtensions.removeRepo(base); toRemove = null }) { Text("Retirer") }
            },
            dismissButton = { TextButton(onClick = { toRemove = null }) { Text("Annuler") } },
        )
    }
}

// ============================================================================ commun

@Composable
private fun ExtIcon(url: String?) {
    AsyncImage(
        model = url,
        contentDescription = null,
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp)),
    )
}

@Composable
private fun EmptyText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
}
