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
 * Controller: [UniversalEngine] — Android 12–16, APK+root (vcplax) sem reboot.
 */
class VirtualCameraController(private val context: Context) {
    private val _status = MutableStateFlow(VirtualCameraStatus())
    val status: StateFlow<VirtualCameraStatus> = _status.asStateFlow()

    init {
        UniversalEngine.bindContext(context)
    }

    suspend fun refreshModuleStatus() {
        val alive = withContext(Dispatchers.IO) { UniversalEngine.isAlive(context) }
        val line = withContext(Dispatchers.IO) { UniversalEngine.statusLine(context) }
        _status.value = _status.value.copy(
            moduleInstalled = alive,
            zygiskEvent = line,
            message = when {
                alive && _status.value.state == VirtualCameraState.ENABLED ->
                    "Virtual ativa (${UniversalEngine.lastDiag.engine})"
                alive -> "Motor pronto (${UniversalEngine.lastDiag.engine})"
                else -> "Motor parado — root + ative a virtual (Android 12–16)"
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
            message = "Iniciando motor universal (12–16)...",
        )
        delay(50)

        val boot = withContext(Dispatchers.IO) { UniversalEngine.ensureRunning(context) }
        if (boot is UniversalEngine.Result.Failed) {
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

        val play = withContext(Dispatchers.IO) { UniversalEngine.startPlay(playTarget) }
        if (play is UniversalEngine.Result.Failed) {
            fail(play.reason)
            return Result.failure(IllegalStateException(play.reason))
        }

        withContext(Dispatchers.IO) { NativeBridge.restartCameraApps() }
        _status.value = VirtualCameraStatus(
            state = VirtualCameraState.ENABLED,
            message = "Virtual ON. Abra a camera (Android ${android.os.Build.VERSION.RELEASE}).",
            usingRealCamera = false,
            moduleInstalled = true,
            zygiskEvent = UniversalEngine.statusLine(context),
        )
        Log.i("KingVCam", "enable OK target=$playTarget diag=${UniversalEngine.lastDiag}")
        return Result.success(Unit)
    }

    suspend fun disable(): Result<Unit> {
        withContext(Dispatchers.IO) {
            UniversalEngine.stopPlay()
            runCatching { NativeBridge.disable(context) }
            NativeBridge.restartCameraApps()
        }
        _status.value = VirtualCameraStatus(
            state = VirtualCameraState.DISABLED,
            message = "Virtual OFF — camera real",
            usingRealCamera = true,
            moduleInstalled = UniversalEngine.isAlive(context),
            zygiskEvent = "stopped",
        )
        return Result.success(Unit)
    }

    fun switchToRealCamera() {
        UniversalEngine.stopPlay()
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
        UniversalEngine.startPlay(path)
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
                    "chcon u:object_r:system_data_file:s0 '$dest' 2>/dev/null; " +
                    "ls -lZ '$dest'; echo OK",
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
