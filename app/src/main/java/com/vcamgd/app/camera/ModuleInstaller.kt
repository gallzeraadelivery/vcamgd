package com.vcamgd.app.camera

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * Instala o modulo Zygisk embutido no APK (modelo OVCAM: usuario so instala o APK).
 * Requer Magisk/KernelSU com Zygisk + root (su).
 */
object ModuleInstaller {
    private const val TAG = "VCamGD-ModuleInstall"
    private const val ASSET_ZIP = "vcamgd-magisk.zip"
    private const val MODULE_DIR = "/data/adb/modules/vcamgd"
    private const val MODULE_PROP = "$MODULE_DIR/module.prop"
    private const val EMBEDDED_VERSION_CODE = 11

    sealed class Result {
        data object AlreadyInstalled : Result()
        data object InstalledNeedsReboot : Result()
        data class Failed(val reason: String) : Result()
    }

    fun isModulePresent(): Boolean = fileExistsAsRoot(MODULE_PROP)

    fun installedVersionCode(): Int {
        val raw = readFileAsRoot(MODULE_PROP) ?: return -1
        val line = raw.lineSequence().firstOrNull { it.startsWith("versionCode=") } ?: return -1
        return line.substringAfter("=").trim().toIntOrNull() ?: -1
    }

    /**
     * Garante modulo instalado/atualizado a partir do asset do APK.
     */
    fun ensureInstalled(context: Context): Result {
        if (!hasSu()) {
            return Result.Failed("Root (su) indisponivel")
        }
        if (!isMagiskModulesDirPresent()) {
            return Result.Failed("Magisk/KernelSU nao detectado (/data/adb/modules)")
        }

        val current = installedVersionCode()
        if (current >= EMBEDDED_VERSION_CODE && isModulePresent() && !isModuleDisabled()) {
            Log.i(TAG, "module ok versionCode=$current")
            return Result.AlreadyInstalled
        }

        return try {
            val staged = stageZipFromAssets(context) ?: return Result.Failed("Asset $ASSET_ZIP ausente no APK")
            val extracted = File(context.cacheDir, "vcamgd_module_extract")
            if (extracted.exists()) extracted.deleteRecursively()
            extracted.mkdirs()
            unzip(staged, extracted)

            val ok = deployExtracted(extracted)
            if (!ok) return Result.Failed("Falha ao copiar modulo para $MODULE_DIR")

            // Prep IPC paths + pine (equivalente ao customize/service)
            shellSu(
                "mkdir -p /data/local/tmp/vcamgd /data/adb/vcamgd; " +
                    "chmod 777 /data/local/tmp/vcamgd; " +
                    "if [ -f $MODULE_DIR/lib/arm64-v8a/libpine.so ]; then " +
                    "cp -f $MODULE_DIR/lib/arm64-v8a/libpine.so /data/local/tmp/vcamgd/libpine.so; " +
                    "chmod 755 /data/local/tmp/vcamgd/libpine.so; fi; " +
                    "echo '{\"enabled\":false,\"virtual\":false,\"mode\":\"real\",\"source\":\"\",\"uri\":\"\",\"url\":\"\"}' > /data/local/tmp/vcamgd/control.json; " +
                    "cp -f /data/local/tmp/vcamgd/control.json /data/adb/vcamgd/control.json; " +
                    "chmod 666 /data/local/tmp/vcamgd/control.json /data/adb/vcamgd/control.json; " +
                    "echo OK",
            )

            Log.i(TAG, "module installed/updated to $EMBEDDED_VERSION_CODE")
            Result.InstalledNeedsReboot
        } catch (t: Throwable) {
            Log.e(TAG, "ensureInstalled", t)
            Result.Failed(t.message ?: "erro desconhecido")
        }
    }

    private fun isModuleDisabled(): Boolean =
        fileExistsAsRoot("$MODULE_DIR/disable") || fileExistsAsRoot("$MODULE_DIR/remove")

    private fun isMagiskModulesDirPresent(): Boolean =
        shellSu("test -d /data/adb/modules && echo OK").contains("OK")

    private fun hasSu(): Boolean =
        shellSu("id").contains("uid=0") || shellSu("echo OK").contains("OK")

    private fun stageZipFromAssets(context: Context): File? {
        return try {
            val out = File(context.cacheDir, ASSET_ZIP)
            context.assets.open(ASSET_ZIP).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            if (out.length() < 1000) null else out
        } catch (t: Throwable) {
            Log.e(TAG, "stageZipFromAssets", t)
            null
        }
    }

    private fun unzip(zipFile: File, destDir: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
            }
        }
    }

    private fun deployExtracted(extracted: File): Boolean {
        val src = extracted.absolutePath
        // Prefer magisk CLI when available
        val zipInCache = File(extracted.parentFile, ASSET_ZIP)
        if (zipInCache.exists()) {
            val viaMagisk = shellSu(
                "magisk --install-module '${zipInCache.absolutePath}' 2>&1; echo EXIT:\$?",
            )
            Log.i(TAG, "magisk --install-module: $viaMagisk")
            if (viaMagisk.contains("EXIT:0") || fileExistsAsRoot(MODULE_PROP)) {
                shellSu("rm -f $MODULE_DIR/disable $MODULE_DIR/remove; echo OK")
                return fileExistsAsRoot(MODULE_PROP)
            }
        }

        val script =
            "rm -rf '$MODULE_DIR'; " +
                "mkdir -p '$MODULE_DIR/zygisk' '$MODULE_DIR/dex' '$MODULE_DIR/lib/arm64-v8a'; " +
                "cp -f '$src/module.prop' '$MODULE_DIR/module.prop'; " +
                "cp -f '$src/customize.sh' '$MODULE_DIR/customize.sh' 2>/dev/null; " +
                "cp -f '$src/service.sh' '$MODULE_DIR/service.sh' 2>/dev/null; " +
                "cp -f '$src/uninstall.sh' '$MODULE_DIR/uninstall.sh' 2>/dev/null; " +
                "cp -f '$src/zygisk/arm64-v8a.so' '$MODULE_DIR/zygisk/arm64-v8a.so'; " +
                "cp -f '$src/dex/hook.dex' '$MODULE_DIR/dex/hook.dex'; " +
                "cp -f '$src/lib/arm64-v8a/libpine.so' '$MODULE_DIR/lib/arm64-v8a/libpine.so'; " +
                "chmod 755 '$MODULE_DIR' '$MODULE_DIR/zygisk' '$MODULE_DIR/service.sh' '$MODULE_DIR/customize.sh' 2>/dev/null; " +
                "chmod 644 '$MODULE_DIR/module.prop' '$MODULE_DIR/dex/hook.dex'; " +
                "chmod 755 '$MODULE_DIR/zygisk/arm64-v8a.so' '$MODULE_DIR/lib/arm64-v8a/libpine.so'; " +
                "rm -f '$MODULE_DIR/disable' '$MODULE_DIR/remove'; " +
                "test -f '$MODULE_DIR/module.prop' && test -f '$MODULE_DIR/zygisk/arm64-v8a.so' && echo OK"
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
