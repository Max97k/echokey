package com.akeyboard

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.akeyboard.data.ApiKeyStore
import com.akeyboard.data.ModelRegistryClient
import com.akeyboard.data.SettingsRepository
import com.akeyboard.data.UsageDelta
import com.akeyboard.data.UsageRepository
import com.akeyboard.data.UsageStore
import com.akeyboard.data.UsageStoreSerializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "akeyboard_settings")
private val Context.usageDataStore: DataStore<UsageStore> by dataStore(
    fileName = "akeyboard_usage.json",
    serializer = UsageStoreSerializer,
    corruptionHandler = ReplaceFileCorruptionHandler { UsageStore() }
)

class AKeyboardApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var okHttpClient: OkHttpClient
        private set
    lateinit var apiKeyStore: ApiKeyStore
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var usageRepository: UsageRepository
        private set
    lateinit var modelRegistryClient: ModelRegistryClient
        private set

    override fun onCreate() {
        super.onCreate()
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        apiKeyStore = ApiKeyStore(dataStore)
        settingsRepository = SettingsRepository(dataStore)
        usageRepository = UsageRepository(usageDataStore)
        modelRegistryClient = ModelRegistryClient(okHttpClient)
    }

    fun recordUsage(delta: UsageDelta) {
        if (!delta.hasAnyUsage) return
        applicationScope.launch {
            try {
                usageRepository.record(delta)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort local usage accounting must never crash dictation.
            }
        }
    }
}
