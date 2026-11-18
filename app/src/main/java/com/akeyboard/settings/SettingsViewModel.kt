package com.akeyboard.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.akeyboard.data.ApiKeyStore
import com.akeyboard.data.ModelInfo
import com.akeyboard.data.ModelRegistryClient
import com.akeyboard.data.ModelRegistryException
import com.akeyboard.data.SettingsRepository
import com.akeyboard.data.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class KeyValidationStatus {
    data object Idle : KeyValidationStatus()
    data object Validating : KeyValidationStatus()
    data class Valid(val modelCount: Int) : KeyValidationStatus()
    data object InvalidKey : KeyValidationStatus()
    data object QuotaExceeded : KeyValidationStatus()
    data object NetworkError : KeyValidationStatus()
    data object Cleared : KeyValidationStatus()
}

data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val hasStoredKey: Boolean = false,
    val keyInput: String = "",
    val models: List<ModelInfo> = emptyList(),
    val keyStatus: KeyValidationStatus = KeyValidationStatus.Idle
)

class SettingsViewModel(
    private val apiKeyStore: ApiKeyStore,
    private val settingsRepository: SettingsRepository,
    private val modelRegistryClient: ModelRegistryClient
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
            val savedKey = apiKeyStore.load()
            if (!savedKey.isNullOrBlank()) {
                _uiState.update { it.copy(hasStoredKey = true) }
                refreshModels(savedKey, persistKey = false)
            }
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

    private suspend fun refreshModels(apiKey: String, persistKey: Boolean) {
        _uiState.update { it.copy(keyStatus = KeyValidationStatus.Validating) }
        val result = modelRegistryClient.fetchLiveModels(apiKey)
        result.fold(
            onSuccess = { models ->
                if (persistKey) {
                    apiKeyStore.save(apiKey)
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
                    else -> KeyValidationStatus.NetworkError
                }
                _uiState.update { it.copy(keyStatus = status) }
            }
        )
    }

    class Factory(
        private val apiKeyStore: ApiKeyStore,
        private val settingsRepository: SettingsRepository,
        private val modelRegistryClient: ModelRegistryClient
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(apiKeyStore, settingsRepository, modelRegistryClient) as T
        }
    }
}
