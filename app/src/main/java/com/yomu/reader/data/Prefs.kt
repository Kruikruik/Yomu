package com.yomu.reader.data

import android.content.Context
import android.content.SharedPreferences

enum class ReaderMode(val label: String) {
    WEBTOON("Webtoon (haut → bas)"),
    PAGED_RTL("Manga (droite → gauche)"),
    PAGED_LTR("Pages (gauche → droite)"),
}

enum class LibrarySort(val label: String) {
    TITLE("Titre"),
    UNREAD("Non lus"),
    LAST_READ("Dernière lecture"),
    ADDED("Date d'ajout"),
    RATING("Ma note"),
}

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("yomu", Context.MODE_PRIVATE)
        theme.value = sp.getString("theme", "dark") ?: "dark"
    }

    var defaultMode: ReaderMode
        get() = ReaderMode.entries.firstOrNull { it.name == sp.getString("mode", null) } ?: ReaderMode.WEBTOON
        set(value) { sp.edit().putString("mode", value.name).apply() }

    fun modeFor(key: String): ReaderMode =
        ReaderMode.entries.firstOrNull { it.name == sp.getString("mode_$key", null) } ?: defaultMode

    fun setModeFor(key: String, mode: ReaderMode) {
        sp.edit().putString("mode_$key", mode.name).apply()
    }

    var dataSaver: Boolean
        get() = sp.getBoolean("data_saver", false)
        set(value) { sp.edit().putBoolean("data_saver", value).apply() }

    var librarySort: LibrarySort
        get() = LibrarySort.entries.firstOrNull { it.name == sp.getString("lib_sort", null) } ?: LibrarySort.UNREAD
        set(value) { sp.edit().putString("lib_sort", value.name).apply() }

    var filterUnread: Boolean
        get() = sp.getBoolean("filter_unread", false)
        set(value) { sp.edit().putBoolean("filter_unread", value).apply() }

    var filterDownloaded: Boolean
        get() = sp.getBoolean("filter_downloaded", false)
        set(value) { sp.edit().putBoolean("filter_downloaded", value).apply() }

    /** Intervalle de vérification auto des nouveaux chapitres, en heures (0 = désactivé). */
    var updateInterval: Int
        get() = sp.getInt("update_interval", 12)
        set(value) { sp.edit().putInt("update_interval", value).apply() }

    /** Dossier choisi pour la source « Fichiers locaux » (URI SAF). */
    var localTreeUri: String?
        get() = sp.getString("local_tree", null)
        set(value) { sp.edit().putString("local_tree", value).apply() }

    var autoDownload: Boolean
        get() = sp.getBoolean("auto_download", false)
        set(value) { sp.edit().putBoolean("auto_download", value).apply() }

    var deleteAfterRead: Boolean
        get() = sp.getBoolean("delete_after_read", false)
        set(value) { sp.edit().putBoolean("delete_after_read", value).apply() }

    /** Assombrissement du lecteur, de 0 (aucun) à 0.8. */
    var readerDim: Float
        get() = sp.getFloat("reader_dim", 0f)
        set(value) { sp.edit().putFloat("reader_dim", value).apply() }

    /** Marges latérales en mode webtoon, en % de la largeur (0 à 25). */
    var webtoonMargin: Int
        get() = sp.getInt("webtoon_margin", 0)
        set(value) { sp.edit().putInt("webtoon_margin", value).apply() }

    /** Vitesse du défilement automatique en mode webtoon (1 à 10). */
    var autoScrollSpeed: Int
        get() = sp.getInt("auto_scroll_speed", 4)
        set(value) { sp.edit().putInt("auto_scroll_speed", value).apply() }

    /** Thème : "dark", "amoled" ou "light". */
    val theme = kotlinx.coroutines.flow.MutableStateFlow("dark")

    fun setTheme(value: String) {
        theme.value = value
        sp.edit().putString("theme", value).apply()
    }

    var volumeKeys: Boolean
        get() = sp.getBoolean("volume_keys", true)
        set(value) { sp.edit().putBoolean("volume_keys", value).apply() }

    /** Webtoon : passe tout seul au chapitre suivant en bas de page. */
    var continuousWebtoon: Boolean
        get() = sp.getBoolean("continuous_webtoon", true)
        set(value) { sp.edit().putBoolean("continuous_webtoon", value).apply() }

    var cropBorders: Boolean
        get() = sp.getBoolean("crop_borders", false)
        set(value) { sp.edit().putBoolean("crop_borders", value).apply() }

    var backupTreeUri: String?
        get() = sp.getString("backup_tree", null)
        set(value) { sp.edit().putString("backup_tree", value).apply() }

    var lastAppUpdateCheck: Long
        get() = sp.getLong("last_app_check", 0L)
        set(value) { sp.edit().putLong("last_app_check", value).apply() }
}
