package dev.local.videoinject;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Runs inside the target app. The module's settings screen (with root) puts vinject.mp4 and
 * vinject.cfg into the target app's own files folder, so the hook only reads local files and
 * the target app does not need any change. The hook also leaves a one-line vinject.status file
 * so the module's "Check setup" button can tell what happened last.
 */
final class VideoInjector {

    static final String VIDEO_NAME = "vinject.mp4";
    static final String CFG_NAME = "vinject.cfg";
    static final String STATUS_NAME = "vinject.status";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static MediaPlayer player;
    private static GlVideoRenderer gl;

    private VideoInjector() {}

    private static Context appContext() {
        try {
            return (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
        } catch (Throwable t) {
            return null;
        }
    }

    private static void note(Context ctx, String msg) {
        try {
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            FileOutputStream o = new FileOutputStream(new File(ctx.getFilesDir(), STATUS_NAME));
            try {
                o.write((stamp + "  " + msg + "\n").getBytes("UTF-8"));
            } finally {
                o.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Returns true if the video will be played on {@code target}; the caller must then
     * swap the real camera output for a dummy. Returns false (change nothing) on any problem.
     */
    static boolean begin(final Surface target) {
        Context ctx = null;
        try {
            ctx = appContext();
            if (ctx == null) return false;
            final Context fctx = ctx;
            if (target == null || !target.isValid()) {
                note(ctx, "SKIP: camera preview surface was not ready");
                return false;
            }

            File dir = ctx.getFilesDir();
            final File video = new File(dir, VIDEO_NAME);
            if (!video.isFile() || video.length() == 0) {
                note(ctx, "SKIP: no video in the app folder (tap Apply to app)");
                return false;
            }

            Properties p = new Properties();
            File cfg = new File(dir, CFG_NAME);
            if (cfg.isFile()) {
                FileInputStream in = new FileInputStream(cfg);
                try {
                    p.load(in);
                } finally {
                    in.close();
                }
            }
            boolean enabled = !"0".equals(p.getProperty("enabled", "1").trim());
            final boolean loop = !"0".equals(p.getProperty("loop", "1").trim());
            boolean useGl = !"0".equals(p.getProperty("gl", "1").trim());
            if (!enabled) {
                note(ctx, "SKIP: Inject video is switched off");
                return false;
            }

            releaseAll();
            if (useGl) {
                final GlVideoRenderer r = new GlVideoRenderer(target, video.getAbsolutePath(), loop,
                        GlVideoRenderer.Fit.fromProps(p), new GlVideoRenderer.Callback() {
                            @Override
                            public void onStatus(String msg) {
                                note(fctx, msg);
                            }

                            @Override
                            public void onFailed(final String why, boolean wasShowing) {
                                XposedBridge.log("[VideoInject] GL failed: " + why);
                                if (wasShowing) {
                                    note(fctx, "ERROR: " + why);
                                    return;
                                }
                                MAIN.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        note(fctx, "NOTE: smart fit unavailable (" + why + "), using plain video");
                                        playDirect(fctx, target, video, loop);
                                    }
                                });
                            }
                        });
                gl = r;
                r.start();
            } else {
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        playDirect(fctx, target, video, loop);
                    }
                });
            }
            return true;
        } catch (Throwable t) {
            XposedBridge.log("[VideoInject] begin failed: " + t);
            if (ctx != null) note(ctx, "ERROR: " + t);
            return false;
        }
    }

    /** Old simple path: the player draws straight onto the preview surface (no fitting). */
    private static void playDirect(final Context fctx, Surface target, File video, boolean loop) {
        try {
            releasePlayer();
            MediaPlayer mp = new MediaPlayer();
            mp.setSurface(target);
            mp.setDataSource(video.getAbsolutePath());
            mp.setLooping(loop);
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer p) {
                    p.start();
                    note(fctx, "OK: video is playing in the camera preview (plain, no fit)");
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer p, int what, int extra) {
                    XposedBridge.log("[VideoInject] player error " + what + "/" + extra);
                    note(fctx, "ERROR: video could not play (" + what + "/" + extra
                            + "). Try another MP4 (H.264, 1080p or lower)");
                    return true;
                }
            });
            mp.prepareAsync();
            player = mp;
        } catch (Throwable t) {
            XposedBridge.log("[VideoInject] play failed: " + t);
            note(fctx, "ERROR: " + t);
        }
    }

    static void stop() {
        final GlVideoRenderer r;
        final MediaPlayer mp;
        synchronized (VideoInjector.class) {
            r = gl;
            gl = null;
            mp = player;
            player = null;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (r != null) {
                    try { r.release(); } catch (Throwable ignored) {}
                }
                killPlayer(mp);
            }
        });
    }

    private static synchronized void releaseAll() {
        GlVideoRenderer r = gl;
        gl = null;
        if (r != null) {
            try { r.release(); } catch (Throwable ignored) {}
        }
        releasePlayer();
    }

    private static void releasePlayer() {
        MediaPlayer mp = player;
        player = null;
        killPlayer(mp);
    }

    private static void killPlayer(MediaPlayer mp) {
        if (mp == null) return;
        try { mp.stop(); } catch (Throwable ignored) {}
        try { mp.release(); } catch (Throwable ignored) {}
    }
}
