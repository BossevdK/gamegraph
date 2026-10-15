package nl.gamegraph.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

enum class ThemeMode(val label: String) { SYSTEM("Systeem"), DARK("Donker"), LIGHT("Licht") }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val includePrereleases: Boolean = false,
    val autoCheckUpdates: Boolean = true,
    /** Leersignalen van vóór dit moment tellen niet meer mee in het profiel (na "profiel opnieuw instellen"). */
    val profileResetAt: Long = 0L,
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "gamegraph_settings",
    // Beschadigde voorkeuren: val terug op de standaardwaarden in plaats van te crashen.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class SettingsStore(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val prereleases = booleanPreferencesKey("include_prereleases")
        val autoCheck = booleanPreferencesKey("auto_check_updates")
        val resetAt = longPreferencesKey("profile_reset_at")
    }

    val settings: Flow<AppSettings> = store.data
        .catch { emit(emptyPreferences()) }
        .map { p ->
            AppSettings(
                theme = p[Keys.theme]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.DARK,
                includePrereleases = p[Keys.prereleases] ?: false,
                autoCheckUpdates = p[Keys.autoCheck] ?: true,
                profileResetAt = p[Keys.resetAt] ?: 0L,
            )
        }

    suspend fun setTheme(mode: ThemeMode) = store.edit { it[Keys.theme] = mode.name }
    suspend fun setIncludePrereleases(on: Boolean) = store.edit { it[Keys.prereleases] = on }
    suspend fun setAutoCheck(on: Boolean) = store.edit { it[Keys.autoCheck] = on }
    suspend fun setProfileResetAt(time: Long) = store.edit { it[Keys.resetAt] = time }
}
