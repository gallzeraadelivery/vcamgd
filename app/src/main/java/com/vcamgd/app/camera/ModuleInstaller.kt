package com.vcamgd.app.camera

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Instala o modulo Zygisk embutido no APK (modelo OVCAM: usuario so instala o APK).
 * Requer Magisk/KernelSU com Zygisk + root (su).
 *
 * Importante: nao chamar `magisk --install-module` de novo se o modulo ja esta
 * ativo — Magisk coloca em modules_update e exige reboot infinito.
 */
object ModuleInstaller {
    private const val TAG = "VCamGD-ModuleInstall"
    private const val ASSET_ZIP = "vcamgd-magisk.zip"
    private const val MODULE_DIR = "/data/adb/modules/vcamgd"
    private const val MODULE_UPDATE_DIR = "/data/adb/modules_update/vcamgd"
    private const val MODULE_PROP = "$MODULE_DIR/module.prop"
    private const val MODULE_UPDATE_PROP = "$MODULE_UPDATE_DIR/module.prop"
    private const val ZYGISK_SO = "$MODULE_DIR/zygisk/arm64-v8a.so"
    private const val EMBEDDED_VERSION_CODE = 14

    sealed class Result {
        data object AlreadyInstalled : Result()
        data object InstalledNeedsReboot : Result()
        data class Failed(val reason: String) : Result()
    }

    fun isModulePresent(): Boolean = fileExistsAsRoot(MODULE_PROP)

    fun installedVersionCode(): Int = readVersionCode(MODULE_PROP)

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

        val liveVersion = readVersionCode(MODULE_PROP)
        val pendingVersion = readVersionCode(MODULE_UPDATE_PROP)
        val liveOk = liveVersion >= EMBEDDED_VERSION_CODE &&
            fileExistsAsRoot(ZYGISK_SO) &&
            !isModuleDisabled()

        // Ja ativo apos reboot — NUNCA reinstalar (evita loop de reboot).
        if (liveOk) {
            Log.i(TAG, "module already active versionCode=$liveVersion")
            ensureIpcDirs()
            return Result.AlreadyInstalled
        }

        // Magisk ja stageou update; so falta o reboot que o usuario ainda nao fez.
        if (pendingVersion >= EMBEDDED_VERSION_CODE) {
            Log.i(TAG, "module pending in modules_update versionCode=$pendingVersion")
            return Result.InstalledNeedsReboot
        }

        // Modulo antigo/incompleto: atualizar arquivos.
        return try {
            val staged = stageZipFromAssets(context) ?: return Result.Failed("Asset $ASSET_ZIP ausente no APK")
            val extracted = File(context.cacheDir, "vcamgd_module_extract")
            if (extracted.exists()) extracted.deleteRecursively()
            extracted.mkdirs()
            unzip(staged, extracted)

            val hadLiveBefore = fileExistsAsRoot(MODULE_PROP)
            val ok = deployExtracted(extracted, preferInPlace = hadLiveBefore)
            if (!ok) return Result.Failed("Falha ao copiar modulo para $MODULE_DIR")

            ensureIpcDirs()

            val liveAfter = readVersionCode(MODULE_PROP)
            val pendingAfter = readVersionCode(MODULE_UPDATE_PROP)
            Log.i(
                TAG,
                "module deploy done live=$liveAfter pending=$pendingAfter hadLive=$hadLiveBefore",
            )

            // Se ficou so em modules_update, precisa reboot.
            if (pendingAfter >= EMBEDDED_VERSION_CODE && liveAfter < EMBEDDED_VERSION_CODE) {
                return Result.InstalledNeedsReboot
            }

            // Copia in-place para modules/ com versao ok: Zygisk so carrega no boot,
            // mas se ja existia o modulo, um reboot ja foi pedido antes — nao bloquear
            // o app para sempre. Pedimos reboot so na primeira instalacao.
            if (liveAfter >= EMBEDDED_VERSION_CODE && fileExistsAsRoot(ZYGISK_SO)) {
                return if (hadLiveBefore) {
                    Result.AlreadyInstalled
                } else {
                    Result.InstalledNeedsReboot
                }
            }

            if (pendingAfter >= EMBEDDED_VERSION_CODE || liveAfter >= 0) {
                return Result.InstalledNeedsReboot
            }
            Result.Failed("Modulo nao ficou instalado (verifique Magisk/Zygisk)")
        } catch (t: Throwable) {
            Log.e(TAG, "ensureInstalled", t)
            Result.Failed(t.message ?: "erro desconhecido")
        }
    }

    private fun ensureIpcDirs() {
        shellSu(
            "mkdir -p /data/local/tmp/vcamgd /data/adb/vcamgd; " +
                "chmod 777 /data/local/tmp/vcamgd; " +
                "if [ -f $MODULE_DIR/lib/arm64-v8a/libpine.so ]; then " +
                "cp -f $MODULE_DIR/lib/arm64-v8a/libpine.so /data/local/tmp/vcamgd/libpine.so; " +
                "chmod 755 /data/local/tmp/vcamgd/libpine.so; fi; " +
                "if [ ! -f /data/local/tmp/vcamgd/control.json ]; then " +
                "echo '{\"enabled\":false,\"virtual\":false,\"mode\":\"real\",\"source\":\"\",\"uri\":\"\",\"url\":\"\"}' " +
                "> /data/local/tmp/vcamgd/control.json; fi; " +
                "cp -f /data/local/tmp/vcamgd/control.json /data/adb/vcamgd/control.json 2>/dev/null; " +
                "chmod 666 /data/local/tmp/vcamgd/control.json /data/adb/vcamgd/control.json 2>/dev/null; " +
                "echo OK",
        )
    }

    private fun readVersionCode(propPath: String): Int {
        val raw = readFileAsRoot(propPath) ?: return -1
        val line = raw.lineSequence()
            .map { it.trim().trimEnd('\r') }
            .firstOrNull { it.startsWith("versionCode=") }
            ?: return -1
        return line.substringAfter("=").trim().toIntOrNull() ?: -1
    }

    private fun isModuleDisabled(): Boolean =
        fileExistsAsRoot("$MODULE_DIR/disable") || fileExistsAsRoot("$MODULE_DIR/remove")

    private fun isMagiskModulesDirPresent(): Boolean =
        shellSu("test -d /data/adb/modules && echo OK").contains("OK")

    private fun hasSu(): Boolean =
        shellSu("id").contains("uid=0")

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

    /**
     * @param preferInPlace se true, copia direto em modules/ (sem magisk CLI),
     * evitando stages em modules_update que pedem reboot de novo.
     */
    private fun deployExtracted(extracted: File, preferInPlace: Boolean): Boolean {
        val src = extracted.absolutePath
        val zipInCache = File(extracted.parentFile, ASSET_ZIP)

        if (!preferInPlace && zipInCache.exists()) {
            val viaMagisk = shellSu(
                "magisk --install-module '${zipInCache.absolutePath}' 2>&1; echo EXIT:\$?",
            )
            Log.i(TAG, "magisk --install-module: $viaMagisk")
            // Magisk costuma deixar em modules_update ate o reboot
            if (viaMagisk.contains("EXIT:0") ||
                fileExistsAsRoot(MODULE_PROP) ||
                fileExistsAsRoot(MODULE_UPDATE_PROP)
            ) {
                shellSu(
                    "rm -f $MODULE_DIR/disable $MODULE_DIR/remove " +
                        "$MODULE_UPDATE_DIR/disable $MODULE_UPDATE_DIR/remove 2>/dev/null; echo OK",
                )
                return fileExistsAsRoot(MODULE_PROP) || fileExistsAsRoot(MODULE_UPDATE_PROP)
            }
        }

        val script =
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

    private fun shellSu(command: String): String =
        com.vcamgd.app.root.RootShell.run(command, timeoutSec = 12)
}
