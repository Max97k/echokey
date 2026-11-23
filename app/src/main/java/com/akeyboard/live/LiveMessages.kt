// Copyright 2026 Max97k
// SPDX-License-Identifier: Apache-2.0
package com.akeyboard.live

import kotlinx.serialization.Serializable

@Serializable
data class BidiSetupEnvelope(
    val setup: BidiSetup
)

@Serializable
data class BidiSetup(
    val model: String,
    val generationConfig: BidiGenerationConfig,
    val inputAudioTranscription: InputAudioTranscriptionConfig,
    val realtimeInputConfig: RealtimeInputConfig
)

@Serializable
data class BidiGenerationConfig(
    val responseModalities: List<String>
)

@Serializable
data class InputAudioTranscriptionConfig(
    val languageCodes: List<String>? = null,
    val mode: String? = null
)

@Serializable
data class RealtimeInputConfig(
    val automaticActivityDetection: AutomaticActivityDetectionConfig
)

@Serializable
data class AutomaticActivityDetectionConfig(
    val disabled: Boolean
)

@Serializable
data class RealtimeAudioEnvelope(
    val realtimeInput: RealtimeAudioInput
)

@Serializable
data class RealtimeAudioInput(
    val audio: RealtimeAudioBlob
)

@Serializable
data class RealtimeAudioBlob(
    val mimeType: String = "audio/pcm;rate=16000",
    val data: String
)

@Serializable
data class RealtimeActivityStartEnvelope(
    val realtimeInput: RealtimeActivityStartInput = RealtimeActivityStartInput()
)

@Serializable
data class RealtimeActivityStartInput(
    val activityStart: EmptyObject = EmptyObject()
)

@Serializable
data class RealtimeActivityEndEnvelope(
    val realtimeInput: RealtimeActivityEndInput = RealtimeActivityEndInput()
)

@Serializable
data class RealtimeActivityEndInput(
    val activityEnd: EmptyObject = EmptyObject()
)

@Serializable
class EmptyObject

@Serializable
data class ServerEnvelope(
    val setupComplete: EmptyObject? = null,
    val serverContent: ServerContent? = null,
    val usageMetadata: UsageMetadata? = null,
    val error: ServerError? = null
)

@Serializable
data class UsageMetadata(
    val promptTokenCount: Int? = null,
    val cachedContentTokenCount: Int? = null,
    val responseTokenCount: Int? = null,
    val thoughtsTokenCount: Int? = null,
    val totalTokenCount: Int? = null,
    val promptTokensDetails: List<ModalityTokenCount>? = null,
    val cacheTokensDetails: List<ModalityTokenCount>? = null,
    val responseTokensDetails: List<ModalityTokenCount>? = null
)

@Serializable
data class ModalityTokenCount(
    val modality: String? = null,
    val tokenCount: Int? = null
)

@Serializable
data class ServerContent(
    val interimInputTranscription: TranscriptionText? = null,
    val inputTranscription: TranscriptionText? = null,
    val generationComplete: Boolean? = null,
    val turnComplete: Boolean? = null
)

@Serializable
data class TranscriptionText(
    val text: String = ""
)

@Serializable
data class ServerError(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null
)
