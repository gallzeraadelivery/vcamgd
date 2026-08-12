package com.vcamgd.app.camera

import android.content.Context
import android.net.Uri
import android.util.Log
import com.vcamgd.app.data.VideoSourceType
import com.vcamgd.app.root.RootShell
import com.vcamgd.app.util.KingVCamLog
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
            message = "Iniciando motor (vcplax)…",
        )
        KingVCamLog.i("enable", "inicio source=$sourceType")
        delay(50)

        val boot = withContext(Dispatchers.IO) { UniversalEngine.ensureRunning(context) }
        if (boot is UniversalEngine.Result.Failed) {
            KingVCamLog.e("enable", "boot: ${boot.reason}")
            fail(boot.reason)
            return Result.failure(IllegalStateException(boot.reason))
        }

        _status.value = _status.value.copy(message = "Preparando video…")
        val playTarget: String = when (sourceType) {
            VideoSourceType.LOCAL_FILE -> {
                if (localUri == null) {
                    fail("Selecione um arquivo de video")
                    return Result.failure(IllegalArgumentException("missing local video"))
                }
                val staged = withContext(Dispatchers.IO) { stageLocalToPath(localUri) }
                if (staged == null) {
                    KingVCamLog.e("video", "stage falhou uri=$localUri")
                    fail("Falha ao preparar video (root/storage)")
                    return Result.failure(IllegalStateException("stage failed"))
                }
                KingVCamLog.i("video", "staged=$staged")
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

        _status.value = _status.value.copy(message = "Injetando libvc no cameraserver…")
        val play = withContext(Dispatchers.IO) { UniversalEngine.startPlay(playTarget) }
        if (play is UniversalEngine.Result.Failed) {
            KingVCamLog.e("enable", "play: ${play.reason}")
            fail(play.reason)
            return Result.failure(IllegalStateException(play.reason))
        }

        // Estabiliza: play de novo + força apps de camera (sem bounce do HAL)
        _status.value = _status.value.copy(message = "Confirmando inject + play…")
        delay(400)
        withContext(Dispatchers.IO) {
            runCatching {
                UniversalEngine.startPlay(playTarget)
                RootShell.runGlobal(
                    "chmod 644 /data/local/tmp/vcamgd/current.mp4 /dev/vcam/current.mp4 2>/dev/null; " +
                        "chcon u:object_r:system_data_file:s0 /data/local/tmp/vcamgd/current.mp4 2>/dev/null; " +
                        "PID=\$(pidof cameraserver | awk '{print \$1}'); " +
                        "echo cam=\$PID inject=\$(cat /proc/\$PID/maps 2>/dev/null | grep -cE 'libvc\\.so|libvc\\+\\+'); " +
                        "ls -l /data/local/tmp/vcamgd/current.mp4 2>/dev/null | head -1",
                    timeoutSec = 6,
                )
            }
        }
        delay(300)
        withContext(Dispatchers.IO) {
            runCatching { NativeBridge.restartCameraApps() }
            KingVCamLog.i("enable", "force-stop apps de camera (HyperOS enxuto)")
        }
        delay(500)
        val stillInjected = withContext(Dispatchers.IO) {
            runCatching {
                val alive = RootShell.run("pidof cameraserver 2>/dev/null", timeoutSec = 3).trim()
                if (alive.isEmpty()) {
                    Log.w("KingVCam", "cameraserver morreu apos play — recuperando")
                    KingVCamLog.w("cam", "morreu apos force-stop — recuperando")
                    UniversalEngine.ensureInjected(retries = 2)
                    UniversalEngine.startPlay(playTarget)
                } else if (!UniversalEngine.isLibVcInjected()) {
                    KingVCamLog.w("inject", "sumiu apos force-stop — soft kinginject")
                    // Soft: kinginject sem bounce se so o map sumiu
                    CameraInjectHardener.keepWindowAlive()
                    CameraInjectHardener.runKingInject()
                    UniversalEngine.startPlay(playTarget)
                }
                UniversalEngine.isLibVcInjected()
            }.getOrDefault(false)
        }
        if (!stillInjected) {
            KingVCamLog.e("enable", "inject=false apos estabilizar")
            withContext(Dispatchers.IO) {
                KingVCamLog.auditWhyNoPreview("enable_inject_lost")
            }
            fail(
                "Inject nao ficou no cameraserver (inject=false). " +
                    "Desligue/ligue; se repetir, veja Status (ki=/maps=).",
            )
            return Result.failure(IllegalStateException("inject=false after enable"))
        }
        withContext(Dispatchers.IO) {
            KingVCamLog.i("enable", "OK inject=true target=$playTarget")
            KingVCamLog.auditWhyNoPreview("enable_ok_abrir_camera_e_checar_preview")
        }
        _status.value = VirtualCameraStatus(
            state = VirtualCameraState.ENABLED,
            message = "Virtual ON (inject=true). Feche a camera, espere 3s, abra de novo. Veja Status → Log se nao for o video.",
            usingRealCamera = false,
            moduleInstalled = true,
            zygiskEvent = runCatching { UniversalEngine.statusLine(context) }.getOrDefault(""),
        )
        Log.i("KingVCam", "enable OK target=$playTarget inject=true diag=${UniversalEngine.lastDiag}")
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

    suspend fun switchToRealCamera() {
        withContext(Dispatchers.IO) {
            runCatching { UniversalEngine.stopPlay() }
            runCatching { NativeBridge.switchToReal(context) }
        }
        _status.value = _status.value.copy(
            usingRealCamera = true,
            message = "Modo REAL",
            state = VirtualCameraState.ENABLED,
        )
    }

    suspend fun switchToVirtualCamera() {
        val prefs = context.getSharedPreferences("vcam_runtime", Context.MODE_PRIVATE)
        val path = prefs.getString("uri", null)?.takeIf { it.startsWith("/") }
            ?: prefs.getString("url", null)
            ?: "/data/local/tmp/vcamgd/current.mp4"
        withContext(Dispatchers.IO) {
            runCatching { NativeBridge.switchToVirtual(context) }
            runCatching { UniversalEngine.startPlay(path) }
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
            if (cache.length() < 1024) {
                Log.e("KingVCam", "stageLocalToPath: arquivo muito pequeno ${cache.length()}")
                return null
            }
            val dest = "/data/local/tmp/vcamgd/current.mp4"
            val out = RootShell.runGlobal(
                "mkdir -p /data/local/tmp/vcamgd /data/adb/vcamgd /dev/vcam; " +
                    "cp '${cache.absolutePath}' '$dest'; " +
                    "cp '$dest' /data/adb/vcamgd/current.mp4; " +
                    "cp '$dest' /dev/vcam/current.mp4; " +
                    "cp '$dest' /data/local/tmp/current.mp4; " +
                    "chmod 755 /data/local/tmp /data/local/tmp/vcamgd /dev/vcam; " +
                    "chmod 644 '$dest' /dev/vcam/current.mp4 /data/local/tmp/current.mp4 /data/adb/vcamgd/current.mp4; " +
                    "chcon u:object_r:system_data_file:s0 '$dest' /data/local/tmp/current.mp4 2>/dev/null; " +
                    "chcon u:object_r:magisk_file:s0 /data/adb/vcamgd/current.mp4 2>/dev/null; " +
                    "chcon u:object_r:system_lib_file:s0 /dev/vcam/current.mp4 2>/dev/null; " +
                    "SZ=\$(wc -c < '$dest'); echo SIZE=\$SZ; ls -lZ '$dest'; " +
                    "if [ \"\$SZ\" -gt 1000 ]; then echo OK; fi",
                timeoutSec = 14,
            )
            Log.i("KingVCam", "stageLocalToPath: ${out.take(240)}")
            KingVCamLog.i("video", "stage ${out.take(180)}")
            if (out.contains("OK")) {
                // Overlay/switchVirtual precisam do path absoluto, nao content://
                context.getSharedPreferences("vcam_runtime", Context.MODE_PRIVATE)
                    .edit()
                    .putString("uri", dest)
                    .putString("source", "local")
                    .putBoolean("enabled", true)
                    .putBoolean("virtual", true)
                    .putString("mode", "virtual")
                    .apply()
                dest
            } else {
                null
            }
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
