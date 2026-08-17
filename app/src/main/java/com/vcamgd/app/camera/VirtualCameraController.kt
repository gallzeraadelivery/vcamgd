package com.vcamgd.app.camera

import android.content.Context
import android.net.Uri
import com.vcamgd.app.data.VideoSourceType
import com.vcamgd.app.util.KingVCamLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class VirtualCameraState {
    DISABLED,
    ENABLING,
    ENABLED,
    ERROR,
}

data class VirtualCameraStatus(
    val state: VirtualCameraState = VirtualCameraState.DISABLED,
    val message: String = "Camera virtual desativada",
    val usingRealCamera: Boolean = true,
    val moduleInstalled: Boolean = false,
    val zygiskEvent: String = "",
)

/**
 * Controller motor B: Zygisk + Pine hooks no app Camera (VCAM-like).
 * vcplax/libvc nao e mais requisito para enable.
 */
class VirtualCameraController(private val context: Context) {
    private val _status = MutableStateFlow(VirtualCameraStatus())
    val status: StateFlow<VirtualCameraStatus> = _status.asStateFlow()

    init {
        UniversalEngine.bindContext(context)
    }

    suspend fun refreshModuleStatus() {
        val installed = withContext(Dispatchers.IO) { AppHookEngine.isModuleLive() }
        val feeder = withContext(Dispatchers.IO) { AppHookEngine.feederStatus() }
        _status.value = _status.value.copy(
            moduleInstalled = installed,
            zygiskEvent = feeder,
            message = when {
                _status.value.state == VirtualCameraState.ENABLED ->
                    "Virtual ON (hooks no app). ${shortFeeder(feeder)}"
                installed -> "Modulo Zygisk pronto — ative a virtual"
                else -> "Modulo Zygisk ausente — ative a virtual (instala + 1 reboot)"
            },
        )
    }

    suspend fun resumeVirtualSession(): Result<Unit> {
        _status.value = _status.value.copy(message = "Retomando hooks no app Camera…")
        return when (val r = withContext(Dispatchers.IO) { AppHookEngine.resume(context) }) {
            is AppHookEngine.Result.Ok -> {
                _status.value = VirtualCameraStatus(
                    state = VirtualCameraState.ENABLED,
                    message = "Virtual retomada. Feche Camera Xiaomi (recentes) e abra de novo.",
                    usingRealCamera = false,
                    moduleInstalled = true,
                    zygiskEvent = withContext(Dispatchers.IO) { AppHookEngine.feederStatus() },
                )
                Result.success(Unit)
            }
            is AppHookEngine.Result.NeedsReboot -> {
                fail(r.reason)
                Result.failure(IllegalStateException(r.reason))
            }
            is AppHookEngine.Result.Failed -> {
                fail(r.reason)
                Result.failure(IllegalStateException(r.reason))
            }
        }
    }

    suspend fun enable(
        sourceType: VideoSourceType,
        localUri: Uri?,
        networkUrl: String,
    ): Result<Unit> {
        _status.value = _status.value.copy(
            state = VirtualCameraState.ENABLING,
            message = "Instalando/ativando hooks no app Camera…",
        )
        KingVCamLog.i("enable", "hook-path source=$sourceType")
        delay(50)

        val r = withContext(Dispatchers.IO) {
            AppHookEngine.enable(context, sourceType, localUri, networkUrl)
        }
        return when (r) {
            is AppHookEngine.Result.Ok -> {
                delay(400)
                val feeder = withContext(Dispatchers.IO) { AppHookEngine.feederStatus() }
                _status.value = VirtualCameraStatus(
                    state = VirtualCameraState.ENABLED,
                    message = "Virtual ON (hooks). Feche Camera Xiaomi (recentes) e abra de novo.",
                    usingRealCamera = false,
                    moduleInstalled = true,
                    zygiskEvent = feeder,
                )
                KingVCamLog.i("enable", "OK hook feeder=$feeder")
                Result.success(Unit)
            }
            is AppHookEngine.Result.NeedsReboot -> {
                _status.value = VirtualCameraStatus(
                    state = VirtualCameraState.ERROR,
                    message = r.reason,
                    usingRealCamera = true,
                    moduleInstalled = true,
                    zygiskEvent = "needs_reboot",
                )
                Result.failure(IllegalStateException(r.reason))
            }
            is AppHookEngine.Result.Failed -> {
                fail(r.reason)
                Result.failure(IllegalStateException(r.reason))
            }
        }
    }

    suspend fun disable(): Result<Unit> {
        withContext(Dispatchers.IO) { AppHookEngine.disable(context) }
        _status.value = VirtualCameraStatus(
            state = VirtualCameraState.DISABLED,
            message = "Virtual OFF — camera real",
            usingRealCamera = true,
            moduleInstalled = AppHookEngine.isModuleLive(),
            zygiskEvent = "stopped",
        )
        return Result.success(Unit)
    }

    suspend fun switchToRealCamera() {
        withContext(Dispatchers.IO) { NativeBridge.switchToReal(context) }
        _status.value = _status.value.copy(
            usingRealCamera = true,
            message = "Modo REAL",
            state = VirtualCameraState.ENABLED,
        )
    }

    suspend fun switchToVirtualCamera() {
        withContext(Dispatchers.IO) {
            NativeBridge.writeVirtualControl(context, restartApps = true)
        }
        _status.value = _status.value.copy(
            usingRealCamera = false,
            message = "Modo VIRTUAL (hooks)",
            state = VirtualCameraState.ENABLED,
        )
    }

    private fun shortFeeder(raw: String): String {
        val f = Regex("\"feeder\":\"([^\"]+)\"").find(raw)?.groupValues?.getOrNull(1)
        return f ?: raw.take(80)
    }

    private fun fail(message: String) {
        _status.value = _status.value.copy(
            state = VirtualCameraState.ERROR,
            message = message,
            usingRealCamera = true,
        )
    }
}
