package com.akeyboard

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.akeyboard.data.ApiKeyStore
import com.akeyboard.data.ModelRegistryClient
import com.akeyboard.data.SettingsRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "akeyboard_settings")

class AKeyboardApp : Application() {

    lateinit var okHttpClient: OkHttpClient
        private set
    lateinit var apiKeyStore: ApiKeyStore
        private set
    lateinit var settingsRepository: SettingsRepository
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
        modelRegistryClient = ModelRegistryClient(okHttpClient)
    }
}
