package com.vcamgd.app.camera

import android.content.Context
import android.util.Log

/**
 * Fachada no estilo OVCAM (yh2 + a.b.N):
 * o APK de referencia carrega um .so criptografado e fala com ele via opcodes.
 * Aqui espelhamos o protocolo em Kotlin → IPC, ate portarmos o motor nativo proprio.
 *
 * Opcodes OVCAM observados (aprox.):
 *  n4(0/1/2) = status/checks
 *  n0/n1/n2  = controle (fonte/enable)
 *  n19       = decrypt+load payload (assinatura do APK)
 */
object EngineFacade {
    private const val TAG = "KingVCam-Engine"

    enum class InjectStyle {
        /** Nao mexe no HAL — pode perder para a camera real (Moto mais estavel). */
        SOFT,
        /** Substitui Surfaces do HAL — necessario para substituir de verdade. */
        HARD,
    }

    @Volatile
    var injectStyle: InjectStyle = InjectStyle.HARD
        private set

    fun setInjectStyle(style: InjectStyle) {
        injectStyle = style
        Log.i(TAG, "injectStyle=$style")
    }

    fun injectField(): String = when (injectStyle) {
        InjectStyle.SOFT -> "soft"
        InjectStyle.HARD -> "hard"
    }

    fun warmUp(context: Context) {
        // Espelho do yh2.ensureLoaded(): garante modulo embutido + IPC
        when (val r = ModuleInstaller.ensureInstalled(context)) {
            is ModuleInstaller.Result.AlreadyInstalled -> Log.i(TAG, "engine ready")
            is ModuleInstaller.Result.InstalledNeedsReboot -> Log.i(TAG, "engine installed, reboot needed")
            is ModuleInstaller.Result.Failed -> Log.w(TAG, "engine: ${r.reason}")
        }
    }
}
