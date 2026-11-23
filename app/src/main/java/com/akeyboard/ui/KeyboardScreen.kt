// Copyright 2026 Max97k
// SPDX-License-Identifier: Apache-2.0
package com.akeyboard.ui

import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.inline.InlineContentView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.akeyboard.R
import com.akeyboard.ime.ImeErrorReason
import com.akeyboard.ime.ImeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun KeyboardScreen(
    imeStateFlow: StateFlow<ImeState>,
    stepXDpFlow: StateFlow<Int>,
    stepYDpFlow: StateFlow<Int>,
    isMultilineFlow: StateFlow<Boolean>,
    showSwitchImeFlow: StateFlow<Boolean>,
    imeActionFlow: StateFlow<Int>,
    inlineSuggestionsFlow: StateFlow<List<InlineContentView>>,
    onMicPress: () -> Unit,
    onMicRelease: () -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onSwitchIme: () -> Unit,
    onDpadKey: (Int) -> Unit,
    onPaste: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by imeStateFlow.collectAsState()
    val stepXDp by stepXDpFlow.collectAsState()
    val stepYDp by stepYDpFlow.collectAsState()
    val isMultiline by isMultilineFlow.collectAsState()
    val showSwitchIme by showSwitchImeFlow.collectAsState()
    val imeAction by imeActionFlow.collectAsState()
    val inlineSuggestions by inlineSuggestionsFlow.collectAsState()
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    var isMicHeld by remember { mutableStateOf(false) }
    val keepScreenOn = isMicHeld || state is ImeState.Recording || state is ImeState.Finalizing
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose {
            view.keepScreenOn = false
        }
    }
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val windowHeightDp = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val panelHeight = (windowHeightDp * 0.34f).coerceIn(168.dp, 216.dp)
    val isCompact = panelHeight < 200.dp
    val micSize = if (isCompact) 68.dp else 84.dp
    val imeSafeInsets = WindowInsets.navigationBars
        .union(WindowInsets.mandatorySystemGestures)
        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
        .union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))

    AKeyboardTheme {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { testTagsAsResourceId = true }
                .testTag("ime_keyboard_root")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(imeSafeInsets)
                    .height(panelHeight)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                StatusBar(
                    state = state,
                    inlineSuggestions = inlineSuggestions,
                    onPaste = onPaste,
                    onOpenSettings = onOpenSettings
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TouchpadSurface(
                        stepXDp = stepXDp,
                        stepYDp = stepYDp,
                        multiline = isMultiline,
                        enabled = state !is ImeState.Recording && state !is ImeState.Finalizing,
                        onDpadKey = onDpadKey,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        if (!isCompact) {
                            Text(
                                text = stringResource(R.string.touchpad_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 10.dp)
                            )
                        }

                        val micCd = stringResource(R.string.cd_mic_button)
                        val isRecording = state is ImeState.Recording
                        val isDisabled = (state as? ImeState.Error)?.reason == ImeErrorReason.PASSWORD_FIELD
                        val micBg = when {
                            isDisabled -> MaterialTheme.colorScheme.surfaceVariant
                            isRecording -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        }
                        val micFg = when {
                            isDisabled -> MaterialTheme.colorScheme.onSurfaceVariant
                            isRecording -> MaterialTheme.colorScheme.onError
                            else -> MaterialTheme.colorScheme.onPrimary
                        }

                        val pressScale by animateFloatAsState(
                            targetValue = if (isRecording) 1.08f else 1.0f,
                            animationSpec = tween(durationMillis = 120),
                            label = "micScale"
                        )

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.align(Alignment.Center)
                        ) {
                            if (isRecording) {
                                val pulseTransition = rememberInfiniteTransition(label = "micPulse")
                                val pulseScale by pulseTransition.animateFloat(
                                    initialValue = 1.0f,
                                    targetValue = 1.42f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(durationMillis = 900),
                                        repeatMode = RepeatMode.Restart
                                    ),
                                    label = "pulseScale"
                                )
                                val pulseAlpha by pulseTransition.animateFloat(
                                    initialValue = 0.35f,
                                    targetValue = 0f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(durationMillis = 900),
                                        repeatMode = RepeatMode.Restart
                                    ),
                                    label = "pulseAlpha"
                                )
                                Box(
                                    modifier = Modifier
                                        .size(micSize)
                                        .scale(pulseScale)
                                        .alpha(pulseAlpha)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.error)
                                )
                            }

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(micSize)
                                    .scale(pressScale)
                                    .clip(CircleShape)
                                    .background(micBg)
                                    .testTag("ime_mic_btn")
                                    .semantics { contentDescription = micCd }
                                    .pointerInput(isDisabled) {
                                        if (isDisabled) return@pointerInput
                                        awaitEachGesture {
                                            awaitFirstDown(requireUnconsumed = false)
                                            isMicHeld = true
                                            try {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onMicPress()
                                                waitForUpOrCancellation()
                                                onMicRelease()
                                            } finally {
                                                isMicHeld = false
                                            }
                                        }
                                    }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_mic),
                                    contentDescription = null,
                                    tint = micFg,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    val auxBtnSize = if (isCompact) {
                        if (showSwitchIme) 34.dp else 44.dp
                    } else {
                        if (showSwitchIme) 44.dp else 56.dp
                    }
                    val auxSpacing = if (isCompact) {
                        if (showSwitchIme) 4.dp else 8.dp
                    } else {
                        if (showSwitchIme) 8.dp else 14.dp
                    }
                    val auxShape = RoundedCornerShape(18.dp)
                    Column(
                        modifier = Modifier
                            .width(60.dp)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(auxSpacing, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val cdBackspace = stringResource(R.string.cd_backspace)
                        val cdEnter = stringResource(R.string.cd_enter)
                        val cdSwitch = stringResource(R.string.cd_switch_ime)

                        // Backspace with hold-to-repeat auto-delete
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(auxBtnSize)
                                .clip(auxShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .testTag("ime_backspace_btn")
                                .semantics { contentDescription = cdBackspace }
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        onBackspace()
                                        val repeatJob = coroutineScope.launch {
                                            delay(400L)
                                            while (isActive) {
                                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                                onBackspace()
                                                delay(55L)
                                            }
                                        }
                                        waitForUpOrCancellation()
                                        repeatJob.cancel()
                                    }
                                }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_backspace),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // Enter / Contextual Action Key
                        val actionIconRes = when (imeAction) {
                            EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_search
                            EditorInfo.IME_ACTION_SEND -> R.drawable.ic_send
                            EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_GO -> R.drawable.ic_arrow_forward
                            EditorInfo.IME_ACTION_DONE -> R.drawable.ic_check
                            else -> R.drawable.ic_keyboard_return
                        }
                        Surface(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                onEnter()
                            },
                            shape = auxShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .size(auxBtnSize)
                                .testTag("ime_enter_btn")
                                .semantics { contentDescription = cdEnter }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(actionIconRes),
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        if (showSwitchIme) {
                            Surface(
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    onSwitchIme()
                                },
                                shape = auxShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier
                                    .size(auxBtnSize)
                                    .semantics { contentDescription = cdSwitch }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_language),
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBar(
    state: ImeState,
    inlineSuggestions: List<InlineContentView>,
    onPaste: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val errorState = state as? ImeState.Error
    val text = when (state) {
        ImeState.Idle -> stringResource(R.string.hold_to_speak)
        ImeState.Warm -> stringResource(R.string.hold_to_speak)
        ImeState.Recording -> stringResource(R.string.listening)
        ImeState.Finalizing -> stringResource(R.string.finalizing)
        is ImeState.Error -> when (state.reason) {
            ImeErrorReason.NO_KEY -> stringResource(R.string.err_no_key)
            ImeErrorReason.INVALID_KEY -> stringResource(R.string.err_invalid_key)
            ImeErrorReason.NO_PERMISSION -> stringResource(R.string.err_no_permission)
            ImeErrorReason.NO_NETWORK -> stringResource(R.string.err_no_network)
            ImeErrorReason.QUOTA_EXCEEDED -> stringResource(R.string.err_quota)
            ImeErrorReason.REGION_NOT_SUPPORTED -> stringResource(R.string.err_region_not_supported)
            ImeErrorReason.PASSWORD_FIELD -> stringResource(R.string.err_password_field)
            ImeErrorReason.MIC_BUSY -> stringResource(R.string.err_mic_busy)
            ImeErrorReason.MAX_DURATION -> stringResource(R.string.err_max_duration)
            ImeErrorReason.GENERIC -> stringResource(R.string.err_generic)
        }
    }

    val isActionableError = errorState?.reason in setOf(
        ImeErrorReason.NO_KEY,
        ImeErrorReason.INVALID_KEY,
        ImeErrorReason.NO_PERMISSION,
        ImeErrorReason.REGION_NOT_SUPPORTED
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(start = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (inlineSuggestions.isNotEmpty() && errorState == null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .testTag("ime_inline_suggestions")
            ) {
                inlineSuggestions.forEach { suggestion ->
                    AndroidView(
                        factory = {
                            // A reused InlineContentView must be detached from its old parent first.
                            (suggestion.parent as? ViewGroup)?.removeView(suggestion)
                            suggestion
                        }
                    )
                }
            }
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = if (errorState != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (isActionableError) {
                            Modifier.clickable(onClick = onOpenSettings)
                        } else {
                            Modifier
                        }
                    )
            )
        }
        val pasteEnabled = state !is ImeState.Recording && state !is ImeState.Finalizing
        val view = LocalView.current
        Surface(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onPaste()
            },
            enabled = pasteEnabled,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier
                .height(32.dp)
                .alpha(if (pasteEnabled) 1f else 0.45f)
                .testTag("ime_paste_btn")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_content_paste),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.btn_paste),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }
        }
        Spacer(modifier = Modifier.width(2.dp))
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = stringResource(R.string.open_settings),
                tint = if (isActionableError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
