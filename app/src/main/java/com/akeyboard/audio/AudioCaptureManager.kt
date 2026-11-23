// Copyright 2026 Max97k
// SPDX-License-Identifier: Apache-2.0
package com.akeyboard.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.akeyboard.util.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

sealed class AudioCaptureResult {
    data class Started(val frames: Channel<ByteArray>) : AudioCaptureResult()
    data class Failed(val reason: AudioFailureReason) : AudioCaptureResult()
}

enum class AudioFailureReason {
    NO_PERMISSION,
    MIC_UNAVAILABLE
}

open class AudioCaptureManager(
    private val context: Context? = null,
    private val preferBuiltInMic: Boolean = false,
    private val preferBuiltInMicProvider: () -> Boolean = { preferBuiltInMic }
) {
    var onCaptureError: ((AudioFailureReason) -> Unit)? = null

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val isStopping = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    open fun start(scope: CoroutineScope): AudioCaptureResult {
        stop()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            DiagnosticLog.record("Audio", "start_failed", "invalid_min_buf=$minBuf")
            return AudioCaptureResult.Failed(AudioFailureReason.MIC_UNAVAILABLE)
        }
        val bufferSize = maxOf(minBuf, FRAME_BYTES * 4)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (_: SecurityException) {
            DiagnosticLog.record("Audio", "start_failed", "security_exception")
            return AudioCaptureResult.Failed(AudioFailureReason.NO_PERMISSION)
        } catch (_: IllegalArgumentException) {
            DiagnosticLog.record("Audio", "start_failed", "illegal_arg")
            return AudioCaptureResult.Failed(AudioFailureReason.MIC_UNAVAILABLE)
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            DiagnosticLog.record("Audio", "start_failed", "uninitialized")
            recorder.release()
            return AudioCaptureResult.Failed(AudioFailureReason.MIC_UNAVAILABLE)
        }

        if (preferBuiltInMicProvider() && context != null) {
            val audioMgr = context.getSystemService(AudioManager::class.java)
            val builtIn = audioMgr
                ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            if (builtIn != null) {
                recorder.setPreferredDevice(builtIn)
            }
        }

        try {
            recorder.startRecording()
        } catch (_: IllegalStateException) {
            DiagnosticLog.record("Audio", "start_failed", "start_recording_failed")
            recorder.release()
            return AudioCaptureResult.Failed(AudioFailureReason.MIC_UNAVAILABLE)
        } catch (_: SecurityException) {
            DiagnosticLog.record("Audio", "start_failed", "start_recording_security")
            recorder.release()
            return AudioCaptureResult.Failed(AudioFailureReason.NO_PERMISSION)
        }

        audioRecord = recorder
        val channel = Channel<ByteArray>(
            capacity = CHANNEL_CAPACITY_FRAMES,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        isStopping.set(false)
        captureJob = scope.launch(Dispatchers.IO) {
            val readBuf = ByteArray(FRAME_BYTES)
            try {
                while (isActive && !isStopping.get()) {
                    val read = recorder.read(readBuf, 0, FRAME_BYTES)
                    if (read > 0) {
                        channel.trySend(readBuf.copyOf(read))
                    } else if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                        read == AudioRecord.ERROR_DEAD_OBJECT ||
                        read == AudioRecord.ERROR_BAD_VALUE
                    ) {
                        if (!isStopping.get()) {
                            DiagnosticLog.record("Audio", "read_error", "code=$read")
                            onCaptureError?.invoke(AudioFailureReason.MIC_UNAVAILABLE)
                        }
                        break
                    }
                }
            } finally {
                channel.close()
            }
        }
        return AudioCaptureResult.Started(channel)
    }

    open fun stop() {
        isStopping.set(true)
        captureJob?.cancel()
        captureJob = null
        audioRecord?.let { rec ->
            try {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
            } catch (_: IllegalStateException) {
                // Already stopped
            }
            try {
                rec.release()
            } catch (_: Exception) {
                // Ignore release errors
            }
        }
        audioRecord = null
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val FRAME_BYTES = 1280 // 40ms @ 16kHz 16-bit mono
        const val CHANNEL_CAPACITY_FRAMES = 50 // 2.0s buffer
    }
}
