package com.akeyboard.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class ModelInfo(
    val id: String,
    val displayName: String,
    val isTranscribeModel: Boolean
)

sealed class ModelRegistryException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidKey(message: String = "Invalid API key") : ModelRegistryException(message)
    class QuotaExceeded(message: String = "Quota exceeded") : ModelRegistryException(message)
    class RegionNotSupported(message: String = "User location is not supported") : ModelRegistryException(message)
    class NetworkError(message: String, cause: Throwable? = null) : ModelRegistryException(message, cause)
}

@Serializable
internal data class ModelsListResponse(
    val models: List<RemoteModel> = emptyList(),
    val nextPageToken: String? = null
)

@Serializable
internal data class RemoteModel(
    val name: String = "",
    val displayName: String = "",
    val supportedGenerationMethods: List<String> = emptyList()
)

class ModelRegistryClient(
    private val httpClient: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun fetchLiveModels(apiKey: String): Result<List<ModelInfo>> = withContext(Dispatchers.IO) {
        try {
            val collected = mutableListOf<ModelInfo>()
            var pageToken: String? = null
            do {
                val urlBuilder = "$baseUrl/v1beta/models".toHttpUrl().newBuilder()
                    .addQueryParameter("pageSize", "1000")
                if (!pageToken.isNullOrBlank()) {
                    urlBuilder.addQueryParameter("pageToken", pageToken)
                }
                val request = Request.Builder()
                    .url(urlBuilder.build())
                    .header("x-goog-api-key", apiKey.trim())
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val bodyStr = response.body.string()
                    if (!response.isSuccessful) {
                        throw mapHttpError(response.code, bodyStr)
                    }
                    val parsed = json.decodeFromString<ModelsListResponse>(bodyStr)
                    for (m in parsed.models) {
                        if (m.supportedGenerationMethods.contains(METHOD_BIDI)) {
                            val id = m.name.removePrefix("models/")
                            if (id.isNotBlank()) {
                                val isTranscribe = id.contains("transcribe", ignoreCase = true) ||
                                    m.displayName.contains("transcribe", ignoreCase = true)
                                collected += ModelInfo(
                                    id = id,
                                    displayName = m.displayName.ifBlank { id },
                                    isTranscribeModel = isTranscribe
                                )
                            }
                        }
                    }
                    pageToken = parsed.nextPageToken
                }
            } while (!pageToken.isNullOrBlank())

            val sorted = collected.sortedWith(
                compareByDescending<ModelInfo> { it.isTranscribeModel }
                    .thenBy { it.id }
            )
            Result.success(sorted)
        } catch (e: ModelRegistryException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(ModelRegistryException.NetworkError("Network error while listing models", e))
        } catch (e: Exception) {
            Result.failure(ModelRegistryException.NetworkError("Failed to parse models response", e))
        }
    }

    private fun mapHttpError(code: Int, body: String): ModelRegistryException {
        val isRegion = body.contains("location is not supported", ignoreCase = true) ||
            body.contains("Regional restrictions", ignoreCase = true) ||
            (body.contains("FAILED_PRECONDITION", ignoreCase = true) && body.contains("location", ignoreCase = true))
        if (isRegion) {
            return ModelRegistryException.RegionNotSupported()
        }
        return when {
            code == 401 || code == 403 -> ModelRegistryException.InvalidKey()
            code == 400 && body.contains("API_KEY_INVALID", ignoreCase = true) ->
                ModelRegistryException.InvalidKey()
            code == 429 -> ModelRegistryException.QuotaExceeded()
            else -> ModelRegistryException.NetworkError("HTTP $code")
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        private const val METHOD_BIDI = "bidiGenerateContent"
    }
}
