package io.github.gooseandroid.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persistent settings storage using DataStore.
 * All settings survive app restart and navigation.
 */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "goose_settings")

object SettingsKeys {
    // API Keys
    val ANTHROPIC_API_KEY = stringPreferencesKey("anthropic_api_key")
    val OPENAI_API_KEY = stringPreferencesKey("openai_api_key")
    val GOOGLE_API_KEY = stringPreferencesKey("google_api_key")
    val OPENROUTER_API_KEY = stringPreferencesKey("openrouter_api_key")

    // Provider config
    val ACTIVE_PROVIDER = stringPreferencesKey("active_provider")
    val ACTIVE_MODEL = stringPreferencesKey("active_model")
    val LOCAL_MODEL_ID = stringPreferencesKey("local_model_id")

    // Appearance
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val PRIMARY_COLOR = intPreferencesKey("primary_color")
    val SECONDARY_COLOR = intPreferencesKey("secondary_color")
    val ACCENT_COLOR = intPreferencesKey("accent_color")
    val TEXT_SCALE = floatPreferencesKey("text_scale")
    val PANEL_SIDE = stringPreferencesKey("panel_side")

    // Extensions
    val EXTENSION_DEVELOPER = booleanPreferencesKey("ext_developer")
    val EXTENSION_MEMORY = booleanPreferencesKey("ext_memory")
}

class SettingsStore(private val context: Context) {

    private val dataStore = context.settingsDataStore

    // === Read ===

    fun getString(key: Preferences.Key<String>, default: String = ""): Flow<String> =
        dataStore.data.map { it[key] ?: default }

    fun getInt(key: Preferences.Key<Int>, default: Int = 0): Flow<Int> =
        dataStore.data.map { it[key] ?: default }

    fun getFloat(key: Preferences.Key<Float>, default: Float = 1.0f): Flow<Float> =
        dataStore.data.map { it[key] ?: default }

    fun getBoolean(key: Preferences.Key<Boolean>, default: Boolean = false): Flow<Boolean> =
        dataStore.data.map { it[key] ?: default }

    // === Write ===

    suspend fun setString(key: Preferences.Key<String>, value: String) {
        dataStore.edit { it[key] = value }
    }

    suspend fun setInt(key: Preferences.Key<Int>, value: Int) {
        dataStore.edit { it[key] = value }
    }

    suspend fun setFloat(key: Preferences.Key<Float>, value: Float) {
        dataStore.edit { it[key] = value }
    }

    suspend fun setBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { it[key] = value }
    }

    // === Convenience ===

    suspend fun setApiKey(provider: String, key: String) {
        val prefKey = when (provider.lowercase()) {
            "anthropic" -> SettingsKeys.ANTHROPIC_API_KEY
            "openai" -> SettingsKeys.OPENAI_API_KEY
            "google" -> SettingsKeys.GOOGLE_API_KEY
            "openrouter" -> SettingsKeys.OPENROUTER_API_KEY
            else -> return
        }
        dataStore.edit { it[prefKey] = key }
    }

    fun getApiKey(provider: String): Flow<String> {
        val prefKey = when (provider.lowercase()) {
            "anthropic" -> SettingsKeys.ANTHROPIC_API_KEY
            "openai" -> SettingsKeys.OPENAI_API_KEY
            "google" -> SettingsKeys.GOOGLE_API_KEY
            "openrouter" -> SettingsKeys.OPENROUTER_API_KEY
            else -> return kotlinx.coroutines.flow.flowOf("")
        }
        return dataStore.data.map { it[prefKey] ?: "" }
    }

    suspend fun setActiveModel(modelId: String) {
        dataStore.edit { it[SettingsKeys.ACTIVE_MODEL] = modelId }
    }

    fun getActiveModel(): Flow<String> =
        dataStore.data.map { it[SettingsKeys.ACTIVE_MODEL] ?: "" }

    suspend fun setLocalModelId(modelId: String) {
        dataStore.edit { it[SettingsKeys.LOCAL_MODEL_ID] = modelId }
    }

    fun getLocalModelId(): Flow<String> =
        dataStore.data.map { it[SettingsKeys.LOCAL_MODEL_ID] ?: "" }
}
