package dev.local.videoinject;

import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.Properties;

/**
 * Draws a video into a Surface with a "smart fit": the face is moved to the centre of the round
 * preview and scaled so it fills about 70 percent of the circle. Zoom, left/right, up/down, rotate and
 * mirror can be changed live. Used both inside the target app (hook) and in the module's simulation screen,
 * so what you see in the simulation is what the app shows.
 *
 * Everything GL happens on one private thread.
 */
final class GlVideoRenderer implements SurfaceTexture.OnFrameAvailableListener {

    // ------------------------------------------------------------------ settings

    /** How the video is placed. Face values are fractions of the (rotated) video picture, y downwards. */
    static final class Fit {
        float zoom = 1f;       // 0.5 .. 2.5, 1 = automatic size
        float offX = 0f;       // -1 .. 1 (circle radius units), + moves the picture right
        float offY = 0f;       // -1 .. 1, + moves the picture down
        float faceCx = 0.5f;
        float faceCy = 0.38f;
        float faceSize = 0f;   // face width / picture width, 0 = unknown
        int rot = 0;           // 0, 90, 180, 270 (clockwise)
        boolean mirror = false;
        float fill = 0.70f;    // how much of the circle the face fills

        Fit copy() {
            Fit f = new Fit();
            f.zoom = zoom;
            f.offX = offX;
            f.offY = offY;
            f.faceCx = faceCx;
            f.faceCy = faceCy;
            f.faceSize = faceSize;
            f.rot = rot;
            f.mirror = mirror;
            f.fill = fill;
            return f;
        }

        /** Lines for the settings file (key=value, one per line). */
        String toCfg() {
            StringBuilder sb = new StringBuilder();
            sb.append(line("zoom", zoom)).append(line("offx", offX)).append(line("offy", offY));
            sb.append(line("facecx", faceCx)).append(line("facecy", faceCy)).append(line("facesize", faceSize));
            sb.append(line("fill", fill));
            sb.append("rot=").append(rot).append('\n');
            sb.append("mirror=").append(mirror ? 1 : 0).append('\n');
            return sb.toString();
        }

        private static String line(String k, float v) {
            return k + "=" + String.format(Locale.US, "%.4f", v) + "\n";
        }

        static float num(String s, float def) {
            try {
                return Float.parseFloat(s.trim());
            } catch (Exception e) {
                return def;
            }
        }

        static Fit fromProps(Properties p) {
            Fit f = new Fit();
            f.zoom = clamp(num(p.getProperty("zoom", "1"), 1f), 0.3f, 3f);
            f.offX = clamp(num(p.getProperty("offx", "0"), 0f), -2f, 2f);
            f.offY = clamp(num(p.getProperty("offy", "0"), 0f), -2f, 2f);
            f.faceCx = clamp(num(p.getProperty("facecx", "0.5"), 0.5f), 0f, 1f);
            f.faceCy = clamp(num(p.getProperty("facecy", "0.38"), 0.38f), 0f, 1f);
            f.faceSize = clamp(num(p.getProperty("facesize", "0"), 0f), 0f, 1f);
            f.fill = clamp(num(p.getProperty("fill", "0.7"), 0.7f), 0.2f, 1.2f);
            f.rot = normRot((int) num(p.getProperty("rot", "0"), 0f));
            f.mirror = "1".equals(p.getProperty("mirror", "0").trim());
            return f;
        }

        static Fit fromPrefs(SharedPreferences sp) {
            Fit f = new Fit();
            f.zoom = sp.getFloat("fit_zoom", 1f);
            f.offX = sp.getFloat("fit_offx", 0f);
            f.offY = sp.getFloat("fit_offy", 0f);
            f.faceCx = sp.getFloat("fit_facecx", 0.5f);
            f.faceCy = sp.getFloat("fit_facecy", 0.38f);
            f.faceSize = sp.getFloat("fit_facesize", 0f);
            f.rot = normRot(sp.getInt("fit_rot", 0));
            f.mirror = sp.getBoolean("fit_mirror", false);
            return f;
        }

        void toPrefs(SharedPreferences.Editor e) {
            e.putFloat("fit_zoom", zoom).putFloat("fit_offx", offX).putFloat("fit_offy", offY)
                    .putFloat("fit_facecx", faceCx).putFloat("fit_facecy", faceCy)
                    .putFloat("fit_facesize", faceSize).putInt("fit_rot", rot)
                    .putBoolean("fit_mirror", mirror);
        }
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    static int normRot(int r) {
        r = ((r % 360) + 360) % 360;
        return (r / 90) * 90;
    }

    interface Callback {
        void onStatus(String msg);

        /** The GL path could not work. wasShowing = a picture had already been drawn. */
        void onFailed(String why, boolean wasShowing);
    }

    // ------------------------------------------------------------------ state

    private static final String VERT =
            "attribute vec2 aPos;\n"
                    + "varying vec2 vUv;\n"
                    + "void main(){ vUv = aPos*0.5+0.5; gl_Position = vec4(aPos,0.0,1.0); }\n";

    private static final String FRAG =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
                    + "precision highp float;\n"
                    + "#else\n"
                    + "precision mediump float;\n"
                    + "#endif\n"
                    + "varying vec2 vUv;\n"
                    + "uniform samplerExternalOES uTex;\n"
                    + "uniform mat4 uTexM;\n"
                    + "uniform mat2 uM;\n"
                    + "uniform vec4 uAff;\n"
                    + "uniform float uMirror;\n"
                    + "void main(){\n"
                    + "  vec2 p = vec2(uAff.x*vUv.x+uAff.y, uAff.z*vUv.y+uAff.w) - 0.5;\n"
                    + "  if (uMirror > 0.5) p.x = -p.x;\n"
                    + "  vec2 t = uM*p + 0.5;\n"
                    + "  if (t.x < 0.0 || t.x > 1.0 || t.y < 0.0 || t.y > 1.0) {\n"
                    + "    gl_FragColor = vec4(0.0,0.0,0.0,1.0);\n"
                    + "  } else {\n"
                    + "    vec2 tc = (uTexM*vec4(t,0.0,1.0)).xy;\n"
                    + "    gl_FragColor = texture2D(uTex, tc);\n"
                    + "  }\n"
                    + "}\n";

    private final Surface out;
    private final String path;
    private final boolean loop;
    private final Callback cb;
    private volatile Fit fit;

    private HandlerThread thread;
    private Handler h;

    private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
    private EGLContext ctx = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surf = EGL14.EGL_NO_SURFACE;
    private int prog, texId, aPos, uTexM, uM, uAff, uMirror;
    private SurfaceTexture st;
    private Surface playerSurface;
    private MediaPlayer mp;
    private FloatBuffer quad;
    private final float[] texM = new float[16];
    private final int[] tmp = new int[1];
    private int cw, ch;
    private boolean gotFrame;
    private boolean showing;
    private volatile boolean dead;
    private boolean reported;

    GlVideoRenderer(Surface out, String path, boolean loop, Fit fit, Callback cb) {
        this.out = out;
        this.path = path;
        this.loop = loop;
        this.fit = fit == null ? new Fit() : fit.copy();
        this.cb = cb;
    }

    void start() {
        thread = new HandlerThread("vinject-gl");
        thread.start();
        h = new Handler(thread.getLooper());
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    initGl();
                    initPlayer();
                } catch (Throwable t) {
                    fail("GL setup: " + t);
                }
            }
        });
    }

    void setFit(Fit f) {
        fit = f.copy();
        redraw();
    }

    void redraw() {
        final Handler hh = h;
        if (hh == null || dead) return;
        hh.post(new Runnable() {
            @Override
            public void run() {
                if (!dead && gotFrame) draw(false);
            }
        });
    }

    /** Stops everything and waits (briefly) until the Surface is free again. */
    void release() {
        if (dead && thread == null) return;
        final Handler hh = h;
        final HandlerThread th = thread;
        if (hh == null || th == null) {
            dead = true;
            return;
        }
        if (Looper.myLooper() == th.getLooper()) {
            teardown();
            return;
        }
        hh.post(new Runnable() {
            @Override
            public void run() {
                teardown();
            }
        });
        try {
            th.join(1500);
        } catch (InterruptedException ignored) {
        }
    }

    // ------------------------------------------------------------------ setup (GL thread)

    private void initGl() {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (dpy == EGL14.EGL_NO_DISPLAY) throw new IllegalStateException("no EGL display");
        int[] ver = new int[2];
        if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) throw new IllegalStateException("eglInitialize");

        int[] attr = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE};
        EGLConfig[] cfgs = new EGLConfig[1];
        int[] n = new int[1];
        if (!EGL14.eglChooseConfig(dpy, attr, 0, cfgs, 0, 1, n, 0) || n[0] < 1) {
            throw new IllegalStateException("no EGL config");
        }
        ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (ctx == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("no EGL context");
        surf = EGL14.eglCreateWindowSurface(dpy, cfgs[0], out, new int[]{EGL14.EGL_NONE}, 0);
        if (surf == EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("cannot draw on the preview surface (0x"
                    + Integer.toHexString(EGL14.eglGetError()) + ")");
        }
        if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) throw new IllegalStateException("eglMakeCurrent");

        int vs = compile(GLES20.GL_VERTEX_SHADER, VERT);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAG);
        prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glLinkProgram(prog);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("shader link: " + GLES20.glGetProgramInfoLog(prog));
        aPos = GLES20.glGetAttribLocation(prog, "aPos");
        uTexM = GLES20.glGetUniformLocation(prog, "uTexM");
        uM = GLES20.glGetUniformLocation(prog, "uM");
        uAff = GLES20.glGetUniformLocation(prog, "uAff");
        uMirror = GLES20.glGetUniformLocation(prog, "uMirror");

        float[] v = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
        quad = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(v).position(0);

        GLES20.glGenTextures(1, tmp, 0);
        texId = tmp[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        st = new SurfaceTexture(texId);
        st.setOnFrameAvailableListener(this, h);
        playerSurface = new Surface(st);
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new IllegalStateException("shader compile: " + log);
        }
        return s;
    }

    private void initPlayer() throws Exception {
        mp = new MediaPlayer();
        mp.setSurface(playerSurface);
        mp.setDataSource(path);
        mp.setLooping(loop);
        mp.setVolume(0f, 0f);
        mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer p) {
                cw = p.getVideoWidth();
                ch = p.getVideoHeight();
                p.start();
            }
        });
        mp.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
            @Override
            public void onVideoSizeChanged(MediaPlayer p, int w, int hh) {
                if (w > 0 && hh > 0) {
                    cw = w;
                    ch = hh;
                }
            }
        });
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer p, int what, int extra) {
                fail("video could not play (" + what + "/" + extra + ")");
                return true;
            }
        });
        mp.prepareAsync();
    }

    // ------------------------------------------------------------------ drawing (GL thread)

    @Override
    public void onFrameAvailable(SurfaceTexture t) {
        if (dead) return;
        try {
            draw(true);
        } catch (Throwable e) {
            fail("draw: " + e);
        }
    }

    private void draw(boolean newFrame) {
        if (dead || st == null) return;
        if (newFrame) {
            st.updateTexImage();
            st.getTransformMatrix(texM);
            gotFrame = true;
        }
        if (!gotFrame) return;

        EGL14.eglQuerySurface(dpy, surf, EGL14.EGL_WIDTH, tmp, 0);
        int W = tmp[0];
        EGL14.eglQuerySurface(dpy, surf, EGL14.EGL_HEIGHT, tmp, 0);
        int H = tmp[0];
        if (W <= 0 || H <= 0) return;

        Fit f = fit;
        float contentW = cw > 0 ? cw : 1080f;
        float contentH = ch > 0 ? ch : 1920f;
        boolean odd = f.rot == 90 || f.rot == 270;
        float vw = odd ? contentH : contentW;
        float vh = odd ? contentW : contentH;
        float D = Math.min(W, H);

        float cover = D / Math.min(vw, vh);
        float s0 = f.faceSize > 0.01f ? f.fill * D / (f.faceSize * vw) : cover;
        s0 = clamp(s0, cover, cover * 4f);
        float s = s0 * f.zoom;

        float fcx = f.mirror ? 1f - f.faceCx : f.faceCx;
        float a = W / (s * vw);
        float b = fcx - (W / 2f + f.offX * D / 2f) / (s * vw);
        float c = H / (s * vh);
        float d = (1f - f.faceCy) + (-H / 2f + f.offY * D / 2f) / (s * vh);

        double th = Math.toRadians(f.rot);
        float cs = Math.round(Math.cos(th));
        float sn = Math.round(Math.sin(th));
        float m00 = cs * vw / contentW;
        float m01 = -sn * vh / contentW;
        float m10 = sn * vw / contentH;
        float m11 = cs * vh / contentH;

        GLES20.glViewport(0, 0, W, H);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(prog);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glUniformMatrix4fv(uTexM, 1, false, texM, 0);
        GLES20.glUniformMatrix2fv(uM, 1, false, new float[]{m00, m10, m01, m11}, 0);
        GLES20.glUniform4f(uAff, a, b, c, d);
        GLES20.glUniform1f(uMirror, f.mirror ? 1f : 0f);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        if (!EGL14.eglSwapBuffers(dpy, surf)) {
            fail("swap failed (0x" + Integer.toHexString(EGL14.eglGetError()) + ")");
            return;
        }
        showing = true;
        if (!reported) {
            reported = true;
            cb.onStatus("OK: video is playing in the camera preview (smart fit, preview "
                    + W + "x" + H + ", video " + (int) contentW + "x" + (int) contentH + ")");
        }
    }

    // ------------------------------------------------------------------ end

    private void fail(String why) {
        if (dead) return;
        boolean was = showing;
        teardown();
        cb.onFailed(why, was);
    }

    private void teardown() {
        if (dead) return;
        dead = true;
        try {
            if (mp != null) {
                try { mp.stop(); } catch (Throwable ignored) {}
                mp.release();
            }
        } catch (Throwable ignored) {
        }
        mp = null;
        try {
            if (playerSurface != null) playerSurface.release();
        } catch (Throwable ignored) {
        }
        try {
            if (st != null) st.release();
        } catch (Throwable ignored) {
        }
        try {
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf);
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx);
            }
        } catch (Throwable ignored) {
        }
        surf = EGL14.EGL_NO_SURFACE;
        ctx = EGL14.EGL_NO_CONTEXT;
        HandlerThread th = thread;
        if (th != null) th.quitSafely();
    }
}
