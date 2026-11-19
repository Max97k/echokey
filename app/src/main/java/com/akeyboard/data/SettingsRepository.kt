package com.akeyboard.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class UserSettings(
    val selectedModelId: String = SettingsRepository.DEFAULT_MODEL_ID,
    val languageCodes: List<String> = SettingsRepository.DEFAULT_LANGUAGE_CODES,
    val smartModeEnabled: Boolean = true,
    val showSwitchImeKey: Boolean = false,
    val touchpadStepXDp: Int = SettingsRepository.DEFAULT_STEP_X_DP,
    val touchpadStepYDp: Int = SettingsRepository.DEFAULT_STEP_Y_DP,
    val disclosureAccepted: Boolean = false,
    val lastRemoteDeviceAddress: String? = null,
    val lastRemoteDeviceName: String? = null,
    val remotePreferBuiltInMic: Boolean = true
)

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<UserSettings> = dataStore.data.map { prefs ->
        val rawLang = prefs[KEY_LANGUAGE_CODES]
        val langs = when {
            rawLang == null -> DEFAULT_LANGUAGE_CODES
            rawLang.isBlank() -> emptyList()
            else -> rawLang.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        UserSettings(
            selectedModelId = prefs[KEY_SELECTED_MODEL] ?: DEFAULT_MODEL_ID,
            languageCodes = langs,
            smartModeEnabled = prefs[KEY_SMART_MODE] ?: true,
            showSwitchImeKey = prefs[KEY_SHOW_SWITCH_IME_KEY] ?: false,
            touchpadStepXDp = (prefs[KEY_STEP_X_DP] ?: DEFAULT_STEP_X_DP).coerceIn(MIN_STEP_DP, MAX_STEP_DP),
            touchpadStepYDp = (prefs[KEY_STEP_Y_DP] ?: DEFAULT_STEP_Y_DP).coerceIn(MIN_STEP_DP, MAX_STEP_DP),
            disclosureAccepted = prefs[KEY_DISCLOSURE_ACCEPTED] ?: false,
            lastRemoteDeviceAddress = prefs[KEY_LAST_REMOTE_ADDRESS],
            lastRemoteDeviceName = prefs[KEY_LAST_REMOTE_NAME],
            remotePreferBuiltInMic = prefs[KEY_REMOTE_PREFER_BUILT_IN_MIC] ?: true
        )
    }

    suspend fun setSelectedModelId(modelId: String) {
        dataStore.edit { it[KEY_SELECTED_MODEL] = modelId.removePrefix("models/") }
    }

    suspend fun setLanguageCodes(codes: List<String>) {
        dataStore.edit { it[KEY_LANGUAGE_CODES] = codes.joinToString(",") }
    }

    suspend fun setSmartModeEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_SMART_MODE] = enabled }
    }

    suspend fun setShowSwitchImeKey(show: Boolean) {
        dataStore.edit { it[KEY_SHOW_SWITCH_IME_KEY] = show }
    }

    suspend fun setTouchpadStepXDp(stepDp: Int) {
        dataStore.edit { it[KEY_STEP_X_DP] = stepDp.coerceIn(MIN_STEP_DP, MAX_STEP_DP) }
    }

    suspend fun setTouchpadStepYDp(stepDp: Int) {
        dataStore.edit { it[KEY_STEP_Y_DP] = stepDp.coerceIn(MIN_STEP_DP, MAX_STEP_DP) }
    }

    suspend fun setDisclosureAccepted(accepted: Boolean) {
        dataStore.edit { it[KEY_DISCLOSURE_ACCEPTED] = accepted }
    }

    suspend fun setLastRemoteDevice(address: String?, name: String?) {
        dataStore.edit { prefs ->
            if (address == null) {
                prefs.remove(KEY_LAST_REMOTE_ADDRESS)
            } else {
                prefs[KEY_LAST_REMOTE_ADDRESS] = address
            }
            if (name == null) {
                prefs.remove(KEY_LAST_REMOTE_NAME)
            } else {
                prefs[KEY_LAST_REMOTE_NAME] = name
            }
        }
    }

    suspend fun setRemotePreferBuiltInMic(preferBuiltIn: Boolean) {
        dataStore.edit { it[KEY_REMOTE_PREFER_BUILT_IN_MIC] = preferBuiltIn }
    }

    companion object {
        const val DEFAULT_MODEL_ID = "gemini-3.5-transcribe-live"
        val DEFAULT_LANGUAGE_CODES = listOf("zh-TW")
        const val DEFAULT_STEP_X_DP = 16
        const val DEFAULT_STEP_Y_DP = 28
        const val MIN_STEP_DP = 8
        const val MAX_STEP_DP = 48

        private val KEY_SELECTED_MODEL = stringPreferencesKey("selected_model_id")
        private val KEY_LANGUAGE_CODES = stringPreferencesKey("transcription_language_codes")
        private val KEY_SMART_MODE = androidx.datastore.preferences.core.booleanPreferencesKey("smart_transcription_enabled")
        private val KEY_SHOW_SWITCH_IME_KEY = booleanPreferencesKey("show_switch_ime_key")
        private val KEY_STEP_X_DP = intPreferencesKey("touchpad_step_x_dp")
        private val KEY_STEP_Y_DP = intPreferencesKey("touchpad_step_y_dp")
        private val KEY_DISCLOSURE_ACCEPTED = booleanPreferencesKey("disclosure_accepted")
        private val KEY_LAST_REMOTE_ADDRESS = stringPreferencesKey("last_remote_device_address")
        private val KEY_LAST_REMOTE_NAME = stringPreferencesKey("last_remote_device_name")
        private val KEY_REMOTE_PREFER_BUILT_IN_MIC = booleanPreferencesKey("remote_prefer_built_in_mic")
    }
}
