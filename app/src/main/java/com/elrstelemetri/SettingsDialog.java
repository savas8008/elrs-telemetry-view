package com.elrstelemetri;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Full-screen settings: alarms, map and offline maps, logging, alerts, connection. */
public class SettingsDialog {

    public interface Host {
        Settings settings();

        GcsMap map();

        /** Called after settings were saved. */
        void onSettingsSaved(int oldPort);

        void showRawData();

        /** Discards unsaved changes by reloading the stored settings. */
        void reloadSettings();

        /** Goggles USB connection status. */
        String usbStatus();

        /** Connects (or reconnects) the goggles over USB. */
        void usbReconnect();

        /** Starts an offline download of the visible map area. Returns an error message or null. */
        String startOfflineDownload(int maxZoom);

        /** Current offline download status, or empty. */
        String downloadStatus();
    }

    private static final int C_BG = Color.rgb(16, 19, 24);
    private static final int C_CARD = Color.rgb(28, 32, 40);
    private static final int C_TEXT = Color.rgb(235, 238, 242);
    private static final int C_LABEL = Color.rgb(150, 158, 172);
    private static final int C_ACCENT = Color.rgb(0, 200, 255);

    private final Context ctx;
    private final Host host;
    private final Settings s;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<Runnable> savers = new ArrayList<>();
    private Dialog dialog;
    private TextView downloadInfo;
    private int offlineZoom;

    public SettingsDialog(Context ctx, Host host) {
        this.ctx = ctx;
        this.host = host;
        this.s = host.settings();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }

    public Dialog show() {
        dialog = new Dialog(ctx, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        // ---- Header
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(8), dp(12), dp(8));
        TextView title = new TextView(ctx);
        title.setText("Settings");
        title.setTextSize(20);
        title.setTextColor(C_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(button("Reset alarms", false, v -> {
            s.alarms.resetToDefaults();
            dialog.dismiss();
            new SettingsDialog(ctx, host).show();
            Toast.makeText(ctx, "Alarm defaults restored (not saved yet)", Toast.LENGTH_SHORT).show();
        }));
        header.addView(button("Cancel", false, v -> {
            host.reloadSettings();
            dialog.dismiss();
        }));
        header.addView(button("Save", true, v -> save()));
        root.addView(header);

        // ---- Two columns
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout cols = new LinearLayout(ctx);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        cols.setPadding(dp(10), 0, dp(10), dp(16));
        scroll.addView(cols);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout left = column(cols);
        LinearLayout right = column(cols);

        buildAlarms(left);
        buildMap(right);
        buildLogging(right);
        buildAlerts(right);
        buildVideo(right);
        buildConnection(right);

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        dialog.setOnDismissListener(d -> ui.removeCallbacksAndMessages(null));
        dialog.show();
        ui.post(statusTick);
        return dialog;
    }

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            if (downloadInfo != null) {
                String st = host.downloadStatus();
                downloadInfo.setText(st.isEmpty() ? estimateText() : st + "\n" + estimateText());
            }
            ui.postDelayed(this, 700);
        }
    };

    // ---------------------------------------------------------------- Sections

    private void buildAlarms(LinearLayout col) {
        LinearLayout card = card(col, "ALARMS");
        hint(card, "Alarms are shown in red on screen, spoken and vibrate. Minimum altitude and minimum speed only "
                + "arm after the value has been above the threshold in flight, so they stay quiet on the ground.");
        for (AlarmSet.Alarm a : s.alarms.all()) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(2), 0, dp(2));

            Switch sw = new Switch(ctx);
            sw.setText(a.title);
            sw.setTextColor(C_TEXT);
            sw.setTextSize(14);
            sw.setChecked(a.enabled);
            row.addView(sw, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView cmp = label(a.above ? ">" : "<");
            cmp.setPadding(dp(8), 0, dp(4), 0);
            row.addView(cmp);

            EditText e = number(a.format(a.threshold), a.decimal, a.threshold < 0 || a.id.equals(AlarmSet.LOW_RSSI));
            row.addView(e, new LinearLayout.LayoutParams(dp(78), LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView unit = label(a.unit);
            unit.setMinWidth(dp(42));
            unit.setPadding(dp(4), 0, 0, 0);
            row.addView(unit);
            card.addView(row);

            savers.add(() -> {
                a.enabled = sw.isChecked();
                a.threshold = parse(e, a.threshold, a.title);
            });
        }
    }

    private void buildMap(LinearLayout col) {
        LinearLayout card = card(col, "MAP & OFFLINE MAPS");
        RadioGroup layers = radio(card, Settings.MAP_NAMES, s.mapType, false);
        savers.add(() -> s.mapType = checkedIndex(layers, s.mapType));

        hint(card, "The backpack WiFi has no internet. Before flying, while you still have internet: pan and zoom "
                + "the map to your flying field, then download it here. Downloaded tiles are used offline.");
        TextView dl = label("Detail level (max zoom)");
        card.addView(dl);
        final int[] zooms = {15, 16, 17, 18};
        int idx = 2;
        for (int i = 0; i < zooms.length; i++) if (zooms[i] == s.offlineMaxZoom) idx = i;
        offlineZoom = zooms[idx];
        RadioGroup zg = radio(card, new String[]{"15", "16", "17", "18"}, idx, true);
        zg.setOnCheckedChangeListener((g, id) -> offlineZoom = zooms[checkedIndex(zg, 2)]);
        savers.add(() -> s.offlineMaxZoom = offlineZoom);

        downloadInfo = label(estimateText());
        downloadInfo.setSingleLine(false);
        card.addView(downloadInfo);

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.addView(button("Download visible area", true, v -> {
            int layer = checkedIndex(layers, s.mapType);
            if (!GcsMap.supportsOffline(layer)) {
                Toast.makeText(ctx, "OpenStreetMap does not allow bulk downloads. Choose Satellite, Topographic or Street.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (layer != s.mapType) {
                s.mapType = layer;
                host.map().setMapType(layer);
            }
            String err = host.startOfflineDownload(offlineZoom);
            if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_LONG).show();
        }));
        btns.addView(button("Cancel download", false, v -> host.map().cancelDownload()));
        card.addView(btns);
        long mb = GcsMap.cacheSizeBytes(ctx) / (1024 * 1024);
        hint(card, "Offline cache on this device: " + mb + " MB (up to 2 GB). To delete it: Android Settings › Apps › "
                + "ELRS Telemetry › Storage › Clear storage.");
    }

    private String estimateText() {
        int n = host.map().tilesForVisibleArea(offlineZoom);
        if (n < 0) return "Visible area: tile count unavailable for this layer";
        String size = String.format(Locale.US, "%.1f MB", n * (double) GcsMap.APPROX_TILE_KB / 1024);
        String s1 = "Visible area, zoom " + Math.min(host.map().currentZoom(), offlineZoom) + "–" + offlineZoom
                + ": " + n + " tiles ≈ " + size;
        if (n > GcsMap.MAX_DOWNLOAD_TILES) s1 += "  (too large: zoom in or choose a lower detail level)";
        return s1;
    }

    private void buildLogging(LinearLayout col) {
        LinearLayout card = card(col, "FLIGHT LOG");
        Switch auto = sw(card, "Record automatically when telemetry arrives", s.autoRecord);
        TextView l = label("Log rate");
        card.addView(l);
        final int[] hz = {1, 2, 5, 10};
        int idx = 2;
        for (int i = 0; i < hz.length; i++) if (hz[i] == s.recordHz) idx = i;
        RadioGroup g = radio(card, new String[]{"1 Hz", "2 Hz", "5 Hz", "10 Hz"}, idx, true);
        hint(card, "CSV files are saved to Downloads/" + FlightLogger.FOLDER + ".");
        savers.add(() -> {
            s.autoRecord = auto.isChecked();
            s.recordHz = hz[checkedIndex(g, 2)];
        });
    }

    private void buildAlerts(LinearLayout col) {
        LinearLayout card = card(col, "ALERTS");
        Switch voice = sw(card, "Spoken alarms", s.voice);
        Switch vib = sw(card, "Vibrate on alarm", s.vibrate);
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = label("Repeat an active alarm every");
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        EditText rep = number(String.valueOf(s.alarmRepeatSec), false, false);
        row.addView(rep, new LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(label("s"));
        card.addView(row);
        hint(card, "Tap the alarm banner on the main screen to mute alarms for 2 minutes.");
        savers.add(() -> {
            s.voice = voice.isChecked();
            s.vibrate = vib.isChecked();
            s.alarmRepeatSec = (int) Math.max(3, parse(rep, s.alarmRepeatSec, "Repeat interval"));
        });
    }

    private void buildConnection(LinearLayout col) {
        LinearLayout card = card(col, "CONNECTION");
        hint(card, "Connect this device to the WiFi network \"ExpressLRS TX Backpack XXXXXX\" (password: expresslrs). "
                + "On the radio: ELRS Lua › Backpack › Telemetry = WiFi, Link Mode = Normal. "
                + "Requires TX Backpack firmware 1.5.8 or newer.");
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label("UDP port"), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        EditText port = number(String.valueOf(s.port), false, false);
        row.addView(port, new LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(row);
        card.addView(button("Show raw UDP data", false, v -> host.showRawData()));
        savers.add(() -> {
            int p = (int) parse(port, s.port, "UDP port");
            if (p >= 1 && p <= 65535) s.port = p;
        });
    }

    private void buildVideo(LinearLayout col) {
        LinearLayout card = card(col, "GOGGLES VIDEO (USB)");
        hint(card, "Connect DJI Goggles N3 to this device with a USB-C data cable while the goggles are linked to "
                + "the aircraft. When Android asks which app to open for the DJI accessory, choose ELRS Telemetry. "
                + "Unofficial: based on the open-source DJI Unchained VOC project; may stop working after DJI "
                + "firmware updates.");
        TextView usbState = label("Status: " + host.usbStatus());
        usbState.setTextColor(C_LABEL);
        usbState.setTextSize(12);
        usbState.setSingleLine(false);
        card.addView(usbState);
        card.addView(button("Connect", true, v -> {
            host.usbReconnect();
            usbState.setText("Status: " + host.usbStatus());
        }));

        card.addView(label("Display"));
        RadioGroup mode = radio(card, new String[]{
                "Hidden",
                "Floating panel — drag to move, drag the ◢ corner to resize",
                "Replace the map — video fills the screen, telemetry on top"}, s.videoMode, false);
        card.addView(label("Panel size"));
        final int[] preset = {-1};
        TextView presetInfo = label("Current: " + describeGeometry());
        presetInfo.setTextColor(C_LABEL);
        presetInfo.setTextSize(12);
        LinearLayout sizes = new LinearLayout(ctx);
        sizes.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < Settings.VIDEO_PRESETS.length; i++) {
            final int idx = i;
            int[] p = Settings.VIDEO_PRESETS[i];
            sizes.addView(button(Settings.VIDEO_PRESET_NAMES[i] + " " + p[0] + "×" + p[1], false, v -> {
                preset[0] = idx;
                presetInfo.setText("Selected: " + Settings.VIDEO_PRESET_NAMES[idx]
                        + " (placed above the buttons; drag it afterwards to move or resize)");
            }));
        }
        card.addView(sizes);
        card.addView(presetInfo);

        Switch keep = sw(card, "Keep running in the background", s.keepRunning);
        hint(card, "Keeps video, telemetry, spoken alarms and logging running while the screen is off or another "
                + "app is in front (shows a notification; use Exit there to close the app).");
        savers.add(() -> {
            s.keepRunning = keep.isChecked();
            s.videoMode = checkedIndex(mode, s.videoMode);
            if (s.videoMode != Settings.VIDEO_OFF) s.videoShownMode = s.videoMode;
            if (preset[0] >= 0) applyPreset(preset[0]);
        });
    }

    private String describeGeometry() {
        if (s.videoW <= 0) return "default (Medium, above the buttons)";
        android.util.DisplayMetrics m = ctx.getResources().getDisplayMetrics();
        return Math.round(s.videoW * m.widthPixels / m.density) + "×" + Math.round(s.videoH * m.heightPixels / m.density)
                + " dp, custom position";
    }

    /** Sets a preset panel size, centred above the bottom buttons. */
    private void applyPreset(int i) {
        android.util.DisplayMetrics m = ctx.getResources().getDisplayMetrics();
        float sw = m.widthPixels, sh = m.heightPixels;
        float w = Settings.VIDEO_PRESETS[i][0] * m.density, h = Settings.VIDEO_PRESETS[i][1] * m.density;
        s.videoW = Math.min(1f, w / sw);
        s.videoH = Math.min(1f, h / sh);
        s.videoX = Math.max(0f, (sw - w) / 2 / sw);
        s.videoY = Math.max(0f, (sh - h - 58 * m.density) / sh);
    }

    private void save() {
        int oldPort = s.port;
        for (Runnable r : savers) r.run();
        host.onSettingsSaved(oldPort);
        dialog.dismiss();
        Toast.makeText(ctx, "Settings saved", Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- Widgets

    private double parse(EditText e, double fallback, String what) {
        try {
            return Double.parseDouble(e.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            Toast.makeText(ctx, "Invalid number for " + what + ", kept " + fallback, Toast.LENGTH_SHORT).show();
            return fallback;
        }
    }

    private LinearLayout column(LinearLayout parent) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(6), 0, dp(6), 0);
        parent.addView(c, lp);
        return c;
    }

    private LinearLayout card(LinearLayout col, String title) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(10), dp(14), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(C_CARD);
        g.setCornerRadius(dp(12));
        c.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(10), 0, 0);
        col.addView(c, lp);
        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextColor(C_ACCENT);
        t.setTextSize(12);
        t.setLetterSpacing(0.12f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(6));
        c.addView(t);
        return c;
    }

    private void hint(LinearLayout parent, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(C_LABEL);
        t.setTextSize(12);
        t.setPadding(0, dp(2), 0, dp(6));
        parent.addView(t);
    }

    private TextView label(String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(C_TEXT);
        t.setTextSize(14);
        return t;
    }

    private Switch sw(LinearLayout parent, String text, boolean checked) {
        Switch w = new Switch(ctx);
        w.setText(text);
        w.setTextColor(C_TEXT);
        w.setTextSize(14);
        w.setChecked(checked);
        w.setPadding(0, dp(4), 0, dp(4));
        parent.addView(w);
        return w;
    }

    private EditText number(String value, boolean decimal, boolean signed) {
        EditText e = new EditText(ctx);
        int type = InputType.TYPE_CLASS_NUMBER;
        if (decimal) type |= InputType.TYPE_NUMBER_FLAG_DECIMAL;
        if (signed) type |= InputType.TYPE_NUMBER_FLAG_SIGNED;
        e.setInputType(type);
        e.setText(value);
        e.setTextColor(C_TEXT);
        e.setGravity(Gravity.END);
        e.setSelectAllOnFocus(true);
        return e;
    }

    private RadioGroup radio(LinearLayout parent, String[] items, int checked, boolean horizontal) {
        RadioGroup g = new RadioGroup(ctx);
        g.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        for (int i = 0; i < items.length; i++) {
            RadioButton b = new RadioButton(ctx);
            b.setText(items[i]);
            b.setTextColor(C_TEXT);
            b.setId(View.generateViewId());
            g.addView(b);
            if (i == checked) b.setChecked(true);
        }
        parent.addView(g);
        return g;
    }

    private static int checkedIndex(RadioGroup g, int fallback) {
        int i = g.indexOfChild(g.findViewById(g.getCheckedRadioButtonId()));
        return i < 0 ? fallback : i;
    }

    private Button button(String text, boolean primary, View.OnClickListener l) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? Color.rgb(0, 130, 190) : Color.rgb(55, 60, 72));
        g.setCornerRadius(dp(8));
        b.setBackground(g);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        b.setLayoutParams(lp);
        return b;
    }
}
