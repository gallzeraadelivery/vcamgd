package com.vcamgd.app.root

import android.os.Build
import android.util.Log
import android.util.Base64

/**
 * SELinux live (sem reboot) para Android 12–16.
 * magiskpolicy --live e/ou ksud sepolicy apply.
 */
object SelinuxLive {
    private const val TAG = "KingVCam-SELinux"
    private const val POLICY = "/data/local/tmp/vcamgd/king_runtime.te"
    private const val LOG = "/data/local/tmp/vcamgd/selinux_apply.log"

    private val RULE_LINES = listOf(
        "allow su cameraserver process { ptrace getattr sigchld signal sigkill }",
        "allow su system_data_root_file dir search",
        "allow su system_data_file dir { search open read getattr write add_name remove_name }",
        "allow su system_data_file file { create open read write getattr setattr append map unlink rename ioctl lock execute execute_no_trans }",
        "allow su system_lib_file file { create open read write getattr setattr append map unlink rename ioctl lock execute execute_no_trans }",
        "allow su tmpfs file { create open read write getattr setattr append map unlink rename ioctl lock execute execute_no_trans }",
        "allow su default_android_service service_manager { add find }",
        "allow cameraserver system_data_root_file dir search",
        "allow cameraserver system_data_file dir { search open read getattr }",
        "allow cameraserver system_data_file file { open read getattr map execute execute_no_trans }",
        "allow cameraserver system_lib_file file { open read getattr map execute execute_no_trans }",
        "allow cameraserver tmpfs file { open read getattr map execute execute_no_trans }",
        "allow cameraserver cameraserver process execmem",
        "allow cameraserver default_android_service service_manager add",
        "allow untrusted_app default_android_service service_manager find",
        "allow platform_app default_android_service service_manager find",
        "allow su cameraserver binder { call transfer }",
        "allow cameraserver su fd use",
    )

    data class Result(val ok: Boolean, val detail: String)

    fun applyForCameraInject(): Result {
        val sdk = Build.VERSION.SDK_INT
        val body = RULE_LINES.joinToString("\n")
        val b64 = Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val write = RootShell.run(
            "mkdir -p /data/local/tmp/vcamgd; " +
                "echo '$b64' | base64 -d > $POLICY; " +
                "chmod 644 $POLICY; " +
                "chcon u:object_r:system_data_file:s0 $POLICY 2>/dev/null; " +
                "wc -c $POLICY; echo WROTE",
            timeoutSec = 8,
        )
        if (!write.contains("WROTE")) {
            return Result(false, "falha ao escrever policy (sdk=$sdk)")
        }

        val magisk = RootShell.run(
            "MP=\$(command -v magiskpolicy 2>/dev/null); " +
                "if [ -z \"\$MP\" ] && [ -x /system/bin/magiskpolicy ]; then MP=/system/bin/magiskpolicy; fi; " +
                "if [ -z \"\$MP\" ] && [ -x /data/adb/magisk/magiskpolicy ]; then MP=/data/adb/magisk/magiskpolicy; fi; " +
                "if [ -n \"\$MP\" ]; then \"\$MP\" --live --apply $POLICY >$LOG 2>&1; echo MP:\$?; " +
                "\"\$MP\" --live 'allow cameraserver cameraserver process execmem' >/dev/null 2>&1; " +
                "\"\$MP\" --live 'allow su cameraserver process ptrace' >/dev/null 2>&1; " +
                "else echo MP:missing; fi; echo DONE_MP",
            timeoutSec = 14,
        )

        val ksud = RootShell.run(
            "KSUD=\$(command -v ksud 2>/dev/null); " +
                "if [ -z \"\$KSUD\" ] && [ -x /data/adb/ksud ]; then KSUD=/data/adb/ksud; fi; " +
                "if [ -n \"\$KSUD\" ]; then " +
                "\"\$KSUD\" sepolicy apply $POLICY >$LOG 2>&1; echo KS:\$?; " +
                "else echo KS:missing; fi",
            timeoutSec = 12,
        )

        var permissiveNote = ""
        if (sdk >= 35) {
            permissiveNote = RootShell.run(
                "MP=\$(command -v magiskpolicy 2>/dev/null || echo /data/adb/magisk/magiskpolicy); " +
                    "\"\$MP\" --live 'permissive cameraserver' >/dev/null 2>&1; " +
                    "\"\$MP\" --live 'permissive su' >/dev/null 2>&1; " +
                    "\"\$MP\" --live 'permissive magisk' >/dev/null 2>&1; " +
                    "\"\$MP\" --live 'permissive hal_camera_default' >/dev/null 2>&1; " +
                    "\"\$MP\" --live 'permissive vendor_hal_camera_default' >/dev/null 2>&1; " +
                    "\"\$MP\" --live 'permissive mtk_hal_camera' >/dev/null 2>&1; " +
                    "\"\$MP\" --live 'allow cameraserver magisk_file file { open read getattr map execute execute_no_trans }' >/dev/null 2>&1; " +
                    "echo PERM_DONE",
                timeoutSec = 10,
            )
        }

        val ok = magisk.contains("MP:0") ||
            ksud.contains("KS:0") ||
            magisk.contains("DONE_MP") ||
            permissiveNote.contains("PERM_DONE")

        val detail = "sdk=$sdk magisk=${magisk.take(100)} ksud=${ksud.take(60)}"
        Log.i(TAG, "apply ok=$ok $detail")
        return Result(ok, detail)
    }

    fun setEnforcing(enforcing: Boolean) {
        RootShell.run(
            if (enforcing) "setenforce 1" else "setenforce 0",
            timeoutSec = 3,
        )
    }
}
