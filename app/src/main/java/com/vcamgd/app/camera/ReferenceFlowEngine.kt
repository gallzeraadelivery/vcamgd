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
 * Fluxo espelhado do APK de referencia (vlive / OVCAM base) no HyperOS A16.
 *
 * Sequencia observada no APK que funciona (nao a orquestracao Kotlin reimplementada):
 *
 * BOOT:
 *  1. Extrair vcplax + libvc + shadowhook do APK
 *  2. setenforce 0; ptrace_scope=0
 *  3. Copiar para paths originais: /data/libvc.so, /data/libvc++.so, /data/vcplax
 *  4. Espelhar em /dev/vcam (libvc, libvc++.so) — referencia usa ambos
 *  5. rm /data/camera /data/samera; killall vcplax; setsid /data/vcplax <ServerName> &
 *  6. Poll Binder (IMyBinderService) ate pronto
 *
 * ENABLE:
 *  7. Stage video em /data/local/tmp/vcamgd/current.mp4 (path primario do referencia)
 *  8. control.json enabled+virtual
 *  9. Bounce cameraserver UMA vez — vcplax faz ptrace inject (NAO kinginject primeiro)
 * 10. Poll maps ate libvc.so (timeout ~4s)
 * 11. Binder stopPlay (12) → startPlay (11) com path referencia
 * 12. playStatus (15) — code!=0 = OK na referencia
 * 13. force-stop SOMENTE apps de camera (nao cameraserver)
 *
 * FALLBACK (so se inject falhar no passo 10):
 *  bind /data/libvc* + kinginject — patch HyperOS, nao faz parte do referencia puro.
 */
object ReferenceFlowEngine {
    private const val TAG = "KingVCam-RefFlow"

    /** Path primario de play no APK referencia. */
    private const val REF_PLAY_PATH = "/data/local/tmp/vcamgd/current.mp4"

    private val sessionActive = AtomicBoolean(false)

    /** HyperOS / Xiaomi / Android 16 — usa fluxo referencia espelhado. */
    fun applies(): Boolean =
        CameraInjectHardener.isHyperOsFamily() || Build.VERSION.SDK_INT >= 35

    fun bindContext(context: Context) {
        UniversalEngine.bindContext(context)
    }

    /** Boot: deploy paths referencia + start vcplax (full deploy, sem soft rebind no 1o enable). */
    fun boot(context: Context, forceRedeploy: Boolean = false): UniversalEngine.Result {
        return try {
            bindContext(context)
            if (!RootShell.hasRoot(timeoutSec = 6)) {
                return UniversalEngine.Result.Failed("Root (su) necessario")
            }
            SelinuxLive.applyForCameraInject()
            openReferenceWindow()
            CameraInjectHardener.freezeLibDeploy = false

            when (val r = VcplaxEngine.ensureRunning(
                context,
                restoreEnforcing = false,
                forceRedeploy = forceRedeploy,
            )) {
                is VcplaxEngine.Result.Ok -> {
                    deployReferencePaths(context)
                    UniversalEngine.updateDiag(
                        engine = "ref-flow",
                        detail = "sdk=${Build.VERSION.SDK_INT} hyper=${CameraInjectHardener.isHyperOsFamily()} " +
                            "inject=${UniversalEngine.isLibVcInjected()} paths=/data/libvc.so",
                    )
                    KingVCamLog.i("ref-boot", "vcplax OK paths=/data/libvc.so")
                    UniversalEngine.Result.Ok
                }
                is VcplaxEngine.Result.Failed ->
                    UniversalEngine.Result.Failed(r.reason)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "boot", t)
            UniversalEngine.Result.Failed(t.message ?: "erro ref-boot")
        }
    }

    /**
     * Enable completo no estilo referencia: inject via vcplax → play → force-stop apps.
     * Substitui a cadeia kinginject-first + multi-play do UniversalEngine no HyperOS.
     */
    fun enablePlay(context: Context, pathOrUrl: String): UniversalEngine.Result {
        return try {
            bindContext(context)
            val path = resolvePlayPath(pathOrUrl)
            if (path.isEmpty()) {
                return UniversalEngine.Result.Failed("Path de video vazio")
            }
            KingVCamLog.i("ref-enable", "path=$path")
            openReferenceWindow()
            sessionActive.set(true)

            // Passo 9: bounce cameraserver — vcplax inject interno
            UniversalEngine.prepareCameraServerForInject()
            Thread.sleep(800)

            // Passo 10: poll inject vcplax (sem kinginject)
            var injected = waitForVcplaxInject(timeoutMs = 4500L)
            KingVCamLog.i("ref-inject", "vcplax poll inject=$injected")

            // Fallback HyperOS: kinginject so se vcplax nao injectou
            if (!injected) {
                KingVCamLog.w("ref-inject", "vcplax falhou — fallback kinginject")
                CameraInjectHardener.bindDataLibsToDevVcam()
                CameraInjectHardener.runKingInject()
                Thread.sleep(400)
                injected = UniversalEngine.isLibVcInjected()
                KingVCamLog.i("ref-inject", "fallback kinginject inject=$injected")
            }

            if (!injected) {
                sessionActive.set(false)
                KingVCamLog.auditWhyNoPreview("ref_inject_fail")
                return UniversalEngine.Result.Failed(
                    "Inject referencia falhou (vcplax+kinginject). Veja Status (maps=/ki=).",
                )
            }

            CameraInjectHardener.freezeLibDeploy = true
            CameraInjectHardener.bindDataLibsToDevVcam()
            CameraInjectHardener.keepWindowAlive()

            // Passos 11-12: stop + play (ordem referencia)
            val code = playReferenceSequence(path)
            Thread.sleep(500)
            val status = VcplaxEngine.playStatus()
            val stillInjected = UniversalEngine.isLibVcInjected()
            KingVCamLog.i("ref-play", "code=$code status=$status inject=$stillInjected")

            if (!stillInjected || code == 0) {
                // Recover leve: re-play uma vez (referencia nao faz loop agressivo)
                KingVCamLog.w("ref-play", "re-play once inject=$stillInjected code=$code")
                CameraInjectHardener.keepWindowAlive()
                playReferenceSequence(path)
                Thread.sleep(400)
            }

            val finalInject = UniversalEngine.isLibVcInjected()
            val finalCode = VcplaxEngine.playStatus()
            if (!finalInject) {
                sessionActive.set(false)
                KingVCamLog.auditWhyNoPreview("ref_play_inject_lost")
                return UniversalEngine.Result.Failed("Inject perdido apos play referencia")
            }

            // Passo 13: force-stop apps camera (referencia faz apos play OK)
            NativeBridge.restartCameraApps()
            Thread.sleep(600)
            KingVCamLog.i("ref-enable", "OK inject=$finalInject status=$finalCode path=$path")
            KingVCamLog.auditWhyNoPreview("ref_enable_ok_abrir_camera")

            UniversalEngine.updateDiag(
                engine = "ref-flow",
                detail = "play=$code status=$finalCode inject=$finalInject path=$path",
            )
            UniversalEngine.startWatchdogForReference(path)
            UniversalEngine.Result.Ok
        } catch (t: Throwable) {
            Log.e(TAG, "enablePlay", t)
            sessionActive.set(false)
            UniversalEngine.Result.Failed(t.message ?: "erro ref-enable")
        }
    }

    fun resumePlay(context: Context, pathOrUrl: String): UniversalEngine.Result {
        bindContext(context)
        openReferenceWindow()
        sessionActive.set(true)
        val path = resolvePlayPath(pathOrUrl)

        val needHeal = !UniversalEngine.isLibVcInjected() ||
            UniversalEngine.mapsHasDeletedLibvc()
        if (needHeal) {
            KingVCamLog.w("ref-resume", "heal inject")
            CameraInjectHardener.freezeLibDeploy = false
            UniversalEngine.prepareCameraServerForInject()
            Thread.sleep(700)
            if (!waitForVcplaxInject(3500L) && !UniversalEngine.isLibVcInjected()) {
                CameraInjectHardener.runKingInject()
                Thread.sleep(350)
            }
            if (!UniversalEngine.isLibVcInjected()) {
                return UniversalEngine.Result.Failed("Resume referencia: inject falhou")
            }
            CameraInjectHardener.freezeLibDeploy = true
        }

        CameraInjectHardener.bindDataLibsToDevVcam()
        context.let { VcplaxEngine.ensureBinderAlive(it) }
        playReferenceSequence(path)
        NativeBridge.restartCameraApps()
        Thread.sleep(500)
        UniversalEngine.startWatchdogForReference(path)
        KingVCamLog.i("ref-resume", "OK path=$path inject=${UniversalEngine.isLibVcInjected()}")
        return UniversalEngine.Result.Ok
    }

    fun stop() {
        sessionActive.set(false)
        UniversalEngine.stopWatchdog()
        CameraInjectHardener.freezeLibDeploy = false
        runCatching { VcplaxEngine.stopPlay() }
    }

    /** Copia libs para paths originais do referencia (/data/libvc.so etc). */
    private fun deployReferencePaths(context: Context) {
        val abi = abiDir()
        val base = File(context.filesDir, "vcam-engine/$abi").absolutePath
        val out = RootShell.runGlobal(
            "setenforce 0; " +
                "mkdir -p /data/local/tmp/vcamgd /data/adb/vcamgd /dev/vcam; " +
                "safe_cp() { SRC=\"\$1\"; DEST=\"\$2\"; " +
                "[ ! -f \"\$SRC\" ] && return 1; " +
                "if [ -f \"\$DEST\" ] && cmp -s \"\$SRC\" \"\$DEST\" 2>/dev/null; then return 0; fi; " +
                "if [ -f \"\$DEST\" ]; then cat \"\$SRC\" > \"\$DEST\"; else cp \"\$SRC\" \"\$DEST\"; fi; return 0; }; " +
                // Paths referencia (git history VcplaxEngine: /data/libvc.so, /data/libvc++.so, /data/vcplax)
                "safe_cp '$base/libvc.so' /data/libvc.so; " +
                "safe_cp '$base/libshadowhook.so' /data/libvc++.so; " +
                "safe_cp '$base/libshadowhook.so' /data/libshadowhook.so; " +
                "safe_cp '$base/libvc.so' /dev/vcam/libvc.so; " +
                "safe_cp '$base/libshadowhook.so' /dev/vcam/libvc++.so; " +
                "safe_cp '$base/libshadowhook.so' /dev/vcam/libshadowhook.so; " +
                "safe_cp '$base/vcplax.so' /data/vcplax; " +
                "chmod 755 /data/libvc.so /data/libvc++.so /data/libshadowhook.so; " +
                "chmod 755 /dev/vcam/libvc.so /dev/vcam/libvc++.so /dev/vcam/libshadowhook.so; " +
                "chmod 700 /data/vcplax 2>/dev/null; " +
                "chcon u:object_r:system_lib_file:s0 /data/libvc.so /data/libvc++.so /dev/vcam/libvc.so 2>/dev/null; " +
                "chcon u:object_r:system_file:s0 /data/vcplax 2>/dev/null; " +
                "ls -l /data/libvc.so /data/vcplax 2>&1 | head -4; echo REF_DEPLOY_OK",
            timeoutSec = 14,
        )
        Log.i(TAG, "deployReferencePaths: ${out.take(200)}")
        CameraInjectHardener.bindDataLibsToDevVcam()
    }

    /** stopPlay → startPlay com ordem de paths do referencia. */
    private fun playReferenceSequence(primary: String): Int {
        CameraInjectHardener.keepWindowAlive()
        runCatching { VcplaxEngine.stopPlay() }
        Thread.sleep(200)

        val candidates = linkedSetOf(
            REF_PLAY_PATH,
            primary,
            "/dev/vcam/current.mp4",
            "/data/adb/vcamgd/current.mp4",
            "/data/local/tmp/current.mp4",
        )
        for (p in candidates) {
            if (p.isBlank()) continue
            val exists = RootShell.runGlobal(
                "if [ -f '$p' ]; then echo YES; wc -c < '$p'; else echo NO; fi",
                timeoutSec = 4,
            )
            if (!exists.contains("YES")) continue
            val code = VcplaxEngine.startPlay(p, loop = true, autoRotate = false)
            Log.i(TAG, "ref play try path=$p code=$code")
            KingVCamLog.i("ref-play", "try path=$p code=$code")
            if (code != 0 && code != -1) return code
            Thread.sleep(150)
        }
        return VcplaxEngine.startPlay(primary, loop = true, autoRotate = false)
    }

    /** Poll /proc/cameraserver/maps — vcplax inject assincrono apos bounce. */
    private fun waitForVcplaxInject(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            CameraInjectHardener.keepWindowAlive()
            if (UniversalEngine.isLibVcInjected()) return true
            // Nudge: vcplax pode injectar no proximo ciclo — ping playStatus
            runCatching { VcplaxEngine.playStatus() }
            Thread.sleep(350)
        }
        return UniversalEngine.isLibVcInjected()
    }

    private fun resolvePlayPath(pathOrUrl: String): String {
        val p = pathOrUrl.trim()
        if (p.startsWith("rtsp") || p.startsWith("http") || p.startsWith("rtmp")) return p
        // Arquivo local: referencia usa /data/local/tmp/vcamgd/current.mp4
        if (p.startsWith("/") && File(p).name.isNotEmpty()) {
            // Garante espelho no path referencia
            if (p != REF_PLAY_PATH) {
                RootShell.runGlobal(
                    "mkdir -p /data/local/tmp/vcamgd; " +
                        "[ -f '$p' ] && cp '$p' '$REF_PLAY_PATH' 2>/dev/null; " +
                        "cp '$REF_PLAY_PATH' /data/adb/vcamgd/current.mp4 2>/dev/null; " +
                        "cp '$REF_PLAY_PATH' /dev/vcam/current.mp4 2>/dev/null; " +
                        "chmod 644 '$REF_PLAY_PATH' /dev/vcam/current.mp4 2>/dev/null; " +
                        "echo OK",
                    timeoutSec = 8,
                )
            }
            return REF_PLAY_PATH
        }
        return p
    }

    private fun openReferenceWindow() {
        SelinuxLive.setEnforcing(false)
        RootShell.runGlobal(
            "echo 0 > /proc/sys/kernel/yama/ptrace_scope 2>/dev/null; setenforce 0; true",
            timeoutSec = 4,
        )
        runCatching { CameraInjectHardener.openWindow() }
    }

    private fun abiDir(): String {
        val out = RootShell.run("file /system/bin/cameraserver 2>/dev/null", timeoutSec = 4)
        return if (out.contains("32-bit")) "armeabi-v7a" else "arm64-v8a"
    }
}
