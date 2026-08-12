package com.vcamgd.app.camera

import android.content.Context
import android.os.Build
import android.util.Log
import com.vcamgd.app.root.RootShell
import com.vcamgd.app.root.SelinuxLive
import com.vcamgd.app.util.KingVCamLog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Motor APK+root only (vcplax) — SEM Zygisk.
 *
 * v0.10.17: inject limpo = libvc.so sem (deleted); nao falhar se houver deleted + limpo.
 * v0.10.16: sync kinginject apos update APK (soft rebind nao deixava bin antigo).
 * v0.10.15: kinginject — already-loaded so por basename (fix maps=empty apos shadowhook).
 */
object UniversalEngine {
    private const val TAG = "KingVCam-Universal"

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
                    KingVCamLog.i("boot", lastDiag.detail)
                    Result.Ok
                }
                is Result.Failed -> {
                    lastDiag = Diagnostics(engine = "none", detail = r.reason)
                    KingVCamLog.e("boot", r.reason)
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
            val path = pathOrUrl.trim()
            if (path.isEmpty()) {
                KingVCamLog.e("play", "path vazio — abort")
                return Result.Failed("Path de video vazio")
            }
            KingVCamLog.i("play", "start path=$path")
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
                KingVCamLog.e("inject", "FALHOU — $snap")
                KingVCamLog.auditWhyNoPreview("inject_fail")
                return Result.Failed(
                    "Inject falhou (libvc fora do cameraserver). " +
                        "mm/ptrace ok nao basta no HyperOS A16 — veja ki= no diag. " +
                        "Diag: $snap",
                )
            }
            KingVCamLog.i("inject", "OK inject=true")
            CameraInjectHardener.freezeLibDeploy = true
            CameraInjectHardener.keepWindowAlive()

            var code = playWithFallbacks(path)
            Log.i(TAG, "startPlay result=$code path=$path")
            KingVCamLog.i("play", "code=$code apos fallbacks")

            // Confirma cameraserver ainda vivo (libvc quebrado as vezes mata o processo)
            val camPid = RootShell.runGlobal("pidof cameraserver 2>/dev/null", timeoutSec = 3).trim()
            if (!camPid.any { it.isDigit() }) {
                Log.w(TAG, "cameraserver morreu apos inject/play — re-inject")
                KingVCamLog.w("cam", "cameraserver morreu apos play — re-inject")
                CameraInjectHardener.freezeLibDeploy = false
                if (ensureInjected(retries = 2)) {
                    CameraInjectHardener.freezeLibDeploy = true
                    Thread.sleep(400)
                    code = playWithFallbacks(path)
                    KingVCamLog.i("play", "re-play code=$code")
                }
            }

            var injected = isLibVcInjected()
            // Soft re-inject se o map sumiu apos play (sem bounce)
            if (!injected) {
                Log.w(TAG, "inject sumiu apos play — soft re-inject")
                KingVCamLog.w("inject", "sumiu apos play — soft re-inject")
                CameraInjectHardener.keepWindowAlive()
                CameraInjectHardener.runKingInject()
                Thread.sleep(300)
                injected = isLibVcInjected()
                if (injected) {
                    appContext?.let { VcplaxEngine.ensureBinderAlive(it) }
                    code = playWithFallbacks(path)
                    KingVCamLog.i("play", "re-play apos soft inject code=$code")
                }
            }
            CameraInjectHardener.keepWindowAlive()
            val status2 = VcplaxEngine.playStatus()
            val alive2 = RootShell.run("pidof vcplax 2>/dev/null", timeoutSec = 3).trim().isNotEmpty()
            injected = isLibVcInjected()
            lastDiag = lastDiag.copy(
                engine = "vcplax",
                detail = "play=$code status=$status2 inject=$injected alive=$alive2 " +
                    "hyper=${CameraInjectHardener.isHyperOsFamily()}",
            )
            KingVCamLog.i("play", lastDiag.detail)

            val ok = injected && (code != 0 || alive2)
            if (ok) {
                startWatchdog(path)
                KingVCamLog.auditWhyNoPreview("pos_play_ok_checar_se_preview_e_video")
                Result.Ok
            } else {
                stopWatchdog()
                KingVCamLog.e("play", "FALHOU code=$code status=$status2 inject=$injected")
                KingVCamLog.auditWhyNoPreview("play_fail")
                Result.Failed(
                    "startPlay falhou (code=$code status=$status2 inject=$injected). " +
                        "Desligue/ligue a virtual; se persistir, veja Status (ki=/maps=).",
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startPlay crash-guard", t)
            KingVCamLog.e("play", "crash: ${t.message}")
            stopWatchdog()
            virtualSession = false
            Result.Failed(t.message ?: "erro no play")
        }
    }

    fun stopPlay(): Result {
        virtualSession = false
        stopWatchdog()
        CameraInjectHardener.freezeLibDeploy = false
        runCatching { VcplaxEngine.stopPlay() }
        // HyperOS: apos virtual, reinicia cameraserver limpo para camera real voltar
        if (CameraInjectHardener.isHyperOsFamily() || Build.VERSION.SDK_INT >= 35) {
            RootShell.runGlobal(
                "killall -9 cameraserver 2>/dev/null; " +
                    "for i in 1 2 3 4 5 6 7 8 9 10 11 12; do " +
                    "pidof cameraserver >/dev/null && exit 0; sleep 0.25; done; true",
                timeoutSec = 8,
            )
        }
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
            CameraInjectHardener.keepWindowAlive()
            // Ja tem libvc.so LIMPO (sem deleted)? Nao bounce
            if (isLibVcInjected()) {
                KingVCamLog.i("inject", "ja presente (libvc.so limpo) — sem bounce")
                relinkVcplaxAfterInject()
                return true
            }
            if (mapsHasDeletedLibvc()) {
                KingVCamLog.w("inject", "so libvc (deleted) no maps — bounce para inject limpo")
            }
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

                if (CameraInjectHardener.isHyperOsFamily() || Build.VERSION.SDK_INT >= 35) {
                    val ki = CameraInjectHardener.runKingInject()
                    Log.i(TAG, "kinginject early attempt=${attempt + 1}: ${ki.take(160)}")
                    Thread.sleep(250)
                    if (isLibVcInjected()) {
                        Log.i(TAG, "libvc injected (kinginject-early) attempt=${attempt + 1}")
                        KingVCamLog.i("inject", "kinginject-early OK attempt=${attempt + 1}")
                        relinkVcplaxAfterInject()
                        return isLibVcInjected()
                    }
                }

                if (isLibVcInjected()) {
                    Log.i(TAG, "libvc injected (vcplax) attempt=${attempt + 1}")
                    KingVCamLog.i("inject", "vcplax OK attempt=${attempt + 1}")
                    relinkVcplaxAfterInject()
                    return true
                }

                Log.w(TAG, "libvc NOT in maps — restarting vcplax attempt=${attempt + 1}")
                KingVCamLog.w("inject", "maps vazios — restart vcplax attempt=${attempt + 1}")
                val ctx = appContext
                if (ctx != null) {
                    runCatching {
                        CameraInjectHardener.redeployLibPaths(
                            File(ctx.filesDir, "vcam-engine/${abiDir()}").absolutePath,
                        )
                    }
                    VcplaxEngine.ensureRunning(ctx, restoreEnforcing = false, forceRedeploy = true)
                    Thread.sleep(600)
                    if (isLibVcInjected()) return true
                }

                val ki = CameraInjectHardener.runKingInject()
                Log.i(TAG, "kinginject attempt=${attempt + 1}: ${ki.take(120)}")
                Thread.sleep(300)
                if (isLibVcInjected()) {
                    Log.i(TAG, "libvc injected (kinginject) attempt=${attempt + 1}")
                    KingVCamLog.i("inject", "kinginject OK attempt=${attempt + 1}")
                    relinkVcplaxAfterInject()
                    return true
                }

                if (attempt >= 1 && ctx != null) {
                    VcplaxEngine.restartFromAdbPath(ctx)
                    Thread.sleep(500)
                    CameraInjectHardener.runKingInject()
                    if (isLibVcInjected()) {
                        relinkVcplaxAfterInject()
                        return true
                    }
                }
            }
            val diag = CameraInjectHardener.snapshotDiag()
            Log.e(TAG, "inject diagnostics:\n$diag")
            KingVCamLog.e("inject", "esgotou retries — ${diag.lineSequence().take(6).joinToString(" | ")}")
            lastDiag = lastDiag.copy(
                detail = "maps=${diag.lineSequence().take(5).joinToString(" | ")}",
            )
            isLibVcInjected()
        } catch (t: Throwable) {
            Log.e(TAG, "ensureInjected crash-guard", t)
            false
        }
    }

    /**
     * Apos bounce+kinginject, religa Binder SEM matar vcplax e SEM sobrescrever libs mapeadas.
     */
    private fun relinkVcplaxAfterInject() {
        val ctx = appContext ?: return
        CameraInjectHardener.keepWindowAlive()
        // Nao cp sobre /data/libvc*.so se ja injetado — gera (deleted) no maps
        if (!CameraInjectHardener.freezeLibDeploy) {
            RootShell.runGlobal(
                "cp -f /dev/vcam/libvc++.so /dev/vcam/libshadowhook.so 2>/dev/null; " +
                    "chmod 755 /dev/vcam/libshadowhook.so 2>/dev/null; " +
                    "chcon u:object_r:system_lib_file:s0 /dev/vcam/libshadowhook.so 2>/dev/null; " +
                    "true",
                timeoutSec = 6,
            )
        }
        if (!VcplaxEngine.ensureBinderAlive(ctx)) {
            Log.w(TAG, "relink soft: binder morto — ensureRunning soft")
            VcplaxEngine.ensureRunning(ctx, restoreEnforcing = false, forceRedeploy = false)
        }
        Thread.sleep(250)
        CameraInjectHardener.keepWindowAlive()
        if (!isLibVcInjected()) {
            Log.w(TAG, "relink: maps vazios — kinginject sem bounce")
            CameraInjectHardener.runKingInject()
            Thread.sleep(300)
            VcplaxEngine.ensureBinderAlive(ctx)
        }
    }

    /** stop + play — HyperOS prioriza /dev/vcam (cameraserver le melhor). */
    private fun playWithFallbacks(primary: String): Int {
        CameraInjectHardener.keepWindowAlive()
        appContext?.let { ctx ->
            if (!VcplaxEngine.ensureBinderAlive(ctx)) {
                VcplaxEngine.ensureRunning(ctx, restoreEnforcing = false, forceRedeploy = false)
            }
        }
        runCatching { VcplaxEngine.stopPlay() }
        Thread.sleep(200)

        val hyper = CameraInjectHardener.isHyperOsFamily() || Build.VERSION.SDK_INT >= 35
        val candidates = linkedSetOf<String>().apply {
            if (hyper) {
                add("/dev/vcam/current.mp4")
                add(primary)
            } else {
                add(primary)
                add("/dev/vcam/current.mp4")
            }
            add("/data/local/tmp/vcamgd/current.mp4")
            add("/data/local/tmp/current.mp4")
            add("/data/adb/vcamgd/current.mp4")
        }
        var best = -1
        for (path in candidates) {
            if (path.isBlank()) continue
            val exists = RootShell.runGlobal(
                "if [ -f '$path' ]; then echo YES; wc -c < '$path'; else echo NO; fi",
                timeoutSec = 4,
            )
            if (!exists.contains("YES")) continue
            val code = VcplaxEngine.startPlay(path, loop = true, autoRotate = false)
            Log.i(TAG, "play try path=$path code=$code exists=${exists.take(40)}")
            KingVCamLog.i("play", "try path=$path code=$code ${exists.take(50)}")
            if (code != 0 && code != -1) {
                Thread.sleep(250)
                val st = VcplaxEngine.playStatus()
                Log.i(TAG, "playStatus=$st for $path")
                KingVCamLog.i("play", "OK path=$path code=$code status=$st")
                return code
            }
            if (code != -1) best = code
            Thread.sleep(150)
        }
        if (primary.isBlank()) return best
        val code = VcplaxEngine.startPlay(primary, loop = true, autoRotate = false)
        return if (code != -1) code else best
    }

    /**
     * libvc.so real e com arquivo ainda mapeado (nao "(deleted)").
     * Se existir /dev/vcam/libvc.so limpo + /data/libvc.so (deleted), conta como OK.
     */
    fun isLibVcInjected(): Boolean {
        return try {
            val out = RootShell.runGlobal(
                "PID=\$(pidof cameraserver | awk '{print \$1}'); " +
                    "if [ -z \"\$PID\" ]; then echo NO_CAM; exit 0; fi; " +
                    "cat /proc/\$PID/maps 2>/dev/null | grep -E 'libvc\\.so' | grep -v 'libvc++' | grep -v '(deleted)' | head -6; " +
                    "echo END",
                timeoutSec = 6,
            )
            out.contains("libvc.so")
        } catch (_: Throwable) {
            false
        }
    }

    /** Ha algum mapping libvc.so marcado (deleted) — so diagnostico. */
    fun mapsHasDeletedLibvc(): Boolean {
        return try {
            val out = RootShell.runGlobal(
                "PID=\$(pidof cameraserver | awk '{print \$1}'); " +
                    "[ -z \"\$PID\" ] && echo NO; " +
                    "cat /proc/\$PID/maps 2>/dev/null | grep 'libvc\\.so' | grep -v 'libvc++' | grep '(deleted)' | head -2; " +
                    "echo END",
                timeoutSec = 5,
            )
            out.contains("(deleted)")
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
                                if (!VcplaxEngine.ensureBinderAlive(ctx)) {
                                    VcplaxEngine.ensureRunning(
                                        ctx,
                                        restoreEnforcing = false,
                                        forceRedeploy = false,
                                    )
                                }
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
