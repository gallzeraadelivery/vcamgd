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
        val out = RootShell.run(
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
                "cat /proc/sys/kernel/yama/ptrace_scope 2>/dev/null; " +
                "echo WINDOW_OK",
            timeoutSec = 16,
        )
        Log.i(TAG, "openWindow: ${out.take(240)}")
        return out
    }

    fun keepWindowAlive() {
        RootShell.run(
            "echo 0 > /proc/sys/kernel/yama/ptrace_scope 2>/dev/null; setenforce 0",
            timeoutSec = 3,
        )
    }

    /** Reinicia stack de camera (cameraserver + HALs OEM). */
    fun bounceCameraStack(): String {
        val killCmds = CAMERA_HAL_NAMES.joinToString("; ") { name ->
            "killall -9 '$name' 2>/dev/null"
        }
        val out = RootShell.run(
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
     * Redeploy das libs em caminhos legiveis pelo cameraserver + relabel.
     * HyperOS: /dev/vcam + bind em /system/lib64 (linker rejeita /data as vezes).
     */
    fun redeployLibPaths(filesDirEngine: String): String {
        val out = RootShell.run(
            "setenforce 0; " +
                "mkdir -p /data/adb/vcamgd /data/local/tmp/vcamgd /dev/vcam; " +
                "if [ -d '$filesDirEngine' ]; then " +
                "cp -f '$filesDirEngine/libvc.so' /data/libvc.so 2>/dev/null; " +
                "cp -f '$filesDirEngine/libshadowhook.so' /data/libvc++.so 2>/dev/null; " +
                "cp -f '$filesDirEngine/vcplax.so' /data/vcplax 2>/dev/null; " +
                "cp -f '$filesDirEngine/kinginject' /data/local/tmp/vcamgd/kinginject 2>/dev/null; " +
                "cp -f '$filesDirEngine/kinginject' /data/adb/vcamgd/kinginject 2>/dev/null; " +
                "fi; " +
                "cp -f /data/libvc.so /data/adb/vcamgd/libvc.so 2>/dev/null; " +
                "cp -f /data/libvc++.so /data/adb/vcamgd/libvc++.so 2>/dev/null; " +
                "cp -f /data/vcplax /data/adb/vcamgd/vcplax 2>/dev/null; " +
                "cp -f /data/libvc.so /dev/vcam/libvc.so; " +
                "cp -f /data/libvc++.so /dev/vcam/libvc++.so; " +
                "chmod 755 /data/libvc.so /data/libvc++.so /dev/vcam /dev/vcam/libvc.so /dev/vcam/libvc++.so 2>/dev/null; " +
                "chmod 700 /data/vcplax /data/local/tmp/vcamgd/kinginject /data/adb/vcamgd/kinginject 2>/dev/null; " +
                "chcon u:object_r:system_lib_file:s0 /data/libvc.so /data/libvc++.so /dev/vcam/libvc.so /dev/vcam/libvc++.so 2>/dev/null; " +
                "chcon u:object_r:system_file:s0 /data/vcplax /data/local/tmp/vcamgd/kinginject 2>/dev/null; " +
                "chcon u:object_r:magisk_file:s0 /data/adb/vcamgd /data/adb/vcamgd/* 2>/dev/null; " +
                // bind live em /system/lib64 (ns do init) — sem reboot
                "LIBDIR=/system/lib64; " +
                "if [ ! -d \$LIBDIR ]; then LIBDIR=/system/lib; fi; " +
                "if [ -d \$LIBDIR ]; then " +
                "cp -f /dev/vcam/libvc.so \$LIBDIR/libvc.so 2>/dev/null || " +
                "mount -o bind /dev/vcam/libvc.so \$LIBDIR/libvc.so 2>/dev/null; " +
                "cp -f /dev/vcam/libvc++.so \$LIBDIR/libvc++.so 2>/dev/null || " +
                "mount -o bind /dev/vcam/libvc++.so \$LIBDIR/libvc++.so 2>/dev/null; " +
                "nsenter -t 1 -m -- sh -c '" +
                "cp -f /dev/vcam/libvc.so /system/lib64/libvc.so 2>/dev/null; " +
                "cp -f /dev/vcam/libvc++.so /system/lib64/libvc++.so 2>/dev/null; " +
                "true' 2>/dev/null; " +
                "fi; " +
                "ls -lZ /data/libvc.so /dev/vcam/libvc.so /system/lib64/libvc.so 2>&1 | head -8; " +
                "echo REDEPLOY_OK",
            timeoutSec = 14,
        )
        Log.i(TAG, "redeploy: ${out.take(280)}")
        return out
    }

    /**
     * Injector proprio (kinginject) — fallback quando vcplax nao gruda no HyperOS.
     * Tenta varios caminhos de lib.
     */
    fun runKingInject(): String {
        val out = RootShell.run(
            "setenforce 0; " +
                "echo 0 > /proc/sys/kernel/yama/ptrace_scope 2>/dev/null; " +
                "KI=; " +
                "for p in /data/local/tmp/vcamgd/kinginject /data/adb/vcamgd/kinginject; do " +
                "[ -x \$p ] && KI=\$p; done; " +
                "if [ -z \"\$KI\" ]; then echo NO_KINGINJECT; exit 0; fi; " +
                "PID=\$(pidof cameraserver | awk '{print \$1}'); " +
                "echo CAM=\$PID KI=\$KI; " +
                "if [ -z \"\$PID\" ]; then echo NO_CAM; exit 0; fi; " +
                "for lib in /dev/vcam/libvc.so /system/lib64/libvc.so /data/libvc.so /data/adb/vcamgd/libvc.so; do " +
                "if [ -f \$lib ]; then " +
                "echo TRY=\$lib; " +
                "\"\$KI\" --pid \$PID --lib \$lib >>/data/local/tmp/vcamgd/kinginject.log 2>&1; " +
                "echo RC=\$?; " +
                "cat /proc/\$PID/maps 2>/dev/null | grep -E 'libvc|shadowhook|vcam' | head -5; " +
                "fi; done; " +
                // shadowhook / libvc++
                "for lib in /dev/vcam/libvc++.so /system/lib64/libvc++.so /data/libvc++.so; do " +
                "if [ -f \$lib ]; then \"\$KI\" --pid \$PID --lib \$lib >>/data/local/tmp/vcamgd/kinginject.log 2>&1; fi; " +
                "done; " +
                "echo KING_DONE",
            timeoutSec = 20,
        )
        Log.i(TAG, "runKingInject: ${out.take(400)}")
        return out
    }

    fun snapshotDiag(): String {
        return RootShell.run(
            "echo brand=${Build.BRAND}/${Build.MANUFACTURER}; " +
                "echo sdk=${Build.VERSION.SDK_INT}/${Build.VERSION.RELEASE}; " +
                "echo enforce=\$(getenforce 2>/dev/null); " +
                "echo ptrace=\$(cat /proc/sys/kernel/yama/ptrace_scope 2>/dev/null); " +
                "echo cam=\$(pidof cameraserver 2>/dev/null); " +
                "echo vcplax=\$(pidof vcplax 2>/dev/null); " +
                "PID=\$(pidof cameraserver 2>/dev/null | awk '{print \$1}'); " +
                "if [ -n \"\$PID\" ]; then " +
                "echo context=\$(cat /proc/\$PID/attr/current 2>/dev/null); " +
                "cat /proc/\$PID/maps 2>/dev/null | grep -E 'libvc|shadow|vcplax|vcam' | head -8; " +
                "fi; " +
                "tail -n 15 /data/local/tmp/vcamgd/kinginject.log 2>/dev/null; " +
                "tail -n 10 /data/local/tmp/vcamgd/vcplax.log 2>/dev/null",
            timeoutSec = 10,
        )
    }
}
