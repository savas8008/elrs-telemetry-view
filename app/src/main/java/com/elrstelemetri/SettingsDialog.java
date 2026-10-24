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
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.view.ViewGroup;
import android.view.KeyEvent;
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

        /** Opens the offline map planner (choose an area on the map and download it). */
        void openOfflineMapPlanner();

        /** Installed version name. */
        String appVersion();

        /** Checks GitHub for a newer release now and offers it. */
        void checkForUpdatesNow();

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
    private View rootView;  // takes the focus when an input is finished, so no other input gets it
    private TextView downloadInfo;

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
        // focusable root: no input is focused (keyboard closed) when the screen opens or after "Done"
        root.setFocusableInTouchMode(true);
        root.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        rootView = root;

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
        buildUpdates(right);

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
                downloadInfo.setText(st);
                downloadInfo.setVisibility(st.isEmpty() ? View.GONE : View.VISIBLE);
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

        hint(card, "The backpack WiFi has no internet. Before flying, while you still have internet (at home, "
                + "no radio or goggles needed): open the offline map, find your flying field and download it. "
                + "Downloaded tiles are used offline.");
        downloadInfo = label("");
        downloadInfo.setSingleLine(false);
        downloadInfo.setVisibility(View.GONE);
        card.addView(downloadInfo);

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.addView(button("Choose area on map…", true, v -> {
            save();
            host.openOfflineMapPlanner();
        }));
        btns.addView(button("Cancel download", false, v -> host.map().cancelDownload()));
        card.addView(btns);
        long mb = GcsMap.cacheSizeBytes(ctx) / (1024 * 1024);
        hint(card, "Offline cache on this device: " + mb + " MB (up to 2 GB). To delete it: Android Settings › Apps › "
                + "ELRS Telemetry › Storage › Clear storage.");
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

    private void buildUpdates(LinearLayout col) {
        LinearLayout card = card(col, "UPDATES");
        hint(card, "Installed version: " + host.appVersion() + ". New versions are published on GitHub "
                + "(github.com/" + Updater.REPO + "). The app checks when internet is available and asks before "
                + "installing — never during a flight.");
        Switch auto = sw(card, "Check for updates automatically", s.checkUpdates);
        savers.add(() -> s.checkUpdates = auto.isChecked());
        card.addView(button("Check now", false, v -> host.checkForUpdatesNow()));
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
                "Video 30 % · map 70 % — side by side, never overlapping",
                "Video 70 % · map 30 %"}, s.videoMode, false);
        hint(card, "The enlarge/shrink button on the video swaps the two sizes. The telemetry panel is hidden "
                + "when the goggles connect; the telemetry button slides it in from the left.");

        Switch keep = sw(card, "Keep running in the background", s.keepRunning);
        hint(card, "Keeps video, telemetry, spoken alarms and logging running while the screen is off or another "
                + "app is in front (shows a notification; use Exit there to close the app).");
        savers.add(() -> {
            s.keepRunning = keep.isChecked();
            s.videoMode = checkedIndex(mode, s.videoMode);
            if (s.videoMode != Settings.VIDEO_OFF) s.videoShownMode = s.videoMode;
        });
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
        e.setSingleLine(true);
        // "Done" instead of "Next": finishing one value closes the keyboard instead of jumping to the next input
        e.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        e.setOnEditorActionListener((v, actionId, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                finishInput(v);
                return true;
            }
            return false;
        });
        return e;
    }

    /** Closes the keyboard and moves the focus away from the input. */
    private void finishInput(View v) {
        InputMethodManager imm = ctx.getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        if (rootView != null) rootView.requestFocus();
        else v.clearFocus();
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
