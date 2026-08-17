package com.vcamgd.app.root

import java.io.File

data class RootStatus(
    val isRooted: Boolean,
    val hasSuBinary: Boolean,
    val canExecuteSu: Boolean,
    val detail: String,
)

object RootChecker {
    private val suPaths = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
    )

    fun check(): RootStatus {
        val hasSu = suPaths.any { File(it).exists() }
        // Timeout curto: Magisk pode ficar esperando grant
        val canExec = RootShell.hasRoot(timeoutSec = 4)
        val rooted = hasSu || canExec || File("/system/app/Superuser.apk").exists()
        val detail = when {
            canExec -> "Root acessível via su"
            hasSu -> "Binário su encontrado (sem execução confirmada)"
            else -> "Root não detectado"
        }
        return RootStatus(
            isRooted = rooted,
            hasSuBinary = hasSu,
            canExecuteSu = canExec,
            detail = detail,
        )
    }
}
