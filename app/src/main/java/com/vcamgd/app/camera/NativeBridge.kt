package com.vcamgd.app.camera

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Ponte com o modulo Magisk/Zygisk + arquivo de video compartilhado para o hook LSPosed.
 *
 * Controle: /data/adb/vcamgd/control.json
 * Video:    /data/adb/vcamgd/current.mp4
 * Status:   /data/adb/vcamgd/status.json
 * Marcador: /data/adb/modules/vcamgd/module.prop
 */
object NativeBridge {
    private const val TAG = "VCamGD-Native"
    private const val MODULE_PROP = "/data/adb/modules/vcamgd/module.prop"
    private const val CONTROL_PATH = "/data/adb/vcamgd/control.json"
    private const val STATUS_PATH = "/data/adb/vcamgd/status.json"
    private const val VIDEO_PATH = "/data/adb/vcamgd/current.mp4"

    fun isModulePresent(): Boolean = fileExistsAsRoot(MODULE_PROP)

    fun readModuleStatus(): String {
        val raw = readFileAsRoot(STATUS_PATH)
        return if (raw.isNullOrBlank()) "Sem eventos do Zygisk/LSPosed ainda" else raw.trim()
    }

    fun setLocalVideoSource(context: Context, uri: Uri): Boolean {
        persist(context, "local", uri = uri.toString())
        val staged = stageLocalVideo(context, uri)
        if (!staged) {
            Log.e(TAG, "Failed to stage local video")
            return false
        }
        return writeControl(
            enabled = true,
            virtual = true,
            source = "local",
            uri = VIDEO_PATH,
            url = "",
        )
    }

    fun setNetworkSource(context: Context, url: String): Boolean {
        val normalized = url.trim()
        if (!isValidNetworkUrl(normalized)) {
            Log.e(TAG, "Invalid network url: $normalized")
            return false
        }
        persist(context, "network", url = normalized)
        return writeControl(
            enabled = true,
            virtual = true,
            source = "network",
            uri = "",
            url = normalized,
        )
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
        return writeControl(enabled = true, virtual = true, source = "usb", uri = "", url = "")
    }

    fun disable(context: Context) {
        context.getSharedPreferences("vcam_runtime", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", false)
            .apply()
        writeControl(enabled = false, virtual = false, source = "", uri = "", url = "")
    }

    fun switchToReal(context: Context) {
        context.getSharedPreferences("vcam_runtime", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("virtual", false)
            .apply()
        patchControlVirtual(false)
    }

    fun switchToVirtual(context: Context) {
        context.getSharedPreferences("vcam_runtime", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("virtual", true)
            .apply()
        patchControlVirtual(true)
    }

    private fun stageLocalVideo(context: Context, uri: Uri): Boolean {
        return try {
            val cache = File(context.cacheDir, "vcam_input.mp4")
            context.contentResolver.openInputStream(uri)?.use { input ->
                cache.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            val ok = shellSu(
                "mkdir -p /data/adb/vcamgd; " +
                    "cp '${cache.absolutePath}' '$VIDEO_PATH'; " +
                    "chmod 666 '$VIDEO_PATH'; " +
                    "ls -l '$VIDEO_PATH'; echo OK",
            ).contains("OK")
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
            .apply()
    }

    private fun patchControlVirtual(virtual: Boolean) {
        val current = readFileAsRoot(CONTROL_PATH)
        val json = try {
            if (current.isNullOrBlank()) JSONObject() else JSONObject(current)
        } catch (_: Exception) {
            JSONObject()
        }
        json.put("virtual", virtual)
        if (!json.has("enabled")) json.put("enabled", true)
        writeFileAsRoot(CONTROL_PATH, json.toString())
    }

    private fun writeControl(
        enabled: Boolean,
        virtual: Boolean,
        source: String,
        uri: String,
        url: String,
    ): Boolean {
        val present = isModulePresent()
        val json = JSONObject()
            .put("enabled", enabled)
            .put("virtual", virtual)
            .put("source", source)
            .put("uri", uri)
            .put("url", url)
            .toString()
        val ok = writeFileAsRoot(CONTROL_PATH, json)
        Log.i(TAG, "writeControl present=$present ok=$ok json=$json")
        return present && ok
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

    private fun writeFileAsRoot(path: String, content: String): Boolean {
        val escaped = content.replace("'", "'\\''")
        val script =
            "mkdir -p /data/adb/vcamgd; printf '%s' '$escaped' > '$path'; chmod 666 '$path'; echo OK"
        return shellSu(script).contains("OK")
    }

    private fun shellSu(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            process.waitFor()
            (stdout + stderr).trim()
        } catch (e: Exception) {
            Log.w(TAG, "su failed: ${e.message}")
            ""
        }
    }
}
