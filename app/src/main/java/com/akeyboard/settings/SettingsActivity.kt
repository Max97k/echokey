package com.akeyboard.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.akeyboard.AKeyboardApp
import com.akeyboard.R
import com.akeyboard.data.SettingsRepository
import com.akeyboard.remote.RemoteDictationActivity
import com.akeyboard.ui.AKeyboardTheme
import com.akeyboard.util.hasPermission
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels {
        val app = application as AKeyboardApp
        SettingsViewModel.Factory(
            apiKeyStore = app.apiKeyStore,
            settingsRepository = app.settingsRepository,
            modelRegistryClient = app.modelRegistryClient
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            AKeyboardTheme {
                val state by viewModel.uiState.collectAsState()
                if (!state.settings.disclosureAccepted) {
                    DisclosureScreen(onAgree = viewModel::acceptDisclosure)
                } else {
                    SettingsContent(
                        state = state,
                        viewModel = viewModel,
                        onRefreshImeStatus = ::refreshSystemImeStatus
                    )
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            refreshSystemImeStatus()
        }
    }

    private fun refreshSystemImeStatus() {
        val imm = getSystemService(InputMethodManager::class.java)
        val enabled = imm?.enabledInputMethodList?.any { it.packageName == packageName } ?: false
        val defaultIme = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )
        val selected = SettingsViewModel.parseDefaultImePackage(defaultIme) == packageName
        viewModel.refreshImeStatus(isEnabled = enabled, isSelected = selected)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisclosureScreen(onAgree: () -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.disclosure_title)) })
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.disclosure_body),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = onAgree,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.disclosure_agree))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onRefreshImeStatus: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasMicPerm by remember {
        mutableStateOf(context.hasPermission(Manifest.permission.RECORD_AUDIO))
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasMicPerm = context.hasPermission(Manifest.permission.RECORD_AUDIO)
                onRefreshImeStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPerm = granted
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.settings_title)) })
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = {
                        context.startActivity(Intent(context, RemoteDictationActivity::class.java))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.btn_open_pc_remote))
                }

                HorizontalDivider()

                // 1. Permissions & System IME
                Text(
                    text = stringResource(R.string.section_permissions),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(
                        if (hasMicPerm) R.string.perm_mic_granted else R.string.perm_mic_missing
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasMicPerm) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                if (!hasMicPerm) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = { permLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                            Text(stringResource(R.string.btn_grant_mic))
                        }
                        OutlinedButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                            )
                        }) {
                            Text(stringResource(R.string.btn_open_app_info))
                        }
                    }
                }
                Text(
                    text = stringResource(
                        if (state.isImeEnabled) R.string.ime_status_enabled else R.string.ime_status_disabled
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.isImeEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Text(
                    text = stringResource(
                        if (state.isImeSelected) R.string.ime_status_selected else R.string.ime_status_not_selected
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.isImeSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    }) {
                        Text(stringResource(R.string.btn_enable_ime))
                    }
                    OutlinedButton(onClick = {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.showInputMethodPicker()
                    }) {
                        Text(stringResource(R.string.btn_switch_ime))
                    }
                }

                HorizontalDivider()

                // 2. API Key
                Text(
                    text = stringResource(R.string.section_api_key),
                    style = MaterialTheme.typography.titleMedium
                )
                if (state.hasStoredKey) {
                    Text(
                        text = stringResource(R.string.api_key_saved_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                OutlinedTextField(
                    value = state.keyInput,
                    onValueChange = viewModel::onKeyInputChange,
                    label = { Text(stringResource(R.string.api_key_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::validateAndSaveKey,
                        enabled = state.keyInput.isNotBlank() &&
                            state.keyStatus != KeyValidationStatus.Validating
                    ) {
                        Text(stringResource(R.string.btn_validate_save))
                    }
                    if (state.hasStoredKey) {
                        OutlinedButton(onClick = viewModel::clearKey) {
                            Text(stringResource(R.string.btn_clear_key))
                        }
                    }
                }
                KeyStatusText(state.keyStatus)

                HorizontalDivider()

                // 3. Model Selection
                Text(
                    text = stringResource(R.string.section_model),
                    style = MaterialTheme.typography.titleMedium
                )
                if (state.models.isEmpty()) {
                    Text(
                        text = "${stringResource(R.string.model_label)}: ${state.settings.selectedModelId}\n${stringResource(R.string.no_models_loaded)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    var expanded by remember { mutableStateOf(false) }
                    val selectedDisplay = state.models
                        .firstOrNull { it.id == state.settings.selectedModelId }
                        ?.let { model ->
                            val badge = stringResource(
                                if (model.isTranscribeModel) R.string.model_badge_transcribe else R.string.model_badge_live
                            )
                            "$badge ${model.displayName} (${model.id})"
                        }
                        ?: state.settings.selectedModelId

                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = selectedDisplay,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.model_label)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            state.models.forEach { model ->
                                val badge = stringResource(
                                    if (model.isTranscribeModel) R.string.model_badge_transcribe else R.string.model_badge_live
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text("$badge ${model.displayName} (${model.id})")
                                    },
                                    onClick = {
                                        viewModel.selectModel(model.id)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // 4. Transcription Language & Smart Mode
                Text(
                    text = stringResource(R.string.section_transcription),
                    style = MaterialTheme.typography.titleMedium
                )
                val languageOptions = listOf(
                    listOf("zh-TW") to stringResource(R.string.lang_zh_tw),
                    listOf("zh-TW", "en-US") to stringResource(R.string.lang_zh_tw_en),
                    emptyList<String>() to stringResource(R.string.lang_auto),
                    listOf("zh-CN") to stringResource(R.string.lang_zh_cn),
                    listOf("en-US") to stringResource(R.string.lang_en_us),
                    listOf("ja-JP") to stringResource(R.string.lang_ja_jp),
                    listOf("ko-KR") to stringResource(R.string.lang_ko_kr)
                )
                var langExpanded by remember { mutableStateOf(false) }
                val currentLangLabel = languageOptions
                    .firstOrNull { it.first == state.settings.languageCodes }
                    ?.second
                    ?: state.settings.languageCodes.joinToString(", ").ifBlank { stringResource(R.string.lang_auto) }

                ExposedDropdownMenuBox(
                    expanded = langExpanded,
                    onExpandedChange = { langExpanded = !langExpanded }
                ) {
                    OutlinedTextField(
                        value = currentLangLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.lang_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = langExpanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = langExpanded,
                        onDismissRequest = { langExpanded = false }
                    ) {
                        languageOptions.forEach { (codes, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    viewModel.selectLanguageCodes(codes)
                                    langExpanded = false
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = stringResource(R.string.smart_mode_title),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = stringResource(R.string.smart_mode_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.settings.smartModeEnabled,
                        onCheckedChange = viewModel::setSmartModeEnabled
                    )
                }

                if (!state.settings.selectedModelId.contains("transcribe", ignoreCase = true)) {
                    Text(
                        text = stringResource(R.string.model_non_transcribe_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                HorizontalDivider()

                // 5. Touchpad Sensitivity
                Text(
                    text = stringResource(R.string.section_touchpad),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.step_x_label, state.settings.touchpadStepXDp),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = state.settings.touchpadStepXDp.toFloat(),
                    onValueChange = { viewModel.setStepXDp(it.roundToInt()) },
                    valueRange = SettingsRepository.MIN_STEP_DP.toFloat()..SettingsRepository.MAX_STEP_DP.toFloat()
                )
                Text(
                    text = stringResource(R.string.step_y_label, state.settings.touchpadStepYDp),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = state.settings.touchpadStepYDp.toFloat(),
                    onValueChange = { viewModel.setStepYDp(it.roundToInt()) },
                    valueRange = SettingsRepository.MIN_STEP_DP.toFloat()..SettingsRepository.MAX_STEP_DP.toFloat()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = stringResource(R.string.show_switch_ime_title),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = stringResource(R.string.show_switch_ime_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.settings.showSwitchImeKey,
                        onCheckedChange = viewModel::setShowSwitchImeKey
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun KeyStatusText(status: KeyValidationStatus) {
    val msg = when (status) {
        KeyValidationStatus.Idle -> null
        KeyValidationStatus.Validating -> stringResource(R.string.validating)
        is KeyValidationStatus.Valid -> stringResource(R.string.key_valid_status, status.modelCount)
        KeyValidationStatus.InvalidKey -> stringResource(R.string.err_invalid_key)
        KeyValidationStatus.QuotaExceeded -> stringResource(R.string.err_quota)
        KeyValidationStatus.NetworkError -> stringResource(R.string.err_generic)
        KeyValidationStatus.StorageError -> stringResource(R.string.key_storage_error)
        KeyValidationStatus.Cleared -> stringResource(R.string.key_cleared_status)
    } ?: return

    val isError = status in setOf(
        KeyValidationStatus.InvalidKey,
        KeyValidationStatus.QuotaExceeded,
        KeyValidationStatus.NetworkError,
        KeyValidationStatus.StorageError
    )
    Text(
        text = msg,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    )
}
