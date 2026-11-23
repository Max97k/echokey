// Copyright 2026 Max97k
// SPDX-License-Identifier: Apache-2.0
package com.akeyboard.data

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `remote device and mic preferences default, update, and remove on null`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(
            storage = OkioStorage(
                fileSystem = FileSystem.SYSTEM,
                serializer = PreferencesSerializer
            ) {
                File(tempFolder.root, "settings.preferences_pb").toOkioPath()
            },
            scope = scope
        )
        val repo = SettingsRepository(dataStore)

        try {
            val initial = repo.settings.first()
            assertNull(initial.lastRemoteDeviceAddress)
            assertNull(initial.lastRemoteDeviceName)
            assertTrue(initial.remotePreferBuiltInMic)

            repo.setLastRemoteDevice("AA:BB:CC:DD:EE:FF", "Office-PC")
            repo.setRemotePreferBuiltInMic(false)

            val updated = repo.settings.first()
            assertEquals("AA:BB:CC:DD:EE:FF", updated.lastRemoteDeviceAddress)
            assertEquals("Office-PC", updated.lastRemoteDeviceName)
            assertFalse(updated.remotePreferBuiltInMic)

            repo.setLastRemoteDevice("11:22:33:44:55:66", null)
            val nameCleared = repo.settings.first()
            assertEquals("11:22:33:44:55:66", nameCleared.lastRemoteDeviceAddress)
            assertNull(nameCleared.lastRemoteDeviceName)
            val rawAfterNameClear = dataStore.data.first()
            assertTrue(rawAfterNameClear.contains(stringPreferencesKey("last_remote_device_address")))
            assertFalse(rawAfterNameClear.contains(stringPreferencesKey("last_remote_device_name")))

            repo.setLastRemoteDevice(null, null)
            repo.setRemotePreferBuiltInMic(true)

            val cleared = repo.settings.first()
            assertNull(cleared.lastRemoteDeviceAddress)
            assertNull(cleared.lastRemoteDeviceName)
            assertTrue(cleared.remotePreferBuiltInMic)
            val rawAfterClear = dataStore.data.first()
            assertFalse(rawAfterClear.contains(stringPreferencesKey("last_remote_device_address")))
            assertFalse(rawAfterClear.contains(stringPreferencesKey("last_remote_device_name")))
        } finally {
            scope.cancel()
        }
    }
}
