package com.vcamgd.xposed

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import org.json.JSONObject
import java.io.File

/**
 * Lê o controle do Magisk module e alimenta Surfaces com vídeo (loop).
 */
object VideoFeeder {
    private const val TAG = "VCamGD-Feeder"
    private const val CONTROL = "/data/adb/vcamgd/control.json"
    private const val VIDEO_PATH = "/data/adb/vcamgd/current.mp4"
    private const val STATUS = "/data/adb/vcamgd/status.json"

    @Volatile private var player: MediaPlayer? = null
    @Volatile private var dummyTextures: MutableList<SurfaceTexture> = mutableListOf()
    @Volatile private var active = false

    data class Control(
        val enabled: Boolean,
        val virtual: Boolean,
        val source: String,
        val uri: String,
        val url: String,
    )

    fun readControl(): Control? {
        return try {
            val f = File(CONTROL)
            if (!f.exists()) return null
            val json = JSONObject(f.readText())
            Control(
                enabled = json.optBoolean("enabled", false),
                virtual = json.optBoolean("virtual", true),
                source = json.optString("source", ""),
                uri = json.optString("uri", ""),
                url = json.optString("url", ""),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "readControl failed", t)
            null
        }
    }

    fun shouldInject(): Boolean {
        val c = readControl() ?: return false
        return c.enabled && c.virtual
    }

    fun resolveVideoPath(): String? {
        val c = readControl() ?: return null
        val file = File(VIDEO_PATH)
        if (file.exists() && file.length() > 0) return file.absolutePath
        if (c.source == "local" && c.uri.startsWith("/")) {
            val direct = File(c.uri)
            if (direct.exists()) return direct.absolutePath
        }
        return if (file.exists()) file.absolutePath else null
    }

    @Synchronized
    fun startOnSurfaces(surfaces: List<Surface>) {
        if (surfaces.isEmpty()) return
        val path = resolveVideoPath()
        if (path == null) {
            writeStatus("no_video_file")
            Log.e(TAG, "No video at $VIDEO_PATH")
            return
        }
        stop()
        try {
            val target = surfaces.first()
            val mp = MediaPlayer().apply {
                setDataSource(path)
                isLooping = true
                setSurface(target)
                setOnPreparedListener {
                    start()
                    active = true
                    writeStatus("feeding:$path")
                    Log.i(TAG, "Feeding video $path")
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                    writeStatus("player_error:$what:$extra")
                    true
                }
                prepareAsync()
            }
            player = mp
            // Extra surfaces: attach silent looping players if needed later
            for (i in 1 until surfaces.size) {
                // keep placeholders occupied by dummy texture surfaces elsewhere
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startOnSurfaces failed", t)
            writeStatus("feeder_error:${t.message}")
        }
    }

    /** Surfaces descartáveis para a câmera real escrever sem afetar o preview. */
    fun createDummySurfaces(count: Int): List<Surface> {
        val out = ArrayList<Surface>(count)
        repeat(count) {
            val st = SurfaceTexture(false)
            st.setDefaultBufferSize(1280, 720)
            dummyTextures.add(st)
            out.add(Surface(st))
        }
        return out
    }

    @Synchronized
    fun stop() {
        active = false
        try {
            player?.reset()
            player?.release()
        } catch (_: Throwable) {
        }
        player = null
        dummyTextures.forEach {
            try {
                it.release()
            } catch (_: Throwable) {
            }
        }
        dummyTextures.clear()
    }

    private fun writeStatus(msg: String) {
        try {
            File(STATUS).writeText(
                """{"feeder":"$msg","ts":${System.currentTimeMillis()}}""",
            )
        } catch (_: Throwable) {
        }
    }
}
