package dev.local.videoinject;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;

/**
 * Shows the video inside a round preview exactly the way the chosen app will show it (same renderer).
 * Move the sliders until the face sits nicely, then "Save as final output".
 */
public class SimulationActivity extends Activity {

    private static final String PREFS = "config";
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final String[] SHAPES = {"Square 1:1", "Portrait 3:4", "Landscape 4:3", "Tall 9:16"};
    private static final float[] ASPECT = {1f, 0.75f, 1.3333f, 0.5625f};

    /** Dims everything outside the circle and draws its edge. Same circle size the renderer uses. */
    private static final class GuideView extends View {
        private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        GuideView(android.content.Context c) {
            super(c);
            dim.setColor(0xCC000000);
            dim.setStyle(Paint.Style.FILL);
            ring.setColor(0xFFFFFFFF);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(3f * c.getResources().getDisplayMetrics().density);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float r = Math.min(w, h) / 2f;
            path.reset();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(0, 0, w, h, Path.Direction.CW);
            path.addCircle(w / 2f, h / 2f, r, Path.Direction.CW);
            canvas.drawPath(path, dim);
            canvas.drawCircle(w / 2f, h / 2f, r - ring.getStrokeWidth() / 2f, ring);
        }
    }

    private SharedPreferences sp;
    private Ui ui;
    private GlVideoRenderer.Fit fit;
    private GlVideoRenderer renderer;
    private Surface surface;
    private SurfaceTexture surfaceTexture;
    private TextureView tv;
    private FrameLayout stage;
    private TextView info, status;
    private SeekBar sbZoom, sbX, sbY;
    private Switch swMirror;
    private File video;
    private int shape;
    private boolean ready;
    private boolean updating;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        ui = new Ui(this);
        fit = GlVideoRenderer.Fit.fromPrefs(sp);
        shape = sp.getInt("sim_shape", 0);
        if (shape < 0 || shape >= SHAPES.length) shape = 0;
        video = new File(getFilesDir(), "video.mp4");
        build();
        syncControls();
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int bm) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w < 0 ? w : ui.dp(w), h < 0 ? h : ui.dp(h));
        p.setMargins(ui.dp(l), ui.dp(t), ui.dp(r), ui.dp(bm));
        return p;
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);
        root.setPadding(ui.dp(16), ui.dp(24), ui.dp(16), 0);

        root.addView(ui.label("Fit simulation", 24, ui.text, true));
        TextView sub = ui.label("This is what the app will show inside its round preview.", 13, ui.sub, false);
        sub.setPadding(0, ui.dp(2), 0, ui.dp(10));
        root.addView(sub);

        stage = new FrameLayout(this);
        stage.setBackground(ui.round(0xFF000000, 12));
        tv = new TextureView(this);
        stage.addView(tv, new FrameLayout.LayoutParams(MATCH, MATCH));
        stage.addView(new GuideView(this), new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(stage, stageParams());

        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) {
                surfaceTexture = t;
                startRenderer();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) {
                startRenderer();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture t) {
                stopRenderer();
                surfaceTexture = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture t) {
            }
        });

        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, ui.dp(12), 0, ui.dp(24));
        scroll.addView(panel, new ViewGroup.LayoutParams(MATCH, WRAP));
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        status = ui.label("", 13, ui.sub, false);
        panel.addView(status);
        if (!video.isFile()) status.setText("Choose a video on the home screen first.");

        LinearLayout c = ui.card();
        c.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(14));
        sbZoom = seek(c, "Zoom out  ↔  Zoom in");
        sbX = seek(c, "Move left  ↔  right");
        sbY = seek(c, "Move up  ↔  down");
        info = ui.label("", 12, ui.sub, false);
        info.setPadding(0, ui.dp(8), 0, 0);
        c.addView(info);
        panel.addView(c, lp(MATCH, WRAP, 0, 12, 0, 0));

        sbZoom.setOnSeekBarChangeListener(new Seek() {
            @Override
            void changed(int p) {
                fit.zoom = 0.5f + p / 100f;
            }
        });
        sbX.setOnSeekBarChangeListener(new Seek() {
            @Override
            void changed(int p) {
                fit.offX = (p - 100) / 100f;
            }
        });
        sbY.setOnSeekBarChangeListener(new Seek() {
            @Override
            void changed(int p) {
                fit.offY = (p - 100) / 100f;
            }
        });

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        Button rot = ui.button("Rotate 90°", false);
        rot.setOnClickListener(v -> {
            fit.rot = GlVideoRenderer.normRot(fit.rot + 90);
            push();
        });
        row1.addView(rot, lp(0, WRAP, 0, 0, 6, 0));
        ((LinearLayout.LayoutParams) rot.getLayoutParams()).weight = 1f;
        Button shapeBtn = ui.button("Frame: " + SHAPES[shape], false);
        shapeBtn.setOnClickListener(v -> {
            shape = (shape + 1) % SHAPES.length;
            sp.edit().putInt("sim_shape", shape).apply();
            ((Button) v).setText("Frame: " + SHAPES[shape]);
            stage.setLayoutParams(stageParams());
        });
        row1.addView(shapeBtn, lp(0, WRAP, 6, 0, 0, 0));
        ((LinearLayout.LayoutParams) shapeBtn.getLayoutParams()).weight = 1f;
        panel.addView(row1, lp(MATCH, WRAP, 0, 12, 0, 0));

        swMirror = new Switch(this);
        swMirror.setText("Mirror (flip left/right)");
        swMirror.setTextColor(ui.text);
        swMirror.setTextSize(15);
        swMirror.setOnCheckedChangeListener((CompoundButton v, boolean on) -> {
            if (updating) return;
            fit.mirror = on;
            push();
        });
        panel.addView(swMirror, lp(MATCH, WRAP, 4, 14, 4, 0));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        Button auto = ui.button("Auto-fit face", false);
        auto.setOnClickListener(v -> autoFit());
        row2.addView(auto, lp(0, WRAP, 0, 0, 6, 0));
        ((LinearLayout.LayoutParams) auto.getLayoutParams()).weight = 1f;
        Button reset = ui.button("Reset", false);
        reset.setOnClickListener(v -> {
            fit.zoom = 1f;
            fit.offX = 0f;
            fit.offY = 0f;
            syncControls();
            push();
        });
        row2.addView(reset, lp(0, WRAP, 6, 0, 0, 0));
        ((LinearLayout.LayoutParams) reset.getLayoutParams()).weight = 1f;
        panel.addView(row2, lp(MATCH, WRAP, 0, 12, 0, 0));

        Button save = ui.button("Save as final output", true);
        save.setOnClickListener(v -> {
            SharedPreferences.Editor e = sp.edit();
            fit.toPrefs(e);
            e.apply();
            setResult(RESULT_OK);
            finish();
        });
        panel.addView(save, lp(MATCH, WRAP, 0, 16, 0, 0));

        setContentView(root);
    }

    private LinearLayout.LayoutParams stageParams() {
        int screenW = getResources().getDisplayMetrics().widthPixels - ui.dp(32);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        float a = ASPECT[shape];
        int w = screenW;
        int h = Math.round(w / a);
        int maxH = Math.round(screenH * 0.46f);
        if (h > maxH) {
            h = maxH;
            w = Math.round(h * a);
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        return p;
    }

    private SeekBar seek(LinearLayout parent, String title) {
        TextView t = ui.label(title, 13, ui.text, true);
        t.setPadding(0, ui.dp(10), 0, 0);
        parent.addView(t);
        SeekBar s = new SeekBar(this);
        s.setMax(200);
        parent.addView(s, new LinearLayout.LayoutParams(MATCH, WRAP));
        return s;
    }

    private abstract class Seek implements SeekBar.OnSeekBarChangeListener {
        abstract void changed(int p);

        @Override
        public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
            if (!fromUser) return;
            changed(p);
            push();
        }

        @Override
        public void onStartTrackingTouch(SeekBar s) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar s) {
        }
    }

    private void syncControls() {
        updating = true;
        sbZoom.setProgress(Math.round((fit.zoom - 0.5f) * 100f));
        sbX.setProgress(Math.round(fit.offX * 100f) + 100);
        sbY.setProgress(Math.round(fit.offY * 100f) + 100);
        swMirror.setChecked(fit.mirror);
        updating = false;
        updateInfo();
    }

    private void updateInfo() {
        String face = fit.faceSize > 0.01f ? "face found" : "no face found (centred)";
        info.setText("Zoom " + Math.round(fit.zoom * 100) + "%  ·  turn " + fit.rot + "°  ·  " + face);
    }

    private void push() {
        updateInfo();
        if (renderer != null) renderer.setFit(fit);
    }

    // ------------------------------------------------------------------ renderer

    private void startRenderer() {
        stopRenderer();
        if (surfaceTexture == null || !video.isFile()) return;
        surface = new Surface(surfaceTexture);
        renderer = new GlVideoRenderer(surface, video.getAbsolutePath(), true, fit,
                new GlVideoRenderer.Callback() {
                    @Override
                    public void onStatus(final String msg) {
                        runOnUiThread(() -> status.setText("Playing. Drag the sliders until the face sits well in the circle."));
                    }

                    @Override
                    public void onFailed(final String why, boolean wasShowing) {
                        runOnUiThread(() -> status.setText("Simulation could not start: " + why));
                    }
                });
        renderer.start();
        ready = true;
    }

    private void stopRenderer() {
        if (renderer != null) {
            renderer.release();
            renderer = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
        ready = false;
    }

    private void autoFit() {
        if (!video.isFile()) return;
        status.setText("Looking for the face...");
        new Thread(() -> {
            final FaceFit.Result r = FaceFit.detect(video);
            runOnUiThread(() -> {
                fit.faceCx = r.cx;
                fit.faceCy = r.cy;
                fit.faceSize = r.found ? r.size : 0f;
                fit.rot = r.rot;
                fit.zoom = 1f;
                fit.offX = 0f;
                fit.offY = 0f;
                sp.edit().putString("face_note", r.note).apply();
                status.setText(r.note);
                syncControls();
                push();
            });
        }).start();
    }

    @Override
    protected void onDestroy() {
        stopRenderer();
        super.onDestroy();
    }
}
