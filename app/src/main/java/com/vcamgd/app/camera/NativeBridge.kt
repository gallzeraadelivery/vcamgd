package com.vcamgd.app.camera

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * IPC primario: /data/local/tmp/vcamgd (legivel pelos apps / hooks Zygisk)
 * Espelho:     /data/adb/vcamgd (Magisk/Zygisk)
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

    fun isModulePresent(): Boolean = fileExistsAsRoot(MODULE_PROP)

    fun readModuleStatus(): String {
        val raw = readFileAsRoot(STATUS_TMP) ?: readFileAsRoot(STATUS_ADB)
        return if (raw.isNullOrBlank()) "Sem eventos do Zygisk ainda" else raw.trim()
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
            uri = VIDEO_TMP,
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
            val script =
                "mkdir -p '$TMP_DIR' '$ADB_DIR'; " +
                    "cp '${cache.absolutePath}' '$VIDEO_TMP'; " +
                    "cp '${cache.absolutePath}' '$VIDEO_ADB'; " +
                    "chmod 777 '$TMP_DIR'; chmod 666 '$VIDEO_TMP' '$VIDEO_ADB'; " +
                    "ls -l '$VIDEO_TMP'; echo OK"
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
            .apply()
    }

    private fun patchControlVirtual(virtual: Boolean) {
        val current = readFileAsRoot(CONTROL_TMP) ?: readFileAsRoot(CONTROL_ADB)
        val json = try {
            if (current.isNullOrBlank()) JSONObject() else JSONObject(current)
        } catch (_: Exception) {
            JSONObject()
        }
        json.put("virtual", virtual)
        if (!json.has("enabled")) json.put("enabled", true)
        writeControlFiles(json.toString())
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
