package dev.local.videoinject;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lists the apps installed on this phone so you tap one instead of typing a package name. */
public class AppPickerActivity extends Activity {

    static final String EXTRA_PKG = "pkg";

    private static final class Item {
        String label;
        String pkg;
        ApplicationInfo ai;
    }

    private static final class Holder {
        ImageView icon;
        TextView name;
        TextView pkg;
    }

    private Ui ui;
    private PackageManager pm;
    private final List<Item> all = new ArrayList<Item>();
    private final List<Item> shown = new ArrayList<Item>();
    private final Map<String, Drawable> icons = new HashMap<String, Drawable>();
    private BaseAdapter adapter;
    private TextView status;
    private EditText search;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Ui(this);
        pm = getPackageManager();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);
        root.setPadding(ui.dp(16), ui.dp(28), ui.dp(16), 0);

        root.addView(ui.label("Choose the app", 26, ui.text, true));
        TextView sub = ui.label("Tap the app that should show your video in its camera preview.",
                14, ui.sub, false);
        sub.setPadding(0, ui.dp(4), 0, ui.dp(14));
        root.addView(sub);

        search = new EditText(this);
        search.setHint("Search apps");
        search.setSingleLine(true);
        search.setTextColor(ui.text);
        search.setHintTextColor(ui.sub);
        search.setTextSize(15);
        search.setBackground(ui.round(ui.card, 12));
        search.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = ui.label("Loading apps...", 12, ui.sub, false);
        status.setPadding(ui.dp(4), ui.dp(10), 0, ui.dp(6));
        root.addView(status);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return shown.size();
            }

            @Override
            public Object getItem(int i) {
                return shown.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                Holder h;
                if (cv == null) {
                    LinearLayout row = new LinearLayout(AppPickerActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(ui.dp(4), ui.dp(9), ui.dp(4), ui.dp(9));

                    h = new Holder();
                    h.icon = new ImageView(AppPickerActivity.this);
                    LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44));
                    il.setMargins(0, 0, ui.dp(14), 0);
                    row.addView(h.icon, il);

                    LinearLayout col = new LinearLayout(AppPickerActivity.this);
                    col.setOrientation(LinearLayout.VERTICAL);
                    h.name = ui.label("", 15, ui.text, true);
                    h.pkg = ui.label("", 12, ui.sub, false);
                    col.addView(h.name);
                    col.addView(h.pkg);
                    row.addView(col, new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    row.setTag(h);
                    cv = row;
                } else {
                    h = (Holder) cv.getTag();
                }
                Item it = shown.get(pos);
                h.name.setText(it.label);
                h.pkg.setText(it.pkg);
                Drawable d = icons.get(it.pkg);
                if (d == null) {
                    d = it.ai.loadIcon(pm);
                    icons.put(it.pkg, d);
                }
                h.icon.setImageDrawable(d);
                return cv;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                Item it = shown.get(position);
                Intent r = new Intent();
                r.putExtra(EXTRA_PKG, it.pkg);
                setResult(RESULT_OK, r);
                finish();
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {}

            public void onTextChanged(CharSequence s, int a, int c, int d) {}

            public void afterTextChanged(Editable e) {
                filter(e.toString());
            }
        });

        setContentView(root);
        loadApps();
    }

    private void loadApps() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Item> tmp = new ArrayList<Item>();
                try {
                    for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                        if (ai.packageName.equals(getPackageName())) continue;
                        boolean systemApp = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                                && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                        if (systemApp && pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
                        Item it = new Item();
                        it.ai = ai;
                        it.pkg = ai.packageName;
                        it.label = String.valueOf(pm.getApplicationLabel(ai));
                        tmp.add(it);
                    }
                } catch (Throwable ignored) {
                }
                Collections.sort(tmp, new Comparator<Item>() {
                    @Override
                    public int compare(Item a, Item c) {
                        return a.label.compareToIgnoreCase(c.label);
                    }
                });
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        all.clear();
                        all.addAll(tmp);
                        filter(search.getText().toString());
                    }
                });
            }
        }).start();
    }

    private void filter(String q) {
        String needle = q.trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (Item it : all) {
            if (needle.isEmpty()
                    || it.label.toLowerCase(Locale.ROOT).contains(needle)
                    || it.pkg.toLowerCase(Locale.ROOT).contains(needle)) {
                shown.add(it);
            }
        }
        adapter.notifyDataSetChanged();
        status.setText(all.isEmpty() ? "Loading apps..." : shown.size() + " apps");
    }
}
