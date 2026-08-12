package com.vcamgd.app.camera

import android.content.Context
import android.net.Uri
import android.util.Log
import com.vcamgd.app.data.VideoSourceType
import com.vcamgd.app.root.RootShell
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
 * Controller primario: motor vcplax (APK + root + inject nativo).
 * Sem Magisk module / sem reboot.
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
                alive && _status.value.state == VirtualCameraState.ENABLED ->
                    "Virtual ativa (vcplax)"
                alive -> "Motor vcplax pronto"
                else -> "Motor parado — ative a virtual (APK + root)"
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
        delay(50)

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

        // Espelho IPC legado so para status/UI
        withContext(Dispatchers.IO) {
            runCatching {
                when (sourceType) {
                    VideoSourceType.LOCAL_FILE ->
                        NativeBridge.setLocalVideoSource(context, localUri!!)
                    VideoSourceType.NETWORK_STREAM ->
                        NativeBridge.setNetworkSource(context, networkUrl)
                    else -> Unit
                }
            }
        }

        val code = withContext(Dispatchers.IO) {
            VcplaxEngine.startPlay(playTarget, loop = true, autoRotate = false)
        }
        Log.i("KingVCam", "vcplax startPlay code=$code path=$playTarget")

        // Referencia: code==0 => falhou; !=0 = OK
        return if (code != 0) {
            withContext(Dispatchers.IO) { NativeBridge.restartCameraApps() }
            _status.value = VirtualCameraStatus(
                state = VirtualCameraState.ENABLED,
                message = "Virtual ON (vcplax). Abra a camera.",
                usingRealCamera = false,
                moduleInstalled = true,
                zygiskEvent = "startPlay=$code",
            )
            Result.success(Unit)
        } else {
            fail("Motor recusou play (code=0)")
            Result.failure(IllegalStateException("startPlay=0"))
        }
    }

    suspend fun disable(): Result<Unit> {
        withContext(Dispatchers.IO) {
            VcplaxEngine.stopPlay()
            runCatching { NativeBridge.disable(context) }
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
        runCatching { NativeBridge.switchToReal(context) }
        _status.value = _status.value.copy(
            usingRealCamera = true,
            message = "Modo REAL",
            state = VirtualCameraState.ENABLED,
        )
    }

    fun switchToVirtualCamera() {
        runCatching { NativeBridge.switchToVirtual(context) }
        val prefs = context.getSharedPreferences("vcam_runtime", Context.MODE_PRIVATE)
        val path = prefs.getString("uri", null)?.takeIf { it.startsWith("/") }
            ?: prefs.getString("url", null)
            ?: "/data/local/tmp/vcamgd/current.mp4"
        if (path.isNotBlank()) {
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
            val out = RootShell.run(
                "mkdir -p /data/local/tmp/vcamgd /data/adb/vcamgd; " +
                    "cp '${cache.absolutePath}' '$dest'; " +
                    "cp '$dest' /data/adb/vcamgd/current.mp4 2>/dev/null; " +
                    "chmod 777 /data/local/tmp/vcamgd; chmod 666 '$dest'; " +
                    "chcon u:object_r:magisk_file:s0 '$dest' 2>/dev/null; " +
                    "ls -l '$dest'; echo OK",
                timeoutSec = 10,
            )
            if (out.contains("OK")) dest else null
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
