package dev.local.videoinject;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.media.FaceDetector;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Looks at a few pictures of the video, finds the face and the way the picture must be turned so the
 * face is upright. Uses Android's built-in FaceDetector (no download, works offline). It only finds
 * faces that look roughly towards the camera; if none is found the caller keeps a sensible default.
 */
final class FaceFit {

    static final class Result {
        boolean found;
        float cx = 0.5f, cy = 0.38f, size = 0f;
        int rot = 0;
        String note = "";
    }

    private static final double[] SPOTS = {0.15, 0.45, 0.75};
    private static final int[] ROTS = {0, 90, 270, 180};

    private FaceFit() {}

    private static final class Hit {
        float cx, cy, size, conf;
        int rot;
    }

    static Result detect(File video) {
        Result res = new Result();
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        List<Hit> hits = new ArrayList<>();
        try {
            m.setDataSource(video.getAbsolutePath());
            String d = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durMs = 0;
            try {
                durMs = Long.parseLong(d);
            } catch (Exception ignored) {
            }
            for (double spot : SPOTS) {
                Bitmap frame = m.getFrameAtTime((long) (spot * durMs * 1000),
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame == null) continue;
                Hit best = bestInFrame(frame);
                frame.recycle();
                if (best != null) hits.add(best);
            }
        } catch (Throwable t) {
            res.note = "Could not read the video for face search (" + t.getMessage() + ")";
            return res;
        } finally {
            try {
                m.release();
            } catch (Throwable ignored) {
            }
        }

        if (hits.isEmpty()) {
            res.note = "No face found automatically. The picture is centred; adjust it in the simulation.";
            return res;
        }

        Hit top = hits.get(0);
        for (Hit h : hits) if (h.conf > top.conf) top = h;
        float sx = 0, sy = 0, ss = 0;
        int n = 0;
        for (Hit h : hits) {
            if (h.rot != top.rot) continue;
            sx += h.cx;
            sy += h.cy;
            ss += h.size;
            n++;
        }
        res.found = true;
        res.cx = sx / n;
        res.cy = sy / n;
        res.size = ss / n;
        res.rot = top.rot;
        res.note = "Face found" + (top.rot != 0 ? " (picture turned " + top.rot + "°)" : "")
                + ". Auto-fit applied.";
        return res;
    }

    private static Hit bestInFrame(Bitmap frame) {
        int fw = frame.getWidth();
        int fh = frame.getHeight();
        float scale = Math.min(1f, 480f / Math.max(fw, fh));
        int bw = Math.max(2, Math.round(fw * scale));
        int bh = Math.max(2, Math.round(fh * scale));
        Bitmap base = Bitmap.createScaledBitmap(frame, bw, bh, true);

        Hit best = null;
        for (int rot : ROTS) {
            Bitmap r = base;
            if (rot != 0) {
                Matrix mx = new Matrix();
                mx.postRotate(rot);
                r = Bitmap.createBitmap(base, 0, 0, base.getWidth(), base.getHeight(), mx, true);
            }
            int w = r.getWidth() & ~1;
            int h = r.getHeight();
            Bitmap rgb = Bitmap.createBitmap(r, 0, 0, w, h).copy(Bitmap.Config.RGB_565, false);
            if (r != base) r.recycle();
            if (rgb == null) continue;
            try {
                FaceDetector fd = new FaceDetector(w, h, 1);
                FaceDetector.Face[] faces = new FaceDetector.Face[1];
                int n = fd.findFaces(rgb, faces);
                if (n > 0 && faces[0] != null) {
                    PointF mid = new PointF();
                    faces[0].getMidPoint(mid);
                    float eyes = faces[0].eyesDistance();
                    float conf = faces[0].confidence();
                    // prefer the picture as it is unless another turn is clearly better
                    float bonus = rot == 0 ? 0.08f : 0f;
                    if (best == null || conf + bonus > best.conf) {
                        Hit hit = new Hit();
                        hit.rot = rot;
                        hit.conf = conf + bonus;
                        hit.cx = mid.x / w;
                        hit.cy = (mid.y + eyes * 0.35f) / h;
                        hit.size = Math.min(1f, eyes * 2.4f / w);
                        best = hit;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                rgb.recycle();
            }
        }
        base.recycle();
        return best != null && best.conf >= 0.3f ? best : null;
    }
}
