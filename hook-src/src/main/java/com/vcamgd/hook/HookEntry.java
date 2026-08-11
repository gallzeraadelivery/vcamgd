package com.vcamgd.hook;

import android.graphics.SurfaceTexture;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.MediaPlayer;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

/**
 * Soft virtual camera: plays video onto preview Surfaces WITHOUT replacing HAL surfaces.
 * mode=real or enabled=false => complete pass-through (real camera works).
 * mode=virtual + enabled => MediaPlayer feeds preview; camera session stays intact.
 */
public final class HookEntry {
    private static final String TAG = "VCamGD-ZygiskHook";
    public static final String CONTROL_DIR = "/data/local/tmp/vcamgd";
    private static final String CONTROL = CONTROL_DIR + "/control.json";
    private static final String VIDEO = CONTROL_DIR + "/current.mp4";
    private static final String STATUS = CONTROL_DIR + "/status.json";
    private static final String LEGACY_CONTROL = "/data/adb/vcamgd/control.json";
    private static final String LEGACY_VIDEO = "/data/adb/vcamgd/current.mp4";
    private static final String PINE_PATH = CONTROL_DIR + "/libpine.so";

    private static final String[] CAMERA2_CLASSES = {
            "android.hardware.camera2.CameraDevice",
            "android.hardware.camera2.impl.CameraDeviceImpl",
            "android.hardware.camera2.legacy.LegacyCameraDevice",
    };

    private static final String[] SESSION_METHODS = {
            "createCaptureSession",
            "createCaptureSessionByOutputConfigurations",
            "createReprocessableCaptureSession",
            "createConstrainedHighSpeedCaptureSession",
            "createExtensionSession",
    };

    static {
        try {
            System.load(PINE_PATH);
        } catch (Throwable t) {
            try {
                System.loadLibrary("pine");
            } catch (Throwable ignored) {
            }
        }
    }

    private static MediaPlayer player;
    private static final ArrayList<SurfaceTexture> retainedTextures = new ArrayList<>();
    private static final AtomicReference<String> processHint = new AtomicReference<>("unknown");
    private static final AtomicBoolean watching = new AtomicBoolean(false);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static FileObserver controlObserver;
    private static volatile List<Surface> lastPreviewSurfaces = new ArrayList<>();

    public static void install() {
        install("unknown");
    }

    public static void install(String processName) {
        if (processName != null && !processName.isEmpty()) {
            processHint.set(processName);
        }
        try {
            writeStatus("installing:" + processHint.get());
            int hooked = 0;
            hooked += hookCamera2();
            hooked += hookCamera1();
            startControlWatcher();
            applyControlState("boot");
            writeStatus("hooks_ready:" + processHint.get() + ":n=" + hooked);
            Log.i(TAG, "HookEntry.install done process=" + processHint.get() + " hooked=" + hooked);
        } catch (Throwable t) {
            Log.e(TAG, "install failed", t);
            writeStatus("install_error:" + t.getMessage());
        }
    }

    private static void startControlWatcher() {
        if (!watching.compareAndSet(false, true)) return;
        try {
            File dir = new File(CONTROL_DIR);
            dir.mkdirs();
            controlObserver = new FileObserver(dir.getAbsolutePath(),
                    FileObserver.MODIFY | FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.CREATE) {
                @Override
                public void onEvent(int event, String path) {
                    if (path == null) return;
                    if (!"control.json".equals(path)) return;
                    mainHandler.post(() -> applyControlState("watch"));
                }
            };
            controlObserver.startWatching();
            // Fallback poll every 1.5s (some ROMs miss FileObserver on /data/local/tmp)
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    applyControlState("poll");
                    mainHandler.postDelayed(this, 1500);
                }
            }, 1500);
        } catch (Throwable t) {
            Log.w(TAG, "control watcher failed", t);
        }
    }

    /** React to Real/Virtual switch without waiting for a new camera session. */
    private static synchronized void applyControlState(String reason) {
        try {
            if (!shouldInject()) {
                if (player != null) {
                    stopPlayer();
                    writeStatus("passthrough_real:" + processHint.get() + ":" + reason);
                }
                return;
            }
            // Virtual mode: if we already have preview surfaces, (re)start feeder
            if (!lastPreviewSurfaces.isEmpty()) {
                startOnSurfaces(lastPreviewSurfaces);
                writeStatus("refeed:" + processHint.get() + ":" + reason);
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyControlState", t);
        }
    }

    private static int hookCamera2() {
        int count = 0;
        for (String className : CAMERA2_CLASSES) {
            try {
                Class<?> clazz = Class.forName(className);
                for (Method m : clazz.getDeclaredMethods()) {
                    String name = m.getName();
                    boolean match = false;
                    for (String sm : SESSION_METHODS) {
                        if (sm.equals(name)) {
                            match = true;
                            break;
                        }
                    }
                    if (!match) continue;
                    final Method method = m;
                    Pine.hook(method, new MethodHook() {
                        @Override
                        public void beforeCall(Pine.CallFrame callFrame) {
                            try {
                                handleCamera2Session(callFrame);
                            } catch (Throwable t) {
                                Log.e(TAG, "camera2 beforeCall failed", t);
                            }
                        }
                    });
                    count++;
                    Log.i(TAG, "hooked " + className + "#" + method.getName());
                }
            } catch (Throwable t) {
                Log.w(TAG, "skip class " + className + ": " + t.getMessage());
            }
        }
        return count;
    }

    private static int hookCamera1() {
        int count = 0;
        try {
            Class<?> camera = Class.forName("android.hardware.Camera");
            for (Method m : camera.getDeclaredMethods()) {
                String name = m.getName();
                if (!"setPreviewDisplay".equals(name)
                        && !"setPreviewTexture".equals(name)
                        && !"startPreview".equals(name)) {
                    continue;
                }
                final Method method = m;
                Pine.hook(method, new MethodHook() {
                    @Override
                    public void beforeCall(Pine.CallFrame callFrame) {
                        try {
                            handleCamera1(method.getName(), callFrame);
                        } catch (Throwable t) {
                            Log.e(TAG, "camera1 beforeCall failed", t);
                        }
                    }
                });
                count++;
                Log.i(TAG, "hooked Camera#" + name);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Camera1 hook skipped: " + t.getMessage());
        }
        return count;
    }

    private static void handleCamera1(String methodName, Pine.CallFrame frame) throws Throwable {
        // Soft inject: NEVER replace Camera1 args — keeps real camera usable when mode=real
        if (!shouldInject()) return;
        Object[] args = frame.args;
        if ("setPreviewDisplay".equals(methodName) && args != null && args.length > 0) {
            SurfaceHolder holder = (SurfaceHolder) args[0];
            if (holder == null) return;
            Surface real = holder.getSurface();
            if (real == null || !real.isValid()) return;
            rememberAndFeed(singleton(real));
            writeStatus("soft_cam1_display:" + processHint.get());
            return;
        }
        if ("setPreviewTexture".equals(methodName) && args != null && args.length > 0) {
            SurfaceTexture tex = (SurfaceTexture) args[0];
            if (tex == null) return;
            retainedTextures.add(tex);
            Surface real = new Surface(tex);
            rememberAndFeed(singleton(real));
            // Do NOT swap for dummy — real camera HAL keeps working
            writeStatus("soft_cam1_texture:" + processHint.get());
            return;
        }
        if ("startPreview".equals(methodName) && shouldInject() && !lastPreviewSurfaces.isEmpty()) {
            startOnSurfaces(lastPreviewSurfaces);
            writeStatus("soft_cam1_start:" + processHint.get());
        }
    }

    private static void handleCamera2Session(Pine.CallFrame frame) throws Throwable {
        Object[] args = frame.args;
        if (args == null || args.length == 0) return;

        Object primary = args[0];
        ArrayList<Surface> surfaces = extractSurfaces(primary);
        if (surfaces.isEmpty() && args.length > 1) {
            surfaces = extractSurfaces(args[1]);
        }
        if (surfaces.isEmpty()) return;

        // Always remember preview surfaces so Real->Virtual can refeed without new session
        ArrayList<Surface> preview = pickPreviewSurfaces(surfaces);
        lastPreviewSurfaces = new ArrayList<>(preview);

        if (!shouldInject()) {
            // Complete pass-through — do not touch frame.args
            writeStatus("passthrough_session:" + processHint.get() + ":s=" + surfaces.size());
            return;
        }

        // Soft inject: feed MediaPlayer onto preview, leave HAL surfaces untouched
        startOnSurfaces(preview);
        writeStatus("soft_cam2:" + processHint.get() + ":s=" + surfaces.size());
    }

    private static void rememberAndFeed(List<Surface> surfaces) {
        lastPreviewSurfaces = new ArrayList<>(surfaces);
        startOnSurfaces(surfaces);
    }

    @SuppressWarnings("unchecked")
    private static ArrayList<Surface> extractSurfaces(Object arg) {
        ArrayList<Surface> surfaces = new ArrayList<>();
        if (arg == null) return surfaces;
        if (arg instanceof Surface) {
            surfaces.add((Surface) arg);
            return surfaces;
        }
        if (arg instanceof List) {
            for (Object o : (List<Object>) arg) {
                if (o instanceof Surface) {
                    surfaces.add((Surface) o);
                } else if (o instanceof OutputConfiguration) {
                    surfaces.addAll(((OutputConfiguration) o).getSurfaces());
                }
            }
            return surfaces;
        }
        if (arg instanceof SessionConfiguration) {
            for (OutputConfiguration out : ((SessionConfiguration) arg).getOutputConfigurations()) {
                surfaces.addAll(out.getSurfaces());
            }
        }
        return surfaces;
    }

    private static ArrayList<Surface> pickPreviewSurfaces(List<Surface> all) {
        ArrayList<Surface> preferred = new ArrayList<>();
        for (Surface s : all) {
            if (s != null && s.isValid()) preferred.add(s);
        }
        if (preferred.isEmpty()) return preferred;
        ArrayList<Surface> ordered = new ArrayList<>();
        ordered.add(preferred.get(preferred.size() - 1));
        if (preferred.size() > 1) {
            ordered.add(preferred.get(0));
        }
        for (int i = 1; i < preferred.size() - 1; i++) {
            ordered.add(preferred.get(i));
        }
        return ordered;
    }

    private static List<Surface> singleton(Surface s) {
        ArrayList<Surface> list = new ArrayList<>(1);
        list.add(s);
        return list;
    }

    /**
     * Virtual only when enabled AND mode!=real AND virtual!=false AND source exists.
     */
    private static boolean shouldInject() {
        JSONObject json = readControl();
        if (json == null) return false;
        if (!json.optBoolean("enabled", false)) return false;

        String mode = json.optString("mode", "").trim().toLowerCase(Locale.US);
        if ("real".equals(mode)) return false;
        if ("virtual".equals(mode)) {
            return resolveSource(json) != null;
        }
        // Legacy: virtual boolean
        if (!json.optBoolean("virtual", true)) return false;
        return resolveSource(json) != null;
    }

    private static Object resolveSource(JSONObject json) {
        String source = json.optString("source", "local").toLowerCase(Locale.US);
        if ("network".equals(source)) {
            String url = json.optString("url", "").trim();
            return url.isEmpty() ? null : url;
        }
        File f = new File(VIDEO);
        if (f.exists() && f.length() > 0) return f.getAbsolutePath();
        f = new File(LEGACY_VIDEO);
        if (f.exists() && f.length() > 0) return f.getAbsolutePath();
        String uri = json.optString("uri", "");
        if (uri.startsWith("/")) {
            File d = new File(uri);
            if (d.exists()) return d.getAbsolutePath();
        }
        return null;
    }

    private static synchronized void startOnSurfaces(List<Surface> surfaces) {
        JSONObject json = readControl();
        if (json == null || !shouldInject()) {
            stopPlayer();
            return;
        }
        Object src = resolveSource(json);
        if (src == null) {
            writeStatus("no_playable_source");
            return;
        }
        stopPlayer();
        try {
            Surface target = null;
            for (Surface s : surfaces) {
                if (s != null && s.isValid()) {
                    target = s;
                    break;
                }
            }
            if (target == null) {
                writeStatus("no_valid_surface");
                return;
            }
            MediaPlayer mp = new MediaPlayer();
            String source = String.valueOf(src);
            mp.setDataSource(source);
            boolean network = source.startsWith("rtsp") || source.startsWith("http");
            mp.setLooping(!network || source.startsWith("http"));
            mp.setSurface(target);
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(p -> {
                try {
                    if (!shouldInject()) {
                        p.release();
                        return;
                    }
                    p.start();
                    writeStatus("feeding:" + processHint.get() + ":" + source);
                } catch (Throwable t) {
                    writeStatus("start_error:" + t.getMessage());
                }
            });
            mp.setOnErrorListener((p, what, extra) -> {
                writeStatus("player_error:" + what + ":" + extra);
                return true;
            });
            mp.prepareAsync();
            player = mp;
        } catch (Throwable t) {
            writeStatus("feeder_error:" + t.getMessage());
            Log.e(TAG, "startOnSurfaces", t);
        }
    }

    private static void stopPlayer() {
        try {
            if (player != null) {
                player.reset();
                player.release();
            }
        } catch (Throwable ignored) {
        }
        player = null;
    }

    private static JSONObject readControl() {
        try {
            File f = new File(CONTROL);
            if (!f.exists()) f = new File(LEGACY_CONTROL);
            if (!f.exists()) return null;
            return new JSONObject(readFile(f));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readFile(File f) throws Exception {
        byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
        return new String(data);
    }

    private static void writeStatus(String msg) {
        try {
            new File(CONTROL_DIR).mkdirs();
            String safe = msg.replace("\"", "'");
            java.nio.file.Files.write(
                    new File(STATUS).toPath(),
                    ("{\"feeder\":\"" + safe + "\",\"pkg\":\"" + processHint.get()
                            + "\",\"ts\":" + System.currentTimeMillis() + "}").getBytes()
            );
        } catch (Throwable ignored) {
        }
    }
}
