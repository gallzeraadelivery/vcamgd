package com.vcamgd.app.camera

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.vcamgd.app.root.RootShell
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import java.util.zip.ZipFile

/**
 * Motor do APK base liberado (vcplax + libvc + shadowhook).
 *
 * Modelo: APK + root — SEM modulo Magisk/Zygisk e SEM reboot.
 * 1) Extrai .so do APK
 * 2) root: /data/libvc.so, /data/libvc++.so, /data/vcplax
 * 3) root: /data/vcplax <serverName> &
 * 4) Binder play (transact 11/12/15)
 */
object VcplaxEngine {
    private const val TAG = "KingVCam-Vcplax"
    private const val PREFS = "vcplax_engine"
    private const val KEY_SERVER = "ServerName"
    private const val IFACE = "com.xiaomi.vlive.IMyBinderService"

    @Volatile private var binder: IBinder? = null

    sealed class Result {
        data object Ok : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * @param forceRedeploy se true, mata e redeploya vcplax (perde sessao com inject).
     * Preferir soft=false apos inject bem-sucedido no HyperOS.
     */
    fun ensureRunning(
        context: Context,
        restoreEnforcing: Boolean = false,
        forceRedeploy: Boolean = false,
    ): Result {
        return try {
            if (!RootShell.hasRoot(timeoutSec = 6)) {
                return Result.Failed("Root (su) necessario — conceda no Magisk")
            }
            val abiDir = resolveAbiDir()
            extractNativeLibs(context, abiDir)
            val server = ensureServerName(context)

            // Soft: se daemon+binder ja vivos, so reatacha — nao killall (preserva inject)
            if (!forceRedeploy && softRebind(server)) {
                if (restoreEnforcing) {
                    RootShell.run("setenforce 1", timeoutSec = 3)
                }
                Log.i(TAG, "binder soft-ok server=$server (sem killall)")
                return Result.Ok
            }

            deployAndStart(context, abiDir, server)
            waitForBinder(server, restoreEnforcing)
        } catch (t: Throwable) {
            Log.e(TAG, "ensureRunning", t)
            Result.Failed(t.message ?: "erro ao iniciar motor")
        }
    }

    /** Reobtem Binder sem matar vcplax. */
    fun ensureBinderAlive(context: Context): Boolean {
        return try {
            val server = prefs(context).getString(KEY_SERVER, null) ?: return false
            softRebind(server)
        } catch (t: Throwable) {
            Log.w(TAG, "ensureBinderAlive: ${t.message}")
            false
        }
    }

    private fun softRebind(server: String): Boolean {
        RootShell.run("setenforce 0", timeoutSec = 2)
        val pid = RootShell.run("pidof vcplax 2>/dev/null", timeoutSec = 3).trim()
        if (pid.isEmpty()) return false
        val last = getService(server) ?: return false
        binder = last
        try {
            last.linkToDeath({ binder = null }, 0)
        } catch (_: Throwable) {
        }
        return true
    }

    private fun waitForBinder(server: String, restoreEnforcing: Boolean): Result {
        repeat(24) {
            Thread.sleep(250)
            RootShell.run("setenforce 0", timeoutSec = 3)
            val last = getService(server)
            if (last != null) {
                binder = last
                try {
                    last.linkToDeath({ binder = null }, 0)
                } catch (_: Throwable) {
                }
                // HyperOS/A16: NAO restaurar enforcing aqui — mata o inject
                if (restoreEnforcing) {
                    RootShell.run("setenforce 1", timeoutSec = 3)
                }
                Log.i(TAG, "binder ready server=$server restoreEnforcing=$restoreEnforcing")
                return Result.Ok
            }
        }
        val id = RootShell.run("id", timeoutSec = 4)
        val enf = RootShell.run("getenforce", timeoutSec = 3)
        return Result.Failed(
            when {
                !id.contains("uid=0") -> "Root falhou (su)"
                enf.contains("Enforcing", ignoreCase = true) ->
                    "SELinux bloqueou o daemon — tente de novo"
                else -> "Daemon vcplax nao respondeu. server=$server"
            },
        )
    }

    fun isAlive(context: Context): Boolean {
        if (!RootShell.hasRoot(timeoutSec = 3)) return false
        val server = prefs(context).getString(KEY_SERVER, null) ?: return false
        RootShell.run("setenforce 0", timeoutSec = 2)
        val b = getService(server)
        // Nao volta enforcing — sessao virtual precisa disso no A16
        if (b == null) {
            val pid = RootShell.run("pidof vcplax 2>/dev/null", timeoutSec = 3).trim()
            return pid.isNotEmpty()
        }
        binder = b
        return true
    }

    /**
     * Inicia virtual com arquivo/URL.
     * Na referencia: code==0 => "falhou"; qualquer !=0 = OK.
     */
    fun startPlay(pathOrUrl: String, loop: Boolean = true, autoRotate: Boolean = false): Int {
        val b = binder ?: return -1
        return runCatching {
            transactInt(b, 11) { data ->
                data.writeString(pathOrUrl)
                data.writeInt(if (autoRotate) 1 else 0)
                data.writeInt(if (loop) 1 else 0)
            }
        }.getOrElse { t ->
            Log.w(TAG, "startPlay binder: ${t.message}")
            binder = null
            -1
        }
    }

    fun stopPlay(): Int {
        val b = binder ?: return -1
        return runCatching { transactInt(b, 12) { } }.getOrElse { t ->
            Log.w(TAG, "stopPlay binder: ${t.message}")
            binder = null
            -1
        }
    }

    fun playStatus(): Int {
        val b = binder ?: return -1
        return runCatching { transactInt(b, 15) { } }.getOrElse { -1 }
    }

    fun shutdown() {
        try {
            stopPlay()
        } catch (_: Throwable) {
        }
        RootShell.run("killall vcplax 2>/dev/null", timeoutSec = 4)
        binder = null
        Log.i(TAG, "shutdown")
    }

    private fun resolveAbiDir(): String {
        val out = RootShell.run("file /system/bin/cameraserver 2>/dev/null", timeoutSec = 4)
        return if (out.contains("32-bit")) "armeabi-v7a" else "arm64-v8a"
    }

    private fun extractNativeLibs(context: Context, abi: String) {
        val base = File(context.filesDir, "vcam-engine/$abi")
        base.mkdirs()
        val names = listOf("libvc.so", "libshadowhook.so", "vcplax.so", "kinginject")
        var fromAssets = true
        for (n in names) {
            try {
                context.assets.open("vcam-engine/$abi/$n").use { input ->
                    File(base, n).outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Throwable) {
                if (n == "kinginject") {
                    // opcional em builds antigos
                    continue
                }
                fromAssets = false
                break
            }
        }
        if (fromAssets) {
            Log.i(TAG, "extracted from assets abi=$abi")
            return
        }
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
        val sdk = Build.VERSION.SDK_INT
        // Mount master (su -mm): sem isso o vcplax nao ptrace o cameraserver no HyperOS
        val out = RootShell.runGlobal(
            "mkdir -p /data/local/tmp/vcamgd /data/adb/vcamgd; " +
                "echo 0 > /proc/sys/kernel/yama/ptrace_scope 2>/dev/null; " +
                "setenforce 0; " +
                "killall vcplax 2>/dev/null; " +
                "chattr -i /data/camera 2>/dev/null; rm -rf /data/camera /data/samera 2>/dev/null; " +
                "cp '$base/libvc.so' /data/libvc.so; " +
                "cp '$base/libshadowhook.so' /data/libvc++.so; " +
                "cp '$base/vcplax.so' /data/vcplax; " +
                "cp '$base/kinginject' /data/local/tmp/vcamgd/kinginject 2>/dev/null; " +
                "cp '$base/kinginject' /data/adb/vcamgd/kinginject 2>/dev/null; " +
                "chmod 700 /data/vcplax; " +
                "chmod 755 /data/libvc.so /data/libvc++.so; " +
                "chmod 700 /data/local/tmp/vcamgd/kinginject /data/adb/vcamgd/kinginject 2>/dev/null; " +
                "chcon u:object_r:system_lib_file:s0 /data/vcplax /data/libvc.so /data/libvc++.so 2>/dev/null; " +
                "chcon u:object_r:system_file:s0 /data/vcplax 2>/dev/null; " +
                "chcon u:object_r:system_file:s0 /data/local/tmp/vcamgd/kinginject 2>/dev/null; " +
                "cp /data/libvc.so /data/local/tmp/vcamgd/libvc.so; " +
                "cp /data/libvc++.so /data/local/tmp/vcamgd/libvc++.so; " +
                "cp /data/vcplax /data/local/tmp/vcamgd/vcplax; " +
                "cp /data/libvc.so /data/adb/vcamgd/libvc.so; " +
                "cp /data/libvc++.so /data/adb/vcamgd/libvc++.so; " +
                "cp /data/vcplax /data/adb/vcamgd/vcplax; " +
                "chmod 755 /data/local/tmp/vcamgd/libvc.so /data/local/tmp/vcamgd/libvc++.so; " +
                "chmod 755 /data/adb/vcamgd/libvc.so /data/adb/vcamgd/libvc++.so; " +
                "chmod 700 /data/local/tmp/vcamgd/vcplax /data/adb/vcamgd/vcplax; " +
                "chcon u:object_r:magisk_file:s0 /data/local/tmp/vcamgd /data/local/tmp/vcamgd/* 2>/dev/null; " +
                "chcon u:object_r:magisk_file:s0 /data/adb/vcamgd /data/adb/vcamgd/* 2>/dev/null; " +
                "mkdir -p /dev/vcam; " +
                "cp /data/libvc.so /dev/vcam/libvc.so; " +
                "cp /data/libvc++.so /dev/vcam/libvc++.so; " +
                "cp /data/libvc++.so /data/libshadowhook.so; " +
                "cp /data/libvc++.so /dev/vcam/libshadowhook.so; " +
                "chmod 755 /dev/vcam /dev/vcam/libvc.so /dev/vcam/libvc++.so /dev/vcam/libshadowhook.so /data/libshadowhook.so; " +
                "chcon u:object_r:system_lib_file:s0 /dev/vcam/libvc.so /dev/vcam/libvc++.so /dev/vcam/libshadowhook.so /data/libshadowhook.so 2>/dev/null; " +
                "echo SDK=$sdk; " +
                "setsid /data/vcplax $server >>/data/local/tmp/vcamgd/vcplax.log 2>&1 < /dev/null & " +
                "sleep 0.45; pidof vcplax; ls -lZ /data/vcplax /data/libvc.so /dev/vcam/libvc.so 2>&1 | head -8; echo OK",
            timeoutSec = 16,
        )
        Log.i(TAG, "deploy: $out")
    }

    /**
     * Reinicia o daemon a partir de /data/adb/vcamgd (contexto magisk_file),
     * mantendo o mesmo nome de Binder.
     */
    fun restartFromAdbPath(context: Context) {
        val server = prefs(context).getString(KEY_SERVER, null) ?: return
        val out = RootShell.runGlobal(
            "setenforce 0; " +
                "echo 0 > /proc/sys/kernel/yama/ptrace_scope 2>/dev/null; " +
                "if [ ! -x /data/adb/vcamgd/vcplax ]; then echo NO_ADB_BIN; exit 0; fi; " +
                "killall vcplax 2>/dev/null; " +
                "setsid /data/adb/vcamgd/vcplax $server >>/data/local/tmp/vcamgd/vcplax.log 2>&1 < /dev/null & " +
                "sleep 0.5; pidof vcplax; echo ADB_START",
            timeoutSec = 12,
        )
        Log.i(TAG, "restartFromAdbPath: $out")
        Thread.sleep(300)
        binder = getService(server)
    }

    private fun ensureServerName(context: Context): String {
        val p = prefs(context)
        val existing = p.getString(KEY_SERVER, null)
        if (!existing.isNullOrBlank()) return existing
        val name = inventServerName()
        p.edit().putString(KEY_SERVER, name).apply()
        return name
    }

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
}
