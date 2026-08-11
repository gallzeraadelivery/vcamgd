package com.vcamgd.hook;

import android.graphics.SurfaceTexture;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.Surface;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

public final class HookEntry {
    private static final String TAG = "VCamGD-ZygiskHook";
    public static final String CONTROL_DIR = "/data/local/tmp/vcamgd";
    private static final String CONTROL = CONTROL_DIR + "/control.json";
    private static final String VIDEO = CONTROL_DIR + "/current.mp4";
    private static final String STATUS = CONTROL_DIR + "/status.json";
    private static final String LEGACY_CONTROL = "/data/adb/vcamgd/control.json";
    private static final String LEGACY_VIDEO = "/data/adb/vcamgd/current.mp4";
    private static final String PINE_PATH = CONTROL_DIR + "/libpine.so";

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

    public static void install() {
        try {
            writeStatus("installing");
            Class<?> cameraDevice = Class.forName("android.hardware.camera2.CameraDevice");
            for (Method m : cameraDevice.getDeclaredMethods()) {
                if (!"createCaptureSession".equals(m.getName())) continue;
                final Method method = m;
                Pine.hook(method, new MethodHook() {
                    @Override
                    public void beforeCall(Pine.CallFrame callFrame) {
                        try {
                            handleCreateCaptureSession(callFrame);
                        } catch (Throwable t) {
                            Log.e(TAG, "beforeCall failed", t);
                        }
                    }
                });
                Log.i(TAG, "hooked " + method);
            }
            // Impl interna
            hookClassMethods("android.hardware.camera2.impl.CameraDeviceImpl");
            writeStatus("hooks_ready");
            Log.i(TAG, "HookEntry.install done");
        } catch (Throwable t) {
            Log.e(TAG, "install failed", t);
            writeStatus("install_error:" + t.getMessage());
        }
    }

    private static void hookClassMethods(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            for (Method m : clazz.getDeclaredMethods()) {
                if (!"createCaptureSession".equals(m.getName())) continue;
                Pine.hook(m, new MethodHook() {
                    @Override
                    public void beforeCall(Pine.CallFrame callFrame) {
                        try {
                            handleCreateCaptureSession(callFrame);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    private static void handleCreateCaptureSession(Pine.CallFrame frame) throws Throwable {
        if (!shouldInject()) return;
        Object[] args = frame.args;
        if (args == null || args.length == 0) return;

        if (args[0] instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) args[0];
            ArrayList<Surface> surfaces = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Surface) surfaces.add((Surface) o);
            }
            if (surfaces.isEmpty()) return;
            startOnSurfaces(surfaces);
            frame.args[0] = createDummySurfaces(surfaces.size());
            return;
        }

        if (args[0] instanceof SessionConfiguration) {
            SessionConfiguration config = (SessionConfiguration) args[0];
            ArrayList<Surface> surfaces = new ArrayList<>();
            for (OutputConfiguration out : config.getOutputConfigurations()) {
                surfaces.addAll(out.getSurfaces());
            }
            if (surfaces.isEmpty()) return;
            startOnSurfaces(surfaces);
            ArrayList<OutputConfiguration> outs = new ArrayList<>();
            for (Surface dummy : createDummySurfaces(surfaces.size())) {
                outs.add(new OutputConfiguration(dummy));
            }
            frame.args[0] = new SessionConfiguration(
                    config.getSessionType(),
                    outs,
                    config.getExecutor(),
                    config.getStateCallback()
            );
        }
    }

    private static boolean shouldInject() {
        JSONObject json = readControl();
        if (json == null) return false;
        if (!json.optBoolean("enabled", false) || !json.optBoolean("virtual", true)) return false;
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
        if (json == null) return;
        Object src = resolveSource(json);
        if (src == null) {
            writeStatus("no_playable_source");
            return;
        }
        stopPlayer();
        try {
            MediaPlayer mp = new MediaPlayer();
            String source = String.valueOf(src);
            mp.setDataSource(source);
            boolean network = source.startsWith("rtsp") || source.startsWith("http");
            mp.setLooping(!network || source.startsWith("http"));
            mp.setSurface(surfaces.get(0));
            mp.setOnPreparedListener(p -> {
                try {
                    p.start();
                    writeStatus("feeding:" + source);
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
            SurfaceTexture st = new SurfaceTexture(false);
            st.setDefaultBufferSize(1280, 720);
            dummies.add(st);
            out.add(new Surface(st));
        }
        return out;
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
                    ("{\"feeder\":\"" + safe + "\",\"ts\":" + System.currentTimeMillis() + "}").getBytes()
            );
        } catch (Throwable ignored) {
        }
    }
}
