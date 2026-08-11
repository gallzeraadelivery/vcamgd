package com.vcamgd.xposed

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.util.Log
import android.view.Surface
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class CameraHookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "com.vcamgd.app" || lpparam.packageName == "com.vcamgd.xposed") {
            return
        }

        // Sempre instala os hooks. O enable é checado na hora do createCaptureSession.
        Log.i(TAG, "Installing Camera2 hooks in ${lpparam.packageName}")
        hookListSession(lpparam.classLoader)
        hookSessionConfiguration(lpparam.classLoader)
        hookCameraDeviceImpl(lpparam.classLoader)
        writeHookStatus(lpparam.packageName)
    }

    private fun hookListSession(cl: ClassLoader) {
        val listClass = Class.forName("java.util.List")
        try {
            XposedHelpers.findAndHookMethod(
                "android.hardware.camera2.CameraDevice",
                cl,
                "createCaptureSession",
                listClass,
                CameraCaptureSession.StateCallback::class.java,
                Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        injectListSurfaces(param)
                    }
                },
            )
            XposedBridge.log("$TAG hooked createCaptureSession(List,StateCallback,Handler)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG list session hook failed: ${t.message}")
        }
    }

    private fun injectListSurfaces(param: XC_MethodHook.MethodHookParam) {
        if (!VideoFeeder.shouldInject()) return
        @Suppress("UNCHECKED_CAST")
        val surfaces = (param.args[0] as? List<*>)?.mapNotNull { it as? Surface }.orEmpty()
        if (surfaces.isEmpty()) return
        Log.i(TAG, "createCaptureSession surfaces=${surfaces.size}")
        VideoFeeder.startOnSurfaces(surfaces)
        param.args[0] = VideoFeeder.createDummySurfaces(surfaces.size)
    }

    private fun hookSessionConfiguration(cl: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.hardware.camera2.CameraDevice",
                cl,
                "createCaptureSession",
                SessionConfiguration::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!VideoFeeder.shouldInject()) return
                        val config = param.args[0] as? SessionConfiguration ?: return
                        val surfaces = ArrayList<Surface>()
                        for (out in config.outputConfigurations) {
                            surfaces.addAll(out.surfaces)
                        }
                        if (surfaces.isEmpty()) return
                        Log.i(TAG, "SessionConfiguration surfaces=${surfaces.size}")
                        VideoFeeder.startOnSurfaces(surfaces)
                        try {
                            val dummies = VideoFeeder.createDummySurfaces(surfaces.size)
                            val newOutputs = dummies.map { OutputConfiguration(it) }
                            param.args[0] = SessionConfiguration(
                                config.sessionType,
                                newOutputs,
                                config.executor,
                                config.stateCallback,
                            )
                        } catch (t: Throwable) {
                            Log.w(TAG, "rebuild SessionConfiguration failed", t)
                        }
                    }
                },
            )
            XposedBridge.log("$TAG hooked createCaptureSession(SessionConfiguration)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG SessionConfiguration hook skipped: ${t.message}")
        }
    }

    /** Algumas ROMs chamam a impl interna em vez da API pública. */
    private fun hookCameraDeviceImpl(cl: ClassLoader) {
        val candidates = arrayOf(
            "android.hardware.camera2.impl.CameraDeviceImpl",
            "android.hardware.camera2.legacy.LegacyCameraDevice",
        )
        val listClass = Class.forName("java.util.List")
        for (name in candidates) {
            try {
                XposedHelpers.findAndHookMethod(
                    name,
                    cl,
                    "createCaptureSession",
                    listClass,
                    CameraCaptureSession.StateCallback::class.java,
                    Handler::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            injectListSurfaces(param)
                        }
                    },
                )
                XposedBridge.log("$TAG hooked $name.createCaptureSession")
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.findAndHookMethod(
                    name,
                    cl,
                    "createCaptureSession",
                    SessionConfiguration::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!VideoFeeder.shouldInject()) return
                            val config = param.args[0] as? SessionConfiguration ?: return
                            val surfaces = ArrayList<Surface>()
                            for (out in config.outputConfigurations) {
                                surfaces.addAll(out.surfaces)
                            }
                            if (surfaces.isEmpty()) return
                            VideoFeeder.startOnSurfaces(surfaces)
                        }
                    },
                )
            } catch (_: Throwable) {
            }
        }
    }

    private fun writeHookStatus(pkg: String) {
        try {
            val dir = java.io.File(VideoFeeder.CONTROL_DIR)
            dir.mkdirs()
            java.io.File(dir, "status.json").writeText(
                """{"hook":"installed","pkg":"$pkg","ts":${System.currentTimeMillis()}}""",
            )
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "VCamGD-Xposed"
    }
}
