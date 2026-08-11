package com.vcamgd.xposed

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * IPC em /data/local/tmp/vcamgd (acessivel aos apps; /data/adb costuma ser bloqueado por SELinux).
 */
object VideoFeeder {
    private const val TAG = "VCamGD-Feeder"
    const val CONTROL_DIR = "/data/local/tmp/vcamgd"
    private const val CONTROL = "$CONTROL_DIR/control.json"
    private const val VIDEO_PATH = "$CONTROL_DIR/current.mp4"
    private const val STATUS = "$CONTROL_DIR/status.json"
    // Fallback legado (Magisk)
    private const val LEGACY_CONTROL = "/data/adb/vcamgd/control.json"
    private const val LEGACY_VIDEO = "/data/adb/vcamgd/current.mp4"

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
        val text = readFirstExisting(CONTROL, LEGACY_CONTROL) ?: return null
        return try {
            val json = JSONObject(text)
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
                val path = firstExistingFile(VIDEO_PATH, LEGACY_VIDEO) ?: return null
                return PlaySource.FilePath(path)
            }
            else -> {
                val path = firstExistingFile(VIDEO_PATH, LEGACY_VIDEO)
                if (path != null) return PlaySource.FilePath(path)
                if (c.uri.startsWith("/")) {
                    val direct = File(c.uri)
                    if (direct.exists() && direct.canRead()) return PlaySource.FilePath(direct.absolutePath)
                }
                return null
            }
        }
    }

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
            Log.e(TAG, "No playable source")
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
                Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                val hint = if (source is PlaySource.NetworkUrl && source.url.startsWith("rtmp", true)) {
                    "rtmp_unsupported_use_rtsp"
                } else {
                    "player_error:$what:$extra"
                }
                writeStatus(hint)
                true
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

    private fun readFirstExisting(vararg paths: String): String? {
        for (p in paths) {
            val f = File(p)
            if (f.exists() && f.canRead()) {
                return runCatching { f.readText() }.getOrNull()
            }
        }
        return null
    }

    private fun firstExistingFile(vararg paths: String): String? {
        for (p in paths) {
            val f = File(p)
            if (f.exists() && f.canRead() && f.length() > 0) return f.absolutePath
        }
        return null
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
            File(CONTROL_DIR).mkdirs()
            val safe = msg.replace("\"", "'")
            File(STATUS).writeText(
                """{"feeder":"$safe","ts":${System.currentTimeMillis()}}""",
            )
        } catch (_: Throwable) {
        }
    }
}
