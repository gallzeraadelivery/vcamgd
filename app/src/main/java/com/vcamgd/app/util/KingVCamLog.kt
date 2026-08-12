package com.vcamgd.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.vcamgd.app.root.RootShell
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Log auditavel in-app (Status) + arquivo root.
 * Foco: diagnosticar por que a camera do celular nao mostra o video.
 */
object KingVCamLog {
    private const val TAG = "KingVCam-Audit"
    private const val MAX_LINES = 400
    private const val FILE_APP = "kingvcam-audit.log"
    private const val FILE_ROOT = "/data/local/tmp/vcamgd/kingvcam-audit.log"

    private val lines = CopyOnWriteArrayList<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var appFilesDir: String? = null

    fun bind(context: Context) {
        appFilesDir = context.applicationContext.filesDir.absolutePath
    }

    fun i(scope: String, msg: String) = append("I", scope, msg)

    fun w(scope: String, msg: String) = append("W", scope, msg)

    fun e(scope: String, msg: String) = append("E", scope, msg)

    fun clear() {
        lines.clear()
        append("I", "log", "limpo pelo usuario")
        runCatching {
            RootShell.runGlobal(": > $FILE_ROOT", timeoutSec = 3)
        }
    }

    fun dump(): String {
        if (lines.isEmpty()) return "(sem eventos ainda — ligue a virtual e volte aqui)"
        return lines.joinToString("\n")
    }

    fun copyToClipboard(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("KingVCam audit", dump()))
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Snapshot focado em: inject ok mas preview ainda e camera real / sem video.
     */
    fun auditWhyNoPreview(extra: String = ""): String {
        val snap = RootShell.runGlobal(
            "echo === WHY_NO_PREVIEW ===; " +
                "echo brand=${Build.BRAND}/${Build.MANUFACTURER}; " +
                "echo sdk=${Build.VERSION.SDK_INT}/${Build.VERSION.RELEASE}; " +
                "echo enforce=\$(getenforce 2>/dev/null); " +
                "PTR=\$(cat /proc/sys/kernel/yama/ptrace_scope 2>/dev/null || echo none); echo ptrace=\$PTR; " +
                "PID=\$(pidof cameraserver 2>/dev/null | awk '{print \$1}'); echo cam=\$PID; " +
                "echo vcplax=\$(pidof vcplax 2>/dev/null | tr ' ' ','); " +
                "if [ -n \"\$PID\" ]; then " +
                "MAPS=\$(cat /proc/\$PID/maps 2>/dev/null | grep -E 'libvc\\.so|libvc\\+\\+|libshadowhook\\.so|/dev/vcam/' | head -6 | tr '\\n' '|'); " +
                "echo maps=\${MAPS:-EMPTY}; " +
                "echo sectx=\$(cat /proc/\$PID/attr/current 2>/dev/null); " +
                "else echo maps=NO_CAM; fi; " +
                "for f in /dev/vcam/current.mp4 /data/local/tmp/vcamgd/current.mp4 /data/adb/vcamgd/current.mp4 /data/local/tmp/current.mp4; do " +
                "if [ -f \$f ]; then echo video=\$f size=\$(wc -c < \$f) mode=\$(stat -c %a \$f 2>/dev/null); " +
                "else echo video=\$f MISSING; fi; done; " +
                "echo libs=\$(ls -l /dev/vcam/libvc.so /data/libvc.so /data/libshadowhook.so 2>&1 | tr '\\n' '|'); " +
                "echo ki=\$(tail -n 8 /data/local/tmp/vcamgd/kinginject.log 2>/dev/null | tr '\\n' ';'); " +
                "echo vx=\$(tail -n 6 /data/local/tmp/vcamgd/vcplax.log 2>/dev/null | tr '\\n' ';'); " +
                "echo cam_apps=\$(pidof com.android.camera com.android.camera2 com.miui.camera 2>/dev/null | tr ' ' ','); " +
                "echo EXTRA=$extra; " +
                "echo === END ===",
            timeoutSec = 14,
        )
        val hints = buildHints(snap)
        val block = buildString {
            appendLine("--- auditoria preview ---")
            appendLine(snap.trim())
            appendLine("--- hipoteses ---")
            hints.forEach { appendLine("• $it") }
        }
        w("preview", block.replace("\n", " | ").take(900))
        // tambem grava bloco completo no arquivo
        persistRaw(block)
        return block
    }

    private fun buildHints(snap: String): List<String> {
        val h = mutableListOf<String>()
        val mapsLine = snap.lineSequence().firstOrNull { it.startsWith("maps=") }.orEmpty()
        val mapsEmpty = mapsLine.contains("EMPTY", ignoreCase = true) ||
            mapsLine.contains("NO_CAM") ||
            (
                !mapsLine.contains("libvc") &&
                    !mapsLine.contains("shadowhook") &&
                    !mapsLine.contains("/dev/vcam")
                )
        val hasVideo = Regex("""size=\s*(\d+)""").findAll(snap)
            .any { (it.groupValues[1].toLongOrNull() ?: 0L) > 1000L }
        val missingVideo = snap.contains("MISSING") && !hasVideo
        val camLine = snap.lineSequence().firstOrNull { it.startsWith("cam=") }.orEmpty()
        val camDead = snap.contains("maps=NO_CAM") ||
            camLine.removePrefix("cam=").trim().isEmpty()
        val vcplaxLine = snap.lineSequence().firstOrNull { it.startsWith("vcplax=") }.orEmpty()
        val vcplaxDead = !Regex("""\d""").containsMatchIn(vcplaxLine)

        when {
            camDead -> h.add("cameraserver morto — inject/play invalidos; reative a virtual")
            mapsEmpty -> h.add("libvc NAO esta no maps do cameraserver (inject=false) — preview sempre sera camera real")
            !hasVideo || missingVideo -> h.add("arquivo de video ausente/pequeno — stage falhou; selecione o video de novo")
            vcplaxDead -> h.add("vcplax morto — binder play nao alimenta o libvc mesmo com inject=true")
            else -> {
                h.add("inject+video+vcplax aparentam OK — provavel: (1) app de camera abriu antes do play estabilizar; (2) libvc no maps mas hook nao substituiu o stream; (3) feche camera 3s e reabra")
                h.add("se HyperOS: force-stop so da camera Xiaomi e reabra; evite matar cameraserver apos inject")
            }
        }
        if (snap.contains("Enforcing", ignoreCase = true)) {
            h.add("SELinux Enforcing — janela de inject pode ter fechado")
        }
        if (Regex("""ptrace=[1-9]""").containsMatchIn(snap)) {
            h.add("ptrace_scope!=0 — kinginject/ptrace pode falhar")
        }
        return h
    }

    private fun append(level: String, scope: String, msg: String) {
        val line = "${timeFmt.format(Date())} $level/$scope: ${msg.take(1200)}"
        lines.add(line)
        while (lines.size > MAX_LINES) {
            lines.removeAt(0)
        }
        when (level) {
            "E" -> Log.e(TAG, "$scope: $msg")
            "W" -> Log.w(TAG, "$scope: $msg")
            else -> Log.i(TAG, "$scope: $msg")
        }
        persistLine(line)
    }

    private fun persistLine(line: String) {
        val dir = appFilesDir ?: return
        runCatching {
            val f = java.io.File(dir, FILE_APP)
            synchronized(this) {
                f.appendText(line + "\n")
                if (f.length() > 512_000) {
                    val keep = f.readLines().takeLast(200).joinToString("\n")
                    f.writeText(keep + "\n")
                }
            }
            RootShell.runGlobal(
                "mkdir -p /data/local/tmp/vcamgd; " +
                    "cp -f '${f.absolutePath}' $FILE_ROOT 2>/dev/null; " +
                    "chmod 644 $FILE_ROOT 2>/dev/null; true",
                timeoutSec = 4,
            )
        }
    }

    private fun persistRaw(block: String) {
        val dir = appFilesDir ?: return
        runCatching {
            val f = java.io.File(dir, FILE_APP)
            synchronized(this) {
                f.appendText(block + "\n")
            }
            RootShell.runGlobal(
                "mkdir -p /data/local/tmp/vcamgd; " +
                    "cp -f '${f.absolutePath}' $FILE_ROOT 2>/dev/null; true",
                timeoutSec = 4,
            )
        }
    }
}
