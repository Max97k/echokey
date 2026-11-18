package com.akeyboard.remote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.akeyboard.AKeyboardApp
import com.akeyboard.R
import com.akeyboard.bridge.BridgeKey
import com.akeyboard.bridge.ConnectionState
import com.akeyboard.live.VoiceSessionError
import com.akeyboard.ui.AKeyboardTheme

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
    val connectionState by viewModel.connectionState.collectAsState()
    val dictationState by viewModel.dictationState.collectAsState()
    val interimText by viewModel.interimText.collectAsState()
    val pendingText by viewModel.pendingResendText.collectAsState()
    val deliveryError by viewModel.deliveryError.collectAsState()
    val devices by viewModel.bondedDevices.collectAsState()

    var hasBtPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasMicPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasBtPerm = results[Manifest.permission.BLUETOOTH_CONNECT] ?: hasBtPerm
        hasMicPerm = results[Manifest.permission.RECORD_AUDIO] ?: hasMicPerm
        if (hasBtPerm) {
            viewModel.refreshBondedDevices()
        }
    }

    LaunchedEffect(Unit) {
        val missing = buildList {
            if (!hasBtPerm) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (!hasMicPerm) add(Manifest.permission.RECORD_AUDIO)
        }
        if (missing.isNotEmpty()) {
            permLauncher.launch(missing.toTypedArray())
        }
    }

    var selectedDevice by remember(devices) { mutableStateOf<BluetoothDevice?>(devices.firstOrNull()) }
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.remote_title)) })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!hasBtPerm || !hasMicPerm) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.remote_permissions_needed),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            permLauncher.launch(
                                arrayOf(
                                    Manifest.permission.BLUETOOTH_CONNECT,
                                    Manifest.permission.RECORD_AUDIO
                                )
                            )
                        }) {
                            Text(stringResource(R.string.remote_grant_permissions))
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExposedDropdownMenuBox(
                    expanded = menuExpanded,
                    onExpandedChange = {
                        viewModel.refreshBondedDevices()
                        menuExpanded = !menuExpanded
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    val label = selectedDevice?.let { it.name ?: it.address }
                        ?: stringResource(R.string.remote_no_paired_devices)
                    OutlinedTextField(
                        value = label,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(R.string.remote_select_pc)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        devices.forEach { device ->
                            DropdownMenuItem(
                                text = { Text(device.name ?: device.address) },
                                onClick = {
                                    selectedDevice = device
                                    menuExpanded = false
                                }
                            )
                        }
                    }
                }

                when (connectionState) {
                    is ConnectionState.Connected -> {
                        OutlinedButton(onClick = { viewModel.disconnect() }) {
                            Text(stringResource(R.string.remote_disconnect))
                        }
                    }
                    is ConnectionState.Connecting -> {
                        OutlinedButton(onClick = {}, enabled = false) {
                            Text(stringResource(R.string.remote_connecting))
                        }
                    }
                    else -> {
                        Button(
                            onClick = { selectedDevice?.let { viewModel.connect(it) } },
                            enabled = selectedDevice != null && hasBtPerm
                        ) {
                            Text(stringResource(R.string.remote_connect))
                        }
                    }
                }
            }

            Text(
                text = when (val cs = connectionState) {
                    ConnectionState.Disconnected -> stringResource(R.string.remote_status_disconnected)
                    is ConnectionState.Connecting -> stringResource(R.string.remote_status_connecting, cs.deviceName)
                    is ConnectionState.Connected -> stringResource(R.string.remote_status_connected, cs.deviceName)
                    is ConnectionState.Error -> stringResource(R.string.remote_status_error, cs.message)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (connectionState is ConnectionState.Connected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
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
                    } else if (pendingText.isEmpty()) {
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
                }
            }

            val errBanner = deliveryError?.let { err ->
                if (err == "uipi") stringResource(R.string.remote_error_uipi) else err
            } ?: (dictationState as? DictationState.Error)?.let { st ->
                when (st.error) {
                    VoiceSessionError.NO_KEY -> stringResource(R.string.remote_error_no_key)
                    VoiceSessionError.NO_NETWORK -> stringResource(R.string.remote_error_no_network)
                    VoiceSessionError.INVALID_KEY -> stringResource(R.string.remote_error_invalid_key)
                    VoiceSessionError.QUOTA_EXCEEDED -> stringResource(R.string.remote_error_quota)
                    VoiceSessionError.MAX_DURATION -> stringResource(R.string.remote_error_max_duration)
                    else -> stringResource(R.string.remote_error_generic)
                }
            }
            if (errBanner != null) {
                Text(
                    text = errBanner,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { viewModel.sendKey(BridgeKey.BACKSPACE) },
                    enabled = connectionState is ConnectionState.Connected
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_backspace),
                        contentDescription = stringResource(R.string.remote_backspace)
                    )
                }

                val isListening = dictationState is DictationState.Listening
                Surface(
                    shape = CircleShape,
                    color = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(88.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    viewModel.onMicPressed()
                                    tryAwaitRelease()
                                    viewModel.onMicReleased()
                                }
                            )
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_mic),
                            contentDescription = stringResource(R.string.remote_mic_hold),
                            tint = if (isListening) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                OutlinedButton(
                    onClick = { viewModel.sendKey(BridgeKey.ENTER) },
                    enabled = connectionState is ConnectionState.Connected
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
