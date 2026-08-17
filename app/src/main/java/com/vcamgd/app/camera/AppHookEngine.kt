package com.vcamgd.app.camera

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.vcamgd.app.root.RootShell
import com.vcamgd.app.util.KingVCamLog
import java.io.File

/**
 * Motor B: hooks Camera1/Camera2 **no processo do app** (Zygisk + Pine + hook.dex).
 *
 * E o caminho que a internet documenta (VCAM / xCam / LSPosed): substitui Surfaces
 * no app da Camera, nao no cameraserver. libvc/vcplax fica opcional.
 *
 * Fluxo:
 *  1. Instalar modulo Magisk/Zygisk embutido (1 reboot na 1a vez)
 *  2. Stage do mp4 em /data/local/tmp/vcamgd/current.mp4 (+ espelho VCAM)
 *  3. control.json enabled+virtual+inject=hard
 *  4. force-stop **so o app Camera** (Zygisk injeta no proximo start)
 *  5. NAO mata cameraserver / NAO bounce HAL
 */
object AppHookEngine {
    private const val TAG = "KingVCam-AppHook"
    const val VIDEO_TMP = "/data/local/tmp/vcamgd/current.mp4"
    private const val VIDEO_ADB = "/data/adb/vcamgd/current.mp4"
    private const val VIDEO_DEV = "/dev/vcam/current.mp4"
    private const val VCAM_PUBLIC = "/sdcard/DCIM/Camera1/virtual.mp4"
    private const val STATUS_TMP = "/data/local/tmp/vcamgd/status.json"

    sealed class Result {
        data object Ok : Result()
        data class NeedsReboot(val reason: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun ensureReady(context: Context): Result {
        EngineFacade.setInjectStyle(EngineFacade.InjectStyle.HARD)
        return when (val r = ModuleInstaller.ensureInstalled(context)) {
            is ModuleInstaller.Result.AlreadyInstalled -> {
                KingVCamLog.i("hook", "modulo Zygisk ja ativo")
                Result.Ok
            }
            is ModuleInstaller.Result.InstalledNeedsReboot -> {
                KingVCamLog.w("hook", "modulo instalado — precisa reboot")
                Result.NeedsReboot(
                    "Modulo Zygisk instalado. Reinicie o celular 1 vez (Magisk → Zygisk ON) e ative de novo.",
                )
            }
            is ModuleInstaller.Result.Failed -> {
                KingVCamLog.e("hook", "modulo: ${r.reason}")
                Result.Failed(r.reason)
            }
        }
    }

    fun isModuleLive(): Boolean = ModuleInstaller.isModulePresent()

    fun feederStatus(): String {
        val raw = RootShell.run("cat $STATUS_TMP 2>/dev/null", timeoutSec = 3).trim()
        return raw.ifBlank { "sem status do hook ainda" }
    }

    fun enable(
        context: Context,
        sourceType: com.vcamgd.app.data.VideoSourceType,
        localUri: Uri?,
        networkUrl: String,
    ): Result {
        return try {
            EngineFacade.setInjectStyle(EngineFacade.InjectStyle.HARD)
            when (val ready = ensureReady(context)) {
                is Result.NeedsReboot -> return ready
                is Result.Failed -> return ready
                is Result.Ok -> Unit
            }

            val playPath: String = when (sourceType) {
                com.vcamgd.app.data.VideoSourceType.LOCAL_FILE -> {
                    if (localUri == null) {
                        return Result.Failed("Selecione um arquivo de video")
                    }
                    val staged = stageLocalVideo(context, localUri)
                        ?: return Result.Failed("Falha ao copiar video (root/storage)")
                    staged
                }
                com.vcamgd.app.data.VideoSourceType.NETWORK_STREAM -> {
                    val url = networkUrl.trim()
                    if (url.isBlank()) return Result.Failed("Informe URL RTSP/HTTP/RTMP")
                    url
                }
                com.vcamgd.app.data.VideoSourceType.USB_TRANSFER ->
                    return Result.Failed("USB: use arquivo ou rede neste motor")
            }

            val ok = if (sourceType == com.vcamgd.app.data.VideoSourceType.NETWORK_STREAM) {
                NativeBridge.setNetworkSource(context, networkUrl)
            } else {
                persistLocal(context, playPath)
                NativeBridge.writeVirtualControl(
                    context = context,
                    source = "local",
                    uri = VIDEO_TMP,
                    url = "",
                    restartApps = false,
                )
            }
            if (!ok) {
                return Result.Failed("Falha ao escrever control.json")
            }

            // Zygisk so entra no proximo start do processo — force-stop do APP, nao do HAL
            NativeBridge.restartCameraApps()
            KingVCamLog.i(
                "hook",
                "enable OK hard sdk=${Build.VERSION.SDK_INT} path=$playPath status=${feederStatus()}",
            )
            Result.Ok
        } catch (t: Throwable) {
            Log.e(TAG, "enable", t)
            Result.Failed(t.message ?: "erro no motor hook")
        }
    }

    fun resume(context: Context): Result {
        return try {
            EngineFacade.setInjectStyle(EngineFacade.InjectStyle.HARD)
            when (val ready = ensureReady(context)) {
                is Result.NeedsReboot -> return ready
                is Result.Failed -> return ready
                is Result.Ok -> Unit
            }
            NativeBridge.writeVirtualControl(
                context = context,
                restartApps = false,
            )
            NativeBridge.restartCameraApps()
            KingVCamLog.i("hook", "resume OK status=${feederStatus()}")
            Result.Ok
        } catch (t: Throwable) {
            Result.Failed(t.message ?: "erro resume hook")
        }
    }

    fun disable(context: Context) {
        NativeBridge.disable(context)
        KingVCamLog.i("hook", "disable — mode=real")
    }

    private fun persistLocal(context: Context, path: String) {
        context.getSharedPreferences("vcam_runtime", Context.MODE_PRIVATE)
            .edit()
            .putString("source", "local")
            .putString("uri", path)
            .putString("url", "")
            .putBoolean("enabled", true)
            .putBoolean("virtual", true)
            .putString("mode", "virtual")
            .apply()
    }

    private fun stageLocalVideo(context: Context, uri: Uri): String? {
        return try {
            val cache = File(context.cacheDir, "vcam_input.mp4")
            context.contentResolver.openInputStream(uri)?.use { input ->
                cache.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (cache.length() < 1024) {
                KingVCamLog.e("video", "arquivo muito pequeno ${cache.length()}")
                return null
            }
            val src = cache.absolutePath
            val camPkg = "com.android.camera"
            val out = RootShell.runGlobal(
                "mkdir -p /data/local/tmp/vcamgd /data/adb/vcamgd /dev/vcam " +
                    "/sdcard/DCIM/Camera1 /sdcard/Android/data/$camPkg/files/Camera1; " +
                    "cp '$src' '$VIDEO_TMP'; " +
                    "cp '$src' '$VIDEO_ADB'; " +
                    "cp '$src' '$VIDEO_DEV'; " +
                    "cp '$src' '$VCAM_PUBLIC'; " +
                    "cp '$src' /sdcard/Android/data/$camPkg/files/Camera1/virtual.mp4; " +
                    "chmod 777 /data/local/tmp/vcamgd /sdcard/DCIM/Camera1; " +
                    "chmod 666 '$VIDEO_TMP' '$VIDEO_ADB' '$VIDEO_DEV' '$VCAM_PUBLIC' " +
                    "/sdcard/Android/data/$camPkg/files/Camera1/virtual.mp4 2>/dev/null; " +
                    "chcon u:object_r:system_data_file:s0 '$VIDEO_TMP' 2>/dev/null; " +
                    "SZ=\$(wc -c < '$VIDEO_TMP'); echo SIZE=\$SZ; " +
                    "if [ \"\$SZ\" -gt 1000 ]; then echo OK; fi",
                timeoutSec = 16,
            )
            KingVCamLog.i("video", "hook-stage ${out.take(180)}")
            if (out.contains("OK")) VIDEO_TMP else null
        } catch (t: Throwable) {
            Log.e(TAG, "stageLocalVideo", t)
            null
        }
    }
}
