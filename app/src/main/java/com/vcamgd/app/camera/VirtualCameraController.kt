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
    val message: String = "Câmera virtual desativada",
    val usingRealCamera: Boolean = true,
    val moduleInstalled: Boolean = false,
)

/**
 * Controlador da câmera virtual.
 *
 * O OVCAM original injeta frames via camada nativa (bootstrap + payload criptografado)
 * e exige root/módulo. Aqui a API pública já está pronta; a implementação nativa
 * será plugada em [NativeBridge] / módulo Magisk.
 */
class VirtualCameraController(private val context: Context) {
    private val _status = MutableStateFlow(VirtualCameraStatus())
    val status: StateFlow<VirtualCameraStatus> = _status.asStateFlow()

    suspend fun refreshModuleStatus() {
        val installed = NativeBridge.isModulePresent(context)
        _status.value = _status.value.copy(
            moduleInstalled = installed,
            message = if (installed) {
                "Módulo detectado"
            } else {
                "Módulo de câmera virtual não instalado"
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
            message = "Ativando câmera virtual…",
        )
        delay(400)

        if (!NativeBridge.isModulePresent(context)) {
            val error = "Instale o módulo Magisk/LSPosed do VCamGD para ativar a câmera virtual"
            _status.value = _status.value.copy(
                state = VirtualCameraState.ERROR,
                message = error,
                usingRealCamera = true,
            )
            return Result.failure(IllegalStateException(error))
        }

        val configured = when (sourceType) {
            VideoSourceType.LOCAL_FILE -> {
                if (localUri == null) {
                    fail("Selecione um arquivo de vídeo")
                    return Result.failure(IllegalArgumentException("missing local video"))
                }
                NativeBridge.setLocalVideoSource(context, localUri)
            }
            VideoSourceType.NETWORK_STREAM -> {
                if (networkUrl.isBlank()) {
                    fail("Informe a URL RTSP/RTMP")
                    return Result.failure(IllegalArgumentException("missing url"))
                }
                NativeBridge.setNetworkSource(context, networkUrl)
            }
            VideoSourceType.USB_TRANSFER -> NativeBridge.setUsbSource(context)
        }

        return if (configured) {
            _status.value = VirtualCameraStatus(
                state = VirtualCameraState.ENABLED,
                message = "Câmera virtual ativa",
                usingRealCamera = false,
                moduleInstalled = true,
            )
            Result.success(Unit)
        } else {
            fail("Falha ao configurar fonte de vídeo")
            Result.failure(IllegalStateException("native configure failed"))
        }
    }

    suspend fun disable(): Result<Unit> {
        delay(200)
        NativeBridge.disable(context)
        _status.value = VirtualCameraStatus(
            state = VirtualCameraState.DISABLED,
            message = "Usando câmera padrão do sistema",
            usingRealCamera = true,
            moduleInstalled = NativeBridge.isModulePresent(context),
        )
        return Result.success(Unit)
    }

    fun switchToRealCamera() {
        NativeBridge.switchToReal(context)
        _status.value = _status.value.copy(
            usingRealCamera = true,
            message = "Trocado para câmera real",
        )
    }

    fun switchToVirtualCamera() {
        NativeBridge.switchToVirtual(context)
        _status.value = _status.value.copy(
            usingRealCamera = false,
            message = "Trocado para câmera virtual",
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

/**
 * Ponte para o módulo nativo.
 * Stub seguro: detecta marcador do módulo e ainda não injeta frames.
 */
object NativeBridge {
    private const val MODULE_MARKER = "/data/adb/modules/vcamgd/module.prop"
    private const val LEGACY_MARKER = "/data/local/tmp/vcamgd.ready"

    fun isModulePresent(context: Context): Boolean {
        return try {
            java.io.File(MODULE_MARKER).exists() || java.io.File(LEGACY_MARKER).exists()
        } catch (_: Exception) {
            false
        }
    }

    fun setLocalVideoSource(context: Context, uri: Uri): Boolean {
        // TODO: enviar FD/path para o módulo nativo
        context.getSharedPreferences("vcam_runtime", Context.MODE_PRIVATE)
            .edit()
            .putString("source", "local")
            .putString("uri", uri.toString())
            .apply()
        return isModulePresent(context)
    }

    fun setNetworkSource(context: Context, url: String): Boolean {
        context.getSharedPreferences("vcam_runtime", Context.MODE_PRIVATE)
            .edit()
            .putString("source", "network")
            .putString("url", url)
            .apply()
        return isModulePresent(context)
    }

    fun setUsbSource(context: Context): Boolean {
        context.getSharedPreferences("vcam_runtime", Context.MODE_PRIVATE)
            .edit()
            .putString("source", "usb")
            .apply()
        return isModulePresent(context)
    }

    fun disable(context: Context) {
        context.getSharedPreferences("vcam_runtime", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", false)
            .apply()
    }

    fun switchToReal(context: Context) {
        context.getSharedPreferences("vcam_runtime", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("virtual", false)
            .apply()
    }

    fun switchToVirtual(context: Context) {
        context.getSharedPreferences("vcam_runtime", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("virtual", true)
            .apply()
    }
}
