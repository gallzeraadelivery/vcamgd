package com.vcamgd.app.camera

import android.content.Context
import android.os.Build
import android.util.Log
import com.vcamgd.app.root.RootShell
import com.vcamgd.app.root.SelinuxLive
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Motor APK+root only (vcplax) — SEM Zygisk / SEM modulo Magisk.
 *
 * HyperOS / Android 16 (parecer root):
 * 1) SELinux permissivo + domains OEM na sessao
 * 2) ptrace_scope=0 (reaplicado em loop)
 * 3) denylist Magisk liberando cameraserver
 * 4) bounce HAL OEM + vcplax + confirma libvc nas maps
 * 5) startPlay
 * 6) watchdog re-inject se cameraserver perder libvc
 * 7) force-stop apps — nao matar cameraserver de novo
 */
object UniversalEngine {
    private const val TAG = "KingVCam-Universal"
    private const val VCPLAX_LOG = "/data/local/tmp/vcamgd/vcplax.log"

    sealed class Result {
        data object Ok : Result()
        data class Failed(val reason: String) : Result()
    }

    data class Diagnostics(
        val sdk: Int = Build.VERSION.SDK_INT,
        val release: String = Build.VERSION.RELEASE ?: "?",
        val engine: String = "none",
        val detail: String = "",
    )

    @Volatile
    var lastDiag: Diagnostics = Diagnostics()
        private set

    @Volatile
    private var virtualSession = false

    @Volatile
    private var appContext: Context? = null

    private val watchdogRunning = AtomicBoolean(false)

    @Volatile
    private var watchdogThread: Thread? = null

    fun bindContext(context: Context) {
        appContext = context.applicationContext
    }

    fun ensureRunning(context: Context): Result {
        bindContext(context)
        val sdk = Build.VERSION.SDK_INT
        if (sdk < 31) {
            return Result.Failed("Android ${Build.VERSION.RELEASE} abaixo do minimo (12+)")
        }
        if (!RootShell.hasRoot(timeoutSec = 6)) {
            return Result.Failed("Root (su) necessario")
        }

        val se = SelinuxLive.applyForCameraInject()
        val win = CameraInjectHardener.openWindow()
        openInjectWindow(sdk)
        Log.i(TAG, "selinux: ok=${se.ok} ${se.detail} window=${win.take(80)}")

        return when (val r = bootVcplax(context)) {
            is Result.Ok -> {
                lastDiag = Diagnostics(
                    engine = "vcplax",
                    detail = "sdk=$sdk brand=${Build.BRAND}/${Build.MANUFACTURER} " +
                        "hyper=${CameraInjectHardener.isHyperOsFamily()} " +
                        "se=${se.ok} inject=${isLibVcInjected()}",
                )
                Result.Ok
            }
            is Result.Failed -> {
                lastDiag = Diagnostics(engine = "none", detail = r.reason)
                Result.Failed(r.reason)
            }
        }
    }

    fun isAlive(context: Context): Boolean {
        bindContext(context)
        return VcplaxEngine.isAlive(context)
    }

    fun startPlay(pathOrUrl: String): Result {
        openInjectWindow(Build.VERSION.SDK_INT)
        CameraInjectHardener.openWindow()
        virtualSession = true

        val retries = if (CameraInjectHardener.isHyperOsFamily() || Build.VERSION.SDK_INT >= 35) 5 else 3
        if (!ensureInjected(retries = retries)) {
            lastDiag = lastDiag.copy(detail = lastDiag.detail + " inject_fail")
            stopWatchdog()
            return Result.Failed(
                "Inject no cameraserver falhou (libvc ausente nas maps). " +
                    "HyperOS pode estar bloqueando ptrace — root permanente + Magisk/KernelSU. " +
                    "Diag: ${CameraInjectHardener.snapshotDiag().lineSequence().take(4).joinToString(" | ")}",
            )
        }

        var code = VcplaxEngine.startPlay(pathOrUrl, loop = true, autoRotate = false)
        Log.i(TAG, "startPlay#1=$code path=$pathOrUrl")
        if (code == 0) {
            Thread.sleep(350)
            CameraInjectHardener.keepWindowAlive()
            code = VcplaxEngine.startPlay(pathOrUrl, loop = true, autoRotate = false)
            Log.i(TAG, "startPlay#2=$code")
        }

        // Pos-play: HyperOS as vezes respawna HAL e perde o map — reconfirma
        Thread.sleep(400)
        CameraInjectHardener.keepWindowAlive()
        if (!isLibVcInjected()) {
            Log.w(TAG, "libvc lost after play — re-inject")
            if (!ensureInjected(retries = 3)) {
                stopWatchdog()
                return Result.Failed("Inject perdido apos play (libvc sumiu das maps)")
            }
            code = VcplaxEngine.startPlay(pathOrUrl, loop = true, autoRotate = false)
            Log.i(TAG, "startPlay#re=$code")
        }

        val alive = RootShell.run("pidof vcplax 2>/dev/null", timeoutSec = 3).trim().isNotEmpty()
        val injected = isLibVcInjected()
        lastDiag = lastDiag.copy(
            engine = "vcplax",
            detail = "play=$code inject=$injected alive=$alive " +
                "hyper=${CameraInjectHardener.isHyperOsFamily()}",
        )

        return if (code != 0 || (alive && injected)) {
            startWatchdog(pathOrUrl)
            Result.Ok
        } else {
            stopWatchdog()
            Result.Failed("startPlay falhou (code=$code inject=$injected)")
        }
    }

    fun stopPlay(): Result {
        virtualSession = false
        stopWatchdog()
        runCatching { VcplaxEngine.stopPlay() }
        // HyperOS: so volta enforcing se NAO for familia Xiaomi (eles resetam e matam sessao)
        if (Build.VERSION.SDK_INT >= 35 && !CameraInjectHardener.isHyperOsFamily()) {
            SelinuxLive.setEnforcing(true)
        }
        return Result.Ok
    }

    fun statusLine(context: Context): String {
        val d = lastDiag
        val pid = RootShell.run("pidof vcplax cameraserver 2>/dev/null", timeoutSec = 3).trim()
        val inj = isLibVcInjected()
        val enf = RootShell.run("getenforce", timeoutSec = 2).trim()
        val ptr = RootShell.run(
            "cat /proc/sys/kernel/yama/ptrace_scope 2>/dev/null",
            timeoutSec = 2,
        ).trim()
        return "engine=${d.engine} alive=${isAlive(context)} inject=$inj " +
            "sdk=${d.sdk}/${d.release} se=$enf ptrace=$ptr " +
            "hyper=${CameraInjectHardener.isHyperOsFamily()} " +
            "pids={$pid} ${d.detail}"
    }

    private fun bootVcplax(context: Context): Result {
        return when (val r = VcplaxEngine.ensureRunning(context, restoreEnforcing = false)) {
            is VcplaxEngine.Result.Ok -> {
                val abi = if (RootShell.run("file /system/bin/cameraserver 2>/dev/null", timeoutSec = 4)
                        .contains("32-bit")
                ) {
                    "armeabi-v7a"
                } else {
                    "arm64-v8a"
                }
                val engineDir = File(context.filesDir, "vcam-engine/$abi").absolutePath
                CameraInjectHardener.redeployLibPaths(engineDir)
                ensureInjected(
                    retries = if (CameraInjectHardener.isHyperOsFamily()) 3 else 2,
                )
                Result.Ok
            }
            is VcplaxEngine.Result.Failed -> Result.Failed(r.reason)
        }
    }

    /** Reinicia HAL e garante libvc dentro do cameraserver. */
    fun ensureInjected(retries: Int): Boolean {
        repeat(retries) { attempt ->
            CameraInjectHardener.keepWindowAlive()
            CameraInjectHardener.openWindow()
            prepareCameraServerForInject()
            // HyperOS: cameraserver demora a estabilizar antes do ptrace grudar
            val settleMs = if (CameraInjectHardener.isHyperOsFamily() || Build.VERSION.SDK_INT >= 35) {
                900L + attempt * 200L
            } else {
                600L
            }
            Thread.sleep(settleMs)
            if (isLibVcInjected()) {
                Log.i(TAG, "libvc injected attempt=${attempt + 1}")
                return true
            }
            Log.w(TAG, "libvc NOT in maps — restarting vcplax attempt=${attempt + 1}")
            val ctx = appContext
            if (ctx != null) {
                val abi = if (RootShell.run("file /system/bin/cameraserver 2>/dev/null", timeoutSec = 4)
                        .contains("32-bit")
                ) {
                    "armeabi-v7a"
                } else {
                    "arm64-v8a"
                }
                CameraInjectHardener.redeployLibPaths(
                    File(ctx.filesDir, "vcam-engine/$abi").absolutePath,
                )
                VcplaxEngine.ensureRunning(ctx, restoreEnforcing = false)
                Thread.sleep(800)
                if (isLibVcInjected()) return true
                // Fallback: daemon sob contexto magisk_file
                VcplaxEngine.restartFromAdbPath(ctx)
                Thread.sleep(900)
            }
            if (isLibVcInjected()) return true
        }
        val maps = RootShell.run(
            "PID=\$(pidof cameraserver | awk '{print \$1}'); " +
                "echo PID=\$PID; " +
                "cat /proc/\$PID/maps 2>/dev/null | grep -E 'libvc|shadow|vcplax' | head -10; " +
                "getenforce; cat /proc/sys/kernel/yama/ptrace_scope 2>/dev/null; " +
                "tail -n 40 $VCPLAX_LOG 2>/dev/null",
            timeoutSec = 8,
        )
        Log.e(TAG, "inject diagnostics:\n$maps")
        lastDiag = lastDiag.copy(
            detail = "maps=${maps.lineSequence().take(4).joinToString(" | ")}",
        )
        return isLibVcInjected()
    }

    fun isLibVcInjected(): Boolean {
        val out = RootShell.run(
            "PID=\$(pidof cameraserver | awk '{print \$1}'); " +
                "if [ -z \"\$PID\" ]; then echo NO_CAM; exit 0; fi; " +
                "cat /proc/\$PID/maps 2>/dev/null | grep -E '/data/libvc|/libvc\\.so|libvc\\+\\+|adb/vcamgd/libvc' | head -5; " +
                "echo END",
            timeoutSec = 5,
        )
        return out.contains("libvc")
    }

    fun prepareCameraServerForInject() {
        val out = CameraInjectHardener.bounceCameraStack()
        Log.i(TAG, "prepareCameraServer: $out")
    }

    private fun openInjectWindow(sdk: Int) {
        SelinuxLive.setEnforcing(false)
        RootShell.run(
            "echo 0 > /proc/sys/kernel/yama/ptrace_scope 2>/dev/null; " +
                "MP=\$(command -v magiskpolicy 2>/dev/null || echo /data/adb/magisk/magiskpolicy); " +
                "\"\$MP\" --live 'permissive cameraserver' >/dev/null 2>&1; " +
                "\"\$MP\" --live 'permissive su' >/dev/null 2>&1; " +
                "\"\$MP\" --live 'permissive vendor_camera_provider' >/dev/null 2>&1; " +
                "\"\$MP\" --live 'permissive hal_camera_default' >/dev/null 2>&1; " +
                "\"\$MP\" --live 'permissive mtk_hal_camera' >/dev/null 2>&1; " +
                "\"\$MP\" --live 'allow su cameraserver process ptrace' >/dev/null 2>&1; " +
                "\"\$MP\" --live 'allow cameraserver cameraserver process execmem' >/dev/null 2>&1; " +
                "true",
            timeoutSec = 8,
        )
        if (sdk >= 35) {
            RootShell.run("setenforce 0", timeoutSec = 3)
        }
    }

    private fun startWatchdog(playPath: String) {
        if (!watchdogRunning.compareAndSet(false, true)) return
        val t = Thread({
            Log.i(TAG, "inject watchdog start")
            var ticks = 0
            while (virtualSession && watchdogRunning.get()) {
                try {
                    Thread.sleep(2500)
                    ticks++
                    CameraInjectHardener.keepWindowAlive()
                    if (!virtualSession) break
                    if (!isLibVcInjected()) {
                        Log.w(TAG, "watchdog: libvc missing — re-inject tick=$ticks")
                        if (ensureInjected(retries = 2)) {
                            VcplaxEngine.startPlay(playPath, loop = true, autoRotate = false)
                            lastDiag = lastDiag.copy(
                                detail = "watchdog_reinject_ok tick=$ticks",
                            )
                        } else {
                            lastDiag = lastDiag.copy(
                                detail = "watchdog_reinject_fail tick=$ticks",
                            )
                        }
                    } else if (ticks % 4 == 0) {
                        // Reaplica play periodicamente (binder pode dropar no HyperOS)
                        VcplaxEngine.startPlay(playPath, loop = true, autoRotate = false)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "watchdog: ${t.message}")
                }
            }
            watchdogRunning.set(false)
            Log.i(TAG, "inject watchdog stop")
        }, "kingvcam-inject-wd")
        t.isDaemon = true
        watchdogThread = t
        t.start()
    }

    private fun stopWatchdog() {
        watchdogRunning.set(false)
        watchdogThread = null
    }
}
