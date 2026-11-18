package com.akeyboard.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class AudioCaptureResult {
    data class Started(val frames: Channel<ByteArray>) : AudioCaptureResult()
    data class Failed(val reason: AudioFailureReason) : AudioCaptureResult()
}

enum class AudioFailureReason {
    NO_PERMISSION,
    MIC_UNAVAILABLE
}

class AudioCaptureManager {

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope): AudioCaptureResult {
        stop()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
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
            return AudioCaptureResult.Failed(AudioFailureReason.NO_PERMISSION)
        } catch (_: IllegalArgumentException) {
            return AudioCaptureResult.Failed(AudioFailureReason.MIC_UNAVAILABLE)
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return AudioCaptureResult.Failed(AudioFailureReason.MIC_UNAVAILABLE)
        }

        try {
            recorder.startRecording()
        } catch (_: IllegalStateException) {
            recorder.release()
            return AudioCaptureResult.Failed(AudioFailureReason.MIC_UNAVAILABLE)
        } catch (_: SecurityException) {
            recorder.release()
            return AudioCaptureResult.Failed(AudioFailureReason.NO_PERMISSION)
        }

        audioRecord = recorder
        val channel = Channel<ByteArray>(
            capacity = CHANNEL_CAPACITY_FRAMES,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        captureJob = scope.launch(Dispatchers.IO) {
            val readBuf = ByteArray(FRAME_BYTES)
            try {
                while (isActive) {
                    val read = recorder.read(readBuf, 0, FRAME_BYTES)
                    if (read > 0) {
                        channel.trySend(readBuf.copyOf(read))
                    } else if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                        read == AudioRecord.ERROR_DEAD_OBJECT ||
                        read == AudioRecord.ERROR_BAD_VALUE
                    ) {
                        break
                    }
                }
            } finally {
                channel.close()
            }
        }
        return AudioCaptureResult.Started(channel)
    }

    fun stop() {
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
