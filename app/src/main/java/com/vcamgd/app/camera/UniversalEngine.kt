package com.vcamgd.app.camera

import android.content.Context
import android.os.Build
import android.util.Log
import com.vcamgd.app.root.RootShell
import com.vcamgd.app.root.SelinuxLive

/**
 * Motor universal Android 12–16 (API 31–36).
 *
 * 1) SELinux live (magiskpolicy/ksud) — sem reboot
 * 2) vcplax + libvc + shadowhook com labels corretas
 * 3) Reinicia cameraserver para o inject grudar
 * 4) Fallback Zygisk so se modulo ja existir
 */
object UniversalEngine {
    private const val TAG = "KingVCam-Universal"
    private const val MODULE_DISABLE = "/data/adb/modules/vcamgd/disable"

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

        when (val r = bootVcplax(context)) {
            is Result.Ok -> {
                active = Mode.VCPLAX
                lastDiag = Diagnostics(engine = "vcplax", detail = "sdk=$sdk se=${se.ok}")
                return Result.Ok
            }
            is Result.Failed -> Log.w(TAG, "vcplax: ${r.reason}")
        }

        if (ModuleInstaller.isModulePresent() && !flagExists(MODULE_DISABLE)) {
            when (val r = bootKingOptional(context)) {
                is Result.Ok -> {
                    active = Mode.KING_ZYGISK
                    lastDiag = Diagnostics(
                        engine = "king+zygisk",
                        detail = "fallback sdk=$sdk",
                    )
                    return Result.Ok
                }
                is Result.Failed -> Log.w(TAG, "king: ${r.reason}")
            }
        }

        active = Mode.NONE
        lastDiag = Diagnostics(engine = "none", detail = "sdk=$sdk se=${se.detail}")
        return Result.Failed(
            "Motor nao subiu no Android ${Build.VERSION.RELEASE} (API $sdk). " +
                "Root permanente + Magisk/KernelSU. SELinux=${se.ok}",
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

    fun startPlay(pathOrUrl: String): Result {
        val ctx = appContext
        // Preferencia: vcplax binder
        if (active == Mode.VCPLAX || (ctx != null && VcplaxEngine.isAlive(ctx))) {
            val code = VcplaxEngine.startPlay(pathOrUrl, loop = true, autoRotate = false)
            Log.i(TAG, "vcplax startPlay=$code")
            if (code != 0) {
                active = Mode.VCPLAX
                refreshCameraServer()
                return Result.Ok
            }
        }
        if (active == Mode.KING_ZYGISK || KingEngine.isAlive()) {
            return when (val k = KingEngine.startPlay(pathOrUrl)) {
                is KingEngine.Result.Ok -> {
                    active = Mode.KING_ZYGISK
                    Result.Ok
                }
                is KingEngine.Result.Failed -> Result.Failed(k.reason)
            }
        }
        // Ultima tentativa: sobe vcplax play mesmo com code estranho se binder respondeu
        val code = VcplaxEngine.startPlay(pathOrUrl, loop = true, autoRotate = false)
        return if (code != 0) {
            active = Mode.VCPLAX
            refreshCameraServer()
            Result.Ok
        } else {
            Result.Failed("play falhou (nenhum motor respondeu)")
        }
    }

    fun stopPlay(): Result {
        runCatching { VcplaxEngine.stopPlay() }
        runCatching { KingEngine.stopPlay() }
        return Result.Ok
    }

    fun statusLine(context: Context): String {
        val d = lastDiag
        return "engine=${d.engine} alive=${isAlive(context)} " +
            "sdk=${d.sdk}/${d.release} ${d.detail}"
    }

    private fun bootVcplax(context: Context): Result {
        SelinuxLive.setEnforcing(false)
        return try {
            when (val r = VcplaxEngine.ensureRunning(context)) {
                is VcplaxEngine.Result.Ok -> {
                    refreshCameraServer()
                    // Re-anexa binder apos restart da camera (daemon continua)
                    Thread.sleep(400)
                    if (!VcplaxEngine.isAlive(context)) {
                        // tenta subir de novo sem matar sepolicy
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
        } finally {
            SelinuxLive.setEnforcing(true)
        }
    }

    private fun bootKingOptional(context: Context): Result =
        when (val r = KingEngine.ensureRunning(context)) {
            is KingEngine.Result.Ok -> Result.Ok
            is KingEngine.Result.Failed -> Result.Failed(r.reason)
        }

    fun refreshCameraServer() {
        val out = RootShell.run(
            "OLDPID=\$(pidof cameraserver 2>/dev/null | awk '{print \$1}'); " +
                "killall -9 cameraserver 2>/dev/null; " +
                "kill -9 \$OLDPID 2>/dev/null; " +
                "for i in 1 2 3 4 5 6 7 8 9 10 11 12; do " +
                "NEW=\$(pidof cameraserver 2>/dev/null | awk '{print \$1}'); " +
                "if [ -n \"\$NEW\" ] && [ \"\$NEW\" != \"\$OLDPID\" ]; then echo NEW=\$NEW; exit 0; fi; " +
                "sleep 0.25; done; " +
                "pidof cameraserver; echo WAIT_CAM",
            timeoutSec = 10,
        )
        Log.i(TAG, "cameraserver refresh: $out")
    }

    private fun flagExists(path: String): Boolean =
        RootShell.run("test -f '$path' && echo Y || echo N", timeoutSec = 3).contains("Y")
}
