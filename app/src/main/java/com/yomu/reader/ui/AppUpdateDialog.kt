package com.yomu.reader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yomu.reader.BuildConfig
import com.yomu.reader.data.AppRelease
import com.yomu.reader.data.AppUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun AppUpdateDialog(release: AppRelease, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("Nouvelle version disponible") },
        text = {
            Column {
                Text("Version installée : ${BuildConfig.VERSION_NAME}\nNouvelle version : ${release.versionName}")
                if (downloading) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                if (apk != null && !downloading) {
                    Spacer(Modifier.height(12.dp))
                    Text("Si Android vient d'ouvrir les réglages, autorise Yomu à installer des applis puis appuie sur « Installer ».")
                }
                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text("Erreur : $it")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    val ready = apk
                    if (ready != null) {
                        AppUpdater.install(context, ready)
                    } else scope.launch {
                        downloading = true
                        error = null
                        try {
                            val file = AppUpdater.download(context, release) { progress = it }
                            apk = file
                            AppUpdater.install(context, file)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            error = e.message ?: e.toString()
                        } finally {
                            downloading = false
                        }
                    }
                },
            ) { Text(if (apk != null) "Installer" else "Mettre à jour") }
        },
        dismissButton = {
            TextButton(enabled = !downloading, onClick = onDismiss) { Text("Plus tard") }
        },
    )
}
