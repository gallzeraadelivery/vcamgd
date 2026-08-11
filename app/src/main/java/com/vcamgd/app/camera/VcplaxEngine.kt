package com.vcamgd.app.camera

import android.content.Context
import android.content.SharedPreferences
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.Random
import java.util.zip.ZipFile

/**
 * Motor do APK base liberado (com.xiaomi.vlive / vcplax + libvc + shadowhook).
 *
 * Fluxo (igual ao App.onCreate da referencia):
 * 1) Extrai .so do APK para filesDir
 * 2) root: copia para /data/libvc.so, /data/libvc++.so, /data/vcplax
 * 3) root: /data/vcplax <serverName> &
 * 4) Binder ServiceManager.getService(serverName) + IMyBinderService
 */
object VcplaxEngine {
    private const val TAG = "KingVCam-Vcplax"
    private const val PREFS = "vcplax_engine"
    private const val KEY_SERVER = "ServerName"
    private const val IFACE = "com.xiaomi.vlive.IMyBinderService"

    @Volatile private var binder: IBinder? = null
    @Volatile private var suProcess: Process? = null
    @Volatile private var suOut: DataOutputStream? = null
    private val suLock = Any()

    sealed class Result {
        data object Ok : Result()
        data class Failed(val reason: String) : Result()
    }

    fun ensureRunning(context: Context): Result {
        return try {
            if (!hasRoot()) {
                return Result.Failed("Root (su) necessario")
            }
            val abiDir = resolveAbiDir()
            extractNativeLibs(context, abiDir)
            val server = ensureServerName(context)
            deployAndStart(context, abiDir, server)
            // Aguarda o servico binder (como U.t.E)
            repeat(20) {
                Thread.sleep(250)
                su("setenforce 0")
                val last = getService(server)
                if (last != null) {
                    binder = last
                    try {
                        last.linkToDeath({ binder = null }, 0)
                    } catch (_: Throwable) {
                    }
                    su("setenforce 1")
                    Log.i(TAG, "binder ready server=$server")
                    return Result.Ok
                }
            }
            val id = su("id")
            val enf = su("getenforce")
            Result.Failed(
                when {
                    !id.contains("uid=0") -> "Root falhou (su)"
                    enf.contains("Enforcing", ignoreCase = true) ->
                        "SELinux bloqueou o daemon — tente de novo"
                    else -> "Daemon vcplax nao respondeu. server=$server"
                },
            )
        } catch (t: Throwable) {
            Log.e(TAG, "ensureRunning", t)
            Result.Failed(t.message ?: "erro ao iniciar motor")
        }
    }

    fun isAlive(context: Context): Boolean {
        val server = prefs(context).getString(KEY_SERVER, null) ?: return false
        su("setenforce 0")
        val b = getService(server)
        su("setenforce 1")
        if (b == null) return false
        binder = b
        return true
    }

    /** Inicia virtual com arquivo/URL. Retorno 0 = sucesso tipico na ref. */
    fun startPlay(pathOrUrl: String, loop: Boolean = true, autoRotate: Boolean = false): Int {
        val b = binder ?: return -1
        return transactInt(b, 11) { data ->
            data.writeString(pathOrUrl)
            data.writeInt(if (autoRotate) 1 else 0)
            data.writeInt(if (loop) 1 else 0)
        }
    }

    /** Para virtual (transact 12). */
    fun stopPlay(): Int {
        val b = binder ?: return -1
        return transactInt(b, 12) { }
    }

    fun playStatus(): Int {
        val b = binder ?: return -1
        return transactInt(b, 15) { }
    }

    fun shutdown(context: Context) {
        try {
            stopPlay()
        } catch (_: Throwable) {
        }
        su("killall vcplax")
        binder = null
        Log.i(TAG, "shutdown")
    }

    private fun resolveAbiDir(): String {
        val out = su("file /system/bin/cameraserver")
        return if (out.contains("32-bit")) "armeabi-v7a" else "arm64-v8a"
    }

    private fun extractNativeLibs(context: Context, abi: String) {
        val base = File(context.filesDir, "vcam-engine/$abi")
        base.mkdirs()
        val names = listOf("libvc.so", "libshadowhook.so", "vcplax.so")
        // 1) Prefer assets
        var fromAssets = true
        for (n in names) {
            val assetPath = "vcam-engine/$abi/$n"
            try {
                context.assets.open(assetPath).use { input ->
                    File(base, n).outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Throwable) {
                fromAssets = false
                break
            }
        }
        if (fromAssets) {
            Log.i(TAG, "extracted from assets abi=$abi")
            return
        }
        // 2) Fallback: zip do proprio APK (lib/abi/)
        val apk = context.applicationInfo.sourceDir
        ZipFile(apk).use { zip ->
            val prefix = "lib/$abi/"
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                val name = e.name
                if (!name.startsWith(prefix) || !name.endsWith(".so")) continue
                val out = File(base, name.substringAfterLast('/'))
                zip.getInputStream(e).use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
            }
        }
        Log.i(TAG, "extracted from apk zip abi=$abi")
    }

    private fun deployAndStart(context: Context, abi: String, server: String) {
        val base = File(context.filesDir, "vcam-engine/$abi").absolutePath
        su("killall vcplax")
        // Limpa residuos de outras vcams que a ref avisa
        su("chattr -i /data/camera 2>/dev/null; rm -rf /data/camera /data/samera 2>/dev/null")
        su("cp '$base/libvc.so' /data/libvc.so")
        su("cp '$base/libshadowhook.so' /data/libvc++.so")
        su("cp '$base/vcplax.so' /data/vcplax")
        su("chmod 700 /data/vcplax")
        // background (igual App.onCreate)
        su("/data/vcplax $server &")
        Log.i(TAG, "started /data/vcplax $server")
    }

    private fun ensureServerName(context: Context): String {
        val p = prefs(context)
        val existing = p.getString(KEY_SERVER, null)
        if (!existing.isNullOrBlank()) return existing
        val name = inventServerName()
        p.edit().putString(KEY_SERVER, name).apply()
        return name
    }

    /** Espelha App.d(): baseia em um servico existente + sufixo aleatorio. */
    private fun inventServerName(): String {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val list = sm.getDeclaredMethod("listServices").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val services = list.invoke(null) as? Array<String>
            if (!services.isNullOrEmpty()) {
                val base = services[Random().nextInt(services.size)]
                base + randomLetters(1, 3)
            } else {
                randomLetters(5, 12)
            }
        } catch (_: Throwable) {
            randomLetters(5, 12)
        }
    }

    private fun randomLetters(min: Int, max: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz"
        val r = Random()
        val n = min + r.nextInt((max - min).coerceAtLeast(0) + 1)
        return buildString {
            repeat(n) { append(alphabet[r.nextInt(alphabet.length)]) }
        }
    }

    private fun hasRoot(): Boolean = su("id").contains("uid=0")

    private fun getService(name: String): IBinder? {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            getService.invoke(null, name) as? IBinder
        } catch (t: Throwable) {
            Log.w(TAG, "getService($name): ${t.message}")
            null
        }
    }

    private fun transactInt(binder: IBinder, code: Int, write: (Parcel) -> Unit): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE)
            write(data)
            binder.transact(code, data, reply, 0)
            reply.readException()
            reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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
