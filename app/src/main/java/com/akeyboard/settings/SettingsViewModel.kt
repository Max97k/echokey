// Copyright 2026 Max97k
// SPDX-License-Identifier: Apache-2.0
package com.akeyboard.settings

import android.content.ComponentName
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.akeyboard.data.ApiKeyStore
import com.akeyboard.data.ModelInfo
import com.akeyboard.data.ModelRegistryClient
import com.akeyboard.data.ModelRegistryException
import com.akeyboard.data.SettingsRepository
import com.akeyboard.data.UsageRepository
import com.akeyboard.data.UsageStore
import com.akeyboard.data.UserSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.security.GeneralSecurityException

sealed class KeyValidationStatus {
    data object Idle : KeyValidationStatus()
    data object Validating : KeyValidationStatus()
    data class Valid(val modelCount: Int) : KeyValidationStatus()
    data object InvalidKey : KeyValidationStatus()
    data object QuotaExceeded : KeyValidationStatus()
    data object RegionNotSupported : KeyValidationStatus()
    data object NetworkError : KeyValidationStatus()
    data object StorageError : KeyValidationStatus()
    data object Cleared : KeyValidationStatus()
}

data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val usageStore: UsageStore = UsageStore(),
    val hasStoredKey: Boolean = false,
    val keyInput: String = "",
    val models: List<ModelInfo> = emptyList(),
    val keyStatus: KeyValidationStatus = KeyValidationStatus.Idle,
    val isImeEnabled: Boolean = false,
    val isImeSelected: Boolean = false
)

class SettingsViewModel(
    private val apiKeyStore: ApiKeyStore,
    private val settingsRepository: SettingsRepository,
    private val modelRegistryClient: ModelRegistryClient,
    private val usageRepository: UsageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { userSettings ->
                _uiState.update { it.copy(settings = userSettings) }
            }
        }
        viewModelScope.launch {
            usageRepository.usage.collect { store ->
                _uiState.update { it.copy(usageStore = store) }
            }
        }
        viewModelScope.launch {
            val savedKey = apiKeyStore.load()
            if (!savedKey.isNullOrBlank()) {
                _uiState.update { it.copy(hasStoredKey = true) }
                refreshModels(savedKey, persistKey = false)
            }
        }
    }

    fun refreshImeStatus(isEnabled: Boolean, isSelected: Boolean) {
        _uiState.update {
            it.copy(isImeEnabled = isEnabled, isImeSelected = isSelected)
        }
    }

    fun onKeyInputChange(value: String) {
        _uiState.update { it.copy(keyInput = value) }
    }

    fun acceptDisclosure() {
        viewModelScope.launch {
            settingsRepository.setDisclosureAccepted(true)
        }
    }

    fun validateAndSaveKey() {
        val raw = _uiState.value.keyInput.trim()
        if (raw.isEmpty()) return
        viewModelScope.launch {
            refreshModels(raw, persistKey = true)
        }
    }

    fun clearKey() {
        viewModelScope.launch {
            apiKeyStore.clear()
            _uiState.update {
                it.copy(
                    hasStoredKey = false,
                    keyInput = "",
                    models = emptyList(),
                    keyStatus = KeyValidationStatus.Cleared
                )
            }
        }
    }

    fun selectModel(modelId: String) {
        viewModelScope.launch {
            settingsRepository.setSelectedModelId(modelId)
        }
    }

    fun selectLanguageCodes(codes: List<String>) {
        viewModelScope.launch {
            settingsRepository.setLanguageCodes(codes)
        }
    }

    fun setSmartModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSmartModeEnabled(enabled)
        }
    }

    fun setShowSwitchImeKey(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowSwitchImeKey(show)
        }
    }

    fun setStepXDp(stepDp: Int) {
        viewModelScope.launch {
            settingsRepository.setTouchpadStepXDp(stepDp)
        }
    }

    fun setStepYDp(stepDp: Int) {
        viewModelScope.launch {
            settingsRepository.setTouchpadStepYDp(stepDp)
        }
    }

    fun resetUsageStats() {
        viewModelScope.launch {
            usageRepository.clear()
        }
    }

    private suspend fun refreshModels(apiKey: String, persistKey: Boolean) {
        _uiState.update { it.copy(keyStatus = KeyValidationStatus.Validating) }
        val result = modelRegistryClient.fetchLiveModels(apiKey)
        result.fold(
            onSuccess = { models ->
                if (persistKey) {
                    try {
                        apiKeyStore.save(apiKey)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: GeneralSecurityException) {
                        _uiState.update { it.copy(keyStatus = KeyValidationStatus.StorageError) }
                        return@fold
                    } catch (_: IOException) {
                        _uiState.update { it.copy(keyStatus = KeyValidationStatus.StorageError) }
                        return@fold
                    }
                }
                val currentSelected = _uiState.value.settings.selectedModelId
                if (models.isNotEmpty() && models.none { it.id == currentSelected }) {
                    val preferred = models.firstOrNull {
                        it.id == SettingsRepository.DEFAULT_MODEL_ID
                    } ?: models.first()
                    settingsRepository.setSelectedModelId(preferred.id)
                }
                _uiState.update {
                    it.copy(
                        hasStoredKey = true,
                        keyInput = if (persistKey) "" else it.keyInput,
                        models = models,
                        keyStatus = KeyValidationStatus.Valid(models.size)
                    )
                }
            },
            onFailure = { err ->
                val status = when (err) {
                    is ModelRegistryException.InvalidKey -> KeyValidationStatus.InvalidKey
                    is ModelRegistryException.QuotaExceeded -> KeyValidationStatus.QuotaExceeded
                    is ModelRegistryException.RegionNotSupported -> KeyValidationStatus.RegionNotSupported
                    else -> KeyValidationStatus.NetworkError
                }
                _uiState.update { it.copy(keyStatus = status) }
            }
        )
    }

    class Factory(
        private val apiKeyStore: ApiKeyStore,
        private val settingsRepository: SettingsRepository,
        private val modelRegistryClient: ModelRegistryClient,
        private val usageRepository: UsageRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(
                apiKeyStore = apiKeyStore,
                settingsRepository = settingsRepository,
                modelRegistryClient = modelRegistryClient,
                usageRepository = usageRepository
            ) as T
        }
    }

    companion object {
        fun parseDefaultImePackage(defaultIme: String?): String? {
            val trimmed = defaultIme?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            ComponentName.unflattenFromString(trimmed)?.packageName?.let { pkg ->
                if (pkg.isNotBlank()) return pkg
            }
            val slash = trimmed.indexOf('/')
            if (slash <= 0 || slash >= trimmed.lastIndex) return null
            return trimmed.substring(0, slash).takeIf { it.isNotBlank() }
        }
    }
}
