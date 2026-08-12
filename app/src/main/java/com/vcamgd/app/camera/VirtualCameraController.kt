package com.vcamgd.app.camera

import android.content.Context
import android.net.Uri
import android.util.Log
import com.vcamgd.app.data.VideoSourceType
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
 * Controller v0.9: motor proprio [KingEngine] + hooks soft Zygisk.
 */
class VirtualCameraController(private val context: Context) {
    private val _status = MutableStateFlow(VirtualCameraStatus())
    val status: StateFlow<VirtualCameraStatus> = _status.asStateFlow()

    suspend fun refreshModuleStatus() {
        val alive = withContext(Dispatchers.IO) { KingEngine.isAlive() }
        val hook = withContext(Dispatchers.IO) { NativeBridge.readModuleStatus() }
        _status.value = _status.value.copy(
            moduleInstalled = alive || NativeBridge.isModulePresent(),
            zygiskEvent = if (alive) "kingvd: ${KingEngine.statusLine()} | $hook" else hook,
            message = when {
                alive && _status.value.state == VirtualCameraState.ENABLED ->
                    "Virtual ativa (KingEngine)"
                alive -> "Motor kingvd pronto"
                else -> "Motor parado — Magisk+Zygisk + root"
            },
        )
    }

    suspend fun enable(
        sourceType: VideoSourceType,
        localUri: Uri?,
        networkUrl: String,
    ): Result<Unit> {
        _status.value = _status.value.copy(
            state = VirtualCameraState.ENABLING,
            message = "Iniciando motor KingEngine...",
        )
        delay(50)

        val boot = withContext(Dispatchers.IO) { KingEngine.ensureRunning(context) }
        if (boot is KingEngine.Result.Failed) {
            fail(boot.reason)
            return Result.failure(IllegalStateException(boot.reason))
        }

        val playTarget: String = when (sourceType) {
            VideoSourceType.LOCAL_FILE -> {
                if (localUri == null) {
                    fail("Selecione um arquivo de video")
                    return Result.failure(IllegalArgumentException("missing local video"))
                }
                val staged = withContext(Dispatchers.IO) {
                    NativeBridge.setLocalVideoSource(context, localUri)
                }
                if (!staged) {
                    fail("Falha ao preparar video (root/storage)")
                    return Result.failure(IllegalStateException("stage failed"))
                }
                "/data/local/tmp/vcamgd/current.mp4"
            }
            VideoSourceType.NETWORK_STREAM -> {
                val url = networkUrl.trim()
                if (url.isBlank()) {
                    fail("Informe URL RTSP/HTTP")
                    return Result.failure(IllegalArgumentException("missing url"))
                }
                withContext(Dispatchers.IO) { NativeBridge.setNetworkSource(context, url) }
                url
            }
            VideoSourceType.USB_TRANSFER -> {
                fail("USB ainda nao suportado no motor proprio")
                return Result.failure(IllegalStateException("usb unsupported"))
            }
        }

        val play = withContext(Dispatchers.IO) { KingEngine.startPlay(playTarget) }
        if (play is KingEngine.Result.Failed) {
            fail(play.reason)
            return Result.failure(IllegalStateException(play.reason))
        }

        withContext(Dispatchers.IO) { NativeBridge.restartCameraApps() }
        _status.value = VirtualCameraStatus(
            state = VirtualCameraState.ENABLED,
            message = "Virtual ON (KingEngine). Reabra a camera.",
            usingRealCamera = false,
            moduleInstalled = true,
            zygiskEvent = KingEngine.statusLine(),
        )
        Log.i("KingVCam", "enable OK target=$playTarget")
        return Result.success(Unit)
    }

    suspend fun disable(): Result<Unit> {
        withContext(Dispatchers.IO) {
            KingEngine.stopPlay()
            NativeBridge.disable(context)
            NativeBridge.restartCameraApps()
        }
        _status.value = VirtualCameraStatus(
            state = VirtualCameraState.DISABLED,
            message = "Virtual OFF — camera real",
            usingRealCamera = true,
            moduleInstalled = KingEngine.isAlive() || NativeBridge.isModulePresent(),
            zygiskEvent = "stopped",
        )
        return Result.success(Unit)
    }

    fun switchToRealCamera() {
        KingEngine.switchReal()
        NativeBridge.switchToReal(context)
        _status.value = _status.value.copy(
            usingRealCamera = true,
            message = "Modo REAL",
            state = VirtualCameraState.ENABLED,
        )
    }

    fun switchToVirtualCamera() {
        KingEngine.switchVirtual()
        NativeBridge.switchToVirtual(context)
        _status.value = _status.value.copy(
            usingRealCamera = false,
            message = "Modo VIRTUAL",
            state = VirtualCameraState.ENABLED,
        )
    }

    private fun fail(message: String) {
        _status.value = _status.value.copy(
            state = VirtualCameraState.ERROR,
            message = message,
            usingRealCamera = true,
        )
    }
}
