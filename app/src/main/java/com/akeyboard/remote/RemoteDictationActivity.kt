// Copyright 2026 Max97k
// SPDX-License-Identifier: Apache-2.0
package com.akeyboard.remote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.akeyboard.AKeyboardApp
import com.akeyboard.R
import com.akeyboard.bridge.BluetoothReadiness
import com.akeyboard.bridge.BridgeDeliveryError
import com.akeyboard.bridge.BridgeKey
import com.akeyboard.bridge.ConnectionState
import com.akeyboard.live.VoiceSessionError
import com.akeyboard.settings.SettingsActivity
import com.akeyboard.ui.AKeyboardTheme
import com.akeyboard.util.hasPermission
import com.akeyboard.util.openExternalUrl
import com.akeyboard.util.readClipboardText

internal const val PC_BRIDGE_SCRIPT_URL =
    "https://github.com/Max97k/voice-keyboard-pc-bridge/blob/main/tools/akeyboard_bridge.py"

class RemoteDictationActivity : ComponentActivity() {

    private val viewModel: RemoteDictationViewModel by viewModels {
        RemoteDictationViewModel.Factory(application as AKeyboardApp)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AKeyboardTheme {
                RemoteDictationScreen(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
private fun RemoteDictationScreen(viewModel: RemoteDictationViewModel) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current

    val connectionState by viewModel.connectionState.collectAsState()
    val dictationState by viewModel.dictationState.collectAsState()
    val interimText by viewModel.interimText.collectAsState()
    val pendingText by viewModel.pendingResendText.collectAsState()
    val recentTranscripts by viewModel.recentTranscripts.collectAsState()
    val deliveryError by viewModel.deliveryError.collectAsState()
    val connectNotice by viewModel.connectNotice.collectAsState()
    val readiness by viewModel.readiness.collectAsState()
    val blocker by viewModel.blocker.collectAsState()
    val devices by viewModel.bondedDevices.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val offlineTargetName by viewModel.offlineTargetName.collectAsState()
    val preferBuiltInMic by viewModel.preferBuiltInMic.collectAsState()
    val pendingConnectActive by viewModel.pendingConnectActive.collectAsState()

    var isMicHeld by remember { mutableStateOf(false) }
    val isListening = dictationState is DictationState.Listening
    val isFinalizing = dictationState is DictationState.Finalizing
    val keepScreenOn = RemoteDictationViewModel.shouldKeepScreenOn(
        isMicHeld = isMicHeld,
        isListening = isListening,
        isFinalizing = isFinalizing
    )
    DisposableEffect(view, activity, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        val window = activity?.window
        if (keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            view.keepScreenOn = false
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var hasBtPerm by remember {
        mutableStateOf(context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
    }
    var hasMicPerm by remember {
        mutableStateOf(context.hasPermission(Manifest.permission.RECORD_AUDIO))
    }
    var btPermRequestedInSession by rememberSaveable { mutableStateOf(false) }
    var micPermRequestedInSession by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasBtPerm = context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
                hasMicPerm = context.hasPermission(Manifest.permission.RECORD_AUDIO)
                viewModel.refreshEnvironmentState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        hasBtPerm = context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        hasMicPerm = context.hasPermission(Manifest.permission.RECORD_AUDIO)
        viewModel.refreshEnvironmentState()
    }

    val btEnableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onBluetoothEnableResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(viewModel) {
        viewModel.uiRequestEnableBt.collect {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            try {
                btEnableLauncher.launch(intent)
            } catch (_: ActivityNotFoundException) {
                try {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    viewModel.onBluetoothEnableResult(false)
                }
            }
        }
    }

    var menuExpanded by remember { mutableStateOf(false) }
    var showNoBrowserHint by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.remote_title)) },
                actions = {
                    IconButton(
                        onClick = {
                            context.startActivity(Intent(context, SettingsActivity::class.java))
                        },
                        modifier = Modifier.testTag("remote_open_settings_btn")
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = stringResource(R.string.open_settings)
                        )
                    }
                }
            )
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
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                blocker?.let { activeBlocker ->
                    val showOpenAppInfo = if (activeBlocker is RemoteBlocker.PermissionsMissing && activity != null) {
                        val btPermanentlyDenied = activeBlocker.missingBt &&
                            btPermRequestedInSession &&
                            !ActivityCompat.shouldShowRequestPermissionRationale(
                                activity,
                                Manifest.permission.BLUETOOTH_CONNECT
                            )
                        val micPermanentlyDenied = activeBlocker.missingMic &&
                            micPermRequestedInSession &&
                            !ActivityCompat.shouldShowRequestPermissionRationale(
                                activity,
                                Manifest.permission.RECORD_AUDIO
                            )
                        btPermanentlyDenied || micPermanentlyDenied
                    } else {
                        false
                    }
                    RemoteBlockerCard(
                        blocker = activeBlocker,
                        showOpenAppInfo = showOpenAppInfo,
                        onGrantPermissions = { missingBt, missingMic ->
                            val toRequest = buildList {
                                if (missingBt) {
                                    btPermRequestedInSession = true
                                    add(Manifest.permission.BLUETOOTH_CONNECT)
                                }
                                if (missingMic) {
                                    micPermRequestedInSession = true
                                    add(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                            if (toRequest.isNotEmpty()) {
                                permLauncher.launch(toRequest.toTypedArray())
                            }
                        },
                        onOpenAppInfo = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                            )
                        },
                        onEnableBluetooth = { viewModel.onEnableBluetoothClicked() },
                        onOpenBluetoothSettings = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                            } catch (_: ActivityNotFoundException) {
                                context.startActivity(Intent(Settings.ACTION_SETTINGS))
                            }
                        },
                        onOpenSettings = {
                            context.startActivity(Intent(context, SettingsActivity::class.java))
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ExposedDropdownMenuBox(
                        expanded = menuExpanded,
                        onExpandedChange = {
                            viewModel.refreshEnvironmentState()
                            if (devices.isNotEmpty()) {
                                menuExpanded = !menuExpanded
                            } else {
                                menuExpanded = false
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        val label = selectedDevice?.let { it.name ?: it.address }
                            ?: if (readiness is BluetoothReadiness.BluetoothDisabled && offlineTargetName != null) {
                                offlineTargetName!!
                            } else if (readiness is BluetoothReadiness.BluetoothDisabled) {
                                stringResource(R.string.remote_blocker_bt_off)
                            } else {
                                stringResource(R.string.remote_no_paired_devices)
                            }
                        OutlinedTextField(
                            value = label,
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            label = { Text(stringResource(R.string.remote_select_pc)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded)
                            },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                                .testTag("remote_device_selector")
                        )
                        ExposedDropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            devices.forEach { device ->
                                DropdownMenuItem(
                                    text = { Text(device.name ?: device.address) },
                                    onClick = {
                                        viewModel.onDeviceSelected(device)
                                        menuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    when (connectionState) {
                        is ConnectionState.Connected -> {
                            OutlinedButton(
                                onClick = { viewModel.disconnect() },
                                modifier = Modifier.testTag("remote_disconnect_btn")
                            ) {
                                Text(stringResource(R.string.remote_disconnect))
                            }
                        }
                        is ConnectionState.Connecting -> {
                            OutlinedButton(
                                onClick = { viewModel.cancelConnect() },
                                modifier = Modifier.testTag("remote_cancel_btn")
                            ) {
                                Text(stringResource(R.string.remote_btn_cancel))
                            }
                        }
                        else -> {
                            if (pendingConnectActive) {
                                OutlinedButton(
                                    onClick = { viewModel.cancelConnect() },
                                    modifier = Modifier.testTag("remote_cancel_btn")
                                ) {
                                    Text(stringResource(R.string.remote_btn_cancel))
                                }
                            } else {
                                val canConnect = when (readiness) {
                                    is BluetoothReadiness.Ready -> selectedDevice != null && hasBtPerm
                                    is BluetoothReadiness.BluetoothDisabled -> offlineTargetName != null && hasBtPerm
                                    else -> false
                                }
                                Button(
                                    onClick = { viewModel.onConnectClicked() },
                                    enabled = canConnect,
                                    modifier = Modifier.testTag("remote_connect_btn")
                                ) {
                                    Text(stringResource(R.string.remote_connect))
                                }
                            }
                        }
                    }
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = preferBuiltInMic,
                        onClick = { viewModel.setPreferBuiltInMic(true) },
                        label = { Text(stringResource(R.string.remote_mic_built_in)) },
                        modifier = Modifier.testTag("remote_mic_builtin_chip")
                    )
                    FilterChip(
                        selected = !preferBuiltInMic,
                        onClick = { viewModel.setPreferBuiltInMic(false) },
                        label = { Text(stringResource(R.string.remote_mic_system)) },
                        modifier = Modifier.testTag("remote_mic_system_chip")
                    )
                }

                Text(
                    text = when {
                        pendingConnectActive && connectionState !is ConnectionState.Connecting ->
                            stringResource(R.string.remote_connecting)
                        else -> when (val cs = connectionState) {
                            ConnectionState.Disconnected -> stringResource(R.string.remote_status_disconnected)
                            is ConnectionState.Connecting -> stringResource(R.string.remote_status_connecting, cs.deviceName)
                            is ConnectionState.Connected -> stringResource(R.string.remote_status_connected, cs.deviceName)
                            is ConnectionState.Error -> stringResource(R.string.remote_status_error, cs.message)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connectionState is ConnectionState.Connected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.testTag("remote_connection_status")
                )

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("remote_windows_ime_tip")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.remote_windows_ime_tip),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.remote_pc_bridge_guide),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = {
                                showNoBrowserHint = !context.openExternalUrl(PC_BRIDGE_SCRIPT_URL)
                            },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                            modifier = Modifier.testTag("remote_pc_bridge_guide_btn")
                        ) {
                            Text(
                                text = stringResource(R.string.remote_btn_pc_bridge_code),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        if (showNoBrowserHint) {
                            Text(
                                text = stringResource(R.string.remote_pc_bridge_no_browser),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("remote_no_browser_hint")
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 120.dp)
                        .fillMaxWidth()
                        .testTag("remote_preview_card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (interimText.isNotEmpty()) {
                            Text(
                                text = interimText,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        } else if (pendingText.isEmpty() && recentTranscripts.isEmpty()) {
                            Text(
                                text = stringResource(R.string.remote_preview_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (pendingText.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.remote_pending_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            Text(
                                text = pendingText,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { viewModel.resendPending() },
                                    enabled = connectionState is ConnectionState.Connected
                                ) {
                                    Text(stringResource(R.string.remote_resend))
                                }
                                TextButton(onClick = { viewModel.clearPending() }) {
                                    Text(stringResource(R.string.remote_clear))
                                }
                            }
                        }

                        if (recentTranscripts.isNotEmpty()) {
                            if (pendingText.isNotEmpty()) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.remote_recent_label),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("remote_recent_label")
                                )
                                TextButton(
                                    onClick = { viewModel.clearRecent() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.testTag("remote_recent_clear_btn")
                                ) {
                                    Text(stringResource(R.string.remote_clear))
                                }
                            }
                            recentTranscripts.forEachIndexed { index, item ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    tonalElevation = 1.dp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("remote_recent_item_$index")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = item.text,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedButton(
                                            onClick = { viewModel.resendRecent(item) },
                                            enabled = connectionState is ConnectionState.Connected,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            modifier = Modifier.testTag("remote_recent_resend_btn_$index")
                                        ) {
                                            Text(stringResource(R.string.remote_resend))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                val errBannerRes = resolveRemoteErrorBannerRes(
                    deliveryError = deliveryError,
                    connectNotice = connectNotice,
                    dictationError = (dictationState as? DictationState.Error)?.error
                )
                if (errBannerRes != null) {
                    Text(
                        text = stringResource(errBannerRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("remote_error_banner")
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val clipboardText = context.readClipboardText()
                                    if (RemoteDictationViewModel.prepareClipboardTextForPc(clipboardText).isEmpty()) {
                                        Toast.makeText(
                                            context,
                                            R.string.api_key_clipboard_empty,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else if (clipboardText != null) {
                                        viewModel.sendClipboardText(clipboardText)
                                    }
                                },
                                enabled = connectionState is ConnectionState.Connected,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("remote_paste_btn")
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_content_paste),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.btn_paste),
                                    maxLines = 1
                                )
                            }
                            OutlinedButton(
                                onClick = { viewModel.sendKey(BridgeKey.BACKSPACE) },
                                enabled = connectionState is ConnectionState.Connected,
                                modifier = Modifier.testTag("remote_backspace_btn")
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_backspace),
                                    contentDescription = stringResource(R.string.remote_backspace)
                                )
                            }
                        }
                    }

                    val micCd = stringResource(R.string.remote_mic_hold)
                    val micBg = when {
                        isListening -> MaterialTheme.colorScheme.error
                        isFinalizing -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.primary
                    }
                    val micFg = when {
                        isListening -> MaterialTheme.colorScheme.onError
                        isFinalizing -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onPrimary
                    }
                    val pressScale by animateFloatAsState(
                        targetValue = if (isListening) 1.08f else 1.0f,
                        animationSpec = tween(durationMillis = 120),
                        label = "remoteMicScale"
                    )

                    Box(contentAlignment = Alignment.Center) {
                        if (isListening) {
                            val pulseTransition = rememberInfiniteTransition(label = "remoteMicPulse")
                            val pulseScale by pulseTransition.animateFloat(
                                initialValue = 1.0f,
                                targetValue = 1.42f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(durationMillis = 900),
                                    repeatMode = RepeatMode.Restart
                                ),
                                label = "remotePulseScale"
                            )
                            val pulseAlpha by pulseTransition.animateFloat(
                                initialValue = 0.35f,
                                targetValue = 0f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(durationMillis = 900),
                                    repeatMode = RepeatMode.Restart
                                ),
                                label = "remotePulseAlpha"
                            )
                            Box(
                                modifier = Modifier
                                    .size(88.dp)
                                    .scale(pulseScale)
                                    .alpha(pulseAlpha)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error)
                            )
                        }

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(88.dp)
                                .scale(pressScale)
                                .clip(CircleShape)
                                .background(micBg)
                                .testTag("remote_mic_hold_btn")
                                .semantics { contentDescription = micCd }
                                .pointerInput(isFinalizing) {
                                    if (isFinalizing) return@pointerInput
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        isMicHeld = true
                                        try {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.onMicPressed()
                                            waitForUpOrCancellation()
                                            viewModel.onMicReleased()
                                        } finally {
                                            isMicHeld = false
                                        }
                                    }
                                }
                        ) {
                            if (isFinalizing) {
                                CircularProgressIndicator(
                                    color = micFg,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(32.dp)
                                )
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.ic_mic),
                                    contentDescription = null,
                                    tint = micFg,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.sendKey(BridgeKey.ENTER) },
                            enabled = connectionState is ConnectionState.Connected,
                            modifier = Modifier.testTag("remote_enter_btn")
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_keyboard_return),
                                contentDescription = stringResource(R.string.remote_enter)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteBlockerCard(
    blocker: RemoteBlocker,
    showOpenAppInfo: Boolean,
    onGrantPermissions: (missingBt: Boolean, missingMic: Boolean) -> Unit,
    onOpenAppInfo: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val isErrorContainer = blocker is RemoteBlocker.NoHardware ||
        blocker is RemoteBlocker.PermissionsMissing
    val containerColor = if (isErrorContainer) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val message = when (blocker) {
        RemoteBlocker.NoHardware -> stringResource(R.string.remote_blocker_no_hardware)
        is RemoteBlocker.PermissionsMissing -> stringResource(R.string.remote_permissions_needed)
        is RemoteBlocker.BluetoothDisabled -> stringResource(
            if (blocker.turningOn) R.string.remote_blocker_bt_turning_on else R.string.remote_blocker_bt_off
        )
        RemoteBlocker.NoPairedDevices -> stringResource(R.string.remote_blocker_no_paired)
        RemoteBlocker.ApiKeyMissing -> stringResource(R.string.remote_blocker_no_key)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("remote_blocker_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("remote_blocker_message")
            )
            when (blocker) {
                RemoteBlocker.NoHardware -> Unit
                is RemoteBlocker.PermissionsMissing -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { onGrantPermissions(blocker.missingBt, blocker.missingMic) },
                            modifier = Modifier.testTag("remote_grant_permissions_btn")
                        ) {
                            Text(stringResource(R.string.remote_grant_permissions))
                        }
                        if (showOpenAppInfo) {
                            TextButton(
                                onClick = onOpenAppInfo,
                                modifier = Modifier.testTag("remote_open_app_info_btn")
                            ) {
                                Text(stringResource(R.string.btn_open_app_info))
                            }
                        }
                    }
                }
                is RemoteBlocker.BluetoothDisabled -> {
                    if (!blocker.turningOn) {
                        TextButton(
                            onClick = onEnableBluetooth,
                            modifier = Modifier.testTag("remote_enable_bt_btn")
                        ) {
                            Text(stringResource(R.string.remote_btn_enable_bt))
                        }
                    }
                }
                RemoteBlocker.NoPairedDevices -> {
                    TextButton(
                        onClick = onOpenBluetoothSettings,
                        modifier = Modifier.testTag("remote_pair_bt_btn")
                    ) {
                        Text(stringResource(R.string.remote_btn_pair_bt))
                    }
                }
                RemoteBlocker.ApiKeyMissing -> {
                    TextButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("remote_blocker_settings_btn")
                    ) {
                        Text(stringResource(R.string.remote_btn_open_settings))
                    }
                }
            }
        }
    }
}

@StringRes
internal fun mapDeliveryErrorRes(error: BridgeDeliveryError): Int = when (error) {
    BridgeDeliveryError.UIPI -> R.string.remote_error_uipi
    BridgeDeliveryError.NO_FOCUS -> R.string.remote_error_no_focus
    BridgeDeliveryError.SEND_FAILED -> R.string.remote_error_send_failed
    BridgeDeliveryError.PC_DISCONNECTED -> R.string.remote_error_pc_disconnected
}

@StringRes
internal fun mapConnectNoticeRes(notice: RemoteConnectNotice): Int = when (notice) {
    RemoteConnectNotice.BT_ENABLE_DENIED -> R.string.remote_error_bt_enable_denied
    RemoteConnectNotice.BT_ENABLE_TIMEOUT -> R.string.remote_error_bt_enable_timeout
    RemoteConnectNotice.DEVICE_UNAVAILABLE -> R.string.remote_no_paired_devices
}

@StringRes
internal fun mapVoiceErrorRes(error: VoiceSessionError): Int = when (error) {
    VoiceSessionError.NO_PERMISSION -> R.string.err_no_permission
    VoiceSessionError.NO_KEY -> R.string.remote_error_no_key
    VoiceSessionError.NO_NETWORK -> R.string.remote_error_no_network
    VoiceSessionError.MIC_BUSY -> R.string.err_mic_busy
    VoiceSessionError.INVALID_KEY -> R.string.remote_error_invalid_key
    VoiceSessionError.QUOTA_EXCEEDED -> R.string.remote_error_quota
    VoiceSessionError.REGION_NOT_SUPPORTED -> R.string.remote_error_region_not_supported
    VoiceSessionError.MAX_DURATION -> R.string.remote_error_max_duration
    VoiceSessionError.GENERIC -> R.string.remote_error_generic
}

@StringRes
internal fun resolveRemoteErrorBannerRes(
    deliveryError: BridgeDeliveryError?,
    connectNotice: RemoteConnectNotice?,
    dictationError: VoiceSessionError?
): Int? = when {
    deliveryError != null -> mapDeliveryErrorRes(deliveryError)
    connectNotice != null -> mapConnectNoticeRes(connectNotice)
    dictationError != null -> mapVoiceErrorRes(dictationError)
    else -> null
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
