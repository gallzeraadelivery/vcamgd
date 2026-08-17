package com.vcamgd.app.camera

import android.os.Build
import android.util.Log
import com.vcamgd.app.root.RootShell

/**
 * Endurecimento de inject no cameraserver (parecer root / Opcao A-D):
 * HyperOS / Xiaomi / MediaTek / Qualcomm — SELinux, ptrace, HAL e denylist.
 *
 * Nao e Camera HAL; amplia a janela para o vcplax grudar via ptrace.
 */
object CameraInjectHardener {
    private const val TAG = "KingVCam-InjectHard"

    /** Apos inject OK, nao sobrescrever libs mapeadas (evita maps (deleted)). */
    @Volatile
    var freezeLibDeploy: Boolean = false

    private val CAMERA_HAL_NAMES = listOf(
        "cameraserver",
        "android.hardware.camera.provider@2.4-service",
        "android.hardware.camera.provider@2.4-service_64",
        "android.hardware.camera.provider@2.5-service",
        "android.hardware.camera.provider@2.5-service_64",
        "android.hardware.camera.provider@2.6-service",
        "android.hardware.camera.provider@2.6-service_64",
        "android.hardware.camera.provider@2.7-service",
        "android.hardware.camera.provider@2.7-service_64",
        "android.hardware.camera.provider-V1-service",
        "android.hardware.camera.provider-V1-service_64",
        "vendor.qti.camera.provider@2.4-service_64",
        "vendor.qti.camera.provider@2.7-service_64",
        "vendor.qti.camera.provider-service_64",
        "vendor.qti.camera.provider@2.4-service",
        "camerahalserver",
        "vendor.mediatek.hardware.camera.provider@2.6-service",
        "vendor.mediatek.hardware.camera.provider@2.6-service-lazy",
        "mtkcam-halserver",
        "mtkcamserver",
        "vendor.xiaomi.hardware.cameraperf@1.0-service",
        "vendor.xiaomi.hardware.campostproc@1.0-service",
    )

    private val PERMISSIVE_DOMAINS = listOf(
        "cameraserver",
        "su",
        "magisk",
        "hal_camera_default",
        "vendor_hal_camera_default",
        "hal_camera_service",
        "vendor_camera_provider",
        "mtk_hal_camera",
        "hal_graphics_composer_default",
        "hal_allocator_default",
    )

    fun isHyperOsFamily(): Boolean {
        val brand = (Build.BRAND ?: "").lowercase()
        val manuf = (Build.MANUFACTURER ?: "").lowercase()
        val fingerprint = (Build.FINGERPRINT ?: "").lowercase()
        return brand.contains("xiaomi") ||
            brand.contains("redmi") ||
            brand.contains("poco") ||
            manuf.contains("xiaomi") ||
            fingerprint.contains("hyperos") ||
            fingerprint.contains("miui")
    }

    /** Abre e mantem janela de ptrace/SELinux para o inject. */
    fun openWindow(): String {
        val domainCmds = PERMISSIVE_DOMAINS.joinToString("; ") { d ->
            "\"\$MP\" --live 'permissive $d' >/dev/null 2>&1"
        }
        val out = RootShell.runGlobal(
            "mkdir -p /data/local/tmp/vcamgd /data/adb/vcamgd; " +
                "echo 0 > /proc/sys/kernel/yama/ptrace_scope 2>/dev/null; " +
                "setenforce 0; " +
                "MAGISK=\$(command -v magisk 2>/dev/null || echo /data/adb/magisk/magisk); " +
                "if [ -x \"\$MAGISK\" ]; then " +
                "\"\$MAGISK\" --denylist rm cameraserver >/dev/null 2>&1; " +
                "\"\$MAGISK\" --denylist rm com.android.camera >/dev/null 2>&1; " +
                "\"\$MAGISK\" --denylist rm com.android.camera2 >/dev/null 2>&1; " +
                "\"\$MAGISK\" --denylist rm com.miui.camera >/dev/null 2>&1; " +
                "\"\$MAGISK\" --denylist rm com.xiaomi.camera >/dev/null 2>&1; " +
                "fi; " +
                "MP=\$(command -v magiskpolicy 2>/dev/null); " +
                "[ -z \"\$MP\" ] && [ -x /data/adb/magisk/magiskpolicy ] && MP=/data/adb/magisk/magiskpolicy; " +
                "[ -z \"\$MP\" ] && [ -x /system/bin/magiskpolicy ] && MP=/system/bin/magiskpolicy; " +
                "if [ -n \"\$MP\" ]; then " +
                "$domainCmds; " +
                "\"\$MP\" --live 'allow su cameraserver process { ptrace getattr sigchld signal sigkill }' >/dev/null 2>&1; " +
                "\"\$MP\" --live 'allow magisk cameraserver process { ptrace getattr sigchld signal }' >/dev/null 2>&1; " +
                "\"\$MP\" --live 'allow cameraserver cameraserver process execmem' >/dev/null 2>&1; " +
                "\"\$MP\" --live 'allow cameraserver system_data_file file { open read getattr map execute execute_no_trans }' >/dev/null 2>&1; " +
                "\"\$MP\" --live 'allow cameraserver system_lib_file file { open read getattr map execute execute_no_trans }' >/dev/null 2>&1; " +
                "\"\$MP\" --live 'allow cameraserver magisk_file file { open read getattr map execute execute_no_trans }' >/dev/null 2>&1; " +
                "\"\$MP\" --live 'allow cameraserver tmpfs file { open read getattr map execute execute_no_trans }' >/dev/null 2>&1; " +
                "fi; " +
                "resetprop ro.debuggable 1 >/dev/null 2>&1; " +
                "settings put global hidden_api_policy 1 >/dev/null 2>&1; " +
                "settings put system miui_optimization 0 >/dev/null 2>&1; " +
                "getenforce; " +
                "PTR=\$(cat /proc/sys/kernel/yama/ptrace_scope 2>/dev/null || echo none); echo ptrace=\$PTR; " +
                "echo mm=\$(if id >/dev/null 2>&1; then echo 1; else echo 0; fi); " +
                "echo WINDOW_OK",
            timeoutSec = 16,
        )
        Log.i(TAG, "openWindow: ${out.take(240)}")
        return out
    }

    fun keepWindowAlive() {
        RootShell.runGlobal(
            "setenforce 0; " +
                "echo 0 > /proc/sys/kernel/yama/ptrace_scope 2>/dev/null; " +
                "MP=\$(command -v magiskpolicy 2>/dev/null || echo /data/adb/magisk/magiskpolicy); " +
                "\"\$MP\" --live 'permissive cameraserver' >/dev/null 2>&1; " +
                "true",
            timeoutSec = 5,
        )
    }

    /** Reinicia stack de camera (cameraserver + HALs OEM). */
    fun bounceCameraStack(): String {
        val killCmds = CAMERA_HAL_NAMES.joinToString("; ") { name ->
            "killall -9 '$name' 2>/dev/null"
        }
        val out = RootShell.runGlobal(
            "OLDPID=\$(pidof cameraserver 2>/dev/null | awk '{print \$1}'); " +
                "$killCmds; " +
                "[ -n \"\$OLDPID\" ] && kill -9 \"\$OLDPID\" 2>/dev/null; " +
                "stop cameraserver 2>/dev/null; " +
                "start cameraserver 2>/dev/null; " +
                "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24; do " +
                "NEW=\$(pidof cameraserver 2>/dev/null | awk '{print \$1}'); " +
                "if [ -n \"\$NEW\" ] && [ \"\$NEW\" != \"\$OLDPID\" ]; then " +
                "echo NEW=\$NEW OLD=\$OLDPID; sleep 0.6; exit 0; fi; sleep 0.25; done; " +
                "pidof cameraserver; echo WAIT_CAM",
            timeoutSec = 16,
        )
        Log.i(TAG, "bounceCameraStack: $out")
        return out
    }

    /**
     * vcplax dlopen usa /data/libvc.so — bind para /dev/vcam evita maps (deleted).
     */
    fun bindDataLibsToDevVcam(): String {
        val out = RootShell.runGlobal(
            "setenforce 0; " +
                "mkdir -p /dev/vcam; " +
                "for n in libvc.so libvc++.so libshadowhook.so; do " +
                "SRC=/dev/vcam/\$n; DST=/data/\$n; " +
                "[ ! -f \"\$SRC\" ] && continue; " +
                "umount \"\$DST\" 2>/dev/null; " +
                "touch \"\$DST\" 2>/dev/null; " +
                "chmod 755 \"\$DST\" 2>/dev/null; " +
                "chcon u:object_r:system_lib_file:s0 \"\$DST\" 2>/dev/null; " +
                "mount -o bind \"\$SRC\" \"\$DST\" 2>/dev/null || " +
                "cat \"\$SRC\" > \"\$DST\"; " +
                "done; " +
                "ls -l /data/libvc.so /dev/vcam/libvc.so 2>&1 | head -4; " +
                "echo BIND_OK",
            timeoutSec = 10,
        )
        Log.i(TAG, "bindDataLibs: ${out.take(200)}")
        return out
    }

    /**
     * Redeploy com cat in-place (nao unlink) — evita /proc/maps "(deleted)".
     * Se freezeLibDeploy, so garante permissoes /dev/vcam.
     */
    fun redeployLibPaths(filesDirEngine: String): String {
        if (freezeLibDeploy) {
            Log.i(TAG, "redeploy SKIP (freeze apos inject)")
            return "SKIP_FREEZE"
        }
        val out = RootShell.runGlobal(
            "setenforce 0; " +
                "mkdir -p /data/adb/vcamgd /data/local/tmp/vcamgd /dev/vcam; " +
                "safe_cp() { SRC=\"\$1\"; DEST=\"\$2\"; " +
                "[ ! -f \"\$SRC\" ] && return 1; " +
                "if [ -f \"\$DEST\" ] && cmp -s \"\$SRC\" \"\$DEST\" 2>/dev/null; then return 0; fi; " +
                "if [ -f \"\$DEST\" ]; then cat \"\$SRC\" > \"\$DEST\"; else cp \"\$SRC\" \"\$DEST\"; fi; return 0; }; " +
                "if [ -d '$filesDirEngine' ]; then " +
                "safe_cp '$filesDirEngine/libvc.so' /dev/vcam/libvc.so; " +
                "safe_cp '$filesDirEngine/libshadowhook.so' /dev/vcam/libvc++.so; " +
                "safe_cp '$filesDirEngine/libshadowhook.so' /dev/vcam/libshadowhook.so; " +
                "safe_cp '$filesDirEngine/vcplax.so' /data/vcplax; " +
                "safe_cp '$filesDirEngine/kinginject' /data/local/tmp/vcamgd/kinginject; " +
                "safe_cp '$filesDirEngine/kinginject' /data/adb/vcamgd/kinginject; " +
                "safe_cp /dev/vcam/libvc.so /data/adb/vcamgd/libvc.so; " +
                "safe_cp /dev/vcam/libvc++.so /data/adb/vcamgd/libvc++.so; " +
                "fi; " +
                "chmod 755 /dev/vcam /dev/vcam/libvc.so /dev/vcam/libvc++.so /dev/vcam/libshadowhook.so 2>/dev/null; " +
                "chmod 700 /data/vcplax /data/local/tmp/vcamgd/kinginject /data/adb/vcamgd/kinginject 2>/dev/null; " +
                "chcon u:object_r:system_lib_file:s0 /dev/vcam/libvc.so /dev/vcam/libvc++.so /dev/vcam/libshadowhook.so 2>/dev/null; " +
                "chcon u:object_r:system_file:s0 /data/vcplax /data/local/tmp/vcamgd/kinginject 2>/dev/null; " +
                "chcon u:object_r:magisk_file:s0 /data/adb/vcamgd /data/adb/vcamgd/* 2>/dev/null; " +
                "ls -lZ /dev/vcam/libvc.so 2>&1 | head -4; " +
                "echo REDEPLOY_OK",
            timeoutSec = 14,
        )
        Log.i(TAG, "redeploy: ${out.take(280)}")
        bindDataLibsToDevVcam()
        return out
    }

    /**
     * Injector proprio — prioriza /dev/vcam (nao (deleted)).
     */
    fun runKingInject(): String {
        val out = RootShell.runGlobal(
            "setenforce 0; " +
                "echo 0 > /proc/sys/kernel/yama/ptrace_scope 2>/dev/null; " +
                "KI=; " +
                "for p in /data/local/tmp/vcamgd/kinginject /data/adb/vcamgd/kinginject; do " +
                "[ -x \$p ] && KI=\$p; done; " +
                "if [ -z \"\$KI\" ]; then echo NO_KINGINJECT; exit 0; fi; " +
                // Espera cameraserver estavel (evita NO_CAM apos bounce)
                "PID=; for i in 1 2 3 4 5 6 7 8 9 10 11 12; do " +
                "PID=\$(pidof cameraserver | awk '{print \$1}'); " +
                "[ -n \"\$PID\" ] && break; sleep 0.25; done; " +
                "echo CAM=\$PID KI=\$KI KI_SZ=\$(wc -c < \"\$KI\" 2>/dev/null); " +
                "if [ -z \"\$PID\" ]; then echo NO_CAM; exit 0; fi; " +
                "sleep 0.35; " +
                ": > /data/local/tmp/vcamgd/kinginject.log; " +
                "BEST_RC=99; " +
                // shadowhook, depois libvc.so OBRIGATORIO (kinginject agora checa basename)
                "for lib in /dev/vcam/libshadowhook.so /dev/vcam/libvc++.so /dev/vcam/libvc.so; do " +
                "if [ -f \$lib ]; then " +
                "echo TRY=\$lib >>/data/local/tmp/vcamgd/kinginject.log; " +
                "\"\$KI\" --pid \$PID --lib \$lib >>/data/local/tmp/vcamgd/kinginject.log 2>&1; " +
                "RC=\$?; echo RC=\$RC lib=\$lib >>/data/local/tmp/vcamgd/kinginject.log; " +
                "case \$lib in *libvc.so) BEST_RC=\$RC ;; esac; " +
                "fi; done; " +
                // Re-try libvc se maps ainda sem libvc.so
                "HAS=\$(cat /proc/\$PID/maps 2>/dev/null | grep 'libvc\\.so' | grep -v 'libvc++' | head -1); " +
                "if [ -z \"\$HAS\" ] && [ -f /dev/vcam/libvc.so ]; then " +
                "echo RETRY_LIBVC >>/data/local/tmp/vcamgd/kinginject.log; " +
                "\"\$KI\" --pid \$PID --lib /dev/vcam/libvc.so >>/data/local/tmp/vcamgd/kinginject.log 2>&1; " +
                "BEST_RC=\$?; echo RC=\$BEST_RC lib=/dev/vcam/libvc.so >>/data/local/tmp/vcamgd/kinginject.log; " +
                "fi; " +
                "MAPS=\$(cat /proc/\$PID/maps 2>/dev/null | grep -E 'libvc\\.so' | grep -v 'libvc++' | grep -v '(deleted)' | head -3 | tr '\\n' ','); " +
                "echo KI_RC=\$BEST_RC; echo MAPS=\${MAPS:-empty}; " +
                "echo KING_DONE",
            timeoutSec = 35,
        )
        Log.i(TAG, "runKingInject: ${out.take(500)}")
        runCatching {
            com.vcamgd.app.util.KingVCamLog.i("kinginject", out.lineSequence().take(8).joinToString(" | "))
        }
        return out
    }

    fun snapshotDiag(): String {
        val mm = if (RootShell.supportsMountMaster()) "yes" else "no"
        return RootShell.runGlobal(
            "echo brand=${Build.BRAND}/${Build.MANUFACTURER}; " +
                "echo sdk=${Build.VERSION.SDK_INT}/${Build.VERSION.RELEASE}; " +
                "echo enforce=\$(getenforce 2>/dev/null); " +
                "PTR=\$(cat /proc/sys/kernel/yama/ptrace_scope 2>/dev/null || echo none); echo ptrace=\$PTR; " +
                "echo mm=$mm; " +
                "PID=\$(pidof cameraserver 2>/dev/null | awk '{print \$1}'); echo cam=\$PID; " +
                "echo vcplax=\$(pidof vcplax 2>/dev/null); " +
                "MAPS=\$(cat /proc/\$PID/maps 2>/dev/null | grep -E 'libvc\\.so' | grep -v 'libvc++' | head -3 | tr '\\n' ','); " +
                "echo maps=\${MAPS:-empty}; " +
                "echo deleted=\$(echo \"\$MAPS\" | grep -c '(deleted)'); " +
                "echo ki=\$(grep -E 'RC=|ATTACH|attach|dlopen|handle|path@|call ret|code=|maps_libvc' /data/local/tmp/vcamgd/kinginject.log 2>/dev/null | tail -n 6 | tr '\\n' ';'); " +
                "echo context=\$(cat /proc/\$PID/attr/current 2>/dev/null); " +
                "echo vx=\$(tail -n 2 /data/local/tmp/vcamgd/vcplax.log 2>/dev/null | tr '\\n' ';')",
            timeoutSec = 12,
        )
    }
}
