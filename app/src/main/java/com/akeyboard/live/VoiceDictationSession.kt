// Copyright 2026 Max97k
// SPDX-License-Identifier: Apache-2.0
package com.akeyboard.live

import com.akeyboard.audio.AudioCaptureManager
import com.akeyboard.audio.AudioCaptureResult
import com.akeyboard.audio.AudioFailureReason
import com.akeyboard.data.ApiKeyStore
import com.akeyboard.data.SettingsRepository
import com.akeyboard.util.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class VoiceSessionPhase {
    IDLE,
    WARM,
    RECORDING,
    FINALIZING
}

enum class VoiceSessionError {
    NO_PERMISSION,
    NO_KEY,
    NO_NETWORK,
    MIC_BUSY,
    INVALID_KEY,
    QUOTA_EXCEEDED,
    REGION_NOT_SUPPORTED,
    MAX_DURATION,
    GENERIC
}

class VoiceDictationSession(
    private val scope: CoroutineScope,
    private val audioManager: AudioCaptureManager,
    private val liveClient: GeminiLiveClient,
    private val apiKeyStore: ApiKeyStore,
    private val settingsRepository: SettingsRepository,
    private val hasMicPermission: () -> Boolean,
    private val isNetworkAvailable: () -> Boolean,
    private val onPhaseChanged: (VoiceSessionPhase) -> Unit,
    private val onInterim: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onFinishComposing: () -> Unit,
    private val onError: (VoiceSessionError) -> Unit,
    private val loadApiKey: suspend () -> String? = { apiKeyStore.load() }
) {
    private var phase = VoiceSessionPhase.IDLE
    private var liveEventsJob: Job? = null
    private var maxDurationJob: Job? = null
    private var finalizeJob: Job? = null
    private val runningComposingText = StringBuilder()
    private var hasCommittedCurrentTurn = false

    init {
        audioManager.onCaptureError = { reason ->
            scope.launch {
                handleCaptureError(reason)
            }
        }
        observeLiveEvents()
    }

    fun warmUp() {
        scope.launch {
            val key = loadApiKey()
            val settings = settingsRepository.settings.first()
            if (!key.isNullOrBlank() && hasMicPermission() && isNetworkAvailable()) {
                liveClient.warmUp(
                    scope = scope,
                    apiKey = key,
                    modelId = settings.selectedModelId,
                    languageCodes = settings.languageCodes,
                    smartMode = settings.smartModeEnabled
                )
            }
        }
    }

    fun onMicPressed() {
        if (phase == VoiceSessionPhase.FINALIZING || phase == VoiceSessionPhase.RECORDING) return
        if (!hasMicPermission()) {
            onError(VoiceSessionError.NO_PERMISSION)
            return
        }
        if (!isNetworkAvailable()) {
            onError(VoiceSessionError.NO_NETWORK)
            return
        }

        scope.launch {
            val key = loadApiKey()
            if (key.isNullOrBlank()) {
                onError(VoiceSessionError.NO_KEY)
                return@launch
            }
            val settings = settingsRepository.settings.first()
            when (val capture = audioManager.start(scope)) {
                is AudioCaptureResult.Failed -> {
                    onError(
                        when (capture.reason) {
                            AudioFailureReason.NO_PERMISSION -> VoiceSessionError.NO_PERMISSION
                            AudioFailureReason.MIC_UNAVAILABLE -> VoiceSessionError.MIC_BUSY
                        }
                    )
                }
                is AudioCaptureResult.Started -> {
                    runningComposingText.clear()
                    hasCommittedCurrentTurn = false
                    setPhase(VoiceSessionPhase.RECORDING)
                    DiagnosticLog.record("Session", "start", "model=${settings.selectedModelId}")
                    liveClient.startUtterance(
                        scope = scope,
                        apiKey = key,
                        modelId = settings.selectedModelId,
                        audioFrames = capture.frames,
                        languageCodes = settings.languageCodes,
                        smartMode = settings.smartModeEnabled
                    )
                    maxDurationJob?.cancel()
                    maxDurationJob = scope.launch {
                        delay(MAX_HOLD_DURATION_MS)
                        if (phase == VoiceSessionPhase.RECORDING) {
                            stopAndFinalize(reachedCap = true)
                        }
                    }
                }
            }
        }
    }

    fun onMicReleased() {
        if (phase != VoiceSessionPhase.RECORDING) return
        stopAndFinalize(reachedCap = false)
    }

    fun abort() {
        maxDurationJob?.cancel()
        finalizeJob?.cancel()
        audioManager.stop()
        if (!hasCommittedCurrentTurn && runningComposingText.isNotEmpty()) {
            onFinishComposing()
        }
        runningComposingText.clear()
        hasCommittedCurrentTurn = false
        if (phase == VoiceSessionPhase.RECORDING || phase == VoiceSessionPhase.FINALIZING) {
            setPhase(VoiceSessionPhase.IDLE)
        }
    }

    fun close() {
        audioManager.onCaptureError = null
        abort()
        liveEventsJob?.cancel()
        liveClient.close()
    }

    fun resetPhaseToIdle() {
        setPhase(VoiceSessionPhase.IDLE)
    }

    private fun handleCaptureError(reason: AudioFailureReason) {
        if (phase != VoiceSessionPhase.RECORDING) return
        maxDurationJob?.cancel()
        finalizeJob?.cancel()
        audioManager.stop()
        liveClient.close()
        if (!hasCommittedCurrentTurn && runningComposingText.isNotEmpty()) {
            onFinishComposing()
        }
        runningComposingText.clear()
        hasCommittedCurrentTurn = false
        setPhase(VoiceSessionPhase.IDLE)
        onError(
            when (reason) {
                AudioFailureReason.NO_PERMISSION -> VoiceSessionError.NO_PERMISSION
                AudioFailureReason.MIC_UNAVAILABLE -> VoiceSessionError.MIC_BUSY
            }
        )
    }

    private fun setPhase(newPhase: VoiceSessionPhase) {
        phase = newPhase
        onPhaseChanged(newPhase)
    }

    private fun observeLiveEvents() {
        liveEventsJob?.cancel()
        liveEventsJob = scope.launch {
            liveClient.events.collect { event ->
                when (event) {
                    LiveEvent.Ready -> {
                        if (phase == VoiceSessionPhase.IDLE) {
                            setPhase(VoiceSessionPhase.WARM)
                        }
                    }
                    is LiveEvent.Interim -> {
                        if (phase == VoiceSessionPhase.RECORDING || phase == VoiceSessionPhase.FINALIZING) {
                            val clean = sanitizeSingleLine(event.text)
                            if (clean.isNotEmpty()) {
                                runningComposingText.clear()
                                runningComposingText.append(clean)
                                onInterim(clean)
                            }
                        }
                    }
                    is LiveEvent.Final -> {
                        if (phase == VoiceSessionPhase.RECORDING || phase == VoiceSessionPhase.FINALIZING) {
                            DiagnosticLog.record("Session", "final", "chars=${event.text.length} phase=$phase")
                            commitFinalText(event.text)
                            if (phase == VoiceSessionPhase.FINALIZING) {
                                finalizeJob?.cancel()
                                rotateWarmConnection()
                            }
                        }
                    }
                    LiveEvent.TurnComplete -> {
                        DiagnosticLog.record(
                            "Session",
                            "turn_complete",
                            "phase=$phase committed=$hasCommittedCurrentTurn"
                        )
                        if (phase == VoiceSessionPhase.FINALIZING) {
                            if (!hasCommittedCurrentTurn && runningComposingText.isNotEmpty()) {
                                commitFinalText(runningComposingText.toString())
                            }
                            finalizeJob?.cancel()
                            rotateWarmConnection()
                        }
                    }
                    is LiveEvent.Failed -> {
                        audioManager.stop()
                        maxDurationJob?.cancel()
                        finalizeJob?.cancel()
                        if (!hasCommittedCurrentTurn && runningComposingText.isNotEmpty()) {
                            onFinishComposing()
                        }
                        runningComposingText.clear()
                        setPhase(VoiceSessionPhase.IDLE)
                        onError(
                            when (event.error) {
                                LiveError.InvalidKey -> VoiceSessionError.INVALID_KEY
                                LiveError.QuotaExceeded -> VoiceSessionError.QUOTA_EXCEEDED
                                LiveError.RegionNotSupported -> VoiceSessionError.REGION_NOT_SUPPORTED
                                is LiveError.Network -> VoiceSessionError.GENERIC
                            }
                        )
                    }
                }
            }
        }
    }

    private fun stopAndFinalize(reachedCap: Boolean) {
        maxDurationJob?.cancel()
        audioManager.stop()
        setPhase(VoiceSessionPhase.FINALIZING)
        DiagnosticLog.record("Session", "release", "reachedCap=$reachedCap")

        finalizeJob?.cancel()
        finalizeJob = scope.launch {
            liveClient.finishUtterance(scope)
            delay(liveClient.finalizeTimeout())
            if (phase == VoiceSessionPhase.FINALIZING) {
                DiagnosticLog.record(
                    "Session",
                    "finalize_timeout",
                    "reachedCap=$reachedCap committed=$hasCommittedCurrentTurn"
                )
                if (!hasCommittedCurrentTurn && runningComposingText.isNotEmpty()) {
                    commitFinalText(runningComposingText.toString())
                } else {
                    onFinishComposing()
                }
                if (reachedCap) {
                    liveClient.close()
                    setPhase(VoiceSessionPhase.IDLE)
                    onError(VoiceSessionError.MAX_DURATION)
                } else {
                    rotateWarmConnection()
                }
            }
        }
    }

    private fun rotateWarmConnection() {
        DiagnosticLog.record("Session", "rotate")
        liveClient.drainAndDetach()
        setPhase(VoiceSessionPhase.IDLE)
        warmUp()
    }

    private fun commitFinalText(text: String) {
        if (hasCommittedCurrentTurn) return
        hasCommittedCurrentTurn = true
        runningComposingText.clear()
        val clean = sanitizeSingleLine(text)
        if (clean.isNotEmpty()) {
            onFinal(clean)
        } else {
            onFinishComposing()
        }
    }

    companion object {
        private const val MAX_HOLD_DURATION_MS = 60_000L
        private val EDGE_LINE_BREAK_REGEX = Regex(
            """^(?:[ \t]*[\r\n\u000B\u000C\u0085\u2028\u2029]+[ \t]*)+|(?:[ \t]*[\r\n\u000B\u000C\u0085\u2028\u2029]+[ \t]*)+$"""
        )
        private val INNER_LINE_BREAK_REGEX = Regex(
            """[ \t]*[\r\n\u000B\u000C\u0085\u2028\u2029]+[ \t]*"""
        )

        internal fun sanitizeSingleLine(text: String): String {
            val trimmedEdges = EDGE_LINE_BREAK_REGEX.replace(text, "")
            return INNER_LINE_BREAK_REGEX.replace(trimmedEdges, " ")
        }
    }
}
