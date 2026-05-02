package io.github.gooseandroid.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "goose_settings")

/**
 * All settings keys used across the app.
 */
object SettingsKeys {
    // Provider API keys
    const val ANTHROPIC_API_KEY = "anthropic_api_key"
    const val OPENAI_API_KEY = "openai_api_key"
    const val GOOGLE_API_KEY = "google_api_key"
    const val MISTRAL_API_KEY = "mistral_api_key"
    const val OPENROUTER_API_KEY = "openrouter_api_key"

    // Custom provider
    const val CUSTOM_PROVIDER_URL = "custom_provider_url"
    const val CUSTOM_PROVIDER_KEY = "custom_provider_key"
    const val CUSTOM_PROVIDER_MODEL = "custom_provider_model"

    // Ollama
    const val OLLAMA_BASE_URL = "ollama_base_url"

    // Active provider/model selection
    const val ACTIVE_PROVIDER = "active_provider"
    const val ACTIVE_MODEL = "active_model"

    // Local model
    const val LOCAL_MODEL_ID = "local_model_id"

    // Appearance
    const val THEME_MODE = "theme_mode"
    const val PRIMARY_COLOR = "primary_color"
    const val SECONDARY_COLOR = "secondary_color"
    const val ACCENT_COLOR = "accent_color"
    const val TEXT_SCALE = "text_scale"
    const val PANEL_SIDE = "panel_side"

    // Extensions
    const val EXTENSION_DEVELOPER = "ext_developer"
    const val EXTENSION_MEMORY = "ext_memory"

    // General
    const val AUTO_COMPACT = "auto_compact"
    const val WORKING_DIRECTORY = "working_directory"
    const val SHELL_PATH = "shell_path"
}

/**
 * Persistent settings storage backed by DataStore.
 */
class SettingsStore(private val context: Context) {

    // String
    fun getString(key: String, default: String = ""): Flow<String> {
        val prefKey = stringPreferencesKey(key)
        return context.dataStore.data.map { prefs -> prefs[prefKey] ?: default }
    }

    suspend fun setString(key: String, value: String) {
        val prefKey = stringPreferencesKey(key)
        context.dataStore.edit { prefs -> prefs[prefKey] = value }
    }

    // Int
    fun getInt(key: String, default: Int = 0): Flow<Int> {
        val prefKey = intPreferencesKey(key)
        return context.dataStore.data.map { prefs -> prefs[prefKey] ?: default }
    }

    suspend fun setInt(key: String, value: Int) {
        val prefKey = intPreferencesKey(key)
        context.dataStore.edit { prefs -> prefs[prefKey] = value }
    }

    // Float
    fun getFloat(key: String, default: Float = 0f): Flow<Float> {
        val prefKey = floatPreferencesKey(key)
        return context.dataStore.data.map { prefs -> prefs[prefKey] ?: default }
    }

    suspend fun setFloat(key: String, value: Float) {
        val prefKey = floatPreferencesKey(key)
        context.dataStore.edit { prefs -> prefs[prefKey] = value }
    }

    // Boolean
    fun getBoolean(key: String, default: Boolean = false): Flow<Boolean> {
        val prefKey = booleanPreferencesKey(key)
        return context.dataStore.data.map { prefs -> prefs[prefKey] ?: default }
    }

    suspend fun setBoolean(key: String, value: Boolean) {
        val prefKey = booleanPreferencesKey(key)
        context.dataStore.edit { prefs -> prefs[prefKey] = value }
    }

    // Convenience: get local model ID
    fun getLocalModelId(): Flow<String> = getString(SettingsKeys.LOCAL_MODEL_ID)

    // Convenience: get active provider
    fun getActiveProvider(): Flow<String> = getString(SettingsKeys.ACTIVE_PROVIDER, "anthropic")

    // Convenience: get active model
    fun getActiveModel(): Flow<String> = getString(SettingsKeys.ACTIVE_MODEL, "claude-sonnet-4-20250514")
}
