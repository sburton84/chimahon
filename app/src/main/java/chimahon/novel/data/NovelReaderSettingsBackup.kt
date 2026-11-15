package chimahon.novel.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import kotlinx.coroutines.flow.first

/**
 * Carries the novel reader's DataStore ([novelReaderDataStore]: font, size,
 * spacing, theme, per-book namespaced overrides, ...) inside the app
 * preference list of a backup. That store lives outside the app
 * [tachiyomi.core.common.preference.PreferenceStore], so plain preference
 * backups never saw it.
 *
 * Entries ride under a reserved key prefix so restore can route them back to
 * the DataStore instead of SharedPreferences. The backup format has no double
 * type, so doubles travel as their exact string form under their own prefix.
 */
object NovelReaderSettingsBackup {

    private const val PREFIX = "novel_reader_settings::"
    private const val DOUBLE_PREFIX = "novel_reader_settings.double::"

    fun isReaderSettingKey(key: String): Boolean =
        key.startsWith(PREFIX) || key.startsWith(DOUBLE_PREFIX)

    suspend fun create(context: Context): List<BackupPreference> {
        val prefs = context.novelReaderDataStore.data.first()
        return prefs.asMap().mapNotNull { (key, value) ->
            val name = key.name
            @Suppress("UNCHECKED_CAST")
            when (value) {
                is Double -> BackupPreference(DOUBLE_PREFIX + name, StringPreferenceValue(value.toString()))
                is Int -> BackupPreference(PREFIX + name, IntPreferenceValue(value))
                is Long -> BackupPreference(PREFIX + name, LongPreferenceValue(value))
                is Float -> BackupPreference(PREFIX + name, FloatPreferenceValue(value))
                is String -> BackupPreference(PREFIX + name, StringPreferenceValue(value))
                is Boolean -> BackupPreference(PREFIX + name, BooleanPreferenceValue(value))
                is Set<*> -> BackupPreference(PREFIX + name, StringSetPreferenceValue(value as Set<String>))
                else -> null
            }
        }
    }

    /** Writes entries accepted by [isReaderSettingKey] back into the reader DataStore. */
    suspend fun restore(context: Context, preferences: List<BackupPreference>) {
        if (preferences.isEmpty()) return
        context.novelReaderDataStore.edit { prefs ->
            preferences.forEach { (key, value) ->
                if (key.startsWith(DOUBLE_PREFIX)) {
                    val number = (value as? StringPreferenceValue)?.value?.toDoubleOrNull() ?: return@forEach
                    prefs[doublePreferencesKey(key.removePrefix(DOUBLE_PREFIX))] = number
                    return@forEach
                }
                val name = key.removePrefix(PREFIX)
                when (value) {
                    is IntPreferenceValue -> prefs[intPreferencesKey(name)] = value.value
                    is LongPreferenceValue -> prefs[longPreferencesKey(name)] = value.value
                    is FloatPreferenceValue -> prefs[floatPreferencesKey(name)] = value.value
                    is StringPreferenceValue -> prefs[stringPreferencesKey(name)] = value.value
                    is BooleanPreferenceValue -> prefs[booleanPreferencesKey(name)] = value.value
                    is StringSetPreferenceValue -> prefs[stringSetPreferencesKey(name)] = value.value
                }
            }
        }
    }
}
