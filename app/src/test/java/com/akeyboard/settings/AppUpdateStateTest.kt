package com.akeyboard.settings

import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

class AppUpdateStateTest {

    @Test
    fun `resolveAppUpdateUiState maps downloaded and in-progress states first`() {
        assertEquals(
            AppUpdateUiState.Downloaded,
            resolveAppUpdateUiState(
                updateAvailability = UpdateAvailability.UPDATE_AVAILABLE,
                installStatus = InstallStatus.DOWNLOADED,
                flexibleAllowed = true,
                availableVersionCode = 12,
                userInitiated = false
            )
        )

        for (inProgressStatus in listOf(
            InstallStatus.DOWNLOADING,
            InstallStatus.PENDING,
            InstallStatus.INSTALLING
        )) {
            assertEquals(
                AppUpdateUiState.Downloading,
                resolveAppUpdateUiState(
                    updateAvailability = UpdateAvailability.UPDATE_AVAILABLE,
                    installStatus = inProgressStatus,
                    flexibleAllowed = true,
                    availableVersionCode = 12,
                    userInitiated = true
                )
            )
        }
    }

    @Test
    fun `resolveAppUpdateUiState handles available, up-to-date, and silent auto-check`() {
        assertEquals(
            AppUpdateUiState.Available(42),
            resolveAppUpdateUiState(
                updateAvailability = UpdateAvailability.UPDATE_AVAILABLE,
                installStatus = InstallStatus.UNKNOWN,
                flexibleAllowed = true,
                availableVersionCode = 42,
                userInitiated = false
            )
        )

        assertEquals(
            AppUpdateUiState.UpToDate,
            resolveAppUpdateUiState(
                updateAvailability = UpdateAvailability.UPDATE_AVAILABLE,
                installStatus = InstallStatus.UNKNOWN,
                flexibleAllowed = false,
                availableVersionCode = 42,
                userInitiated = true
            )
        )

        assertEquals(
            AppUpdateUiState.UpToDate,
            resolveAppUpdateUiState(
                updateAvailability = UpdateAvailability.UPDATE_NOT_AVAILABLE,
                installStatus = InstallStatus.UNKNOWN,
                flexibleAllowed = false,
                availableVersionCode = 0,
                userInitiated = true
            )
        )

        assertEquals(
            AppUpdateUiState.Idle,
            resolveAppUpdateUiState(
                updateAvailability = UpdateAvailability.UPDATE_NOT_AVAILABLE,
                installStatus = InstallStatus.UNKNOWN,
                flexibleAllowed = false,
                availableVersionCode = 0,
                userInitiated = false
            )
        )
    }

    @Test
    fun `mapInstallStatusToUiState transitions cleanly across listener events`() {
        assertEquals(
            AppUpdateUiState.Downloading,
            mapInstallStatusToUiState(InstallStatus.DOWNLOADING, AppUpdateUiState.Available(5))
        )
        assertEquals(
            AppUpdateUiState.Downloaded,
            mapInstallStatusToUiState(InstallStatus.DOWNLOADED, AppUpdateUiState.Downloading)
        )
        assertEquals(
            AppUpdateUiState.Idle,
            mapInstallStatusToUiState(InstallStatus.INSTALLED, AppUpdateUiState.Downloaded)
        )
        assertEquals(
            AppUpdateUiState.Idle,
            mapInstallStatusToUiState(InstallStatus.CANCELED, AppUpdateUiState.Downloading)
        )
        assertEquals(
            AppUpdateUiState.Idle,
            mapInstallStatusToUiState(InstallStatus.FAILED, AppUpdateUiState.Downloading)
        )
        assertEquals(
            AppUpdateUiState.UpToDate,
            mapInstallStatusToUiState(InstallStatus.UNKNOWN, AppUpdateUiState.UpToDate)
        )
    }

    @Test
    fun `buildReleaseNotesUrl maps semver versionName to tag URL and falls back safely`() {
        assertEquals(
            "https://github.com/Max97k/echokey/releases/tag/v0.1.0",
            buildReleaseNotesUrl("0.1.0")
        )
        assertEquals(
            "https://github.com/Max97k/echokey/releases/tag/v0.2.0",
            buildReleaseNotesUrl("v0.2.0")
        )
        assertEquals(
            "https://github.com/Max97k/echokey/releases/tag/v0.3.0-beta.1",
            buildReleaseNotesUrl("0.3.0-beta.1")
        )
        assertEquals(
            "https://github.com/Max97k/echokey/releases",
            buildReleaseNotesUrl("")
        )
        assertEquals(
            "https://github.com/Max97k/echokey/releases",
            buildReleaseNotesUrl("dev build")
        )
    }
}
