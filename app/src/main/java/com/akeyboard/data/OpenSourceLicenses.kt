// Copyright 2026 Max97k
// SPDX-License-Identifier: Apache-2.0
package com.akeyboard.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val LICENSEE_ASSET_PATH = "app/cash/licensee/artifacts.json"

@Serializable
internal data class LicenseeSpdxLicense(
    val identifier: String,
    val name: String? = null,
    val url: String? = null
)

@Serializable
internal data class LicenseeUnknownLicense(
    val name: String? = null,
    val url: String? = null
)

@Serializable
internal data class LicenseeArtifact(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val name: String? = null,
    val spdxLicenses: List<LicenseeSpdxLicense> = emptyList(),
    val unknownLicenses: List<LicenseeUnknownLicense> = emptyList()
)

data class OpenSourceNotice(
    val displayName: String,
    val coordinates: String,
    val licenseSummary: String
)

private val licenseeJson = Json { ignoreUnknownKeys = true }

internal fun parseLicenseeArtifacts(rawJson: String): List<OpenSourceNotice> {
    if (rawJson.isBlank()) return emptyList()
    val artifacts = runCatching {
        licenseeJson.decodeFromString<List<LicenseeArtifact>>(rawJson)
    }.getOrNull() ?: return emptyList()

    return artifacts
        .map { artifact ->
            val coordinates = "${artifact.groupId}:${artifact.artifactId}:${artifact.version}"
            val displayName = artifact.name?.trim()?.takeIf { it.isNotEmpty() }
                ?: "${artifact.groupId}:${artifact.artifactId}"
            val spdxNames = artifact.spdxLicenses.mapNotNull { entry ->
                entry.name?.trim()?.takeIf { it.isNotEmpty() } ?: entry.identifier.takeIf { it.isNotBlank() }
            }
            val unknownNames = artifact.unknownLicenses.mapNotNull { entry ->
                entry.name?.trim()?.takeIf { it.isNotEmpty() } ?: entry.url?.trim()?.takeIf { it.isNotEmpty() }
            }
            val licenseSummary = (spdxNames + unknownNames)
                .distinct()
                .joinToString(" · ")
                .ifEmpty { "Unknown license" }
            OpenSourceNotice(
                displayName = displayName,
                coordinates = coordinates,
                licenseSummary = licenseSummary
            )
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
}

internal fun loadBundledOpenSourceNotices(context: Context): List<OpenSourceNotice> {
    val raw = runCatching {
        context.assets.open(LICENSEE_ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull() ?: return emptyList()
    return parseLicenseeArtifacts(raw)
}
