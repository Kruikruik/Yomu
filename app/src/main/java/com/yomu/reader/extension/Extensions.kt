package com.yomu.reader.extension

import android.content.Context
import android.net.Uri
import com.yomu.reader.data.CrashLog
import com.yomu.reader.source.Source
import com.yomu.reader.source.inBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Une extension installée par l'utilisateur. */
data class InstalledExtension(
    val config: ExtensionConfig,
    /** La source prête à l'emploi (exécutée hors du thread de l'interface). */
    val source: Source,
    val enabled: Boolean,
    val file: File,
)

/** Fichier refusé au chargement (JSON modifié à la main, schéma trop récent…). */
data class BrokenExtension(val fileName: String, val error: String)

sealed interface ImportResult {
    val name: String
    data class Installed(override val name: String) : ImportResult
    data class Updated(override val name: String, val from: Int, val to: Int) : ImportResult
    data class Failed(override val name: String, val error: String) : ImportResult
}

/**
 * Gestionnaire des extensions JSON locales.
 * Fichiers : filesDir/extensions/<id>.json ; état (activées / désactivées) : filesDir/extensions-state.json.
 *
 * Une extension désactivée disparaît d'Explorer et de la recherche globale, mais les séries
 * déjà dans la bibliothèque restent lisibles (sinon elles seraient « source introuvable »).
 */
object Extensions {
    private lateinit var dir: File
    private lateinit var stateFile: File
    private val lock = Any()

    private val _installed = MutableStateFlow<List<InstalledExtension>>(emptyList())
    val installed: StateFlow<List<InstalledExtension>> = _installed.asStateFlow()

    private val _broken = MutableStateFlow<List<BrokenExtension>>(emptyList())
    val broken: StateFlow<List<BrokenExtension>> = _broken.asStateFlow()

    fun init(context: Context) {
        dir = File(context.filesDir, "extensions").apply { mkdirs() }
        stateFile = File(context.filesDir, "extensions-state.json")
        reload()
    }

    /** Relit tous les fichiers du dossier. */
    fun reload() {
        val disabled = readDisabled()
        val ok = ArrayList<InstalledExtension>()
        val bad = ArrayList<BrokenExtension>()
        dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }.forEach { f ->
            try {
                val cfg = ExtensionConfig.parse(f.readText())
                if (ok.none { it.config.id == cfg.id }) ok += build(cfg, f, cfg.id !in disabled)
            } catch (e: Exception) {
                bad += BrokenExtension(f.name, e.message ?: e.toString())
                if (e !is ConfigException) CrashLog.record("Extensions.reload", e)
            }
        }
        _installed.value = ok.sortedBy { it.config.name.lowercase() }
        _broken.value = bad
    }

    fun enabledSources(): List<Source> = _installed.value.filter { it.enabled }.map { it.source }

    /** Toutes les extensions installées, même désactivées (pour la bibliothèque). */
    fun sourceById(sourceId: String): Source? = _installed.value.firstOrNull { it.source.id == sourceId }?.source

    // -------------------------------------------------------------- import

    /** Importe un texte JSON (un objet, ou un tableau de plusieurs extensions). */
    fun importText(text: String, fileName: String = "extension.json"): List<ImportResult> {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) {
            val arr = runCatching { JSONArray(trimmed) }.getOrElse {
                return listOf(ImportResult.Failed(fileName, "JSON invalide : ${it.message}"))
            }
            return (0 until arr.length()).map { i -> importOne(arr.optJSONObject(i)?.toString().orEmpty(), "$fileName #${i + 1}") }
        }
        return listOf(importOne(trimmed, fileName))
    }

    private fun importOne(text: String, fileName: String): ImportResult {
        val cfg = try {
            ExtensionConfig.parse(text)
        } catch (e: ConfigException) {
            return ImportResult.Failed(fileName, e.message.orEmpty())
        }
        val previous = _installed.value.firstOrNull { it.config.id == cfg.id }
        synchronized(lock) {
            val target = File(dir, "${cfg.id}.json")
            val tmp = File(dir, "${cfg.id}.json.tmp")
            tmp.writeText(JSONObject(text).toString(2))
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
        }
        reload()
        return if (previous != null) {
            ImportResult.Updated(cfg.name, previous.config.version, cfg.version)
        } else {
            ImportResult.Installed(cfg.name)
        }
    }

    /** Importe des fichiers choisis par l'utilisateur (sélecteur de fichiers Android). */
    suspend fun importUris(context: Context, uris: List<Uri>): List<ImportResult> = withContext(Dispatchers.IO) {
        uris.flatMap { uri ->
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "fichier"
            try {
                val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                    ?: return@flatMap listOf(ImportResult.Failed(name, "Fichier illisible"))
                if (text.length > 512 * 1024) return@flatMap listOf(ImportResult.Failed(name, "Fichier trop gros (512 Ko max)"))
                importText(text, name)
            } catch (e: Exception) {
                listOf(ImportResult.Failed(name, e.message ?: e.toString()))
            }
        }
    }

    // -------------------------------------------------------------- gestion

    fun setEnabled(extensionId: String, enabled: Boolean) {
        synchronized(lock) {
            val disabled = readDisabled().toMutableSet()
            if (enabled) disabled -= extensionId else disabled += extensionId
            writeDisabled(disabled)
        }
        _installed.value = _installed.value.map { if (it.config.id == extensionId) it.copy(enabled = enabled) else it }
    }

    fun delete(extensionId: String) {
        synchronized(lock) {
            _installed.value.firstOrNull { it.config.id == extensionId }?.file?.delete()
            writeDisabled(readDisabled() - extensionId)
        }
        reload()
    }

    fun deleteBroken(fileName: String) {
        File(dir, fileName).takeIf { it.parentFile == dir }?.delete()
        reload()
    }

    /** Contenu du fichier, pour le partager ou le sauvegarder. */
    fun exportText(extensionId: String): String? =
        _installed.value.firstOrNull { it.config.id == extensionId }?.file?.takeIf { it.exists() }?.readText()

    // -------------------------------------------------------------- interne

    private fun build(cfg: ExtensionConfig, file: File, enabled: Boolean) =
        InstalledExtension(cfg, ConfigSource(cfg).inBackground(), enabled, file)

    private fun readDisabled(): Set<String> = runCatching {
        val arr = JSONObject(stateFile.readText()).optJSONArray("disabled") ?: JSONArray()
        (0 until arr.length()).map { arr.optString(it) }.toSet()
    }.getOrDefault(emptySet())

    private fun writeDisabled(ids: Set<String>) {
        stateFile.writeText(JSONObject().put("disabled", JSONArray(ids.toList())).toString())
    }
}
