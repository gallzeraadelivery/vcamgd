package com.vcamgd.app.camera

import android.content.Context
import android.os.Build
import android.util.Log
import com.vcamgd.app.root.RootShell
import com.vcamgd.app.root.SelinuxLive

/**
 * Motor APK+root only (vcplax) — SEM Zygisk / SEM modulo Magisk.
 *
 * HyperOS / Android 16:
 * 1) SELinux permissivo na sessao (nao restaurar enforcing cedo)
 * 2) ptrace_scope=0
 * 3) sobe vcplax
 * 4) reinicia cameraserver e confirma libvc nas maps
 * 5) startPlay
 * 6) force-stop apps — nao matar cameraserver de novo
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
        openInjectWindow(sdk)
        Log.i(TAG, "selinux: ok=${se.ok} ${se.detail}")

        return when (val r = bootVcplax(context)) {
            is Result.Ok -> {
                lastDiag = Diagnostics(
                    engine = "vcplax",
                    detail = "sdk=$sdk brand=${Build.BRAND}/${Build.MANUFACTURER} " +
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
        virtualSession = true

        // Inject no cameraserver ANTES do play; confirma libvc nas maps
        if (!ensureInjected(retries = 3)) {
            lastDiag = lastDiag.copy(detail = lastDiag.detail + " inject_fail")
            return Result.Failed(
                "Inject no cameraserver falhou (libvc ausente nas maps). " +
                    "HyperOS pode estar bloqueando ptrace — root permanente + Magisk/KernelSU.",
            )
        }

        var code = VcplaxEngine.startPlay(pathOrUrl, loop = true, autoRotate = false)
        Log.i(TAG, "startPlay#1=$code path=$pathOrUrl")
        if (code == 0) {
            Thread.sleep(350)
            code = VcplaxEngine.startPlay(pathOrUrl, loop = true, autoRotate = false)
            Log.i(TAG, "startPlay#2=$code")
        }

        val alive = RootShell.run("pidof vcplax 2>/dev/null", timeoutSec = 3).trim().isNotEmpty()
        val injected = isLibVcInjected()
        lastDiag = lastDiag.copy(
            engine = "vcplax",
            detail = "play=$code inject=$injected alive=$alive",
        )

        // Referencia: !=0 = OK. Se code==0 mas inject+daemon ok, ainda tenta seguir.
        return if (code != 0 || (alive && injected)) {
            Result.Ok
        } else {
            Result.Failed("startPlay falhou (code=$code inject=$injected)")
        }
    }

    fun stopPlay(): Result {
        virtualSession = false
        runCatching { VcplaxEngine.stopPlay() }
        if (Build.VERSION.SDK_INT >= 35) {
            SelinuxLive.setEnforcing(true)
        }
        return Result.Ok
    }

    fun statusLine(context: Context): String {
        val d = lastDiag
        val pid = RootShell.run("pidof vcplax cameraserver 2>/dev/null", timeoutSec = 3).trim()
        val inj = isLibVcInjected()
        return "engine=${d.engine} alive=${isAlive(context)} inject=$inj " +
            "sdk=${d.sdk}/${d.release} pids={$pid} ${d.detail}"
    }

    private fun bootVcplax(context: Context): Result {
        // Nao restaurar enforcing aqui — HyperOS precisa da janela aberta
        return when (val r = VcplaxEngine.ensureRunning(context, restoreEnforcing = false)) {
            is VcplaxEngine.Result.Ok -> {
                ensureInjected(retries = 2)
                Result.Ok
            }
            is VcplaxEngine.Result.Failed -> Result.Failed(r.reason)
        }
    }

    /** Reinicia HAL e tenta garantir libvc dentro do cameraserver. */
    private fun ensureInjected(retries: Int): Boolean {
        repeat(retries) { attempt ->
            prepareCameraServerForInject()
            Thread.sleep(600)
            if (isLibVcInjected()) {
                Log.i(TAG, "libvc injected attempt=${attempt + 1}")
                return true
            }
            // Re-sobe daemon para re-attach ptrace
            Log.w(TAG, "libvc NOT in maps — restarting vcplax attempt=${attempt + 1}")
            val ctx = appContext
            if (ctx != null) {
                VcplaxEngine.ensureRunning(ctx, restoreEnforcing = false)
                Thread.sleep(800)
            }
            if (isLibVcInjected()) return true
        }
        // diagnostico
        val maps = RootShell.run(
            "PID=\$(pidof cameraserver | awk '{print \$1}'); " +
                "echo PID=\$PID; " +
                "cat /proc/\$PID/maps 2>/dev/null | grep -E 'libvc|shadow|vcplax' | head -10; " +
                "getenforce; cat /proc/sys/kernel/yama/ptrace_scope 2>/dev/null; " +
                "tail -n 30 $VCPLAX_LOG 2>/dev/null",
            timeoutSec = 8,
        )
        Log.e(TAG, "inject diagnostics:\n$maps")
        lastDiag = lastDiag.copy(detail = "maps=${maps.lineSequence().take(3).joinToString(" | ")}")
        return isLibVcInjected()
    }

    fun isLibVcInjected(): Boolean {
        val out = RootShell.run(
            "PID=\$(pidof cameraserver | awk '{print \$1}'); " +
                "if [ -z \"\$PID\" ]; then echo NO_CAM; exit 0; fi; " +
                "cat /proc/\$PID/maps 2>/dev/null | grep -E '/data/libvc|/libvc\\.so|libvc\\+\\+' | head -3; " +
                "echo END",
            timeoutSec = 5,
        )
        return out.contains("libvc")
    }

    fun prepareCameraServerForInject() {
        val out = RootShell.run(
            "OLDPID=\$(pidof cameraserver 2>/dev/null | awk '{print \$1}'); " +
                "killall -9 cameraserver 2>/dev/null; " +
                "killall -9 android.hardware.camera.provider@2.4-service_64 2>/dev/null; " +
                "killall -9 android.hardware.camera.provider@2.5-service_64 2>/dev/null; " +
                "killall -9 android.hardware.camera.provider@2.6-service_64 2>/dev/null; " +
                "killall -9 android.hardware.camera.provider@2.7-service_64 2>/dev/null; " +
                "killall -9 vendor.qti.camera.provider@2.4-service_64 2>/dev/null; " +
                "killall -9 vendor.qti.camera.provider-service_64 2>/dev/null; " +
                "killall -9 vendor.qti.camera.provider@2.7-service_64 2>/dev/null; " +
                "kill -9 \$OLDPID 2>/dev/null; " +
                "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do " +
                "NEW=\$(pidof cameraserver 2>/dev/null | awk '{print \$1}'); " +
                "if [ -n \"\$NEW\" ] && [ \"\$NEW\" != \"\$OLDPID\" ]; then " +
                "echo NEW=\$NEW; sleep 0.5; exit 0; fi; sleep 0.25; done; " +
                "pidof cameraserver; echo WAIT_CAM",
            timeoutSec = 14,
        )
        Log.i(TAG, "prepareCameraServer: $out")
    }

    private fun openInjectWindow(sdk: Int) {
        SelinuxLive.setEnforcing(false)
        RootShell.run(
            // ptrace entre processos root
            "echo 0 > /proc/sys/kernel/yama/ptrace_scope 2>/dev/null; " +
                "MP=\$(command -v magiskpolicy 2>/dev/null || echo /data/adb/magisk/magiskpolicy); " +
                "\"\$MP\" --live 'permissive cameraserver' >/dev/null 2>&1; " +
                "\"\$MP\" --live 'permissive su' >/dev/null 2>&1; " +
                "\"\$MP\" --live 'permissive vendor_camera_provider' >/dev/null 2>&1; " +
                "\"\$MP\" --live 'permissive hal_camera_default' >/dev/null 2>&1; " +
                "\"\$MP\" --live 'allow su cameraserver process ptrace' >/dev/null 2>&1; " +
                "\"\$MP\" --live 'allow cameraserver cameraserver process execmem' >/dev/null 2>&1; " +
                "true",
            timeoutSec = 8,
        )
        if (sdk >= 35) {
            // HyperOS costuma ser mais fechado
            RootShell.run("setenforce 0", timeoutSec = 3)
        }
    }
}
