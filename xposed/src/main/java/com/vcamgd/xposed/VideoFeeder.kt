package com.vcamgd.xposed

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Alimenta Surfaces com arquivo local ou stream de rede (RTSP/HTTP).
 * RTMP: tenta MediaPlayer; na falha oriente a republicar como RTSP.
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

    sealed class PlaySource {
        data class FilePath(val path: String) : PlaySource()
        data class NetworkUrl(val url: String, val looping: Boolean) : PlaySource()
    }

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
        if (!c.enabled || !c.virtual) return false
        return resolvePlaySource(c) != null
    }

    fun resolvePlaySource(control: Control? = readControl()): PlaySource? {
        val c = control ?: return null
        when (c.source.lowercase(Locale.US)) {
            "network" -> {
                val url = c.url.trim()
                if (url.isEmpty()) return null
                if (!isSupportedNetworkUrl(url)) {
                    writeStatus("unsupported_url:$url")
                    return null
                }
                return PlaySource.NetworkUrl(url, looping = isHttpProgressive(url))
            }
            "usb" -> {
                val file = File(VIDEO_PATH)
                return if (file.exists() && file.length() > 0) PlaySource.FilePath(file.absolutePath) else null
            }
            else -> {
                val file = File(VIDEO_PATH)
                if (file.exists() && file.length() > 0) {
                    return PlaySource.FilePath(file.absolutePath)
                }
                if (c.uri.startsWith("/")) {
                    val direct = File(c.uri)
                    if (direct.exists()) return PlaySource.FilePath(direct.absolutePath)
                }
                return null
            }
        }
    }

    /** Compat: retorna path local se houver. */
    fun resolveVideoPath(): String? {
        return when (val src = resolvePlaySource()) {
            is PlaySource.FilePath -> src.path
            else -> null
        }
    }

    @Synchronized
    fun startOnSurfaces(surfaces: List<Surface>) {
        if (surfaces.isEmpty()) return
        val source = resolvePlaySource()
        if (source == null) {
            writeStatus("no_playable_source")
            Log.e(TAG, "No playable source in control.json")
            return
        }
        stop()
        try {
            val target = surfaces.first()
            val mp = MediaPlayer()
            when (source) {
                is PlaySource.FilePath -> {
                    mp.setDataSource(source.path)
                    mp.isLooping = true
                    writeStatus("preparing_file:${source.path}")
                }
                is PlaySource.NetworkUrl -> {
                    mp.setDataSource(source.url)
                    mp.isLooping = source.looping
                    writeStatus("preparing_network:${source.url}")
                }
            }
            mp.setSurface(target)
            mp.setOnPreparedListener {
                try {
                    it.start()
                    active = true
                    writeStatus("feeding:${describe(source)}")
                    Log.i(TAG, "Feeding ${describe(source)}")
                } catch (t: Throwable) {
                    Log.e(TAG, "start failed", t)
                    writeStatus("start_error:${t.message}")
                }
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error what=$what extra=$extra source=${describe(source)}")
                val hint = if (source is PlaySource.NetworkUrl && source.url.startsWith("rtmp", true)) {
                    "rtmp_unsupported_use_rtsp"
                } else {
                    "player_error:$what:$extra"
                }
                writeStatus(hint)
                true
            }
            mp.setOnInfoListener { _, what, _ ->
                Log.i(TAG, "MediaPlayer info=$what")
                false
            }
            mp.prepareAsync()
            player = mp
        } catch (t: Throwable) {
            Log.e(TAG, "startOnSurfaces failed", t)
            writeStatus("feeder_error:${t.message}")
        }
    }

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

    private fun isSupportedNetworkUrl(url: String): Boolean {
        val u = url.lowercase(Locale.US)
        return u.startsWith("rtsp://") ||
            u.startsWith("rtspt://") ||
            u.startsWith("http://") ||
            u.startsWith("https://") ||
            u.startsWith("rtmp://") ||
            u.startsWith("rtmps://")
    }

    private fun isHttpProgressive(url: String): Boolean {
        val u = url.lowercase(Locale.US)
        return u.startsWith("http://") || u.startsWith("https://")
    }

    private fun describe(source: PlaySource): String = when (source) {
        is PlaySource.FilePath -> "file:${source.path}"
        is PlaySource.NetworkUrl -> "net:${source.url}"
    }

    private fun writeStatus(msg: String) {
        try {
            val safe = msg.replace("\"", "'")
            File(STATUS).writeText(
                """{"feeder":"$safe","ts":${System.currentTimeMillis()}}""",
            )
        } catch (_: Throwable) {
        }
    }
}
