package com.vcamgd.hook;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.ImageReader;
import android.media.MediaPlayer;
import android.os.Build;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

/**
 * Injeção alinhada ao padrão OVCAM (observado no APK de referência):
 * - Por padrão NÃO altera os argumentos de createCaptureSession (evita 03400001 na Moto).
 * - Alimenta o preview com MediaPlayer no afterCall, depois que a sessão já abriu.
 * - Modo "hard" opcional (control.inject=hard) para apps que precisam bloquear o HAL.
 */
public final class HookEntry {
    private static final String TAG = "KingVCam-KingHook";
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
    private static final ArrayList<SurfaceTexture> dummies = new ArrayList<>();
    private static final ArrayList<ImageReader> dummyReaders = new ArrayList<>();
    private static final AtomicReference<String> processHint = new AtomicReference<>("unknown");
    private static volatile List<Surface> lastPreview = new ArrayList<>();

    public static void install() {
        install("unknown");
    }

    public static void install(String processName) {
        if (processName != null && !processName.isEmpty()) {
            processHint.set(processName);
        }
        try {
            writeStatus("installing:" + processHint.get() + ":mfr=" + Build.MANUFACTURER);
            int hooked = 0;
            hooked += hookCamera2();
            hooked += hookCamera1();
            writeStatus("hooks_ready:" + processHint.get() + ":n=" + hooked + ":mode=" + injectMode());
            Log.i(TAG, "install done process=" + processHint.get() + " hooked=" + hooked);
        } catch (Throwable t) {
            Log.e(TAG, "install failed", t);
            writeStatus("install_error:" + t.getMessage());
        }
    }

    /** soft (default, OVCAM-like) | hard (dummy HAL surfaces) */
    private static String injectMode() {
        JSONObject json = readControl();
        if (json == null) return "soft";
        String m = json.optString("inject", "soft").trim().toLowerCase(Locale.US);
        if ("hard".equals(m)) return "hard";
        return "soft";
    }

    private static int hookCamera2() {
        int count = 0;
        for (String className : CAMERA2_CLASSES) {
            try {
                Class<?> clazz = Class.forName(className);
                for (Method m : clazz.getDeclaredMethods()) {
                    boolean match = false;
                    for (String sm : SESSION_METHODS) {
                        if (sm.equals(m.getName())) {
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
                                beforeCamera2(callFrame);
                            } catch (Throwable t) {
                                Log.e(TAG, "camera2 before", t);
                            }
                        }

                        @Override
                        public void afterCall(Pine.CallFrame callFrame) {
                            try {
                                afterCamera2(callFrame);
                            } catch (Throwable t) {
                                Log.e(TAG, "camera2 after", t);
                            }
                        }
                    });
                    count++;
                }
            } catch (Throwable t) {
                Log.w(TAG, "skip " + className + ": " + t.getMessage());
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
                            beforeCamera1(method.getName(), callFrame);
                        } catch (Throwable t) {
                            Log.e(TAG, "camera1 before", t);
                        }
                    }

                    @Override
                    public void afterCall(Pine.CallFrame callFrame) {
                        try {
                            if ("startPreview".equals(method.getName()) && shouldInject()) {
                                if (!lastPreview.isEmpty()) {
                                    startOnSurfaces(lastPreview);
                                    writeStatus("soft_cam1_after_start:" + processHint.get());
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                count++;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Camera1 skip: " + t.getMessage());
        }
        return count;
    }

    private static void beforeCamera1(String methodName, Pine.CallFrame frame) {
        if (!shouldInject()) return;
        Object[] args = frame.args;
        boolean hard = "hard".equals(injectMode());
        if ("setPreviewDisplay".equals(methodName) && args != null && args.length > 0 && args[0] instanceof SurfaceHolder) {
            SurfaceHolder holder = (SurfaceHolder) args[0];
            Surface real = holder.getSurface();
            if (real != null && real.isValid()) {
                lastPreview = singleton(real);
                if (!hard) {
                    // OVCAM-like: nao mexe no holder; feeder no after/startPreview
                    return;
                }
                startOnSurfaces(lastPreview);
            }
            return;
        }
        if ("setPreviewTexture".equals(methodName) && args != null && args.length > 0 && args[0] instanceof SurfaceTexture) {
            SurfaceTexture tex = (SurfaceTexture) args[0];
            Surface real = new Surface(tex);
            lastPreview = singleton(real);
            if (hard) {
                startOnSurfaces(lastPreview);
                frame.args[0] = newDummyTexture(1280, 720);
            }
        }
    }

    private static void beforeCamera2(Pine.CallFrame frame) {
        Object[] args = frame.args;
        if (args == null || args.length == 0) return;
        ArrayList<Surface> surfaces = extractSurfaces(args[0]);
        if (surfaces.isEmpty() && args.length > 1) surfaces = extractSurfaces(args[1]);
        if (surfaces.isEmpty()) return;

        lastPreview = pickPreviewSurfaces(surfaces);

        if (!shouldInject()) return;
        if (!"hard".equals(injectMode())) {
            // soft: nao altera args — sessao HAL nativa (Moto abre sem 03400001)
            writeStatus("soft_cam2_before:" + processHint.get() + ":s=" + surfaces.size());
            return;
        }

        // hard: troca por dummies ImageReader-compativeis
        startOnSurfaces(lastPreview);
        Object primary = args[0];
        if (primary instanceof SessionConfiguration) {
            SessionConfiguration config = (SessionConfiguration) primary;
            ArrayList<OutputConfiguration> outs = new ArrayList<>();
            for (Surface dummy : createDummySurfaces(surfaces.size())) {
                outs.add(new OutputConfiguration(dummy));
            }
            frame.args[0] = new SessionConfiguration(
                    config.getSessionType(), outs, config.getExecutor(), config.getStateCallback());
            writeStatus("hard_cam2_sessioncfg:" + processHint.get());
            return;
        }
        if (primary instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) primary;
            if (!list.isEmpty() && list.get(0) instanceof OutputConfiguration) {
                ArrayList<OutputConfiguration> outs = new ArrayList<>();
                for (Surface dummy : createDummySurfaces(surfaces.size())) {
                    outs.add(new OutputConfiguration(dummy));
                }
                frame.args[0] = outs;
            } else {
                frame.args[0] = createDummySurfaces(surfaces.size());
            }
            writeStatus("hard_cam2_list:" + processHint.get());
        }
    }

    private static void afterCamera2(Pine.CallFrame frame) {
        if (!shouldInject()) return;
        if ("hard".equals(injectMode())) return; // ja alimentou no before
        if (lastPreview == null || lastPreview.isEmpty()) return;
        // OVCAM-like: sessao ja criada com Surfaces reais → agora joga o video por cima
        startOnSurfaces(lastPreview);
        writeStatus("soft_cam2_after:" + processHint.get() + ":s=" + lastPreview.size());
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
                if (o instanceof Surface) surfaces.add((Surface) o);
                else if (o instanceof OutputConfiguration) {
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
        if (preferred.size() > 1) ordered.add(preferred.get(0));
        return ordered;
    }

    private static List<Surface> singleton(Surface s) {
        ArrayList<Surface> list = new ArrayList<>(1);
        list.add(s);
        return list;
    }

    private static boolean shouldInject() {
        JSONObject json = readControl();
        if (json == null) return false;
        if (!json.optBoolean("enabled", false)) return false;
        String mode = json.optString("mode", "").trim().toLowerCase(Locale.US);
        if ("real".equals(mode)) return false;
        if (!json.optBoolean("virtual", true) && !"virtual".equals(mode)) return false;
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
        if (!shouldInject()) {
            stopPlayer();
            return;
        }
        JSONObject json = readControl();
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
                    writeStatus("feeding:" + processHint.get() + ":" + injectMode() + ":" + source);
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

    private static List<Surface> createDummySurfaces(int count) {
        ArrayList<Surface> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(newHalCompatibleSurface(1280, 720));
        }
        return out;
    }

    private static Surface newHalCompatibleSurface(int w, int h) {
        try {
            ImageReader reader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 2);
            dummyReaders.add(reader);
            Surface s = reader.getSurface();
            if (s != null && s.isValid()) return s;
        } catch (Throwable ignored) {
        }
        return new Surface(newDummyTexture(w, h));
    }

    private static SurfaceTexture newDummyTexture(int w, int h) {
        SurfaceTexture st = new SurfaceTexture(false);
        st.setDefaultBufferSize(w, h);
        dummies.add(st);
        return st;
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
        for (SurfaceTexture st : dummies) {
            try {
                st.release();
            } catch (Throwable ignored) {
            }
        }
        dummies.clear();
        for (ImageReader r : dummyReaders) {
            try {
                r.close();
            } catch (Throwable ignored) {
            }
        }
        dummyReaders.clear();
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
        return new String(java.nio.file.Files.readAllBytes(f.toPath()));
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
