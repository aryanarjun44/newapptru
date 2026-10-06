package dev.local.videoinject;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Build;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Loaded by LSPosed only into the app(s) you tick in the module's scope.
 * Replaces the camera PREVIEW output with a video; everything else is left alone.
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "[VideoInject] ";

    /** real preview surface -> dummy surface handed to the camera */
    private static final Map<Surface, Surface> SWAPPED = new ConcurrentHashMap<>();
    private static final List<Object> KEEP_ALIVE = Collections.synchronizedList(new ArrayList<Object>());
    private static SurfaceTexture dummyTexture;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!lpp.isFirstApplication) return;
        if ("android".equals(lpp.packageName) || "dev.local.videoinject".equals(lpp.packageName)) return;

        XposedBridge.log(TAG + "active in " + lpp.packageName);
        writeLoadedMarker(lpp);
        try { hookCamera1(); } catch (Throwable t) { XposedBridge.log(TAG + "camera1: " + t); }
        try { hookCamera2(lpp.classLoader); } catch (Throwable t) { XposedBridge.log(TAG + "camera2: " + t); }
    }

    /**
     * Leaves a one-line file in the app's own folder so the module's "Check status" can prove that
     * LSPosed really loaded the module into this app. Uses reflection because only the real Xposed API
     * (not our compile-time stand-in) has the appInfo field.
     */
    private static void writeLoadedMarker(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Object ai = lpp.getClass().getField("appInfo").get(lpp);
            String dir = (String) ai.getClass().getField("dataDir").get(ai);
            File files = new File(dir, "files");
            files.mkdirs();
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            FileOutputStream o = new FileOutputStream(new File(files, "vinject.loaded"));
            try {
                o.write((stamp + " in " + lpp.processName).getBytes("UTF-8"));
            } finally {
                o.close();
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- dummies

    private static synchronized SurfaceTexture dummyTexture() {
        if (dummyTexture == null) {
            dummyTexture = new SurfaceTexture(10);
            dummyTexture.setDefaultBufferSize(1280, 720);
        }
        return dummyTexture;
    }

    private static synchronized Surface newDummySurface() {
        SurfaceTexture st = new SurfaceTexture(0);
        st.setDefaultBufferSize(1280, 720);
        Surface s = new Surface(st);
        KEEP_ALIVE.add(st);
        KEEP_ALIVE.add(s);
        return s;
    }

    // ---------------------------------------------------------------- Camera1

    private void hookCamera1() {
        XposedHelpers.findAndHookMethod(Camera.class, "setPreviewTexture", SurfaceTexture.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        SurfaceTexture orig = (SurfaceTexture) p.args[0];
                        if (orig == null || orig == dummyTexture) return;
                        Surface s = new Surface(orig);
                        if (VideoInjector.begin(s)) {
                            KEEP_ALIVE.add(s);
                            p.args[0] = dummyTexture();
                        } else {
                            s.release();
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(Camera.class, "setPreviewDisplay", SurfaceHolder.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                        SurfaceHolder h = (SurfaceHolder) p.args[0];
                        if (h == null) return;
                        Surface s = h.getSurface();
                        if (s != null && VideoInjector.begin(s)) {
                            p.setResult(null);
                            ((Camera) p.thisObject).setPreviewTexture(dummyTexture());
                        }
                    }
                });

        XC_MethodHook stopHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                VideoInjector.stop();
            }
        };
        XposedHelpers.findAndHookMethod(Camera.class, "stopPreview", stopHook);
        XposedHelpers.findAndHookMethod(Camera.class, "release", stopHook);
    }

    // ---------------------------------------------------------------- Camera2 / CameraX

    private void hookCamera2(ClassLoader cl) {
        Class<?> impl = XposedHelpers.findClass("android.hardware.camera2.impl.CameraDeviceImpl", cl);

        XC_MethodHook sessionHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    for (int i = 0; i < p.args.length; i++) {
                        Object a = p.args[i];
                        if (a instanceof List && !((List<?>) a).isEmpty()) {
                            Object first = ((List<?>) a).get(0);
                            if (first instanceof Surface) {
                                @SuppressWarnings("unchecked")
                                List<Surface> swapped = swapSurfaces((List<Surface>) a);
                                if (swapped != null) p.args[i] = swapped;
                            } else if (first instanceof OutputConfiguration) {
                                @SuppressWarnings("unchecked")
                                List<OutputConfiguration> swapped = swapConfigs((List<OutputConfiguration>) a);
                                if (swapped != null) p.args[i] = swapped;
                            }
                        } else if (Build.VERSION.SDK_INT >= 28 && a instanceof SessionConfiguration) {
                            SessionConfiguration sc = swapSessionConfig((SessionConfiguration) a);
                            if (sc != null) p.args[i] = sc;
                        }
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "session hook: " + t);
                }
            }
        };
        XposedBridge.hookAllMethods(impl, "createCaptureSession", sessionHook);
        XposedBridge.hookAllMethods(impl, "createCaptureSessionByOutputConfigurations", sessionHook);

        // Requests must target the dummy, not the surface we took away from the camera.
        XposedHelpers.findAndHookMethod(CaptureRequest.Builder.class, "addTarget", Surface.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        Surface d = SWAPPED.get((Surface) p.args[0]);
                        if (d != null) p.args[0] = d;
                    }
                });
        XposedHelpers.findAndHookMethod(CaptureRequest.Builder.class, "removeTarget", Surface.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        Surface d = SWAPPED.get((Surface) p.args[0]);
                        if (d != null) p.args[0] = d;
                    }
                });

        XposedBridge.hookAllMethods(impl, "close", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                VideoInjector.stop();
                SWAPPED.clear();
            }
        });
    }

    /** First valid surface in the list is treated as the preview. */
    private static List<Surface> swapSurfaces(List<Surface> src) {
        for (int i = 0; i < src.size(); i++) {
            Surface orig = src.get(i);
            if (orig == null || !orig.isValid()) continue;
            if (!VideoInjector.begin(orig)) return null;
            Surface dummy = newDummySurface();
            SWAPPED.put(orig, dummy);
            List<Surface> copy = new ArrayList<>(src);
            copy.set(i, dummy);
            return copy;
        }
        return null;
    }

    private static List<OutputConfiguration> swapConfigs(List<OutputConfiguration> src) {
        for (int i = 0; i < src.size(); i++) {
            Surface orig = src.get(i).getSurface();
            if (orig == null || !orig.isValid()) continue;
            if (!VideoInjector.begin(orig)) return null;
            Surface dummy = newDummySurface();
            SWAPPED.put(orig, dummy);
            List<OutputConfiguration> copy = new ArrayList<>(src);
            copy.set(i, new OutputConfiguration(dummy));
            return copy;
        }
        return null;
    }

    private static SessionConfiguration swapSessionConfig(SessionConfiguration sc) {
        List<OutputConfiguration> swapped = swapConfigs(sc.getOutputConfigurations());
        if (swapped == null) return null;
        SessionConfiguration n = new SessionConfiguration(
                sc.getSessionType(), swapped, sc.getExecutor(), sc.getStateCallback());
        if (sc.getSessionParameters() != null) n.setSessionParameters(sc.getSessionParameters());
        if (sc.getInputConfiguration() != null) n.setInputConfiguration(sc.getInputConfiguration());
        return n;
    }
}
