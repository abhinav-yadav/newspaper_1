package com.epaper.reader.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ReaderLayout { VERTICAL, HORIZONTAL }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val readerLayout: ReaderLayout = ReaderLayout.VERTICAL,
    val readerDark: Boolean = false,
    val fitPage: Boolean = false,
    val keepScreenOn: Boolean = true,
    val autoSync: Boolean = true,
    /** Download new papers without being asked. */
    val autoDownload: Boolean = true,
    /** Publications the user paused for automatic downloads. */
    val excludedTitles: Set<String> = emptySet(),
)

/** Tiny SharedPreferences-backed settings store; values are exposed as a StateFlow. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        prefs.edit()
            .putString("theme", next.theme.name)
            .putBoolean("dynamicColor", next.dynamicColor)
            .putString("readerLayout", next.readerLayout.name)
            .putBoolean("readerDark", next.readerDark)
            .putBoolean("fitPage", next.fitPage)
            .putBoolean("keepScreenOn", next.keepScreenOn)
            .putBoolean("autoSync", next.autoSync)
            .putBoolean("autoDownload", next.autoDownload)
            .putStringSet("excludedTitles", next.excludedTitles)
            .apply()
        _state.value = next
    }

    private fun read(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            theme = enumOr(prefs.getString("theme", null), d.theme),
            dynamicColor = prefs.getBoolean("dynamicColor", d.dynamicColor),
            readerLayout = enumOr(prefs.getString("readerLayout", null), d.readerLayout),
            readerDark = prefs.getBoolean("readerDark", d.readerDark),
            fitPage = prefs.getBoolean("fitPage", d.fitPage),
            keepScreenOn = prefs.getBoolean("keepScreenOn", d.keepScreenOn),
            autoSync = prefs.getBoolean("autoSync", d.autoSync),
            autoDownload = prefs.getBoolean("autoDownload", d.autoDownload),
            excludedTitles = prefs.getStringSet("excludedTitles", null)?.toSet() ?: d.excludedTitles,
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default
}
