package com.vcamgd.app.camera

import android.content.Context
import android.os.Build
import android.util.Log
import com.vcamgd.app.root.RootShell
import com.vcamgd.app.root.SelinuxLive

/**
 * Motor universal Android 12–16.
 *
 * Ordem critica (HyperOS / Android 16 Xiaomi):
 * 1) SELinux live + janela permissiva
 * 2) sobe vcplax
 * 3) reinicia cameraserver (inject gruda no processo NOVO)
 * 4) startPlay
 * 5) force-stop apps de camera — **NAO** matar cameraserver de novo
 */
object UniversalEngine {
    private const val TAG = "KingVCam-Universal"
    private const val MODULE_DISABLE = "/data/adb/modules/vcamgd/disable"
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

    private enum class Mode { NONE, VCPLAX, KING_ZYGISK }

    @Volatile
    var lastDiag: Diagnostics = Diagnostics()
        private set

    @Volatile
    private var active: Mode = Mode.NONE

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
        Log.i(TAG, "selinux: ok=${se.ok} ${se.detail}")
        // Android 15/16 Xiaomi: manter permissive no dominio critico durante a sessao
        openVirtualSeLinuxWindow(sdk)

        when (val r = bootVcplax(context)) {
            is Result.Ok -> {
                active = Mode.VCPLAX
                lastDiag = Diagnostics(
                    engine = "vcplax",
                    detail = "sdk=$sdk brand=${Build.BRAND} se=${se.ok}",
                )
                return Result.Ok
            }
            is Result.Failed -> Log.w(TAG, "vcplax: ${r.reason}")
        }

        if (ModuleInstaller.isModulePresent() && !flagExists(MODULE_DISABLE)) {
            when (val r = bootKingOptional(context)) {
                is Result.Ok -> {
                    active = Mode.KING_ZYGISK
                    lastDiag = Diagnostics(engine = "king+zygisk", detail = "fallback sdk=$sdk")
                    return Result.Ok
                }
                is Result.Failed -> Log.w(TAG, "king: ${r.reason}")
            }
        }

        active = Mode.NONE
        lastDiag = Diagnostics(engine = "none", detail = "sdk=$sdk se=${se.detail}")
        return Result.Failed(
            "Motor nao subiu no Android ${Build.VERSION.RELEASE} (API $sdk). " +
                "Root permanente + Magisk/KernelSU.",
        )
    }

    fun isAlive(context: Context): Boolean {
        bindContext(context)
        return when (active) {
            Mode.VCPLAX -> VcplaxEngine.isAlive(context)
            Mode.KING_ZYGISK -> KingEngine.isAlive()
            Mode.NONE -> VcplaxEngine.isAlive(context) || KingEngine.isAlive()
        }
    }

    /**
     * Ativa o feeder. Nao reinicia cameraserver aqui — isso ja foi feito no boot
     * e matar de novo no HyperOS volta a camera real.
     */
    fun startPlay(pathOrUrl: String): Result {
        openVirtualSeLinuxWindow(Build.VERSION.SDK_INT)
        virtualSession = true

        // Garante cameraserver fresco + inject ANTES do play
        prepareCameraServerForInject()

        val ctx = appContext
        if (active == Mode.VCPLAX || (ctx != null && VcplaxEngine.isAlive(ctx))) {
            var code = VcplaxEngine.startPlay(pathOrUrl, loop = true, autoRotate = false)
            Log.i(TAG, "vcplax startPlay#1=$code path=$pathOrUrl")
            if (code == 0) {
                // Alguns firmwares tratam 0 como ok — tenta de novo apos micro wait
                Thread.sleep(300)
                code = VcplaxEngine.startPlay(pathOrUrl, loop = true, autoRotate = false)
                Log.i(TAG, "vcplax startPlay#2=$code")
            }
            // Referencia: !=0 sucesso. Se ainda 0, tenta king.
            if (code != 0) {
                active = Mode.VCPLAX
                lastDiag = lastDiag.copy(
                    engine = "vcplax",
                    detail = lastDiag.detail + " play=$code",
                )
                return Result.Ok
            }
        }

        if (active == Mode.KING_ZYGISK || KingEngine.isAlive() || ModuleInstaller.isModulePresent()) {
            return when (val k = KingEngine.startPlay(pathOrUrl)) {
                is KingEngine.Result.Ok -> {
                    active = Mode.KING_ZYGISK
                    Result.Ok
                }
                is KingEngine.Result.Failed -> Result.Failed(k.reason)
            }
        }

        // Ultimo recurso: aceita code 0 se binder respondeu e processo vcplax vivo
        val alive = RootShell.run("pidof vcplax 2>/dev/null", timeoutSec = 3).trim().isNotEmpty()
        val code = VcplaxEngine.startPlay(pathOrUrl, loop = true, autoRotate = false)
        Log.i(TAG, "vcplax startPlay#final=$code alive=$alive")
        return if (alive) {
            active = Mode.VCPLAX
            Result.Ok
        } else {
            Result.Failed("play falhou (vcplax morto / code=$code)")
        }
    }

    fun stopPlay(): Result {
        virtualSession = false
        runCatching { VcplaxEngine.stopPlay() }
        runCatching { KingEngine.stopPlay() }
        // Restaura enforcing ao desligar
        if (Build.VERSION.SDK_INT >= 35) {
            SelinuxLive.setEnforcing(true)
        }
        return Result.Ok
    }

    fun statusLine(context: Context): String {
        val d = lastDiag
        val pid = RootShell.run("pidof vcplax cameraserver 2>/dev/null", timeoutSec = 3).trim()
        return "engine=${d.engine} alive=${isAlive(context)} " +
            "sdk=${d.sdk}/${d.release} pids={$pid} ${d.detail}"
    }

    private fun bootVcplax(context: Context): Result {
        return when (val r = VcplaxEngine.ensureRunning(context)) {
            is VcplaxEngine.Result.Ok -> {
                prepareCameraServerForInject()
                Thread.sleep(500)
                if (!VcplaxEngine.isAlive(context)) {
                    when (val again = VcplaxEngine.ensureRunning(context)) {
                        is VcplaxEngine.Result.Ok -> Result.Ok
                        is VcplaxEngine.Result.Failed -> Result.Failed(again.reason)
                    }
                } else {
                    Result.Ok
                }
            }
            is VcplaxEngine.Result.Failed -> Result.Failed(r.reason)
        }
    }

    private fun bootKingOptional(context: Context): Result =
        when (val r = KingEngine.ensureRunning(context)) {
            is KingEngine.Result.Ok -> Result.Ok
            is KingEngine.Result.Failed -> Result.Failed(r.reason)
        }

    /** Reinicia HAL/camera server e espera PID novo (inject target). */
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
                "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16; do " +
                "NEW=\$(pidof cameraserver 2>/dev/null | awk '{print \$1}'); " +
                "if [ -n \"\$NEW\" ] && [ \"\$NEW\" != \"\$OLDPID\" ]; then " +
                "echo NEW=\$NEW; sleep 0.4; exit 0; fi; sleep 0.25; done; " +
                "pidof cameraserver; echo WAIT_CAM; " +
                "tail -n 15 $VCPLAX_LOG 2>/dev/null",
            timeoutSec = 14,
        )
        Log.i(TAG, "prepareCameraServer: $out")
    }

    private fun openVirtualSeLinuxWindow(sdk: Int) {
        SelinuxLive.setEnforcing(false)
        if (sdk >= 35) {
            RootShell.run(
                "MP=\$(command -v magiskpolicy 2>/dev/null || echo /data/adb/magisk/magiskpolicy); " +
                    "\"\$MP\" --live 'permissive cameraserver' >/dev/null 2>&1; " +
                    "\"\$MP\" --live 'permissive vendor_camera_provider' >/dev/null 2>&1; " +
                    "\"\$MP\" --live 'permissive hal_camera_default' >/dev/null 2>&1; " +
                    "true",
                timeoutSec = 6,
            )
        }
    }

    private fun flagExists(path: String): Boolean =
        RootShell.run("test -f '$path' && echo Y || echo N", timeoutSec = 3).contains("Y")
}
