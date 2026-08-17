package com.vcamgd.app.camera

import android.content.Context
import android.os.Build
import android.util.Log
import com.vcamgd.app.root.RootShell
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Motor proprio KingVCam (v0.9+).
 *
 * Controle via root (su): o app UID nao consegue conectar LocalSocket em
 * /data/local/tmp (SELinux). O daemon [kingvd] roda como root; o app manda
 * comandos escrevendo control.json / checando pid+status via su.
 */
object KingEngine {
    private const val TAG = "KingVCam-KingEngine"
    private const val DIR = "/data/local/tmp/vcamgd"
    private const val SOCK = "$DIR/kingvd.sock"
    private const val BIN = "$DIR/kingvd"
    private const val LOG = "$DIR/kingvd.log"
    private const val CONTROL = "$DIR/control.json"
    private const val STATUS = "$DIR/status.json"
    private const val ADB_CONTROL = "/data/adb/vcamgd/control.json"

    sealed class Result {
        data object Ok : Result()
        data class Failed(val reason: String) : Result()
    }

    fun ensureRunning(context: Context): Result {
        return try {
            if (!RootShell.hasRoot()) return Result.Failed("Root (su) necessario")

            when (val mod = ModuleInstaller.ensureInstalled(context)) {
                is ModuleInstaller.Result.Failed ->
                    return Result.Failed("Zygisk/Magisk: ${mod.reason}")
                is ModuleInstaller.Result.AlreadyInstalled -> Unit
                is ModuleInstaller.Result.InstalledNeedsReboot -> {
                    // Modulo stageado: sobe kingvd mesmo assim. Hooks Zygisk
                    // so entram apos reboot, mas nao bloqueamos o app em loop.
                    Log.w(TAG, "modulo Zygisk aguardando reboot — seguindo com kingvd")
                }
            }

            extractAndDeploy(context)
            if (!waitAlive(timeoutMs = 4000)) {
                val diag = RootShell.run(
                    "echo PID=\$(pidof kingvd 2>/dev/null); " +
                        "ls -la $BIN $SOCK $LOG 2>&1; " +
                        "tail -n 20 $LOG 2>/dev/null",
                    timeoutSec = 5,
                )
                Log.e(TAG, "kingvd not alive: $diag")
                return Result.Failed(
                    "Daemon kingvd nao subiu. Veja Status/log. " +
                        diag.lineSequence().firstOrNull().orEmpty(),
                )
            }
            // Nao forcar mode=real aqui — apagaria virtual se o usuario esta ativando.
            Log.i(TAG, "kingvd ready pid=${RootShell.run("pidof kingvd")}")
            Result.Ok
        } catch (t: Throwable) {
            Log.e(TAG, "ensureRunning", t)
            Result.Failed(t.message ?: "erro ao iniciar kingvd")
        }
    }

    fun isAlive(): Boolean {
        if (!RootShell.hasRoot(timeoutSec = 3)) return false
        val pid = RootShell.run("pidof kingvd 2>/dev/null", timeoutSec = 3).trim()
        return pid.isNotEmpty()
    }

    fun startPlay(pathOrUrl: String): Result {
        if (!ensureDaemonOrRestart()) {
            return Result.Failed("kingvd offline")
        }
        val path = pathOrUrl.trim()
        if (path.isEmpty()) return Result.Failed("path vazio")
        val network = path.startsWith("rtsp") || path.startsWith("http") || path.startsWith("rtmp")
        val ok = writeControl(
            enabled = true,
            virtual = true,
            mode = "virtual",
            source = if (network) "network" else "local",
            uri = if (network) "" else path,
            url = if (network) path else "",
        )
        // marca status para UI
        RootShell.run(
            "printf '%s' '{\"engine\":\"kingvd\",\"feeder\":\"playing\",\"ts\":'\"\$(date +%s)\"'}' > $STATUS; " +
                "chmod 666 $STATUS 2>/dev/null",
        )
        return if (ok) Result.Ok else Result.Failed("falha ao escrever control.json")
    }

    fun stopPlay(): Result {
        val ok = writeControl(
            enabled = false,
            virtual = false,
            mode = "real",
            source = "",
            uri = "",
            url = "",
        )
        return if (ok) Result.Ok else Result.Failed("STOP falhou")
    }

    fun switchReal(): Result {
        val ok = writeControl(
            enabled = true,
            virtual = false,
            mode = "real",
            source = "",
            uri = "",
            url = "",
        )
        return if (ok) Result.Ok else Result.Failed("REAL falhou")
    }

    fun switchVirtual(): Result {
        val ok = writeControl(
            enabled = true,
            virtual = true,
            mode = "virtual",
            source = "local",
            uri = "$DIR/current.mp4",
            url = "",
        )
        return if (ok) Result.Ok else Result.Failed("VIRTUAL falhou")
    }

    fun statusLine(): String {
        val pid = RootShell.run("pidof kingvd 2>/dev/null", timeoutSec = 3).trim().ifBlank { "-" }
        val st = RootShell.run("cat $STATUS 2>/dev/null", timeoutSec = 3).trim().ifBlank { "{}" }
        return "pid=$pid $st"
    }

    fun shutdown() {
        stopPlay()
        RootShell.run("killall kingvd 2>/dev/null; rm -f $SOCK")
    }

    private fun ensureDaemonOrRestart(): Boolean {
        if (isAlive()) return true
        RootShell.run(
            "mkdir -p $DIR /data/adb/vcamgd; " +
                "chmod 755 $BIN 2>/dev/null; " +
                "killall kingvd 2>/dev/null; rm -f $SOCK; " +
                "setsid $BIN >>$LOG 2>&1 < /dev/null &",
            timeoutSec = 6,
        )
        return waitAlive(timeoutMs = 3000)
    }

    private fun extractAndDeploy(context: Context) {
        val abi = preferredAbi()
        val local = File(context.filesDir, "king-engine/$abi")
        local.mkdirs()
        val outBin = File(local, "kingvd")

        var ok = false
        try {
            context.assets.open("king-engine/$abi/kingvd").use { input ->
                FileOutputStream(outBin).use { output -> input.copyTo(output) }
            }
            ok = outBin.length() > 0
        } catch (t: Throwable) {
            Log.w(TAG, "assets extract: ${t.message}")
            ok = false
        }
        if (!ok) {
            val apk = context.applicationInfo.sourceDir
            ZipFile(apk).use { zip ->
                val e = zip.getEntry("assets/king-engine/$abi/kingvd")
                    ?: throw IllegalStateException("kingvd ausente nos assets ($abi)")
                zip.getInputStream(e).use { input ->
                    FileOutputStream(outBin).use { output -> input.copyTo(output) }
                }
            }
        }
        outBin.setExecutable(true, false)

        val deploy = RootShell.run(
            "mkdir -p $DIR /data/adb/vcamgd; " +
                "killall kingvd 2>/dev/null; rm -f $SOCK; " +
                "cp '${outBin.absolutePath}' $BIN; chmod 755 $BIN; " +
                "chown root:root $BIN 2>/dev/null; " +
                "setsid $BIN >>$LOG 2>&1 < /dev/null & " +
                "sleep 0.3; " +
                "pidof kingvd; " +
                "ls -la $BIN $SOCK 2>&1; " +
                "tail -n 5 $LOG 2>/dev/null",
            timeoutSec = 10,
        )
        Log.i(TAG, "deploy:\n$deploy")
    }

    private fun preferredAbi(): String {
        val abis = Build.SUPPORTED_ABIS
        if (abis.any { it.contains("arm64") }) return "arm64-v8a"
        return "armeabi-v7a"
    }

    private fun waitAlive(timeoutMs: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isAlive()) return true
            Thread.sleep(200)
        }
        return isAlive()
    }

    private fun writeControl(
        enabled: Boolean,
        virtual: Boolean,
        mode: String,
        source: String,
        uri: String,
        url: String,
    ): Boolean {
        val json = JSONObject()
            .put("enabled", enabled)
            .put("virtual", virtual)
            .put("mode", mode)
            .put("inject", EngineFacade.injectField())
            .put("source", source)
            .put("uri", uri)
            .put("url", url)
            .put("engine", "kingvd")
            .toString()
        val escaped = json.replace("'", "'\\''")
        val out = RootShell.run(
            "mkdir -p $DIR /data/adb/vcamgd; " +
                "printf '%s' '$escaped' > $CONTROL; chmod 666 $CONTROL; " +
                "printf '%s' '$escaped' > $ADB_CONTROL; chmod 666 $ADB_CONTROL; " +
                "cat $CONTROL",
            timeoutSec = 6,
        )
        val ok = out.contains("\"engine\"") || out.contains("enabled")
        if (!ok) Log.w(TAG, "writeControl failed: $out")
        return ok
    }
}