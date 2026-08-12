package com.vcamgd.app.camera

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * IPC primario: /data/local/tmp/vcamgd (legivel pelos apps / hooks Zygisk)
 * Espelho:     /data/adb/vcamgd (Magisk/Zygisk)
 *
 * Controle:
 *  enabled + mode=virtual => feeder de video
 *  enabled + mode=real    => pass-through (camera real)
 *  enabled=false          => desligado
 */
object NativeBridge {
    private const val TAG = "VCamGD-Native"
    private const val MODULE_PROP = "/data/adb/modules/vcamgd/module.prop"

    private const val TMP_DIR = "/data/local/tmp/vcamgd"
    private const val ADB_DIR = "/data/adb/vcamgd"
    private const val CONTROL_TMP = "$TMP_DIR/control.json"
    private const val CONTROL_ADB = "$ADB_DIR/control.json"
    private const val STATUS_TMP = "$TMP_DIR/status.json"
    private const val STATUS_ADB = "$ADB_DIR/status.json"
    private const val VIDEO_TMP = "$TMP_DIR/current.mp4"
    private const val VIDEO_ADB = "$ADB_DIR/current.mp4"

    /** Pacotes comuns de camera — force-stop ao intercalar Real/Virtual. */
    private val CAMERA_PACKAGES = listOf(
        "com.android.camera",
        "com.android.camera2",
        "com.google.android.GoogleCamera",
        "com.google.android.apps.cameralite",
        "com.sec.android.app.camera",
        "com.samsung.android.camera",
        "com.miui.camera",
        "com.android.mmc",
        "org.codeaurora.snapcam",
        "com.huawei.camera",
        "com.oplus.camera",
        "com.oneplus.camera",
        // Motorola
        "com.motorola.camera",
        "com.motorola.camera2",
        "com.motorola.camera3",
        "com.motorola.camera2.selectivemotion",
        "com.motorola.actions",
        "com.sonyericsson.android.camera",
        "com.transsion.camera",
        // Xiaomi / HyperOS / Redmi Note
        "com.xiaomi.scanner",
        "com.mlab.cam",
        "com.xiaomi.mircs",
        "com.miui.gallery",
        "com.xiaomi.camera.videocast",
        "com.xiaomi.cameratools",
        // Apps frequentes
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.instagram.android",
        "com.facebook.orca",
        "com.facebook.mlite",
        "org.telegram.messenger",
        "com.discord",
        "com.snapchat.android",
        "com.google.android.apps.messaging",
        "com.android.chrome",
        "com.ss.android.ugc.aweme",
        "com.zhiliaoapp.musically",
    )

    fun isModulePresent(): Boolean =
        ModuleInstaller.isModulePresent() || fileExistsAsRoot(MODULE_PROP)

    fun readModuleStatus(): String {
        val raw = readFileAsRoot(STATUS_TMP) ?: readFileAsRoot(STATUS_ADB)
        return if (raw.isNullOrBlank()) "Sem eventos do Zygisk ainda" else raw.trim()
    }

    /**
     * Escreve mode=real no control.json sem matar cameras (seguro no arranque).
     */
    fun writePassthroughOnly(virtualEnabledInApp: Boolean) {
        if (virtualEnabledInApp) return
        runCatching {
            writeControl(
                enabled = false,
                virtual = false,
                mode = "real",
                source = "",
                uri = "",
                url = "",
            )
        }
    }

    /**
     * Garante pass-through (zero hooks) no arranque se a virtual nao estiver ativa no app.
     * Recupera devices em que control.json ficou preso em mode=virtual.
     */
    fun syncPassthroughUnlessVirtualEnabled(virtualEnabledInApp: Boolean) {
        writePassthroughOnly(virtualEnabledInApp)
        if (virtualEnabledInApp) return
        // So reinicia cameras quando o usuario desliga de proposito — nao no boot do app
    }

    fun setLocalVideoSource(context: Context, uri: Uri): Boolean {
        persist(context, "local", uri = uri.toString())
        val staged = stageLocalVideo(context, uri)
        if (!staged) {
            Log.e(TAG, "Failed to stage local video")
            return false
        }
        val ok = writeControl(
            enabled = true,
            virtual = true,
            mode = "virtual",
            source = "local",
            uri = VIDEO_TMP,
            url = "",
        )
        // Restart so no VirtualCameraController — evita double-kill cedo demais
        return ok
    }

    fun setNetworkSource(context: Context, url: String): Boolean {
        val normalized = url.trim()
        if (!isValidNetworkUrl(normalized)) {
            Log.e(TAG, "Invalid network url: $normalized")
            return false
        }
        persist(context, "network", url = normalized)
        val ok = writeControl(
            enabled = true,
            virtual = true,
            mode = "virtual",
            source = "network",
            uri = "",
            url = normalized,
        )
        return ok
    }

    private fun isValidNetworkUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.startsWith("rtsp://") ||
            u.startsWith("rtspt://") ||
            u.startsWith("http://") ||
            u.startsWith("https://") ||
            u.startsWith("rtmp://") ||
            u.startsWith("rtmps://")
    }

    fun setUsbSource(context: Context): Boolean {
        persist(context, "usb")
        val ok = writeControl(
            enabled = true,
            virtual = true,
            mode = "virtual",
            source = "usb",
            uri = "",
            url = "",
        )
        if (ok) restartCameraApps()
        return ok
    }

    fun disable(context: Context) {
        context.getSharedPreferences("vcam_runtime", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", false)
            .putBoolean("virtual", false)
            .putString("mode", "real")
            .apply()
        writeControl(
            enabled = false,
            virtual = false,
            mode = "real",
            source = "",
            uri = "",
            url = "",
        )
        restartCameraApps()
    }

    fun switchToReal(context: Context) {
        context.getSharedPreferences("vcam_runtime", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("virtual", false)
            .putBoolean("enabled", false)
            .putString("mode", "real")
            .apply()
        // enabled=false + mode=real => companion nao envia dex (camera nativa)
        writeControl(
            enabled = false,
            virtual = false,
            mode = "real",
            source = "",
            uri = "",
            url = "",
        )
        restartCameraApps()
    }

    fun switchToVirtual(context: Context) {
        val prefs = context.getSharedPreferences("vcam_runtime", Context.MODE_PRIVATE)
        val source = prefs.getString("source", "local").orEmpty()
        val uri = prefs.getString("uri", "").orEmpty()
        val url = prefs.getString("url", "").orEmpty()
        prefs.edit()
            .putBoolean("virtual", true)
            .putBoolean("enabled", true)
            .putString("mode", "virtual")
            .apply()
        val ok = writeControl(
            enabled = true,
            virtual = true,
            mode = "virtual",
            source = source.ifBlank { "local" },
            uri = if (source == "local" || source.isBlank()) VIDEO_TMP else uri,
            url = url,
        )
        if (ok) restartCameraApps()
    }

    /**
     * Force-stop so apps de camera do OEM.
     * HyperOS: lista enxuta — force-stop em massa derruba a sessao Camera2
     * ("Nao e possivel conectar-se a camera").
     */
    fun restartCameraApps() {
        val pkgs = if (
            android.os.Build.MANUFACTURER.contains("xiaomi", true) ||
            android.os.Build.BRAND.contains("redmi", true) ||
            android.os.Build.BRAND.contains("poco", true) ||
            android.os.Build.BRAND.contains("xiaomi", true)
        ) {
            listOf(
                "com.android.camera",
                "com.android.camera2",
                "com.miui.camera",
                "com.xiaomi.camera",
                "com.mlab.cam",
                "com.android.mmc",
            )
        } else {
            CAMERA_PACKAGES
        }
        val cmds = pkgs.joinToString("; ") { "am force-stop $it 2>/dev/null" }
        val out = shellSu("$cmds; echo OK")
        Log.i(TAG, "restartCameraApps: ${out.take(200)}")
    }

    private fun stageLocalVideo(context: Context, uri: Uri): Boolean {
        return try {
            val cache = File(context.cacheDir, "vcam_input.mp4")
            context.contentResolver.openInputStream(uri)?.use { input ->
                cache.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            val script =
                "mkdir -p '$TMP_DIR' '$ADB_DIR'; " +
                    "cp '${cache.absolutePath}' '$VIDEO_TMP'; " +
                    "cp '${cache.absolutePath}' '$VIDEO_ADB'; " +
                    "chmod 777 '$TMP_DIR' '$ADB_DIR'; " +
                    "chmod 666 '$VIDEO_TMP' '$VIDEO_ADB'; " +
                    "chown root:root '$VIDEO_TMP' '$VIDEO_ADB' 2>/dev/null; " +
                    // SELinux: apps de camera precisam ler o mp4
                    "chcon u:object_r:magisk_file:s0 '$TMP_DIR' '$VIDEO_TMP' 2>/dev/null; " +
                    "chcon u:object_r:system_data_file:s0 '$VIDEO_TMP' 2>/dev/null; " +
                    "restorecon -F '$TMP_DIR' '$VIDEO_TMP' 2>/dev/null; " +
                    "ls -lZ '$VIDEO_TMP'; echo OK"
            val ok = shellSu(script).contains("OK")
            Log.i(TAG, "stageLocalVideo size=${cache.length()} ok=$ok")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "stageLocalVideo", e)
            false
        }
    }

    private fun persist(
        context: Context,
        source: String,
        uri: String = "",
        url: String = "",
    ) {
        context.getSharedPreferences("vcam_runtime", Context.MODE_PRIVATE)
            .edit()
            .putString("source", source)
            .putString("uri", uri)
            .putString("url", url)
            .putBoolean("enabled", true)
            .putBoolean("virtual", true)
            .putString("mode", "virtual")
            .apply()
    }

    private fun writeControl(
        enabled: Boolean,
        virtual: Boolean,
        mode: String,
        source: String,
        uri: String,
        url: String,
    ): Boolean {
        val present = isModulePresent()
        val json = JSONObject()
            .put("enabled", enabled)
            .put("virtual", virtual)
            .put("mode", mode)
            .put("inject", EngineFacade.injectField())
            .put("source", source)
            .put("uri", uri)
            .put("url", url)
            .toString()
        val ok = writeControlFiles(json)
        Log.i(TAG, "writeControl present=$present ok=$ok json=$json")
        return ok
    }

    private fun writeControlFiles(json: String): Boolean {
        val escaped = json.replace("'", "'\\''")
        val script =
            "mkdir -p '$TMP_DIR' '$ADB_DIR'; " +
                "printf '%s' '$escaped' > '$CONTROL_TMP'; " +
                "printf '%s' '$escaped' > '$CONTROL_ADB'; " +
                "chmod 777 '$TMP_DIR'; chmod 666 '$CONTROL_TMP' '$CONTROL_ADB' " +
                "'$STATUS_TMP' '$STATUS_ADB' 2>/dev/null; " +
                "echo OK"
        return shellSu(script).contains("OK")
    }

    private fun fileExistsAsRoot(path: String): Boolean {
        if (File(path).exists()) return true
        return shellSu("test -f '$path' && echo OK || echo NO").contains("OK")
    }

    private fun readFileAsRoot(path: String): String? {
        File(path).takeIf { it.exists() && it.canRead() }?.let {
            return runCatching { it.readText() }.getOrNull()
        }
        val out = shellSu("cat '$path' 2>/dev/null")
        return out.ifBlank { null }
    }

    private fun shellSu(command: String): String =
        com.vcamgd.app.root.RootShell.run(command, timeoutSec = 8)
}
