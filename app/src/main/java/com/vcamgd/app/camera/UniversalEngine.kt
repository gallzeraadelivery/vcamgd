package com.vcamgd.app.camera

import android.content.Context
import android.os.Build
import android.util.Log
import com.vcamgd.app.root.RootShell
import com.vcamgd.app.root.SelinuxLive
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Motor APK+root only (vcplax) — SEM Zygisk.
 *
 * v0.10.8: kinginject stack-path (sem mmap/BTI) + maps sem falso cfi shadow.
 * v0.10.7: kinginject v2 (ELF dlopen + mmap syscall) + diag ki/maps.
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
        return try {
            bindContext(context)
            val sdk = Build.VERSION.SDK_INT
            if (sdk < 31) {
                return Result.Failed("Android ${Build.VERSION.RELEASE} abaixo do minimo (12+)")
            }
            if (!RootShell.hasRoot(timeoutSec = 6)) {
                return Result.Failed("Root (su) necessario")
            }

            val se = SelinuxLive.applyForCameraInject()
            runCatching { CameraInjectHardener.openWindow() }
            openInjectWindow(sdk)
            Log.i(TAG, "selinux: ok=${se.ok} ${se.detail}")

            when (val r = bootVcplax(context)) {
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
        } catch (t: Throwable) {
            Log.e(TAG, "ensureRunning crash-guard", t)
            Result.Failed(t.message ?: "erro no motor")
        }
    }

    fun isAlive(context: Context): Boolean {
        return try {
            bindContext(context)
            VcplaxEngine.isAlive(context)
        } catch (t: Throwable) {
            Log.w(TAG, "isAlive: ${t.message}")
            false
        }
    }

    fun startPlay(pathOrUrl: String): Result {
        return try {
            openInjectWindow(Build.VERSION.SDK_INT)
            runCatching { CameraInjectHardener.openWindow() }
            virtualSession = true

            val retries = if (CameraInjectHardener.isHyperOsFamily() || Build.VERSION.SDK_INT >= 35) 3 else 2
            if (!ensureInjected(retries = retries)) {
                lastDiag = lastDiag.copy(detail = lastDiag.detail + " inject_fail")
                stopWatchdog()
                val snap = runCatching {
                    CameraInjectHardener.snapshotDiag()
                        .lineSequence()
                        .filter { line ->
                            line.startsWith("mm=") ||
                                line.startsWith("maps=") ||
                                line.startsWith("ki=") ||
                                line.startsWith("ptrace=") ||
                                line.startsWith("enforce=") ||
                                line.startsWith("cam=") ||
                                line.startsWith("vcplax=") ||
                                line.startsWith("KI_RC=")
                        }
                        .take(8)
                        .joinToString(" | ")
                }.getOrDefault("")
                return Result.Failed(
                    "Inject falhou (libvc fora do cameraserver). " +
                        "mm/ptrace ok nao basta no HyperOS A16 — veja ki= no diag. " +
                        "Diag: $snap",
                )
            }

            var code = VcplaxEngine.startPlay(pathOrUrl, loop = true, autoRotate = false)
            Log.i(TAG, "startPlay#1=$code path=$pathOrUrl")
            if (code == 0 || code == -1) {
                Thread.sleep(300)
                CameraInjectHardener.keepWindowAlive()
                // Reatacha binder se caiu
                appContext?.let { VcplaxEngine.ensureRunning(it, restoreEnforcing = false) }
                code = VcplaxEngine.startPlay(pathOrUrl, loop = true, autoRotate = false)
                Log.i(TAG, "startPlay#2=$code")
            }

            Thread.sleep(300)
            CameraInjectHardener.keepWindowAlive()
            val alive = RootShell.run("pidof vcplax 2>/dev/null", timeoutSec = 3).trim().isNotEmpty()
            val injected = isLibVcInjected()
            lastDiag = lastDiag.copy(
                engine = "vcplax",
                detail = "play=$code inject=$injected alive=$alive " +
                    "hyper=${CameraInjectHardener.isHyperOsFamily()}",
            )

            if (code != 0 || (alive && injected)) {
                startWatchdog(pathOrUrl)
                Result.Ok
            } else {
                stopWatchdog()
                Result.Failed("startPlay falhou (code=$code inject=$injected)")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startPlay crash-guard", t)
            stopWatchdog()
            virtualSession = false
            Result.Failed(t.message ?: "erro no play")
        }
    }

    fun stopPlay(): Result {
        virtualSession = false
        stopWatchdog()
        runCatching { VcplaxEngine.stopPlay() }
        return Result.Ok
    }

    fun statusLine(context: Context): String {
        return try {
            val d = lastDiag
            val pid = RootShell.run("pidof vcplax cameraserver 2>/dev/null", timeoutSec = 3).trim()
            val inj = isLibVcInjected()
            val enf = RootShell.run("getenforce", timeoutSec = 2).trim()
            val ptr = RootShell.run(
                "cat /proc/sys/kernel/yama/ptrace_scope 2>/dev/null",
                timeoutSec = 2,
            ).trim()
            "engine=${d.engine} alive=${isAlive(context)} inject=$inj " +
                "sdk=${d.sdk}/${d.release} se=$enf ptrace=$ptr " +
                "hyper=${CameraInjectHardener.isHyperOsFamily()} " +
                "pids={$pid} ${d.detail}"
        } catch (t: Throwable) {
            "status_error=${t.message}"
        }
    }

    private fun bootVcplax(context: Context): Result {
        return when (val r = VcplaxEngine.ensureRunning(context, restoreEnforcing = false)) {
            is VcplaxEngine.Result.Ok -> {
                val engineDir = File(context.filesDir, "vcam-engine/${abiDir()}").absolutePath
                runCatching { CameraInjectHardener.redeployLibPaths(engineDir) }
                // Nao chama ensureInjected no boot — matar HAL aqui crashava HyperOS/UI
                Result.Ok
            }
            is VcplaxEngine.Result.Failed -> Result.Failed(r.reason)
        }
    }

    /** Reinicia HAL e garante libvc dentro do cameraserver. */
    fun ensureInjected(retries: Int): Boolean {
        return try {
            repeat(retries) { attempt ->
                CameraInjectHardener.keepWindowAlive()
                if (attempt == 0) {
                    runCatching { CameraInjectHardener.openWindow() }
                }
                prepareCameraServerForInject()
                val settleMs = if (CameraInjectHardener.isHyperOsFamily() || Build.VERSION.SDK_INT >= 35) {
                    700L + attempt * 150L
                } else {
                    500L
                }
                Thread.sleep(settleMs)

                // HyperOS: kinginject cedo (vcplax sozinho nao gruda)
                if (CameraInjectHardener.isHyperOsFamily() || Build.VERSION.SDK_INT >= 35) {
                    val ki = CameraInjectHardener.runKingInject()
                    Log.i(TAG, "kinginject early attempt=${attempt + 1}: ${ki.take(160)}")
                    Thread.sleep(250)
                    if (isLibVcInjected()) {
                        Log.i(TAG, "libvc injected (kinginject-early) attempt=${attempt + 1}")
                        return true
                    }
                }

                // 1) deixa o vcplax tentar sozinho apos bounce
                if (isLibVcInjected()) {
                    Log.i(TAG, "libvc injected (vcplax) attempt=${attempt + 1}")
                    return true
                }

                // 2) reinicia daemon + redeploy /dev
                Log.w(TAG, "libvc NOT in maps — restarting vcplax attempt=${attempt + 1}")
                val ctx = appContext
                if (ctx != null) {
                    runCatching {
                        CameraInjectHardener.redeployLibPaths(
                            File(ctx.filesDir, "vcam-engine/${abiDir()}").absolutePath,
                        )
                    }
                    VcplaxEngine.ensureRunning(ctx, restoreEnforcing = false)
                    Thread.sleep(600)
                    if (isLibVcInjected()) return true
                }

                // 3) fallback: kinginject (ptrace dlopen proprio)
                val ki = CameraInjectHardener.runKingInject()
                Log.i(TAG, "kinginject attempt=${attempt + 1}: ${ki.take(120)}")
                Thread.sleep(300)
                if (isLibVcInjected()) {
                    Log.i(TAG, "libvc injected (kinginject) attempt=${attempt + 1}")
                    return true
                }

                if (attempt >= 1 && ctx != null) {
                    VcplaxEngine.restartFromAdbPath(ctx)
                    Thread.sleep(500)
                    CameraInjectHardener.runKingInject()
                    if (isLibVcInjected()) return true
                }
            }
            val diag = CameraInjectHardener.snapshotDiag()
            Log.e(TAG, "inject diagnostics:\n$diag")
            lastDiag = lastDiag.copy(
                detail = "maps=${diag.lineSequence().take(5).joinToString(" | ")}",
            )
            isLibVcInjected()
        } catch (t: Throwable) {
            Log.e(TAG, "ensureInjected crash-guard", t)
            false
        }
    }

    fun isLibVcInjected(): Boolean {
        return try {
            val out = RootShell.runGlobal(
                "PID=\$(pidof cameraserver | awk '{print \$1}'); " +
                    "if [ -z \"\$PID\" ]; then echo NO_CAM; exit 0; fi; " +
                    "cat /proc/\$PID/maps 2>/dev/null | grep -E 'libvc\\.so|libvc\\+\\+|/dev/vcam/|libshadowhook\\.so' | head -8; " +
                    "echo END",
                timeoutSec = 6,
            )
            out.contains("libvc.so") ||
                out.contains("libvc++") ||
                out.contains("/dev/vcam/") ||
                out.contains("libshadowhook.so")
        } catch (_: Throwable) {
            false
        }
    }

    fun prepareCameraServerForInject() {
        val out = RootShell.runGlobal(
            "OLDPID=\$(pidof cameraserver 2>/dev/null | awk '{print \$1}'); " +
                "killall -9 cameraserver 2>/dev/null; " +
                "[ -n \"\$OLDPID\" ] && kill -9 \"\$OLDPID\" 2>/dev/null; " +
                "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16; do " +
                "NEW=\$(pidof cameraserver 2>/dev/null | awk '{print \$1}'); " +
                "if [ -n \"\$NEW\" ] && [ \"\$NEW\" != \"\$OLDPID\" ]; then " +
                "echo NEW=\$NEW; sleep 0.4; exit 0; fi; sleep 0.25; done; " +
                "pidof cameraserver; echo WAIT_CAM",
            timeoutSec = 12,
        )
        Log.i(TAG, "prepareCameraServer: $out")
    }

    private fun abiDir(): String {
        val out = RootShell.run("file /system/bin/cameraserver 2>/dev/null", timeoutSec = 4)
        return if (out.contains("32-bit")) "armeabi-v7a" else "arm64-v8a"
    }

    private fun openInjectWindow(sdk: Int) {
        SelinuxLive.setEnforcing(false)
        RootShell.runGlobal(
            "echo 0 > /proc/sys/kernel/yama/ptrace_scope 2>/dev/null; " +
                "MP=\$(command -v magiskpolicy 2>/dev/null || echo /data/adb/magisk/magiskpolicy); " +
                "\"\$MP\" --live 'permissive cameraserver' >/dev/null 2>&1; " +
                "\"\$MP\" --live 'permissive su' >/dev/null 2>&1; " +
                "\"\$MP\" --live 'allow su cameraserver process ptrace' >/dev/null 2>&1; " +
                "\"\$MP\" --live 'allow cameraserver cameraserver process execmem' >/dev/null 2>&1; " +
                "true",
            timeoutSec = 8,
        )
        if (sdk >= 35) {
            RootShell.runGlobal("setenforce 0", timeoutSec = 3)
        }
    }

    private fun startWatchdog(playPath: String) {
        if (!watchdogRunning.compareAndSet(false, true)) return
        val t = Thread({
            Log.i(TAG, "inject watchdog start")
            var ticks = 0
            while (virtualSession && watchdogRunning.get()) {
                try {
                    Thread.sleep(3000)
                    ticks++
                    CameraInjectHardener.keepWindowAlive()
                    if (!virtualSession) break
                    // Sem bounce/kill no watchdog — so re-play se binder vivo
                    if (ticks % 3 == 0) {
                        val code = VcplaxEngine.startPlay(playPath, loop = true, autoRotate = false)
                        if (code == -1) {
                            appContext?.let { ctx ->
                                VcplaxEngine.ensureRunning(ctx, restoreEnforcing = false)
                                VcplaxEngine.startPlay(playPath, loop = true, autoRotate = false)
                            }
                        }
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
