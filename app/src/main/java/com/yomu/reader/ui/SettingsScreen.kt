package com.yomu.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import com.yomu.reader.data.Prefs
import com.yomu.reader.data.ReaderMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoilApi::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(Prefs.defaultMode) }
    var saver by remember { mutableStateOf(Prefs.dataSaver) }
    var cacheMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Réglages") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SectionTitle("Mode de lecture par défaut")
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
                "Tu peux aussi changer de mode pour chaque série depuis le lecteur (icône ⚙).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            SectionTitle("Réseau")
            ListItem(
                headlineContent = { Text("Économiseur de données") },
                supportingContent = { Text("Images compressées, plus légères") },
                trailingContent = { Switch(checked = saver, onCheckedChange = null) },
                modifier = Modifier.clickable {
                    saver = !saver
                    Prefs.dataSaver = saver
                },
            )

            SectionTitle("Stockage")
            ListItem(
                headlineContent = { Text("Vider le cache des images") },
                supportingContent = { Text(cacheMessage ?: "Libère de la place sur le téléphone") },
                modifier = Modifier.clickable {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            context.imageLoader.diskCache?.clear()
                        }
                        context.imageLoader.memoryCache?.clear()
                        cacheMessage = "Cache vidé"
                    }
                },
            )

            SectionTitle("À propos")
            ListItem(
                headlineContent = { Text("Yomu 1.0") },
                supportingContent = { Text("Lecteur de mangas et webtoons inspiré de Tachiyomi.") },
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
