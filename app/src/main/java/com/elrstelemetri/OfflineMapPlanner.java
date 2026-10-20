package com.elrstelemetri;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.osmdroid.util.BoundingBox;

import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline map planner: a full-screen mode over the live map for choosing and downloading an area
 * before a flight (usually at home, without the radio or goggles). The user searches for a place
 * or pans/zooms the map; the framed area in the middle of the screen is what gets downloaded.
 * Touches outside the panels go through to the map underneath.
 */
public final class OfflineMapPlanner extends LinearLayout {

    public interface Host {
        GcsMap map();

        Settings settings();

        /** Persists {@link #settings()} (map layer and detail level chosen here). */
        void saveSettings();

        boolean hasInternet();

        boolean isDownloading();

        /** Starts downloading {@code area}. Returns an error message or null. */
        String startOfflineDownload(BoundingBox area, int maxZoom);

        /** Current download status, or empty. */
        String downloadStatus();

        /** Leaves the planner (back to the waiting screen or the GCS). */
        void closePlanner();
    }

    private static final int C_PANEL = Color.argb(225, 12, 15, 20);
    private static final int C_TEXT = Color.rgb(235, 238, 242);
    private static final int C_LABEL = Color.rgb(140, 150, 165);
    private static final int C_ACCENT = Color.rgb(0, 200, 255);
    private static final int C_WARN = Color.rgb(255, 170, 0);
    private static final int C_BUTTON = Color.rgb(0, 130, 190);
    private static final int C_BUTTON2 = Color.rgb(55, 60, 72);
    /** Detail levels offered (max zoom); 17 ≈ 1.2 m per pixel. */
    private static final int[] ZOOMS = {15, 16, 17, 18};
    /** Layers that may be cached (OpenStreetMap forbids bulk downloads). */
    private static final int[] LAYERS = {Settings.MAP_SATELLITE, Settings.MAP_TOPO, Settings.MAP_STREET};
    private static final Pattern COORDS = Pattern.compile("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*[,;\\s]\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$");

    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final EditText search;
    private final AreaFrame frame;
    private final TextView status;
    private final TextView download;
    private final LinearLayout layerChips, zoomChips;
    private int lastTiles = -1;

    public OfflineMapPlanner(Context c, Host host) {
        super(c);
        this.host = host;
        setOrientation(VERTICAL);
        setPadding(dp(8), dp(8), dp(8), dp(8));

        // ---- Top: title, place search, done
        LinearLayout top = panel(HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(c);
        icon.setImageResource(R.drawable.ic_offline_map);
        top.addView(icon, new LayoutParams(dp(26), dp(26)));
        TextView title = new TextView(c);
        title.setText("Offline map");
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(C_TEXT);
        title.setPadding(dp(8), 0, dp(12), 0);
        top.addView(title);
        search = new EditText(c);
        search.setHint("Search a place, or enter coordinates: 37.0742, 36.2464");
        search.setHintTextColor(C_LABEL);
        search.setTextColor(C_TEXT);
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setPadding(dp(10), dp(6), dp(10), dp(6));
        search.setBackground(round(Color.argb(255, 30, 34, 42), 8));
        search.setOnEditorActionListener((v, actionId, e) -> {
            boolean enter = e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                runSearch();
                return true;
            }
            return false;
        });
        top.addView(search, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        top.addView(button("Search", false, v -> runSearch()));
        top.addView(button("Done", true, v -> host.closePlanner()));
        addView(top, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // ---- Middle: the download area (transparent, touches reach the map)
        frame = new AreaFrame(c);
        addView(frame, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- Bottom: layer, detail level, size estimate, download
        LinearLayout bottom = panel(VERTICAL);
        LinearLayout options = new LinearLayout(c);
        options.setOrientation(HORIZONTAL);
        options.setGravity(Gravity.CENTER_VERTICAL);
        options.addView(label("LAYER"));
        String[] layerNames = new String[LAYERS.length];
        int layerIdx = 0;
        for (int i = 0; i < LAYERS.length; i++) {
            layerNames[i] = Settings.MAP_NAMES[LAYERS[i]];
            if (LAYERS[i] == host.settings().mapType) layerIdx = i;
        }
        layerChips = chips(layerNames, layerIdx, i -> {
            host.settings().mapType = LAYERS[i];
            host.map().setMapType(LAYERS[i]);
            host.saveSettings();
        });
        options.addView(layerChips);
        View gap = new View(c);
        options.addView(gap, new LayoutParams(dp(16), 1));
        options.addView(label("DETAIL (MAX ZOOM)"));
        String[] zoomNames = new String[ZOOMS.length];
        int zoomIdx = 2;
        for (int i = 0; i < ZOOMS.length; i++) {
            zoomNames[i] = String.valueOf(ZOOMS[i]);
            if (ZOOMS[i] == host.settings().offlineMaxZoom) zoomIdx = i;
        }
        zoomChips = chips(zoomNames, zoomIdx, i -> {
            host.settings().offlineMaxZoom = ZOOMS[i];
            host.saveSettings();
            updateStatus();
        });
        options.addView(zoomChips);
        bottom.addView(options);

        LinearLayout actions = new LinearLayout(c);
        actions.setOrientation(HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(6), 0, 0);
        status = new TextView(c);
        status.setTextSize(13);
        status.setTextColor(C_TEXT);
        actions.addView(status, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        download = button("Download area", true, v -> startDownload());
        actions.addView(download);
        actions.addView(button("Cancel download", false, v -> host.map().cancelDownload()));
        bottom.addView(actions);
        addView(bottom, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    // ---------------------------------------------------------------- Lifecycle

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        ui.removeCallbacks(tick);
        if (isShown()) {
            host.map().setFollow(false);  // keep the map where the user puts it
            ui.post(tick);
        } else {
            hideKeyboard();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        ui.removeCallbacks(tick);
    }

    // ---------------------------------------------------------------- Area and download

    /** The framed area in map coordinates, or null before layout. */
    BoundingBox selectedArea() {
        RectF r = frame.area();
        if (r == null) return null;
        int[] f = new int[2], m = new int[2];
        frame.getLocationInWindow(f);
        host.map().view.getLocationInWindow(m);
        int dx = f[0] - m[0], dy = f[1] - m[1];
        return host.map().areaForPixels(Math.round(r.left) + dx, Math.round(r.top) + dy,
                Math.round(r.right) + dx, Math.round(r.bottom) + dy);
    }

    private int maxZoom() {
        return host.settings().offlineMaxZoom;
    }

    private void updateStatus() {
        BoundingBox area = selectedArea();
        lastTiles = area == null ? -1 : host.map().tilesForArea(area, maxZoom());
        String size = lastTiles < 0 ? "?" : String.format(Locale.US, "%,d tiles ≈ %.0f MB", lastTiles,
                lastTiles * (double) GcsMap.APPROX_TILE_KB / 1024);
        String line = "Framed area, zoom " + Math.min(GcsMap.OFFLINE_MIN_ZOOM, maxZoom()) + "–" + maxZoom() + ": " + size;
        int color = C_TEXT;
        if (lastTiles > GcsMap.MAX_DOWNLOAD_TILES) {
            line += "  ·  too large: zoom in or lower the detail";
            color = C_WARN;
        }
        String st = host.downloadStatus();
        if (host.isDownloading()) {
            line = st;
            color = C_ACCENT;
        } else if (!host.hasInternet()) {
            line = "No internet — connect to a WiFi with internet (not the radio's) or enable mobile data";
            color = C_WARN;
        } else if (!st.isEmpty()) {
            line += "\n" + st;
        }
        status.setText(line);
        status.setTextColor(color);
        download.setAlpha(host.isDownloading() || lastTiles > GcsMap.MAX_DOWNLOAD_TILES ? 0.45f : 1f);
    }

    private void startDownload() {
        BoundingBox area = selectedArea();
        if (area == null) return;
        String err = host.startOfflineDownload(area, maxZoom());
        if (err != null) Toast.makeText(getContext(), err, Toast.LENGTH_LONG).show();
        updateStatus();
    }

    // ---------------------------------------------------------------- Place search

    private void runSearch() {
        String q = search.getText().toString().trim();
        if (q.isEmpty()) return;
        hideKeyboard();
        Matcher m = COORDS.matcher(q);
        if (m.matches()) {
            double lat = Double.parseDouble(m.group(1)), lon = Double.parseDouble(m.group(2));
            if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180) {
                goTo(lat, lon);
                return;
            }
        }
        if (!Geocoder.isPresent()) {
            toast("Place search is not available on this device — enter coordinates (lat, lon)");
            return;
        }
        if (!host.hasInternet()) {
            toast("Place search needs internet");
            return;
        }
        Context c = getContext();
        new Thread(() -> {
            String err = null;
            Address a = null;
            try {
                @SuppressWarnings("deprecation")
                List<Address> r = new Geocoder(c, Locale.getDefault()).getFromLocationName(q, 1);
                if (r != null && !r.isEmpty()) a = r.get(0);
            } catch (Exception e) {
                err = e.getMessage();
            }
            Address found = a;
            String error = err;
            ui.post(() -> {
                if (found != null) {
                    goTo(found.getLatitude(), found.getLongitude());
                } else {
                    toast(error != null ? "Place search failed: " + error : "Place not found: " + q);
                }
            });
        }, "geocoder").start();
    }

    private void goTo(double lat, double lon) {
        host.map().setFollow(false);
        host.map().startAt(lat, lon, 15.0);
        updateStatus();
    }

    // ---------------------------------------------------------------- Widgets

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout panel(int orientation) {
        LinearLayout p = new LinearLayout(getContext());
        p.setOrientation(orientation);
        p.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable bg = round(C_PANEL, 12);
        bg.setStroke(dp(1), Color.argb(40, 255, 255, 255));
        p.setBackground(bg);
        p.setClickable(true);  // the panels themselves do not move the map
        return p;
    }

    private TextView label(String s) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(10);
        t.setLetterSpacing(0.12f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(C_ACCENT);
        t.setPadding(0, 0, dp(8), 0);
        return t;
    }

    private TextView button(String text, boolean primary, View.OnClickListener l) {
        TextView b = new TextView(getContext());
        b.setText(text);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setBackground(round(primary ? C_BUTTON : C_BUTTON2, 8));
        b.setOnClickListener(l);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(8), 0, 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    /** Single-choice row of rounded chips. */
    private LinearLayout chips(String[] names, int selected, IntConsumer onPick) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        TextView[] all = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            TextView t = new TextView(getContext());
            t.setText(names[i]);
            t.setTextSize(13);
            t.setTextColor(Color.WHITE);
            t.setPadding(dp(10), dp(5), dp(10), dp(5));
            final int idx = i;
            t.setOnClickListener(v -> {
                for (int k = 0; k < all.length; k++) styleChip(all[k], k == idx);
                onPick.accept(idx);
            });
            all[i] = t;
            styleChip(t, i == selected);
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(5), 0);
            row.addView(t, lp);
        }
        return row;
    }

    private void styleChip(TextView t, boolean on) {
        GradientDrawable g = round(on ? C_BUTTON : Color.argb(255, 38, 42, 52), 14);
        if (!on) g.setStroke(dp(1), Color.argb(60, 255, 255, 255));
        t.setBackground(g);
        t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private void toast(String s) {
        Toast.makeText(getContext(), s, Toast.LENGTH_LONG).show();
    }

    private void hideKeyboard() {
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }

    // ---------------------------------------------------------------- Frame drawing

    /** Dims the map outside the download area and draws a dashed frame with corner marks. */
    private final class AreaFrame extends View {
        private final Paint shade = new Paint();
        private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint corner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path outside = new Path();

        AreaFrame(Context c) {
            super(c);
            shade.setColor(Color.argb(110, 0, 0, 0));
            dash.setStyle(Paint.Style.STROKE);
            dash.setStrokeWidth(dp(1.5f));
            dash.setColor(Color.argb(220, 255, 255, 255));
            dash.setPathEffect(new DashPathEffect(new float[]{dp(8), dp(6)}, 0));
            corner.setStyle(Paint.Style.STROKE);
            corner.setStrokeWidth(dp(3.5f));
            corner.setStrokeCap(Paint.Cap.ROUND);
            corner.setColor(C_ACCENT);
            text.setColor(C_ACCENT);
            text.setTextSize(dp(11));
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setLetterSpacing(0.12f);
        }

        /** Download area in this view's coordinates. */
        RectF area() {
            int w = getWidth(), h = getHeight();
            if (w < dp(40) || h < dp(40)) return null;
            float m = dp(10);
            return new RectF(m, m, w - m, h - m);
        }

        @Override
        protected void onDraw(Canvas c) {
            RectF r = area();
            if (r == null) return;
            outside.reset();
            outside.setFillType(Path.FillType.EVEN_ODD);
            outside.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
            outside.addRect(r, Path.Direction.CW);
            c.drawPath(outside, shade);
            c.drawRect(r, dash);
            float k = dp(18);
            c.drawLine(r.left, r.top, r.left + k, r.top, corner);
            c.drawLine(r.left, r.top, r.left, r.top + k, corner);
            c.drawLine(r.right, r.top, r.right - k, r.top, corner);
            c.drawLine(r.right, r.top, r.right, r.top + k, corner);
            c.drawLine(r.left, r.bottom, r.left + k, r.bottom, corner);
            c.drawLine(r.left, r.bottom, r.left, r.bottom - k, corner);
            c.drawLine(r.right, r.bottom, r.right - k, r.bottom, corner);
            c.drawLine(r.right, r.bottom, r.right, r.bottom - k, corner);
            c.drawText("DOWNLOAD AREA", r.left + dp(10), r.top + dp(18), text);
        }
    }
}
