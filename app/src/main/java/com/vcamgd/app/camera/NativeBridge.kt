package com.vcamgd.app.camera

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Ponte com o modulo Magisk/Zygisk.
 *
 * Controle: /data/adb/vcamgd/control.json (via su)
 * Status:   /data/adb/vcamgd/status.json  (Zygisk)
 * Marcador: /data/adb/modules/vcamgd/module.prop
 */
object NativeBridge {
    private const val TAG = "VCamGD-Native"
    private const val MODULE_PROP = "/data/adb/modules/vcamgd/module.prop"
    private const val CONTROL_PATH = "/data/adb/vcamgd/control.json"
    private const val STATUS_PATH = "/data/adb/vcamgd/status.json"

    fun isModulePresent(): Boolean {
        return fileExistsAsRoot(MODULE_PROP)
    }

    fun readModuleStatus(): String {
        val raw = readFileAsRoot(STATUS_PATH)
        return if (raw.isNullOrBlank()) "Sem eventos do Zygisk ainda" else raw.trim()
    }

    fun setLocalVideoSource(context: Context, uri: Uri): Boolean {
        persist(context, "local", uri = uri.toString())
        return writeControl(enabled = true, virtual = true, source = "local", uri = uri.toString(), url = "")
    }

    fun setNetworkSource(context: Context, url: String): Boolean {
        persist(context, "network", url = url)
        return writeControl(enabled = true, virtual = true, source = "network", uri = "", url = url)
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
