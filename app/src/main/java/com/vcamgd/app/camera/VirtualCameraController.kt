package com.vcamgd.app.camera

import android.content.Context
import android.net.Uri
import com.vcamgd.app.data.VideoSourceType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

class VirtualCameraController(private val context: Context) {
    private val _status = MutableStateFlow(VirtualCameraStatus())
    val status: StateFlow<VirtualCameraStatus> = _status.asStateFlow()

    suspend fun refreshModuleStatus() {
        val installed = NativeBridge.isModulePresent()
        val event = if (installed) NativeBridge.readModuleStatus() else ""
        _status.value = _status.value.copy(
            moduleInstalled = installed,
            zygiskEvent = event,
            message = when {
                !installed -> "Motor ainda nao ativo — abra o app com root (instala sozinho) e reinicie"
                _status.value.state == VirtualCameraState.ENABLED && !_status.value.usingRealCamera ->
                    "Modo virtual ativo"
                _status.value.state == VirtualCameraState.ENABLED && _status.value.usingRealCamera ->
                    "Modo real (pass-through)"
                else -> "Modulo Zygisk detectado"
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
            message = "Ativando camera virtual...",
        )
        delay(200)

        val configured = when (sourceType) {
            VideoSourceType.LOCAL_FILE -> {
                if (localUri == null) {
                    fail("Selecione um arquivo de video")
                    return Result.failure(IllegalArgumentException("missing local video"))
                }
                NativeBridge.setLocalVideoSource(context, localUri)
            }
            VideoSourceType.NETWORK_STREAM -> {
                if (networkUrl.isBlank()) {
                    fail("Informe a URL RTSP/HTTP (RTMP pode falhar)")
                    return Result.failure(IllegalArgumentException("missing url"))
                }
                val ok = NativeBridge.setNetworkSource(context, networkUrl)
                if (!ok) {
                    fail("URL de rede invalida. Use rtsp:// ou http(s)://")
                    return Result.failure(IllegalArgumentException("invalid url"))
                }
                true
            }
            VideoSourceType.USB_TRANSFER -> NativeBridge.setUsbSource(context)
        }

        return if (configured) {
            _status.value = VirtualCameraStatus(
                state = VirtualCameraState.ENABLED,
                message = "Virtual (soft/OVCAM-like). Reabra a camera. Status mostra feeding:...",
                usingRealCamera = false,
                moduleInstalled = NativeBridge.isModulePresent(),
                zygiskEvent = NativeBridge.readModuleStatus(),
            )
            Result.success(Unit)
        } else {
            fail("Falha ao gravar controle (conceda root ao KingVCam)")
            Result.failure(IllegalStateException("native configure failed"))
        }
    }

    suspend fun disable(): Result<Unit> {
        delay(100)
        NativeBridge.disable(context)
        _status.value = VirtualCameraStatus(
            state = VirtualCameraState.DISABLED,
            message = "Desligada — camera real liberada (apps de camera reiniciados)",
            usingRealCamera = true,
            moduleInstalled = NativeBridge.isModulePresent(),
            zygiskEvent = NativeBridge.readModuleStatus(),
        )
        return Result.success(Unit)
    }

    fun switchToRealCamera() {
        NativeBridge.switchToReal(context)
        _status.value = _status.value.copy(
            usingRealCamera = true,
            message = "Modo REAL — camera padrao. Reabra o app da camera.",
            state = VirtualCameraState.ENABLED,
        )
    }

    fun switchToVirtualCamera() {
        NativeBridge.switchToVirtual(context)
        _status.value = _status.value.copy(
            usingRealCamera = false,
            message = "Modo VIRTUAL — video. Reabra o app da camera.",
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
