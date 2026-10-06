package dev.local.videoinject;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small helper that builds the app's look (cards, buttons, status rows) in code, light and dark. */
final class Ui {

    final Context ctx;
    final float density;
    final boolean night;

    int bg, card, text, sub, accent, onAccent, ok, warn, bad, tonal;

    Ui(Context c) {
        ctx = c;
        density = c.getResources().getDisplayMetrics().density;
        night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (night) {
            bg = 0xFF111318;
            card = 0xFF1C1F27;
            text = 0xFFE6E8EE;
            sub = 0xFF9AA0AE;
            accent = 0xFF8C9EFF;
            onAccent = 0xFF0B1033;
            ok = 0xFF66BB6A;
            warn = 0xFFFFCA28;
            bad = 0xFFEF5350;
            tonal = 0xFF2A2F44;
        } else {
            bg = 0xFFF5F6FA;
            card = 0xFFFFFFFF;
            text = 0xFF14161C;
            sub = 0xFF5F6575;
            accent = 0xFF3D5AFE;
            onAccent = 0xFFFFFFFF;
            ok = 0xFF2E7D32;
            warn = 0xFFB26A00;
            bad = 0xFFC62828;
            tonal = 0xFFE8EBFF;
        }
    }

    int dp(int v) {
        return Math.round(v * density);
    }

    GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    TextView label(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    LinearLayout card() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(14), dp(16), dp(16));
        l.setBackground(round(card, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12));
        l.setLayoutParams(lp);
        return l;
    }

    Button button(String s, boolean primary) {
        Button b = new Button(ctx);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? onAccent : accent);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(16), dp(13), dp(16), dp(13));
        b.setStateListAnimator(null);
        GradientDrawable base = round(primary ? accent : tonal, 12);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000), base, null));
        return b;
    }

    /** One line of the status card: a coloured dot, a title and a short explanation. */
    final class Row {
        final LinearLayout root;
        private final View dot;
        private final TextView detail;

        Row(String name) {
            root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.TOP);
            root.setPadding(0, dp(12), 0, 0);

            dot = new View(ctx);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(10), dp(10));
            dl.setMargins(0, dp(5), dp(12), 0);
            root.addView(dot, dl);

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(label(name, 14, text, true));
            detail = label("", 12, sub, false);
            col.addView(detail);
            root.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            set(0, "Not checked yet");
        }

        /** level: 0 grey, 1 green, 2 amber, 3 red */
        void set(int level, String msg) {
            int c = level == 1 ? ok : level == 2 ? warn : level == 3 ? bad : sub;
            dot.setBackground(round(c, 8));
            detail.setText(msg);
        }
    }
}
