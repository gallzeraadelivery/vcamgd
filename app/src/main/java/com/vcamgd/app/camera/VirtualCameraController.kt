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
import java.io.File

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
 * Controller principal: motor vcplax (APK base liberado).
 * Mantem IPC legado so para status/compat.
 */
class VirtualCameraController(private val context: Context) {
    private val _status = MutableStateFlow(VirtualCameraStatus())
    val status: StateFlow<VirtualCameraStatus> = _status.asStateFlow()

    suspend fun refreshModuleStatus() {
        val alive = withContext(Dispatchers.IO) { VcplaxEngine.isAlive(context) }
        _status.value = _status.value.copy(
            moduleInstalled = alive,
            zygiskEvent = if (alive) "vcplax binder OK" else "vcplax parado",
            message = when {
                alive && _status.value.state == VirtualCameraState.ENABLED -> "Virtual ativa (vcplax)"
                alive -> "Motor vcplax pronto"
                else -> "Motor parado — ative a virtual (instala sozinho com root)"
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
            message = "Iniciando motor vcplax...",
        )
        delay(100)

        val boot = withContext(Dispatchers.IO) { VcplaxEngine.ensureRunning(context) }
        if (boot is VcplaxEngine.Result.Failed) {
            fail(boot.reason)
            return Result.failure(IllegalStateException(boot.reason))
        }

        val playTarget: String = when (sourceType) {
            VideoSourceType.LOCAL_FILE -> {
                if (localUri == null) {
                    fail("Selecione um arquivo de video")
                    return Result.failure(IllegalArgumentException("missing local video"))
                }
                val staged = withContext(Dispatchers.IO) { stageLocalToPath(localUri) }
                if (staged == null) {
                    fail("Falha ao preparar video (root/storage)")
                    return Result.failure(IllegalStateException("stage failed"))
                }
                staged
            }
            VideoSourceType.NETWORK_STREAM -> {
                val url = networkUrl.trim()
                if (url.isBlank()) {
                    fail("Informe URL RTSP/HTTP/RTMP")
                    return Result.failure(IllegalArgumentException("missing url"))
                }
                url
            }
            VideoSourceType.USB_TRANSFER -> {
                fail("USB: use arquivo/rede neste motor")
                return Result.failure(IllegalStateException("usb unsupported"))
            }
        }

        // Espelho IPC legado (status UI)
        withContext(Dispatchers.IO) {
            when (sourceType) {
                VideoSourceType.LOCAL_FILE ->
                    NativeBridge.setLocalVideoSource(context, localUri!!)
                VideoSourceType.NETWORK_STREAM ->
                    NativeBridge.setNetworkSource(context, networkUrl)
                else -> Unit
            }
        }

        val code = withContext(Dispatchers.IO) {
            VcplaxEngine.startPlay(playTarget, loop = true, autoRotate = false)
        }
        Log.i("KingVCam", "startPlay code=$code path=$playTarget")

        // Na referencia: code==0 => "替换失败"; qualquer !=0 = OK
        return if (code != 0) {
            _status.value = VirtualCameraStatus(
                state = VirtualCameraState.ENABLED,
                message = "Virtual ON (vcplax). Abra a camera do telefone.",
                usingRealCamera = false,
                moduleInstalled = true,
                zygiskEvent = "startPlay=$code",
            )
            withContext(Dispatchers.IO) { NativeBridge.restartCameraApps() }
            Result.success(Unit)
        } else {
            fail("Motor recusou play (code=0)")
            Result.failure(IllegalStateException("startPlay=0"))
        }
    }

    suspend fun disable(): Result<Unit> {
        withContext(Dispatchers.IO) {
            VcplaxEngine.stopPlay()
            NativeBridge.disable(context)
            NativeBridge.restartCameraApps()
        }
        _status.value = VirtualCameraStatus(
            state = VirtualCameraState.DISABLED,
            message = "Virtual OFF — camera real",
            usingRealCamera = true,
            moduleInstalled = VcplaxEngine.isAlive(context),
            zygiskEvent = "stopped",
        )
        return Result.success(Unit)
    }

    fun switchToRealCamera() {
        VcplaxEngine.stopPlay()
        NativeBridge.switchToReal(context)
        _status.value = _status.value.copy(
            usingRealCamera = true,
            message = "Modo REAL",
            state = VirtualCameraState.ENABLED,
        )
    }

    fun switchToVirtualCamera() {
        NativeBridge.switchToVirtual(context)
        // Re-play ultimo caminho via prefs se existir
        val prefs = context.getSharedPreferences("vcam_runtime", Context.MODE_PRIVATE)
        val path = prefs.getString("uri", null)?.takeIf { it.startsWith("/") }
            ?: prefs.getString("url", null)
        if (!path.isNullOrBlank()) {
            VcplaxEngine.startPlay(path, loop = true, autoRotate = false)
        }
        _status.value = _status.value.copy(
            usingRealCamera = false,
            message = "Modo VIRTUAL",
            state = VirtualCameraState.ENABLED,
        )
    }

    private fun stageLocalToPath(uri: Uri): String? {
        return try {
            val cache = File(context.cacheDir, "vcam_input.mp4")
            context.contentResolver.openInputStream(uri)?.use { input ->
                cache.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            val dest = "/data/local/tmp/vcamgd/current.mp4"
            val script =
                "mkdir -p /data/local/tmp/vcamgd; " +
                    "cp '${cache.absolutePath}' '$dest'; chmod 666 '$dest'; " +
                    "cp '$dest' /data/adb/vcamgd/current.mp4 2>/dev/null; echo OK"
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (out.contains("OK") || File(dest).exists()) dest else cache.absolutePath
        } catch (t: Throwable) {
            Log.e("KingVCam", "stageLocalToPath", t)
            null
        }
    }

    private fun fail(message: String) {
        _status.value = _status.value.copy(
            state = VirtualCameraState.ERROR,
            message = message,
            usingRealCamera = true,
        )
    }
}
