package com.elrstelemetri;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final long STALE_MS = 3000;
    private static final long LOG_CLOSE_AFTER_MS = 30000;

    private static final int C_TEXT = Color.rgb(235, 238, 242);
    private static final int C_LABEL = Color.rgb(140, 150, 165);
    private static final int C_ACCENT = Color.rgb(0, 200, 255);
    private static final int C_OK = Color.rgb(60, 210, 110);
    private static final int C_WARN = Color.rgb(255, 170, 0);
    private static final int C_BAD = Color.rgb(255, 64, 64);
    private static final int C_STALE = Color.rgb(95, 100, 110);
    private static final int C_PANEL = Color.argb(200, 12, 15, 20);

    private final TelemetryState state = new TelemetryState();
    private final CrsfParser parser = new CrsfParser(state);
    private final FlightStats stats = new FlightStats();
    private final Settings settings = new Settings();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> rawLines = new ArrayDeque<>();
    private final SimpleDateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US);

    private volatile boolean running;
    private volatile boolean rawEnabled;
    private volatile String netStatus = "";
    private volatile DatagramSocket socket;
    private Thread rxThread;
    private WifiManager.MulticastLock mcLock;
    private SharedPreferences prefs;

    private GcsMap map;
    private NetRouter router;
    private FlightLogger logger;
    private Alerts alerts;
    private boolean recordEnabled;
    private boolean hadData;
    private long lastTrackGpsTime;
    private long rateStart, rateCount;
    private double rate;

    // Hesaplanan anlık değerler (UI iş parçacığı)
    private double curRelAlt = Double.NaN, curHeading, curDist = Double.NaN, curCell = Double.NaN;
    private boolean curArmedKnown, curArmed;

    // Görünümler
    private LinearLayout leftPanel, rightPanel;
    private TextView tMode, tArm, tTimer, tLq, tRssi, tSats, tRec, tLink, tWarn, tInfo;
    private TextView vAlt, vVario, vSpeed, vDist, vHomeDir, vGpsAlt;
    private TextView vCell, vCurr, vMah, vPower, vSnr, vTxPow, vDown, vRf;
    private TextView bFollow;
    private HorizonView horizon;
    private BatteryView battery;
    private SparklineView sparkAlt, sparkVolt;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("ayarlar", MODE_PRIVATE);
        settings.load(prefs);
        recordEnabled = settings.autoRecord;

        GcsMap.configure(this);
        map = new GcsMap(this, getResources().getDisplayMetrics().density);
        map.setMapType(settings.mapType);
        if (settings.hasLastPos) map.startAt(settings.lastLat, settings.lastLon, 15.0);
        else map.startAt(39.0, 35.0, 6.0);  // konum yoksa Türkiye
        map.setOnFollowChanged(this::updateFollowButton);

        logger = new FlightLogger(this);
        alerts = new Alerts(this);
        // Ağ yönlendirmesi değişince UDP soketini doğru ağa yeniden bağla
        router = new NetRouter(this, () -> {
            if (running) {
                stopReceiver();
                startReceiver();
            }
        });
        setContentView(buildUi());
        hideSystemBars();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    protected void onResume() {
        super.onResume();
        map.onResume();
    }

    @Override
    protected void onPause() {
        map.onPause();
        settings.save(prefs);
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        mcLock = wm.createMulticastLock("elrs-telem");
        mcLock.setReferenceCounted(false);
        mcLock.acquire();  // bazı telefonlar broadcast paketlerini bu kilit olmadan düşürür
        router.start();
        startReceiver();
        ui.post(tick);
        ui.post(logTick);
    }

    @Override
    protected void onStop() {
        ui.removeCallbacks(tick);
        ui.removeCallbacks(logTick);
        stopReceiver();
        router.stop();
        logger.close();
        if (mcLock != null && mcLock.isHeld()) mcLock.release();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        alerts.shutdown();
        super.onDestroy();
    }

    // ================================================================ UDP

    private void startReceiver() {
        running = true;
        final int listenPort = settings.port;
        rxThread = new Thread(() -> {
            DatagramSocket s = null;
            try {
                s = new DatagramSocket(null);
                s.setReuseAddress(true);
                s.setBroadcast(true);
                s.setSoTimeout(1000);
                s.bind(new InetSocketAddress(listenPort));
                router.bindToWifi(s);
                socket = s;
                netStatus = "UDP " + listenPort;
                byte[] buf = new byte[2048];
                DatagramPacket pk = new DatagramPacket(buf, buf.length);
                while (running) {
                    pk.setLength(buf.length);
                    try {
                        s.receive(pk);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    int n = pk.getLength();
                    int frames = parser.feed(buf, n);
                    synchronized (state) {
                        state.datagramCount++;
                        state.lastDatagramTime = System.currentTimeMillis();
                        state.lastSource = pk.getAddress().getHostAddress();
                        if (frames > 0) state.mavlinkSuspect = false;
                        else if (n > 0 && (buf[0] == (byte) 0xFD || buf[0] == (byte) 0xFE)) state.mavlinkSuspect = true;
                    }
                    if (rawEnabled) addRaw(buf, n, frames);
                }
            } catch (Exception e) {
                if (running) netStatus = "Hata: " + e.getMessage();
            } finally {
                if (s != null) s.close();
            }
        }, "udp-rx");
        rxThread.start();
    }

    private void stopReceiver() {
        running = false;
        DatagramSocket s = socket;
        if (s != null) s.close();
        socket = null;
        if (rxThread != null) {
            try {
                rxThread.join(1500);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void addRaw(byte[] b, int n, int frames) {
        StringBuilder sb = new StringBuilder();
        sb.append(n).append("B ").append(frames).append("çrç: ");
        int show = Math.min(n, 40);
        for (int i = 0; i < show; i++) sb.append(String.format("%02X ", b[i] & 0xFF));
        if (n > show) sb.append('…');
        synchronized (rawLines) {
            rawLines.addFirst(sb.toString());
            while (rawLines.size() > 14) rawLines.removeLast();
        }
    }

    // ================================================================ Güncelleme döngüleri

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 200);
        }
    };

    private final Runnable logTick = new Runnable() {
        @Override
        public void run() {
            writeLogRow();
            ui.postDelayed(this, 1000 / Math.max(1, settings.recordHz));
        }
    };

    private static boolean fresh(long t, long now) {
        return t != 0 && now - t <= STALE_MS;
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();
        boolean critical = false;
        boolean linkFresh;
        synchronized (state) {
            TelemetryState s = state;
            linkFresh = fresh(s.linkTime, now) || fresh(s.batteryTime, now) || fresh(s.gpsTime, now);
            boolean gpsFresh = fresh(s.gpsTime, now);
            boolean attFresh = fresh(s.attitudeTime, now);
            boolean battFresh = fresh(s.batteryTime, now);
            boolean baroFresh = fresh(s.baroTime, now);
            boolean hasFix = s.gpsTime != 0 && s.sats >= 4 && (s.lat != 0 || s.lon != 0);
            if (linkFresh) hadData = true;

            // Paket hızı
            if (rateStart == 0 || now - rateStart >= 1000) {
                if (rateStart != 0) rate = (s.datagramCount - rateCount) * 1000.0 / (now - rateStart);
                rateStart = now;
                rateCount = s.datagramCount;
            }

            // Yön: GPS hızı yeterliyse GPS rotası, değilse attitude yaw
            if (gpsFresh && s.groundSpeedKmh > 3) curHeading = s.headingDeg;
            else if (attFresh) curHeading = s.yawDeg;
            else if (gpsFresh) curHeading = s.headingDeg;

            // Göreli irtifa: baro varsa baro (FC kalkışa göre sıfırlar), yoksa GPS - ev
            if (baroFresh) curRelAlt = s.baroAltM;
            else if (gpsFresh && s.homeSet) curRelAlt = s.gpsAltM - s.homeAltM;
            else if (!linkFresh) curRelAlt = Double.NaN;

            curDist = s.homeDistanceM();
            int cells = s.cellCount();
            curCell = cells > 0 ? s.voltage / cells : Double.NaN;

            // ARM durumu: Betaflight/INAV disarm iken uçuş modunun sonuna '*' ekler
            String mode = s.flightMode == null ? "" : s.flightMode.trim();
            curArmedKnown = fresh(s.modeTime, now) && !mode.isEmpty();
            curArmed = curArmedKnown && !mode.endsWith("*");
            boolean flying = linkFresh && (!curArmedKnown || curArmed);

            stats.update(now, flying, curRelAlt, gpsFresh ? curDist : Double.NaN,
                    gpsFresh ? s.groundSpeedKmh : Double.NaN,
                    battFresh ? curCell : Double.NaN, battFresh ? s.voltage : Double.NaN,
                    battFresh ? s.current : Double.NaN);

            // Harita
            if (hasFix && s.gpsTime != lastTrackGpsTime) {
                lastTrackGpsTime = s.gpsTime;
                map.addTrackPoint(s.lat, s.lon);
                settings.hasLastPos = true;
                settings.lastLat = s.lat;
                settings.lastLon = s.lon;
            }
            map.update(hasFix, s.lat, s.lon, curHeading, !gpsFresh, s.homeSet, s.homeLat, s.homeLon);

            // ---- Üst çubuk
            String modeTxt = mode.isEmpty() ? "MOD —" : mode.replace("*", "");
            setText(tMode, modeTxt, C_TEXT, !fresh(s.modeTime, now));
            if (!curArmedKnown) styleChip(tArm, "ARM ?", C_STALE);
            else styleChip(tArm, curArmed ? "ARMED" : "DISARMED", curArmed ? C_BAD : C_OK);
            tTimer.setText("⏱ " + FlightStats.formatDuration(stats.flightMs));

            boolean ls = !fresh(s.linkTime, now);
            setText(tLq, "LQ " + s.lq + "%", s.lq < settings.warnLq ? C_BAD : s.lq < 80 ? C_WARN : C_OK, ls);
            setText(tRssi, s.rssi1 + "/" + s.rssi2 + " dBm", s.rssi1 < -100 ? C_WARN : C_TEXT, ls);
            setText(tSats, "🛰 " + s.sats, s.sats < 6 ? C_WARN : C_OK, !gpsFresh);
            if (logger.isOpen()) {
                styleChip(tRec, "● REC " + FlightStats.formatDuration(now - logger.startTime), C_BAD);
            } else {
                styleChip(tRec, recordEnabled ? "○ REC bekliyor" : "○ REC kapalı", C_STALE);
            }
            String link;
            if (s.lastDatagramTime == 0) link = netStatus + " · veri yok · " + router.describe();
            else link = String.format(Locale.US, "%s · %.0f p/s · %s", s.lastSource, rate, router.describe());
            tLink.setText(link);
            tLink.setTextColor(fresh(s.lastDatagramTime, now) ? C_LABEL : C_BAD);

            // ---- Sol panel
            horizon.set(s.pitchDeg, s.rollDeg, curHeading, !attFresh);
            setText(vAlt, Double.isNaN(curRelAlt) ? "—" : String.format(Locale.US, "%.0f m", curRelAlt), C_TEXT,
                    !(baroFresh || gpsFresh));
            setText(vVario, String.format(Locale.US, "%+.1f m/s", s.varioMs), varioColor(s.varioMs),
                    !fresh(s.varioTime, now));
            setText(vSpeed, String.format(Locale.US, "%.0f km/sa", s.groundSpeedKmh), C_TEXT, !gpsFresh);
            setText(vDist, Double.isNaN(curDist) ? "ev yok" : formatDist(curDist), C_TEXT, !gpsFresh);
            double homeDir = Double.isNaN(curDist) ? Double.NaN : (s.homeBearingDeg() + 180) % 360;
            setText(vHomeDir, Double.isNaN(homeDir) ? "—" : String.format(Locale.US, "%03.0f° %s", homeDir, arrow(homeDir - curHeading)),
                    C_ACCENT, !gpsFresh);
            setText(vGpsAlt, s.gpsAltM + " m", C_TEXT, !gpsFresh);

            // ---- Sağ panel
            int battColor = Double.isNaN(curCell) ? C_STALE
                    : curCell < settings.warnCellV ? C_BAD : curCell < settings.warnCellV + 0.2 ? C_WARN : C_OK;
            float frac = s.batteryPct > 0 ? s.batteryPct / 100f
                    : Double.isNaN(curCell) ? 0 : (float) ((curCell - 3.3) / (4.2 - 3.3));
            battery.set(frac, String.format(Locale.US, "%.1fV  %d%%", s.voltage, s.batteryPct), battColor, !battFresh);
            setText(vCell, cells > 0 ? String.format(Locale.US, "%dS %.2fV", cells, curCell) : "—", battColor, !battFresh);
            setText(vCurr, String.format(Locale.US, "%.1f A", s.current), C_TEXT, !battFresh);
            setText(vMah, s.mah + " mAh", C_TEXT, !battFresh);
            setText(vPower, String.format(Locale.US, "%.0f W", s.voltage * s.current), C_TEXT, !battFresh);
            setText(vSnr, s.snr + " dB", C_TEXT, ls);
            setText(vTxPow, s.txPowerMw >= 0 ? s.txPowerMw + " mW" : "?", C_TEXT, ls);
            setText(vDown, s.downRssi + " dBm · " + s.downLq + "%", C_TEXT, ls);
            setText(vRf, "Ant " + (s.antenna + 1) + " · RF " + s.rfMode, C_TEXT, ls);
            sparkAlt.set(stats.altHistory, stats.historyCount, stats.historyPos);
            sparkVolt.set(stats.voltHistory, stats.historyCount, stats.historyPos);

            // ---- Alt bilgi
            StringBuilder info = new StringBuilder();
            if (hasFix) info.append(String.format(Locale.US, "%.6f, %.6f", s.lat, s.lon));
            else info.append("GPS konumu yok");
            if (logger.isOpen()) info.append("   ·   ").append(logger.fileName).append(" (").append(logger.rows).append(" satır)");
            tInfo.setText(info);

            // ---- Uyarılar
            if (s.lastDatagramTime == 0) {
                warnings.add("Telemetri bekleniyor — tablet backpack WiFi'ına bağlı mı? Lua: Backpack › Telemetry = WiFi");
            } else if (s.mavlinkSuspect) {
                warnings.add("MAVLink verisi geliyor — Link Mode = Normal olmalı");
            } else if (s.datagramCount > 20 && s.frameCount == 0) {
                warnings.add("Paket geliyor ama CRSF çerçevesi yok — Ayarlar › Ham veri");
            }
            if (hadData && !linkFresh) {
                warnings.add("TELEMETRİ KAYIP");
                critical = true;
                speak("lost", "Telemetri kayboldu");
            } else alerts.clear("lost");
            if (!ls && s.lq < settings.warnLq) {
                warnings.add("ZAYIF BAĞLANTI · LQ " + s.lq + "%");
                critical = true;
                speak("lq", "Bağlantı zayıf");
            } else alerts.clear("lq");
            if (battFresh && !Double.isNaN(curCell) && curCell < settings.warnCellV) {
                warnings.add(String.format(Locale.US, "DÜŞÜK BATARYA · %.2f V/hücre", curCell));
                critical = true;
                speak("batt", "Batarya düşük");
            } else alerts.clear("batt");
            if (gpsFresh && s.sats < 5) warnings.add("GPS ZAYIF · " + s.sats + " uydu");
        }

        // Kayıt aç/kapat
        long lastData;
        synchronized (state) {
            lastData = state.lastDatagramTime;
        }
        if (recordEnabled && linkFresh && !logger.isOpen()) {
            if (!logger.open()) warnings.add("Kayıt açılamadı: " + logger.lastError);
        }
        if (logger.isOpen() && (!recordEnabled || now - lastData > LOG_CLOSE_AFTER_MS)) logger.close();

        if (warnings.isEmpty()) {
            tWarn.setVisibility(View.GONE);
        } else {
            tWarn.setVisibility(View.VISIBLE);
            tWarn.setText(String.join("\n", warnings));
            ((GradientDrawable) tWarn.getBackground()).setColor(critical ? Color.argb(220, 200, 30, 30) : Color.argb(210, 40, 45, 55));
        }
    }

    private void speak(String key, String text) {
        if (settings.voice) alerts.say(key, text, 15000);
    }

    private void writeLogRow() {
        if (!logger.isOpen()) return;
        long now = System.currentTimeMillis();
        String row;
        synchronized (state) {
            TelemetryState s = state;
            if (!fresh(s.lastDatagramTime, now)) return;
            int cells = s.cellCount();
            row = String.format(Locale.US,
                    "%s,%d,%.7f,%.7f,%d,%s,%.1f,%.2f,%.1f,%.1f,%d,%.1f,%.1f,%.1f,%.2f,%s,%.2f,%d,%d,%.1f,%d,%d,%d,%d,%d,%d,%d,%s,%s,%s",
                    isoFmt.format(new Date(now)), now - logger.startTime,
                    s.lat, s.lon, s.gpsAltM, num(curRelAlt, "%.1f"), s.baroAltM, s.varioMs, s.groundSpeedKmh,
                    s.headingDeg, s.sats, s.pitchDeg, s.rollDeg, s.yawDeg,
                    s.voltage, cells > 0 ? String.format(Locale.US, "%.3f", s.voltage / cells) : "",
                    s.current, s.mah, s.batteryPct, s.voltage * s.current,
                    s.rssi1, s.rssi2, s.lq, s.snr, s.txPowerMw, s.downRssi, s.downLq,
                    csv(s.flightMode), num(s.homeDistanceM(), "%.1f"), num(s.homeBearingDeg(), "%.0f"));
        }
        logger.write(row);
    }

    private static String num(double v, String fmt) {
        return Double.isNaN(v) ? "" : String.format(Locale.US, fmt, v);
    }

    private static String csv(String s) {
        if (s == null) return "";
        return "\"" + s.replace("\"", "'") + "\"";
    }

    private static String formatDist(double m) {
        return m < 1000 ? String.format(Locale.US, "%.0f m", m) : String.format(Locale.US, "%.2f km", m / 1000);
    }

    private static int varioColor(double v) {
        return v > 0.5 ? C_OK : v < -0.5 ? C_WARN : C_TEXT;
    }

    /** Uçağın burnuna göre eve dönmek için hangi yöne dönmeli. */
    private static String arrow(double rel) {
        double r = ((rel % 360) + 540) % 360 - 180;  // -180..180
        if (Math.abs(r) < 22.5) return "↑";
        if (Math.abs(r) > 157.5) return "↓";
        if (r > 0) return r < 67.5 ? "↗" : r < 112.5 ? "→" : "↘";
        return r > -67.5 ? "↖" : r > -112.5 ? "←" : "↙";
    }

    private void setText(TextView v, String text, int color, boolean stale) {
        v.setText(text);
        v.setTextColor(stale ? C_STALE : color);
    }

    // ================================================================ Arayüz

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private GradientDrawable roundBg(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), Color.argb(40, 255, 255, 255));
        return g;
    }

    private void styleChip(TextView t, String text, int color) {
        t.setText(text);
        t.setTextColor(Color.WHITE);
        ((GradientDrawable) t.getBackground()).setColor(Color.argb(200, Color.red(color), Color.green(color), Color.blue(color)));
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        if (bold) t.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        return t;
    }

    private TextView chip(String s) {
        TextView t = text(12, Color.WHITE, true);
        t.setText(s);
        t.setPadding(dp(8), dp(3), dp(8), dp(3));
        t.setBackground(roundBg(C_STALE, 6));
        return t;
    }

    /** Etiket + değer kutusu; değer TextView'ını döndürür. */
    private TextView stat(LinearLayout row, String label, float valueSp) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(3), dp(4), dp(3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(box, lp);
        TextView l = text(9, C_LABEL, false);
        l.setText(label);
        l.setLetterSpacing(0.08f);
        box.addView(l);
        TextView v = text(valueSp, C_STALE, true);
        v.setText("—");
        box.addView(v);
        return v;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(r, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private void sectionTitle(LinearLayout parent, String s) {
        TextView t = text(9, C_ACCENT, true);
        t.setText(s);
        t.setLetterSpacing(0.15f);
        t.setPadding(dp(6), dp(4), 0, 0);
        parent.addView(t);
    }

    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(6), dp(6), dp(6), dp(6));
        p.setBackground(roundBg(C_PANEL, 12));
        return p;
    }

    private TextView mapButton(LinearLayout parent, String icon, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(icon);
        t.setTextSize(18);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setBackground(roundBg(C_PANEL, 22));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(42));
        lp.setMargins(dp(4), 0, dp(4), 0);
        parent.addView(t, lp);
        return t;
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(map.view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // ---- Üst çubuk
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), dp(4), dp(10), dp(4));
        top.setBackground(roundBg(C_PANEL, 12));
        tMode = text(18, C_TEXT, true);
        top.addView(tMode);
        tArm = chip("ARM ?");
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cl.setMargins(dp(10), 0, dp(6), 0);
        top.addView(tArm, cl);
        tTimer = text(14, C_TEXT, true);
        top.addView(tTimer);
        View spacer = new View(this);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        tLq = text(14, C_STALE, true);
        tRssi = text(12, C_STALE, true);
        tSats = text(14, C_STALE, true);
        for (TextView t : new TextView[]{tLq, tRssi, tSats}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(8), 0, dp(8), 0);
            top.addView(t, lp);
        }
        tRec = chip("○ REC");
        tRec.setOnClickListener(v -> {
            recordEnabled = !recordEnabled;
            Toast.makeText(this, recordEnabled ? "Kayıt açık (veri gelince başlar)" : "Kayıt durduruldu", Toast.LENGTH_SHORT).show();
        });
        top.addView(tRec, cl);
        tLink = text(10, C_LABEL, false);
        top.addView(tLink);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(44), Gravity.TOP);
        tlp.setMargins(dp(8), dp(6), dp(8), 0);
        root.addView(top, tlp);

        // ---- Sol panel: uçuş
        leftPanel = panel();
        horizon = new HorizonView(this);
        leftPanel.addView(horizon, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(118)));
        LinearLayout r = row(leftPanel);
        vAlt = stat(r, "İRTİFA", 20);
        vVario = stat(r, "VARIO", 15);
        r = row(leftPanel);
        vSpeed = stat(r, "HIZ", 16);
        vDist = stat(r, "EV UZAKLIĞI", 16);
        r = row(leftPanel);
        vHomeDir = stat(r, "EVE YÖN", 15);
        vGpsAlt = stat(r, "GPS İRT (MSL)", 15);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(dp(210), FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        llp.setMargins(dp(8), dp(56), 0, 0);
        root.addView(leftPanel, llp);

        // ---- Sağ panel: güç + bağlantı
        rightPanel = panel();
        battery = new BatteryView(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        blp.setMargins(dp(2), dp(2), dp(2), dp(2));
        rightPanel.addView(battery, blp);
        r = row(rightPanel);
        vCell = stat(r, "HÜCRE", 14);
        vCurr = stat(r, "AKIM", 14);
        r = row(rightPanel);
        vMah = stat(r, "HARCANAN", 14);
        vPower = stat(r, "GÜÇ", 14);
        sectionTitle(rightPanel, "BAĞLANTI");
        r = row(rightPanel);
        vSnr = stat(r, "SNR", 13);
        vTxPow = stat(r, "TX GÜÇ", 13);
        r = row(rightPanel);
        vDown = stat(r, "DOWNLINK", 12);
        vRf = stat(r, "ANTEN / RF", 12);
        sparkAlt = new SparklineView(this, "İRTİFA (5 dk)", "m", C_ACCENT);
        rightPanel.addView(sparkAlt, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));
        sparkVolt = new SparklineView(this, "VOLTAJ (5 dk)", "V", Color.rgb(255, 200, 0));
        rightPanel.addView(sparkVolt, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(dp(210), FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        rlp.setMargins(0, dp(56), dp(8), 0);
        root.addView(rightPanel, rlp);

        // ---- Uyarı bandı
        tWarn = new TextView(this);
        tWarn.setTextColor(Color.WHITE);
        tWarn.setTextSize(13);
        tWarn.setTypeface(Typeface.DEFAULT_BOLD);
        tWarn.setGravity(Gravity.CENTER);
        tWarn.setPadding(dp(14), dp(6), dp(14), dp(6));
        tWarn.setBackground(roundBg(Color.argb(210, 40, 45, 55), 10));
        tWarn.setVisibility(View.GONE);
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        wlp.setMargins(dp(230), dp(58), dp(230), 0);
        root.addView(tWarn, wlp);

        // ---- Alt: bilgi satırı + harita düğmeleri
        tInfo = text(11, C_TEXT, false);
        tInfo.setSingleLine(false);
        tInfo.setMaxLines(2);
        tInfo.setMaxWidth(dp(210));
        tInfo.setPadding(dp(10), dp(4), dp(10), dp(4));
        tInfo.setBackground(roundBg(C_PANEL, 8));
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        ilp.setMargins(dp(8), 0, 0, dp(10));
        root.addView(tInfo, ilp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        bFollow = mapButton(buttons, "⌖", v -> {
            map.setFollow(!map.isFollowing());
            centerOnPlane();
        });
        mapButton(buttons, "🗺", v -> {
            settings.mapType = (settings.mapType + 1) % 3;
            map.setMapType(settings.mapType);
            String[] names = {"Uydu", "Sokak", "Topoğrafik"};
            Toast.makeText(this, "Harita: " + names[settings.mapType], Toast.LENGTH_SHORT).show();
        });
        mapButton(buttons, "⌂", v -> {
            synchronized (state) {
                state.resetHome();
            }
            Toast.makeText(this, "Ev noktası yeniden alınacak", Toast.LENGTH_SHORT).show();
        });
        mapButton(buttons, "⌫", v -> {
            map.clearTrack();
            Toast.makeText(this, "Uçuş izi temizlendi", Toast.LENGTH_SHORT).show();
        });
        mapButton(buttons, "▣", v -> {
            settings.showPanels = !settings.showPanels;
            applyPanelVisibility();
        });
        mapButton(buttons, "📊", v -> showSummary());
        mapButton(buttons, "⚙", v -> showSettings());
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bl.setMargins(0, 0, 0, dp(8));
        root.addView(buttons, bl);

        updateFollowButton();
        applyPanelVisibility();
        return root;
    }

    private void applyPanelVisibility() {
        int v = settings.showPanels ? View.VISIBLE : View.GONE;
        leftPanel.setVisibility(v);
        rightPanel.setVisibility(v);
    }

    private void updateFollowButton() {
        if (bFollow == null) return;
        ((GradientDrawable) bFollow.getBackground()).setColor(map.isFollowing() ? Color.argb(220, 0, 140, 200) : C_PANEL);
    }

    private void centerOnPlane() {
        synchronized (state) {
            if (state.gpsTime != 0) map.centerOn(state.lat, state.lon);
        }
    }

    // ================================================================ Diyaloglar

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    private void showSummary() {
        String msg = String.format(Locale.US,
                "Uçuş süresi:  %s\nEn yüksek irtifa:  %s\nEn uzak mesafe:  %s\nEn yüksek hız:  %.0f km/sa\n"
                        + "En yüksek akım:  %.1f A\nEn düşük hücre:  %s\nİz noktası:  %d\n\nKayıt: %s",
                FlightStats.formatDuration(stats.flightMs),
                Double.isNaN(stats.maxAltM) ? "—" : String.format(Locale.US, "%.0f m", stats.maxAltM),
                formatDist(stats.maxDistM), stats.maxSpeedKmh, stats.maxCurrentA,
                Double.isNaN(stats.minCellV) ? "—" : String.format(Locale.US, "%.2f V", stats.minCellV),
                map.trackSize(),
                logger.isOpen() ? "İndirilenler/" + FlightLogger.FOLDER + "/" + logger.fileName : "kapalı");
        dialog().setTitle("Uçuş özeti")
                .setMessage(msg)
                .setPositiveButton("Tamam", null)
                .setNeutralButton("Sıfırla", (d, w) -> stats.reset())
                .show();
    }

    private void showSettings() {
        ScrollView sv = new ScrollView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(8), dp(20), dp(8));
        sv.addView(l);

        l.addView(header("Harita katmanı"));
        RadioGroup mapGroup = radio(l, new String[]{"Uydu (Esri)", "Sokak (OpenStreetMap)", "Topoğrafik (OpenTopoMap)"}, settings.mapType);

        l.addView(header("Uçuş kaydı (CSV → İndirilenler/" + FlightLogger.FOLDER + ")"));
        Switch swRec = new Switch(this);
        swRec.setText("Veri gelince otomatik kaydet");
        swRec.setChecked(settings.autoRecord);
        l.addView(swRec);
        final int[] hzValues = {1, 2, 5, 10};
        int hzIdx = 2;
        for (int i = 0; i < hzValues.length; i++) if (hzValues[i] == settings.recordHz) hzIdx = i;
        RadioGroup hzGroup = radio(l, new String[]{"1 Hz", "2 Hz", "5 Hz", "10 Hz"}, hzIdx);
        hzGroup.setOrientation(LinearLayout.HORIZONTAL);

        l.addView(header("Uyarılar"));
        Switch swVoice = new Switch(this);
        swVoice.setText("Sesli uyarılar");
        swVoice.setChecked(settings.voice);
        l.addView(swVoice);
        EditText eCell = field(l, "Düşük hücre voltajı (V)", String.format(Locale.US, "%.2f", settings.warnCellV), true);
        EditText eLq = field(l, "Düşük LQ eşiği (%)", String.valueOf(settings.warnLq), false);

        l.addView(header("Bağlantı"));
        EditText ePort = field(l, "UDP port", String.valueOf(settings.port), false);

        dialog().setTitle("Ayarlar")
                .setView(sv)
                .setPositiveButton("Kaydet", (d, w) -> {
                    settings.mapType = mapGroup.indexOfChild(mapGroup.findViewById(mapGroup.getCheckedRadioButtonId()));
                    if (settings.mapType < 0) settings.mapType = Settings.MAP_SATELLITE;
                    map.setMapType(settings.mapType);
                    settings.autoRecord = swRec.isChecked();
                    recordEnabled = settings.autoRecord;
                    int hi = hzGroup.indexOfChild(hzGroup.findViewById(hzGroup.getCheckedRadioButtonId()));
                    settings.recordHz = hzValues[Math.max(0, hi)];
                    settings.voice = swVoice.isChecked();
                    try {
                        settings.warnCellV = Float.parseFloat(eCell.getText().toString().replace(',', '.'));
                        settings.warnLq = Integer.parseInt(eLq.getText().toString().trim());
                        int p = Integer.parseInt(ePort.getText().toString().trim());
                        if (p >= 1 && p <= 65535 && p != settings.port) {
                            settings.port = p;
                            stopReceiver();
                            startReceiver();
                        }
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Geçersiz sayı; diğer ayarlar kaydedildi", Toast.LENGTH_LONG).show();
                    }
                    settings.save(prefs);
                })
                .setNeutralButton("Ham veri", (d, w) -> showRaw())
                .setNegativeButton("İptal", null)
                .show();
    }

    private TextView header(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(C_ACCENT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    private RadioGroup radio(LinearLayout parent, String[] items, int checked) {
        RadioGroup g = new RadioGroup(this);
        for (int i = 0; i < items.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setText(items[i]);
            b.setId(View.generateViewId());
            g.addView(b);
            if (i == checked) b.setChecked(true);
        }
        parent.addView(g);
        return g;
    }

    private EditText field(LinearLayout parent, String label, String value, boolean decimal) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        EditText e = new EditText(this);
        e.setInputType(decimal ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL : InputType.TYPE_CLASS_NUMBER);
        e.setText(value);
        e.setEms(5);
        r.addView(e);
        parent.addView(r);
        return e;
    }

    private void showRaw() {
        TextView t = new TextView(this);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(10);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        rawEnabled = true;
        Runnable upd = new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                synchronized (rawLines) {
                    for (String s : rawLines) sb.append(s).append('\n');
                }
                t.setText(sb.length() == 0 ? "Henüz paket yok…" : sb.toString());
                ui.postDelayed(this, 500);
            }
        };
        ui.post(upd);
        dialog().setTitle("Ham UDP paketleri")
                .setView(t)
                .setPositiveButton("Kapat", null)
                .setOnDismissListener(d -> {
                    rawEnabled = false;
                    ui.removeCallbacks(upd);
                })
                .show();
    }
}
