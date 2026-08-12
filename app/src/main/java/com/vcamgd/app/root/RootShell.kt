package com.vcamgd.app.root

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * su one-shot com timeout.
 *
 * HyperOS/Magisk: inject no cameraserver exige **mount master** (`su -mm`).
 * Sem isso o daemon fica em namespace isolado e o ptrace/dlopen falha
 * mesmo com SELinux Permissive.
 */
object RootShell {
    private const val TAG = "KingVCam-RootShell"

    fun run(command: String, timeoutSec: Long = 8): String =
        exec(arrayOf("su", "-c", command), timeoutSec)

    /**
     * Executa no namespace de mount global (init/Magisk master).
     * Essencial para ptrace do cameraserver no HyperOS.
     */
    fun runGlobal(command: String, timeoutSec: Long = 14): String {
        val wrapped = "$command; echo __VCAM_DONE__"
        val attempts = listOf(
            arrayOf("su", "-mm", "-c", wrapped),
            arrayOf("su", "--mount-master", "-c", wrapped),
            arrayOf("su", "0", "-mm", "-c", wrapped),
            arrayOf(
                "su", "-c",
                "nsenter -t 1 -m -- sh -c '${wrapped.replace("'", "'\\''")}'",
            ),
        )
        for (args in attempts) {
            val out = exec(args, timeoutSec)
            if (out.contains("Unknown option", ignoreCase = true)) continue
            if (out.contains("invalid option", ignoreCase = true)) continue
            if (out.contains("unrecognized option", ignoreCase = true)) continue
            if (out.contains("__VCAM_DONE__") || out.isNotBlank()) {
                Log.i(TAG, "runGlobal via ${args.take(3).joinToString(" ")}")
                return out.replace("__VCAM_DONE__", "").trim()
            }
        }
        Log.w(TAG, "runGlobal fallback to plain su")
        return run(command, timeoutSec)
    }

    fun hasRoot(timeoutSec: Long = 5): Boolean =
        run("id", timeoutSec).contains("uid=0")

    fun supportsMountMaster(): Boolean {
        val out = exec(arrayOf("su", "-mm", "-c", "id; echo MM_OK"), 5)
        return out.contains("uid=0") && out.contains("MM_OK") &&
            !out.contains("Unknown option", ignoreCase = true)
    }

    private fun exec(args: Array<String>, timeoutSec: Long): String {
        return try {
            val process = Runtime.getRuntime().exec(args)
            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                Log.w(TAG, "su timeout: ${args.joinToString(" ").take(90)}")
                return ""
            }
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            (stdout + stderr).trim()
        } catch (e: Exception) {
            Log.w(TAG, "su failed: ${e.message}")
            ""
        }
    }
}
