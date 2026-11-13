package com.yomu.reader.data

import android.content.Context
import android.content.SharedPreferences

enum class ReaderMode(val label: String) {
    WEBTOON("Webtoon (défilement vertical)"),
    PAGED_RTL("Manga (droite → gauche)"),
    PAGED_LTR("Pages (gauche → droite)"),
}

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("yomu", Context.MODE_PRIVATE)
    }

    private fun parseMode(s: String?): ReaderMode? =
        ReaderMode.entries.firstOrNull { it.name == s }

    var defaultMode: ReaderMode
        get() = parseMode(sp.getString("mode", null)) ?: ReaderMode.WEBTOON
        set(value) { sp.edit().putString("mode", value.name).apply() }

    fun modeFor(key: String): ReaderMode = parseMode(sp.getString("mode_$key", null)) ?: defaultMode

    fun setModeFor(key: String, mode: ReaderMode) {
        sp.edit().putString("mode_$key", mode.name).apply()
    }

    var dataSaver: Boolean
        get() = sp.getBoolean("data_saver", false)
        set(value) { sp.edit().putBoolean("data_saver", value).apply() }
}
