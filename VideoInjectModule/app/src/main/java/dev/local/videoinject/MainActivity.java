package dev.local.videoinject;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Home screen of the module: choose the app, choose the video, press Apply.
 * Root is used only to copy the video and a small settings file into the chosen app's own data folder,
 * so the chosen app itself never has to be changed.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "config";
    private static final String KEY_TARGET = "target";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_LOOP = "loop";
    private static final String KEY_VIDEO_NAME = "video_name";
    private static final String KEY_VIDEO_INFO = "video_info";
    private static final String KEY_APPLIED = "applied";
    private static final String LOCAL_VIDEO = "video.mp4";

    private static final int REQ_VIDEO = 1;
    private static final int REQ_APP = 2;
    private static final int REQ_SIM = 3;

    private static final int MODE_APPLY = 0;
    private static final int MODE_REMOVE = 1;
    private static final int MODE_CHECK = 2;

    private static final int GRAY = 0;
    private static final int OK = 1;
    private static final int WARN = 2;
    private static final int BAD = 3;

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private SharedPreferences sp;
    private Ui ui;
    private Ui.Row rRoot, rVideo, rApplied, rHook, rCamera;
    private ImageView appIcon, thumb;
    private TextView appName, appPkg, videoName, videoInfo, message, details, fitSummary;
    private boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        ui = new Ui(this);
        build();
        refreshLocal();
        loadThumb();
    }

    // ------------------------------------------------------------------ screen

    private int cv(int v) {
        return v < 0 ? v : ui.dp(v);
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int bm) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(cv(w), cv(h));
        p.setMargins(ui.dp(l), ui.dp(t), ui.dp(r), ui.dp(bm));
        return p;
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(ui.bg);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ui.dp(16), ui.dp(28), ui.dp(16), ui.dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(MATCH, WRAP));

        root.addView(ui.label("Video Inject", 30, ui.text, true));
        TextView sub = ui.label("Show your own video inside another app's camera preview.",
                14, ui.sub, false);
        sub.setPadding(0, ui.dp(4), 0, ui.dp(18));
        root.addView(sub);

        // ---- status card
        LinearLayout st = ui.card();
        st.addView(ui.label("Status", 16, ui.text, true));
        rRoot = ui.new Row("Root access");
        rVideo = ui.new Row("Video chosen");
        rApplied = ui.new Row("Video inside the app");
        rHook = ui.new Row("LSPosed hook");
        rCamera = ui.new Row("Camera preview");
        st.addView(rRoot.root);
        st.addView(rVideo.root);
        st.addView(rApplied.root);
        st.addView(rHook.root);
        st.addView(rCamera.root);
        Button check = ui.button("Check status", false);
        check.setOnClickListener(v -> runRoot(MODE_CHECK));
        st.addView(check, lp(MATCH, WRAP, 0, 16, 0, 0));
        root.addView(st);

        // ---- 1. app
        LinearLayout c1 = ui.card();
        c1.addView(ui.label("1   Choose the app", 16, ui.text, true));
        LinearLayout appRow = new LinearLayout(this);
        appRow.setOrientation(LinearLayout.HORIZONTAL);
        appRow.setGravity(Gravity.CENTER_VERTICAL);
        appRow.setPadding(0, ui.dp(12), 0, 0);
        appIcon = new ImageView(this);
        appRow.addView(appIcon, lp(48, 48, 0, 0, 14, 0));
        LinearLayout appCol = new LinearLayout(this);
        appCol.setOrientation(LinearLayout.VERTICAL);
        appName = ui.label("", 16, ui.text, true);
        appPkg = ui.label("", 12, ui.sub, false);
        appCol.addView(appName);
        appCol.addView(appPkg);
        appRow.addView(appCol, new LinearLayout.LayoutParams(0, WRAP, 1f));
        c1.addView(appRow);
        Button pickApp = ui.button("Choose app", false);
        pickApp.setOnClickListener(v -> startActivityForResult(
                new Intent(MainActivity.this, AppPickerActivity.class), REQ_APP));
        c1.addView(pickApp, lp(MATCH, WRAP, 0, 14, 0, 0));
        root.addView(c1);

        // ---- 2. video
        LinearLayout c2 = ui.card();
        c2.addView(ui.label("2   Choose the video", 16, ui.text, true));
        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(ui.round(0xFF000000, 12));
        thumb.setClipToOutline(true);
        thumb.setVisibility(View.GONE);
        c2.addView(thumb, lp(MATCH, 170, 0, 12, 0, 0));
        videoName = ui.label("", 15, ui.text, true);
        videoName.setPadding(0, ui.dp(12), 0, 0);
        videoInfo = ui.label("", 12, ui.sub, false);
        c2.addView(videoName);
        c2.addView(videoInfo);
        Button pickVideo = ui.button("Choose video", false);
        pickVideo.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("video/*");
            startActivityForResult(i, REQ_VIDEO);
        });
        c2.addView(pickVideo, lp(MATCH, WRAP, 0, 14, 0, 0));
        root.addView(c2);

        // ---- 3. fit in the circle
        LinearLayout cf = ui.card();
        cf.addView(ui.label("3   Fit in the circle", 16, ui.text, true));
        fitSummary = ui.label("", 12, ui.sub, false);
        fitSummary.setPadding(0, ui.dp(4), 0, 0);
        cf.addView(fitSummary);
        Button openSim = ui.button("Open simulation", true);
        openSim.setOnClickListener(v -> {
            if (!new File(getFilesDir(), LOCAL_VIDEO).isFile()) {
                message.setText("Choose a video first (step 2).");
                return;
            }
            startActivityForResult(new Intent(MainActivity.this, SimulationActivity.class), REQ_SIM);
        });
        cf.addView(openSim, lp(MATCH, WRAP, 0, 14, 0, 0));
        Button autoFit = ui.button("Auto-fit face again", false);
        autoFit.setOnClickListener(v -> runFaceDetect(false));
        cf.addView(autoFit, lp(MATCH, WRAP, 0, 10, 0, 0));
        root.addView(cf);

        // ---- 4. options
        LinearLayout c3 = ui.card();
        c3.addView(ui.label("4   Options", 16, ui.text, true));
        Switch swEnabled = new Switch(this);
        swEnabled.setText("Play the video in the camera preview");
        swEnabled.setTextColor(ui.text);
        swEnabled.setTextSize(15);
        swEnabled.setChecked(sp.getBoolean(KEY_ENABLED, true));
        swEnabled.setOnCheckedChangeListener((CompoundButton v, boolean on) -> {
            sp.edit().putBoolean(KEY_ENABLED, on).apply();
            reapplyIfNeeded();
        });
        c3.addView(swEnabled, lp(MATCH, WRAP, 0, 10, 0, 0));
        Switch swLoop = new Switch(this);
        swLoop.setText("Repeat the video (loop)");
        swLoop.setTextColor(ui.text);
        swLoop.setTextSize(15);
        swLoop.setChecked(sp.getBoolean(KEY_LOOP, true));
        swLoop.setOnCheckedChangeListener((CompoundButton v, boolean on) -> {
            sp.edit().putBoolean(KEY_LOOP, on).apply();
            reapplyIfNeeded();
        });
        c3.addView(swLoop, lp(MATCH, WRAP, 0, 12, 0, 0));
        root.addView(c3);

        // ---- 4. apply
        LinearLayout c4 = ui.card();
        c4.addView(ui.label("5   Apply", 16, ui.text, true));
        TextView hint = ui.label("Copies the video into the chosen app. A root popup will appear. Tap Allow.",
                12, ui.sub, false);
        hint.setPadding(0, ui.dp(4), 0, 0);
        c4.addView(hint);
        Button apply = ui.button("Apply to app", true);
        apply.setOnClickListener(v -> runRoot(MODE_APPLY));
        c4.addView(apply, lp(MATCH, WRAP, 0, 14, 0, 0));
        Button remove = ui.button("Remove video from app", false);
        remove.setOnClickListener(v -> runRoot(MODE_REMOVE));
        c4.addView(remove, lp(MATCH, WRAP, 0, 10, 0, 0));
        root.addView(c4);

        // ---- messages
        message = ui.label("", 14, ui.text, true);
        message.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), 0);
        root.addView(message);

        final TextView toggle = ui.label("Show technical details", 13, ui.accent, true);
        toggle.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(4));
        details = ui.label("", 11, ui.sub, false);
        details.setTypeface(Typeface.MONOSPACE);
        details.setTextIsSelectable(true);
        details.setVisibility(View.GONE);
        details.setPadding(ui.dp(4), 0, ui.dp(4), 0);
        toggle.setOnClickListener(v -> {
            boolean show = details.getVisibility() != View.VISIBLE;
            details.setVisibility(show ? View.VISIBLE : View.GONE);
            toggle.setText(show ? "Hide technical details" : "Show technical details");
        });
        root.addView(toggle);
        root.addView(details);

        TextView foot = ui.label("Needs a rooted phone with LSPosed. In LSPosed: enable Video Inject and tick "
                + "only the app you chose. Then open that app once.", 12, ui.sub, false);
        foot.setPadding(ui.dp(4), ui.dp(18), ui.dp(4), 0);
        root.addView(foot);

        setContentView(scroll);
    }

    // ------------------------------------------------------------------ local state

    private String formatSize(long bytes) {
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private String formatDuration(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    private void resetStatus() {
        rRoot.set(GRAY, "Not checked yet");
        rApplied.set(GRAY, "Not checked yet");
        rHook.set(GRAY, "Not checked yet");
        rCamera.set(GRAY, "Not checked yet");
        message.setText("");
        details.setText("");
    }

    private void refreshLocal() {
        String pkg = sp.getString(KEY_TARGET, "");
        PackageManager pm = getPackageManager();
        if (pkg.isEmpty()) {
            appName.setText("No app chosen yet");
            appPkg.setText("Tap Choose app");
            appIcon.setImageDrawable(null);
        } else {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                appName.setText(pm.getApplicationLabel(ai));
                appPkg.setText(pkg);
                appIcon.setImageDrawable(pm.getApplicationIcon(ai));
            } catch (PackageManager.NameNotFoundException e) {
                appName.setText(pkg);
                appPkg.setText("Android cannot see this app. Choose it again.");
                appIcon.setImageDrawable(null);
            }
        }

        updateFitSummary();
        File f = new File(getFilesDir(), LOCAL_VIDEO);
        if (f.isFile()) {
            String name = sp.getString(KEY_VIDEO_NAME, "video.mp4");
            String info = sp.getString(KEY_VIDEO_INFO, formatSize(f.length()));
            videoName.setText(name);
            videoInfo.setText(info);
            rVideo.set(OK, name + "  (" + info + ")");
        } else {
            videoName.setText("No video chosen yet");
            videoInfo.setText("Tap Choose video");
            rVideo.set(GRAY, "Not chosen yet");
            thumb.setVisibility(View.GONE);
        }
    }

    private void updateFitSummary() {
        GlVideoRenderer.Fit f = GlVideoRenderer.Fit.fromPrefs(sp);
        String note = sp.getString("face_note", "");
        String line = "Zoom " + Math.round(f.zoom * 100) + "%, turn " + f.rot + "\u00B0"
                + (f.mirror ? ", mirrored" : "");
        fitSummary.setText((note.isEmpty() ? "Open the simulation to place the face in the circle." : note)
                + "\n" + line);
    }

    /** Finds the face in the chosen video and stores the automatic fit. */
    private void runFaceDetect(final boolean quiet) {
        final File f = new File(getFilesDir(), LOCAL_VIDEO);
        if (!f.isFile()) {
            message.setText("Choose a video first (step 2).");
            return;
        }
        if (!quiet) message.setText("Looking for the face...");
        new Thread(() -> {
            final FaceFit.Result r = FaceFit.detect(f);
            runOnUiThread(() -> {
                GlVideoRenderer.Fit fit = GlVideoRenderer.Fit.fromPrefs(sp);
                fit.faceCx = r.cx;
                fit.faceCy = r.cy;
                fit.faceSize = r.found ? r.size : 0f;
                fit.rot = r.rot;
                fit.zoom = 1f;
                fit.offX = 0f;
                fit.offY = 0f;
                SharedPreferences.Editor e = sp.edit();
                fit.toPrefs(e);
                e.putString("face_note", r.note).apply();
                updateFitSummary();
                message.setText(r.note + " Open the simulation to check it.");
                reapplyIfNeeded();
            });
        }).start();
    }

    private void loadThumb() {
        final File f = new File(getFilesDir(), LOCAL_VIDEO);
        if (!f.isFile()) return;
        new Thread(() -> {
            final Bitmap bmp = makeThumb(f);
            runOnUiThread(() -> {
                if (bmp != null) {
                    thumb.setImageBitmap(bmp);
                    thumb.setVisibility(View.VISIBLE);
                }
            });
        }).start();
    }

    private Bitmap makeThumb(File f) {
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try {
            m.setDataSource(f.getAbsolutePath());
            Bitmap b = m.getFrameAtTime(0);
            if (b == null) return null;
            int h = ui.dp(170);
            int w = Math.max(1, Math.round(b.getWidth() * (h / (float) b.getHeight())));
            return Bitmap.createScaledBitmap(b, w, h, true);
        } catch (Exception e) {
            return null;
        } finally {
            try {
                m.release();
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ results from pickers

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SIM) {
            if (res == RESULT_OK) {
                updateFitSummary();
                message.setText("Fit saved.");
                reapplyIfNeeded();
            }
            return;
        }
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_VIDEO && data.getData() != null) {
            importVideo(data.getData());
        } else if (req == REQ_APP) {
            String p = data.getStringExtra(AppPickerActivity.EXTRA_PKG);
            if (p != null && !p.isEmpty()) {
                sp.edit().putString(KEY_TARGET, p).putBoolean(KEY_APPLIED, false).apply();
                resetStatus();
                refreshLocal();
                message.setText("App chosen. Next: choose a video, then tap Apply to app.");
            }
        }
    }

    private void importVideo(final Uri uri) {
        message.setText("Copying video...");
        new Thread(() -> {
            String name = "video.mp4";
            Cursor c = null;
            try {
                c = getContentResolver().query(uri, null, null, null, null);
                if (c != null && c.moveToFirst()) {
                    int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (i >= 0 && c.getString(i) != null) name = c.getString(i);
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.close();
            }

            File dst = new File(getFilesDir(), LOCAL_VIDEO);
            String err = null;
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new IllegalStateException("could not open the video");
                OutputStream out = new FileOutputStream(dst);
                try {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } finally {
                    in.close();
                    out.close();
                }
            } catch (Exception e) {
                err = String.valueOf(e.getMessage());
            }

            String info = formatSize(dst.length());
            Bitmap bmp = null;
            if (err == null) {
                MediaMetadataRetriever m = new MediaMetadataRetriever();
                try {
                    m.setDataSource(dst.getAbsolutePath());
                    String dur = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (dur != null) info = info + "  ·  " + formatDuration(Long.parseLong(dur));
                } catch (Exception ignored) {
                } finally {
                    try {
                        m.release();
                    } catch (Exception ignored) {
                    }
                }
                bmp = makeThumb(dst);
            }

            final String fName = name;
            final String fInfo = info;
            final String fErr = err;
            final Bitmap fBmp = bmp;
            runOnUiThread(() -> {
                if (fErr != null) {
                    message.setText("Could not copy the video: " + fErr);
                    return;
                }
                sp.edit().putString(KEY_VIDEO_NAME, fName).putString(KEY_VIDEO_INFO, fInfo)
                        .putBoolean(KEY_APPLIED, false).apply();
                refreshLocal();
                if (fBmp != null) {
                    thumb.setImageBitmap(fBmp);
                    thumb.setVisibility(View.VISIBLE);
                }
                rApplied.set(GRAY, "Not applied yet. Tap Apply to app.");
                message.setText("Video ready.");
                runFaceDetect(true);
            });
        }).start();
    }

    // ------------------------------------------------------------------ root work

    private void reapplyIfNeeded() {
        if (sp.getBoolean(KEY_APPLIED, false)) runRoot(MODE_APPLY);
    }

    private String dataDirOf(String pkg) {
        try {
            String d = getPackageManager().getApplicationInfo(pkg, 0).dataDir;
            if (d != null && !d.isEmpty()) return d;
        } catch (Exception ignored) {
        }
        return "/data/user/0/" + pkg;
    }

    /** Shell script that runs as root. Every mode ends with the same report lines. */
    private String buildScript(String pkg, String dataDir, int mode, File src, boolean enabled, boolean loop,
                               GlVideoRenderer.Fit fit) {
        int user = android.os.Process.myUid() / 100000;
        String[] cands = {
                dataDir,
                "/data/user/" + user + "/" + pkg,
                "/data/data/" + pkg,
                "/data_mirror/data_ce/null/" + user + "/" + pkg
        };
        StringBuilder s = new StringBuilder();
        s.append("echo @@RAN@@\n");
        s.append("echo \"root: $(id)\"\n");
        s.append("B=''\n");
        for (String c : cands) {
            s.append("[ -z \"$B\" ] && [ -d '").append(c).append("' ] && B='").append(c).append("'\n");
        }
        s.append("if [ -z \"$B\" ]; then echo @@NODIR@@; ls -ld /data/data /data/user/").append(user)
                .append(" /data_mirror/data_ce/null/").append(user).append(" 2>&1; ls /data/user 2>&1; exit; fi\n");
        s.append("D=\"$B/files\"\n");
        s.append("echo \"app folder: $B\"\n");
        if (mode == MODE_REMOVE) {
            s.append("rm -f \"$D/vinject.mp4\" \"$D/vinject.cfg\" \"$D/vinject.status\" \"$D/vinject.loaded\"\n");
        } else if (mode == MODE_APPLY) {
            s.append("mkdir -p \"$D\"\n");
            s.append("U=$(stat -c %u \"$B\" 2>/dev/null)\n");
            s.append("cp '").append(src.getAbsolutePath())
                    .append("' \"$D/vinject.mp4\" || { echo @@COPYFAIL@@; exit; }\n");
            s.append(": > \"$D/vinject.cfg\"\n");
            String cfgText = "enabled=" + (enabled ? 1 : 0) + "\nloop=" + (loop ? 1 : 0) + "\ngl=1\n" + fit.toCfg();
            for (String ln : cfgText.split("\n")) {
                s.append("echo '").append(ln).append("' >> \"$D/vinject.cfg\"\n");
            }
            s.append("[ -n \"$U\" ] && chown $U:$U \"$D\" \"$D/vinject.mp4\" \"$D/vinject.cfg\"\n");
            s.append("chmod 771 \"$D\"; chmod 666 \"$D/vinject.mp4\" \"$D/vinject.cfg\"\n");
            s.append("chcon --reference=\"$B\" \"$D\" \"$D/vinject.mp4\" \"$D/vinject.cfg\" 2>/dev/null"
                    + " || restorecon -R \"$D\" 2>/dev/null\n");
            s.append("ls -lZ \"$D\" 2>&1\n");
        }
        s.append("if [ -s \"$D/vinject.mp4\" ]; then echo \"@@VIDEO=$(wc -c < \"$D/vinject.mp4\") bytes\";"
                + " else echo @@VIDEO=no; fi\n");
        s.append("echo \"@@LOADED=$(cat \"$D/vinject.loaded\" 2>/dev/null)\"\n");
        s.append("echo \"@@EVENT=$(cat \"$D/vinject.status\" 2>/dev/null)\"\n");
        s.append("echo @@DONE@@\n");
        s.append("exit\n");
        return s.toString();
    }

    private static String exec(String[] cmd, String script) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        OutputStream os = p.getOutputStream();
        os.write(script.getBytes("UTF-8"));
        os.flush();
        os.close();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        p.waitFor();
        return sb.toString();
    }

    /** Tries root in the global mount namespace first (so other apps' folders are visible), then plain su. */
    private static String runAsRoot(String script) throws Exception {
        String[][] attempts = {{"su", "-M"}, {"su", "-mm"}, {"su"}};
        String last = "";
        Exception lastEx = null;
        for (String[] a : attempts) {
            try {
                String out = exec(a, script);
                if (out.contains("@@RAN@@")) return out;
                last = out;
            } catch (Exception e) {
                lastEx = e;
            }
        }
        if (last.isEmpty() && lastEx != null) throw lastEx;
        return last;
    }

    private void runRoot(final int mode) {
        if (busy) return;
        final String pkg = sp.getString(KEY_TARGET, "");
        if (pkg.isEmpty()) {
            message.setText("Choose the app first (step 1).");
            return;
        }
        final File src = new File(getFilesDir(), LOCAL_VIDEO);
        if (mode == MODE_APPLY && !src.isFile()) {
            message.setText("Choose a video first (step 2).");
            return;
        }
        final String script = buildScript(pkg, dataDirOf(pkg), mode, src,
                sp.getBoolean(KEY_ENABLED, true), sp.getBoolean(KEY_LOOP, true),
                GlVideoRenderer.Fit.fromPrefs(sp));
        busy = true;
        message.setText("Working... if a root popup appears, tap Allow.");
        new Thread(() -> {
            String out = "";
            String err = null;
            try {
                out = runAsRoot(script);
            } catch (Exception e) {
                err = String.valueOf(e);
            }
            final String fOut = out;
            final String fErr = err;
            runOnUiThread(() -> {
                busy = false;
                handleResult(mode, fOut, fErr);
            });
        }).start();
    }

    private void handleResult(int mode, String out, String err) {
        details.setText(out + (err != null ? "\n" + err : ""));

        if (!out.contains("@@RAN@@")) {
            rRoot.set(BAD, "Root was not granted. Open Magisk or KernelSU, go to the Superuser list, "
                    + "allow Video Inject, then tap Check status.");
            message.setText("Could not get root permission.");
            return;
        }
        rRoot.set(OK, "Granted");

        if (out.contains("@@NODIR@@")) {
            rApplied.set(BAD, "Root works, but the app's data folder was not found. "
                    + "Open technical details below and send a screenshot.");
            message.setText("Root works, but this app's folder was not found.");
            details.setVisibility(View.VISIBLE);
            return;
        }
        if (out.contains("@@COPYFAIL@@")) {
            rApplied.set(BAD, "Copying the video into the app failed. Open technical details and send a screenshot.");
            message.setText("Copying the video failed.");
            details.setVisibility(View.VISIBLE);
            return;
        }

        String video = null, loaded = null, event = null;
        for (String line : out.split("\n")) {
            if (line.startsWith("@@VIDEO=")) video = line.substring(8).trim();
            else if (line.startsWith("@@LOADED=")) loaded = line.substring(9).trim();
            else if (line.startsWith("@@EVENT=")) event = line.substring(8).trim();
        }

        boolean inApp = video != null && !video.equals("no");
        if (inApp) {
            rApplied.set(OK, "Yes (" + video + ")");
        } else {
            rApplied.set(WARN, "Not in the app yet. Tap Apply to app.");
        }

        if (loaded != null && !loaded.isEmpty()) {
            rHook.set(OK, "LSPosed loaded the module in this app (" + loaded + ")");
        } else {
            rHook.set(WARN, "Not seen yet. In LSPosed enable Video Inject and tick this app, "
                    + "then open the app once and tap Check status.");
        }

        if (event != null && !event.isEmpty()) {
            int level = event.contains("ERROR") ? BAD : event.contains("SKIP") ? WARN : OK;
            rCamera.set(level, event);
        } else {
            rCamera.set(GRAY, "No camera use seen yet. Open the app's camera screen, then tap Check status.");
        }

        if (mode == MODE_APPLY) {
            sp.edit().putBoolean(KEY_APPLIED, inApp).apply();
            message.setText(inApp
                    ? "Done. Now open the app's camera screen. If the app was already open, swipe it away first."
                    : "Applied, but the video was not found in the app. Open technical details.");
        } else if (mode == MODE_REMOVE) {
            sp.edit().putBoolean(KEY_APPLIED, false).apply();
            message.setText("Removed from the app.");
        } else {
            message.setText("Status updated.");
        }
    }
}
