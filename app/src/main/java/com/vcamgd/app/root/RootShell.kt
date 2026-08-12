package com.vcamgd.app.root

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** su one-shot com timeout — evita ANR/crash se Magisk nao responder. */
object RootShell {
    private const val TAG = "KingVCam-RootShell"

    fun run(command: String, timeoutSec: Long = 8): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                Log.w(TAG, "su timeout: ${command.take(80)}")
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

    fun hasRoot(timeoutSec: Long = 5): Boolean =
        run("id", timeoutSec).contains("uid=0")
}
