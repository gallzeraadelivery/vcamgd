package com.vcamgd.app.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("vcamgd_settings")

enum class VideoSourceType {
    LOCAL_FILE,
    NETWORK_STREAM,
    USB_TRANSFER,
}

data class AppPreferences(
    val eulaAccepted: Boolean = false,
    val virtualCameraEnabled: Boolean = false,
    val overlayEnabled: Boolean = false,
    val sourceType: VideoSourceType = VideoSourceType.LOCAL_FILE,
    val localVideoUri: String? = null,
    val networkUrl: String = "",
    val activationCode: String = "",
    val activated: Boolean = false,
)

class AppSettings(private val context: Context) {
    private object Keys {
        val eula = booleanPreferencesKey("eula_accepted")
        val vcam = booleanPreferencesKey("virtual_camera_enabled")
        val overlay = booleanPreferencesKey("overlay_enabled")
        val source = stringPreferencesKey("source_type")
        val localUri = stringPreferencesKey("local_video_uri")
        val networkUrl = stringPreferencesKey("network_url")
        val activation = stringPreferencesKey("activation_code")
        val activated = booleanPreferencesKey("activated")
    }

    val preferences: Flow<AppPreferences> = context.dataStore.data.map { prefs ->
        AppPreferences(
            eulaAccepted = prefs[Keys.eula] ?: false,
            virtualCameraEnabled = prefs[Keys.vcam] ?: false,
            overlayEnabled = prefs[Keys.overlay] ?: false,
            sourceType = runCatching {
                VideoSourceType.valueOf(prefs[Keys.source] ?: VideoSourceType.LOCAL_FILE.name)
            }.getOrDefault(VideoSourceType.LOCAL_FILE),
            localVideoUri = prefs[Keys.localUri],
            networkUrl = prefs[Keys.networkUrl] ?: "",
            activationCode = prefs[Keys.activation] ?: "",
            activated = prefs[Keys.activated] ?: false,
        )
    }

    suspend fun setEulaAccepted(value: Boolean) {
        context.dataStore.edit { it[Keys.eula] = value }
    }

    suspend fun setVirtualCameraEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.vcam] = value }
    }

    suspend fun setOverlayEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.overlay] = value }
    }

    suspend fun setSourceType(type: VideoSourceType) {
        context.dataStore.edit { it[Keys.source] = type.name }
    }

    suspend fun setLocalVideoUri(uri: Uri?) {
        context.dataStore.edit {
            if (uri == null) it.remove(Keys.localUri) else it[Keys.localUri] = uri.toString()
        }
    }

    suspend fun setNetworkUrl(url: String) {
        context.dataStore.edit { it[Keys.networkUrl] = url }
    }

    suspend fun setActivation(code: String, activated: Boolean) {
        context.dataStore.edit {
            it[Keys.activation] = code
            it[Keys.activated] = activated
        }
    }
}
