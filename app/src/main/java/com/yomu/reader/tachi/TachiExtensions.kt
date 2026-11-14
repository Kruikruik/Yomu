package com.yomu.reader.tachi

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.yomu.reader.data.CrashLog
import com.yomu.reader.net.Http
import com.yomu.reader.source.Source
import com.yomu.reader.source.inBackground
import dalvik.system.DelegateLastClassLoader
import dalvik.system.PathClassLoader
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import eu.kanade.tachiyomi.source.Source as TachiApiSource

/** Un dépôt ajouté par l'utilisateur (format « index.min.json » + « repo.json » de Tachiyomi/Mihon). */
data class TachiRepo(
    val baseUrl: String,
    val name: String,
    val website: String?,
    /** Empreinte SHA-256 de la clé qui signe les extensions du dépôt (null si le dépôt n'en publie pas). */
    val fingerprint: String?,
)

/** Une extension proposée par un dépôt. */
data class AvailableExtension(
    val name: String,
    val pkgName: String,
    val versionName: String,
    val versionCode: Long,
    val libVersion: Double?,
    val lang: String,
    val nsfw: Boolean,
    val apkUrl: String,
    val iconUrl: String,
    val repo: TachiRepo,
    val sourceNames: List<String>,
)

/** Une extension installée dans Yomu. */
data class InstalledTachiExtension(
    val pkgName: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val lang: String,
    val nsfw: Boolean,
    val signature: String?,
    val sources: List<TachiSource>,
    /** Raison pour laquelle l'extension ne fonctionne pas (null = chargée). */
    val error: String?,
)

/** Résultat d'une installation. */
sealed interface InstallResult {
    data object Done : InstallResult
    /** Signature inconnue : l'utilisateur doit l'approuver, puis relancer l'installation. */
    data class NeedsTrust(val pkgName: String, val signature: String) : InstallResult
    data class Failed(val message: String) : InstallResult
}

/**
 * Dépôts et extensions Tachiyomi/Mihon (APK) : lecture des dépôts, installation privée
 * dans le dossier de l'appli, contrôle de signature et chargement du code des extensions.
 */
object TachiExtensions {
    private const val EXTENSION_FEATURE = "tachiyomi.extension"
    private const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
    private const val METADATA_NSFW = "tachiyomi.extension.nsfw"
    private const val METADATA_NAME = "tachiyomix.name"
    private const val METADATA_EXTENSION_LIB = "tachiyomix.extensionLib"
    private const val METADATA_CONTENT_WARNING = "tachiyomix.contentWarning"

    /** Versions de la bibliothèque d'extensions gérées (les mêmes que Mihon 0.21). */
    val SUPPORTED_LIB_VERSIONS = listOf(1.4, 1.6)

    @Suppress("DEPRECATION")
    private val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or
        PackageManager.GET_META_DATA or
        PackageManager.GET_SIGNATURES or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)

    private lateinit var app: Context
    private lateinit var extDir: File
    private lateinit var stateFile: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val loaded = CountDownLatch(1)

    private val _repos = MutableStateFlow<List<TachiRepo>>(emptyList())
    val repos: StateFlow<List<TachiRepo>> = _repos.asStateFlow()

    private val _installed = MutableStateFlow<List<InstalledTachiExtension>>(emptyList())
    val installed: StateFlow<List<InstalledTachiExtension>> = _installed.asStateFlow()

    /** Signatures approuvées à la main, sous la forme « paquet:empreinte ». */
    private var trusted: Set<String> = emptySet()

    fun init(context: Context) {
        app = context.applicationContext
        extDir = File(app.filesDir, "tachi-exts").apply { mkdirs() }
        stateFile = File(app.filesDir, "tachi-state.json")
        readState()
        scope.launch {
            try {
                reloadInstalled()
            } finally {
                loaded.countDown()
            }
        }
    }

    // =================================================================== sources

    /** Sources de toutes les extensions chargées. */
    fun sources(): List<Source> = _installed.value.flatMap { ext -> ext.sources.map { it.inBackground() } }

    fun sourceById(id: String): Source? = tachiSource(id)?.inBackground()

    /** Attend (3 s max) le chargement initial, pour que la bibliothèque retrouve ses sources au démarrage. */
    fun tachiSource(id: String): TachiSource? {
        if (!id.startsWith("tachi-")) return null
        if (loaded.count > 0) loaded.await(3, TimeUnit.SECONDS)
        return _installed.value.firstNotNullOfOrNull { ext -> ext.sources.firstOrNull { it.id == id } }
    }

    // =================================================================== dépôts

    /**
     * Ajoute un dépôt. Accepte l'adresse de « index.min.json », de « repo.json » ou du dossier.
     * Lève une exception lisible si le dépôt est introuvable ou invalide.
     */
    suspend fun addRepo(input: String): TachiRepo = withContext(Dispatchers.IO) {
        val base = normalizeRepoUrl(input) ?: throw IOException("Adresse invalide : colle le lien qui finit par index.min.json")
        // Vérifie que l'index existe et se lit.
        val index = fetchText("$base/index.min.json")
        runCatching { JSONArray(index) }.getOrElse { throw IOException("index.min.json n'est pas un index d'extensions valide") }
        val meta = runCatching { JSONObject(fetchText("$base/repo.json")).optJSONObject("meta") }.getOrNull()
        val repo = TachiRepo(
            baseUrl = base,
            name = meta?.optString("name")?.ifBlank { null } ?: base.toHttpUrlOrNull()?.host ?: base,
            website = meta?.optString("website")?.ifBlank { null },
            fingerprint = meta?.optString("signingKeyFingerprint")?.ifBlank { null }?.lowercase(),
        )
        _repos.update { list -> list.filterNot { it.baseUrl == repo.baseUrl } + repo }
        writeState()
        repo
    }

    fun removeRepo(baseUrl: String) {
        _repos.update { list -> list.filterNot { it.baseUrl == baseUrl } }
        writeState()
    }

    /** Liste les extensions de tous les dépôts. Les dépôts en erreur sont renvoyés à part. */
    suspend fun fetchAvailable(): Pair<List<AvailableExtension>, List<String>> = withContext(Dispatchers.IO) {
        val errors = ArrayList<String>()
        val all = _repos.value.flatMap { repo ->
            try {
                parseIndex(repo, fetchText("${repo.baseUrl}/index.min.json"))
            } catch (e: Exception) {
                errors += "${repo.name} : ${e.message ?: e.toString()}"
                emptyList()
            }
        }
        all.sortedWith(compareBy({ it.lang }, { it.name.lowercase() })) to errors
    }

    private fun parseIndex(repo: TachiRepo, text: String): List<AvailableExtension> {
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val pkg = o.optString("pkg").ifBlank { return@mapNotNull null }
            val apk = o.optString("apk").ifBlank { return@mapNotNull null }
            val version = o.optString("version")
            val sources = o.optJSONArray("sources")
            AvailableExtension(
                name = o.optString("name").substringAfter("Tachiyomi: "),
                pkgName = pkg,
                versionName = version,
                versionCode = o.optLong("code"),
                libVersion = version.substringBeforeLast('.').toDoubleOrNull(),
                lang = o.optString("lang"),
                nsfw = o.optInt("nsfw") == 1,
                apkUrl = "${repo.baseUrl}/apk/$apk",
                iconUrl = "${repo.baseUrl}/icon/$pkg.png",
                repo = repo,
                sourceNames = if (sources == null) emptyList() else (0 until sources.length()).mapNotNull {
                    sources.optJSONObject(it)?.optString("name")
                }.distinct(),
            )
        }
    }

    private fun normalizeRepoUrl(input: String): String? {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty()) return null
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        s = s.removeSuffix("/index.min.json").removeSuffix("/index.json").removeSuffix("/repo.json")
        val url = s.toHttpUrlOrNull() ?: return null
        return url.toString().trimEnd('/')
    }

    private fun fetchText(url: String): String {
        val request = Request.Builder().url(url).build()
        Http.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} sur $url")
            return resp.body.string()
        }
    }

    // =================================================================== installation

    /** Télécharge, vérifie et installe (ou met à jour) une extension. */
    suspend fun install(ext: AvailableExtension): InstallResult = withContext(Dispatchers.IO) {
        val tmp = File(app.cacheDir, "tachi-${ext.pkgName}.apk")
        try {
            val request = Request.Builder().url(ext.apkUrl).build()
            Http.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext InstallResult.Failed("Téléchargement impossible (HTTP ${resp.code})")
                tmp.outputStream().use { out -> resp.body.byteStream().copyTo(out) }
            }
            installFile(tmp, ext.repo.fingerprint)
        } catch (e: Exception) {
            CrashLog.record("TachiExtensions.install", e)
            InstallResult.Failed(e.message ?: e.toString())
        } finally {
            tmp.delete()
        }
    }

    private fun installFile(file: File, repoFingerprint: String?): InstallResult {
        val info = app.packageManager.getPackageArchiveInfo(file.absolutePath, PACKAGE_FLAGS)
            ?: return InstallResult.Failed("Fichier APK illisible")
        if (!isExtension(info)) return InstallResult.Failed("Ce fichier n'est pas une extension Tachiyomi/Mihon")
        val pkg = info.packageName
        val signatures = signaturesOf(info)
        val signature = signatures.lastOrNull() ?: return InstallResult.Failed("Extension non signée : installation refusée")

        val current = _installed.value.firstOrNull { it.pkgName == pkg }
        if (current != null) {
            if (PackageInfoCompat.getLongVersionCode(info) < current.versionCode) {
                return InstallResult.Failed("La version installée est plus récente")
            }
            if (current.signature != null && current.signature !in signatures) {
                return InstallResult.Failed("Signée avec une autre clé que la version installée : refusé")
            }
        }
        if (!isTrusted(pkg, signatures, repoFingerprint)) return InstallResult.NeedsTrust(pkg, signature)

        synchronized(lock) {
            val target = File(extDir, "$pkg.ext")
            val staged = File(extDir, "$pkg.ext.part")
            staged.delete()
            file.copyTo(staged, overwrite = true)
            // Android 14+ refuse de charger du code depuis un fichier modifiable.
            staged.setReadOnly()
            if (target.exists()) {
                target.setWritable(true)
                target.delete()
            }
            if (!staged.renameTo(target)) {
                staged.delete()
                return InstallResult.Failed("Impossible d'enregistrer l'extension")
            }
        }
        val loadedExt = loadFile(File(extDir, "$pkg.ext"))
        _installed.update { list -> (list.filterNot { it.pkgName == pkg } + loadedExt).sortedBy { it.name.lowercase() } }
        return if (loadedExt.error == null) InstallResult.Done else InstallResult.Failed(loadedExt.error)
    }

    /** L'utilisateur approuve la signature d'une extension (à refaire si la clé change). */
    fun trust(pkgName: String, signature: String) {
        trusted = trusted + "$pkgName:$signature"
        writeState()
    }

    fun uninstall(pkgName: String) {
        synchronized(lock) {
            File(extDir, "$pkgName.ext").apply { setWritable(true) }.delete()
            trusted = trusted.filterNot { it.startsWith("$pkgName:") }.toSet()
        }
        writeState()
        _installed.update { list -> list.filterNot { it.pkgName == pkgName } }
    }

    // =================================================================== chargement

    private fun reloadInstalled() {
        val files = extDir.listFiles { f -> f.isFile && f.extension == "ext" }.orEmpty()
        val list = files.map { f ->
            if (f.canWrite()) f.setReadOnly()
            loadFile(f)
        }
        _installed.value = list.sortedBy { it.name.lowercase() }
    }

    private fun loadFile(file: File): InstalledTachiExtension {
        val path = file.absolutePath
        val info = app.packageManager.getPackageArchiveInfo(path, PACKAGE_FLAGS)
        val appInfo = info?.applicationInfo
        val meta = appInfo?.metaData
        val pkg = info?.packageName ?: file.nameWithoutExtension
        appInfo?.apply {
            // Sur Android 13+, getPackageArchiveInfo ne remplit pas ces chemins.
            if (sourceDir == null) sourceDir = path
            if (publicSourceDir == null) publicSourceDir = path
        }
        val name = meta?.getString(METADATA_NAME)
            ?: appInfo?.let { app.packageManager.getApplicationLabel(it).toString().substringAfter("Tachiyomi: ") }
            ?: pkg
        val versionName = info?.versionName.orEmpty()
        val versionCode = info?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L
        val signatures = info?.let(::signaturesOf).orEmpty()
        val nsfw = meta != null && (meta.getInt(METADATA_NSFW) == 1 || meta.getInt(METADATA_CONTENT_WARNING) == 2)

        fun failed(reason: String) = InstalledTachiExtension(
            pkgName = pkg, name = name, versionName = versionName, versionCode = versionCode, lang = "",
            nsfw = nsfw, signature = signatures.lastOrNull(), sources = emptyList(), error = reason,
        )

        if (info == null || appInfo == null || meta == null) return failed("Fichier d'extension illisible")
        if (!isExtension(info)) return failed("Ce n'est pas une extension")
        val libVersion = meta.getFloat(METADATA_EXTENSION_LIB).takeUnless { it == 0f }?.toString()?.toDouble()
            ?: versionName.substringBeforeLast('.').toDoubleOrNull()
        if (libVersion == null || libVersion !in SUPPORTED_LIB_VERSIONS) {
            return failed("Version de bibliothèque $libVersion non gérée (gérées : ${SUPPORTED_LIB_VERSIONS.joinToString()})")
        }
        if (signatures.isEmpty()) return failed("Extension non signée")
        if (!isTrusted(pkg, signatures, null)) return failed("Signature non approuvée : réinstalle-la depuis son dépôt")

        val classLoader = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                DelegateLastClassLoader(appInfo.sourceDir, null, app.classLoader)
            } else {
                PathClassLoader(appInfo.sourceDir, null, app.classLoader)
            }
        } catch (e: Throwable) {
            return failed("Chargement impossible : ${e.message}")
        }

        val classNames = meta.getString(METADATA_SOURCE_CLASS)?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.map { if (it.startsWith(".")) pkg + it else it }
        if (classNames.isNullOrEmpty()) return failed("Aucune classe de source déclarée")

        val tachiSources = try {
            classNames.flatMap { className ->
                when (val obj = Class.forName(className, false, classLoader).getDeclaredConstructor().newInstance()) {
                    is TachiApiSource -> listOf(obj)
                    is SourceFactory -> obj.createSources()
                    else -> throw IllegalStateException("Type de classe inconnu : ${obj.javaClass.name}")
                }
            }
        } catch (e: Throwable) {
            CrashLog.record("TachiExtensions.load $pkg", e)
            val root = generateSequence(e) { it.cause }.last()
            return failed("Erreur au chargement : ${root::class.simpleName}: ${root.message}")
        }

        val catalogues = tachiSources.filterIsInstance<CatalogueSource>().map { TachiSource(it, pkg) }
        val langs = catalogues.map { it.lang }.toSet()
        return InstalledTachiExtension(
            pkgName = pkg,
            name = name,
            versionName = versionName,
            versionCode = versionCode,
            lang = when (langs.size) { 0 -> ""; 1 -> langs.first(); else -> "all" },
            nsfw = nsfw,
            signature = signatures.last(),
            sources = catalogues,
            error = if (catalogues.isEmpty()) "Aucune source de lecture dans cette extension" else null,
        )
    }

    // =================================================================== signatures

    private fun isExtension(info: PackageInfo): Boolean = info.reqFeatures.orEmpty().any { it.name == EXTENSION_FEATURE }

    private fun isTrusted(pkg: String, signatures: List<String>, repoFingerprint: String?): Boolean {
        val repoKeys = _repos.value.mapNotNull { it.fingerprint } + listOfNotNull(repoFingerprint)
        return signatures.any { it in repoKeys || "$pkg:$it" in trusted }
    }

    private fun signaturesOf(info: PackageInfo): List<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo
            when {
                signingInfo == null -> null
                signingInfo.hasMultipleSigners() -> signingInfo.apkContentsSigners
                else -> signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return sigs.orEmpty().map { sha256(it.toByteArray()) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // =================================================================== état

    private fun readState() {
        runCatching {
            if (!stateFile.exists()) return
            val o = JSONObject(stateFile.readText())
            val arr = o.optJSONArray("repos") ?: JSONArray()
            _repos.value = (0 until arr.length()).mapNotNull { i ->
                val r = arr.optJSONObject(i) ?: return@mapNotNull null
                TachiRepo(
                    baseUrl = r.optString("baseUrl"),
                    name = r.optString("name"),
                    website = r.optString("website").ifBlank { null },
                    fingerprint = r.optString("fingerprint").ifBlank { null },
                )
            }.filter { it.baseUrl.isNotBlank() }
            val t = o.optJSONArray("trusted") ?: JSONArray()
            trusted = (0 until t.length()).map { t.optString(it) }.toSet()
        }.onFailure { CrashLog.record("TachiExtensions.readState", it) }
    }

    private fun writeState() {
        val o = JSONObject()
            .put("repos", JSONArray().apply {
                _repos.value.forEach { r ->
                    put(
                        JSONObject()
                            .put("baseUrl", r.baseUrl)
                            .put("name", r.name)
                            .put("website", r.website ?: "")
                            .put("fingerprint", r.fingerprint ?: ""),
                    )
                }
            })
            .put("trusted", JSONArray(trusted.toList()))
        synchronized(lock) {
            val tmp = File(stateFile.path + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(stateFile)) {
                stateFile.writeText(o.toString())
                tmp.delete()
            }
        }
    }
}
