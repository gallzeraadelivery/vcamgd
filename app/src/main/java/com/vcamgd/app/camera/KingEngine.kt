package com.vcamgd.app.camera

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

/**
 * Motor proprio KingVCam (v0.9+).
 *
 * - Daemon nativo [kingvd] (unix socket) escreve control.json
 * - Hooks soft via modulo Zygisk+Pine (HookEntry) leem o control.json
 * - Nao usa vcplax / libvc de terceiros
 */
object KingEngine {
    private const val TAG = "KingVCam-KingEngine"
    private const val DIR = "/data/local/tmp/vcamgd"
    private const val SOCK = "$DIR/kingvd.sock"
    private const val BIN = "$DIR/kingvd"

    sealed class Result {
        data object Ok : Result()
        data class Failed(val reason: String) : Result()
    }

    fun ensureRunning(context: Context): Result {
        return try {
            if (!hasRoot()) return Result.Failed("Root (su) necessario")

            // Hooks: modulo Zygisk (soft inject)
            when (val mod = ModuleInstaller.ensureInstalled(context)) {
                is ModuleInstaller.Result.Failed ->
                    return Result.Failed("Zygisk/Magisk: ${mod.reason}")
                is ModuleInstaller.Result.InstalledNeedsReboot ->
                    return Result.Failed("REBOOT_REQUIRED:reinicie o telefone")
                is ModuleInstaller.Result.AlreadyInstalled -> Unit
            }

            extractAndDeploy(context)
            if (!ping(timeoutMs = 2500)) {
                return Result.Failed("Daemon kingvd nao respondeu no socket")
            }
            Log.i(TAG, "kingvd ready")
            Result.Ok
        } catch (t: Throwable) {
            Log.e(TAG, "ensureRunning", t)
            Result.Failed(t.message ?: "erro ao iniciar kingvd")
        }
    }

    fun isAlive(): Boolean = ping(timeoutMs = 800)

    fun startPlay(pathOrUrl: String): Result {
        val r = send("PLAY $pathOrUrl")
        return if (r.startsWith("OK")) Result.Ok else Result.Failed(r.ifBlank { "PLAY falhou" })
    }

    fun stopPlay(): Result {
        val r = send("STOP")
        return if (r.startsWith("OK")) Result.Ok else Result.Failed(r.ifBlank { "STOP falhou" })
    }

    fun switchReal(): Result {
        val r = send("REAL")
        return if (r.startsWith("OK")) Result.Ok else Result.Failed(r)
    }

    fun switchVirtual(): Result {
        val r = send("VIRTUAL")
        return if (r.startsWith("OK")) Result.Ok else Result.Failed(r)
    }

    fun statusLine(): String = send("STATUS").ifBlank { "ERR offline" }

    fun shutdown() {
        try {
            send("STOP")
        } catch (_: Throwable) {
        }
        su("killall kingvd 2>/dev/null; rm -f $SOCK")
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
        } catch (_: Throwable) {
            ok = false
        }
        if (!ok) {
            // fallback: APK zip lib/ (se no futuro empacotarmos como lib)
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

        su("mkdir -p $DIR; killall kingvd 2>/dev/null; rm -f $SOCK")
        su("cp '${outBin.absolutePath}' $BIN; chmod 755 $BIN")
        // background
        su("$BIN >/dev/null 2>&1 &")
        Thread.sleep(400)
    }

    private fun preferredAbi(): String {
        val abis = Build.SUPPORTED_ABIS
        if (abis.any { it.contains("arm64") }) return "arm64-v8a"
        return "armeabi-v7a"
    }

    private fun ping(timeoutMs: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val r = send("PING")
            if (r.startsWith("OK")) return true
            Thread.sleep(150)
        }
        return false
    }

    private fun send(cmd: String): String {
        return try {
            LocalSocket().use { sock ->
                sock.connect(LocalSocketAddress(SOCK, LocalSocketAddress.Namespace.FILESYSTEM))
                sock.soTimeout = 2000
                val out = sock.outputStream
                out.write("$cmd\n".toByteArray(StandardCharsets.UTF_8))
                out.flush()
                val reader = BufferedReader(InputStreamReader(sock.inputStream, StandardCharsets.UTF_8))
                reader.readLine()?.trim().orEmpty()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "send($cmd): ${t.message}")
            ""
        }
    }

    private fun hasRoot(): Boolean = su("id").contains("uid=0")

    private val suLock = Any()
    @Volatile private var suProcess: Process? = null
    @Volatile private var suOut: DataOutputStream? = null

    private fun su(command: String): String {
        synchronized(suLock) {
            return try {
                if (suProcess == null || suOut == null) {
                    val p = Runtime.getRuntime().exec("su")
                    suProcess = p
                    suOut = DataOutputStream(p.outputStream)
                }
                val mark = "EOF_${System.currentTimeMillis()}"
                val out = suOut!!
                out.writeBytes("$command\n")
                out.writeBytes("echo $mark\n")
                out.flush()
                val reader = BufferedReader(InputStreamReader(suProcess!!.inputStream))
                val sb = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line == mark) break
                    sb.append(line).append('\n')
                }
                sb.toString().trim()
            } catch (e: Exception) {
                Log.w(TAG, "su failed: ${e.message}")
                suProcess = null
                suOut = null
                ""
            }
        }
    }
}
