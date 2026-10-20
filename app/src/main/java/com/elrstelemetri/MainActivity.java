package com.elrstelemetri;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.elrstelemetri.n3.H264AnnexBAssembler;
import com.elrstelemetri.n3.N3UsbLink;

import org.osmdroid.tileprovider.cachemanager.CacheManager;

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

public class MainActivity extends Activity implements SettingsDialog.Host {

    private static final long STALE_MS = 3000;
    private static final long LOG_CLOSE_AFTER_MS = 30000;
    private static final long MUTE_MS = 120000;

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
    private String downloadStatus = "";
    private boolean downloading;
    private boolean started;
    private N3UsbLink n3;
    private Intent launchIntent;
    private byte[] n3Sps, n3Pps;   // read thread only
    private TextView bVideo, bExpand;
    private VideoPanel videoPanel;
    private VideoWindow videoWindow;
    private FrameLayout gcsRoot;

    // Derived values (UI thread)
    private double curRelAlt = Double.NaN, curHeading, curDist = Double.NaN, curCell = Double.NaN;
    private boolean curArmedKnown, curArmed;
    private String activeAlarmIds = "";

    // Views
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
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        settings.load(prefs);
        recordEnabled = settings.autoRecord;

        GcsMap.configure(this);
        map = new GcsMap(this, getResources().getDisplayMetrics().density);
        map.setMapType(settings.mapType);
        if (settings.hasLastPos) map.startAt(settings.lastLat, settings.lastLon, 15.0);
        else map.startAt(30.0, 10.0, 3.0);
        map.setOnFollowChanged(this::updateFollowButton);

        logger = new FlightLogger(this);
        alerts = new Alerts(this);
        // Re-bind the UDP socket when the network routing changes
        router = new NetRouter(this, () -> {
            if (running) {
                stopReceiver();
                startReceiver();
            }
        });
        videoPanel = new VideoPanel(this);
        videoPanel.setCallback(this::connectGoggles);
        // Video straight from DJI Goggles N3 over USB
        n3 = new N3UsbLink(this, new N3UsbLink.Listener() {
            @Override
            public void onStreamStarted() {
                videoPanel.onConnected();
                Toast.makeText(MainActivity.this, settings.videoMode == Settings.VIDEO_OFF
                        ? "Goggles video connected — tap 📷 to show it" : "Goggles video connected",
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onAccessUnit(H264AnnexBAssembler.AccessUnit au) {
                if (au.sps != null && au.pps != null
                        && (!java.util.Arrays.equals(au.sps, n3Sps) || !java.util.Arrays.equals(au.pps, n3Pps))) {
                    n3Sps = au.sps;
                    n3Pps = au.pps;
                    videoPanel.onVideoConfig(au.sps, au.pps);
                }
                if (n3Sps != null) videoPanel.onVideoFrame(au.data, android.os.SystemClock.elapsedRealtime(), au.keyFrame);
            }

            @Override
            public void onStreamStopped(String reason) {
                n3Sps = null;
                n3Pps = null;
                videoPanel.onDisconnected(reason);
                ui.post(() -> Toast.makeText(MainActivity.this, "Goggles video ended: " + reason,
                        Toast.LENGTH_SHORT).show());
            }
        });
        launchIntent = getIntent();
        videoWindow = new VideoWindow(this, videoPanel);
        videoWindow.setListener(new VideoWindow.Listener() {
            @Override
            public void onExpand() {
                setVideoMode(Settings.VIDEO_REPLACE_MAP);
            }

            @Override
            public void onClose() {
                setVideoMode(Settings.VIDEO_OFF);
            }

            @Override
            public void onGeometryChanged() {
                float[] g = videoWindow.geometryFractions();
                settings.videoX = g[0];
                settings.videoY = g[1];
                settings.videoW = g[2];
                settings.videoH = g[3];
                settings.save(prefs);
            }
        });
        setContentView(buildUi());
        hideSystemBars();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // only for the "running in background" notification; everything works without it
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        handleExit(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleExit(intent);
        n3.handleIntent(intent);  // goggles plugged in while the app runs
    }

    /** "Exit" from the notification: stop everything and close. */
    private void handleExit(Intent intent) {
        if (intent != null && TelemetryService.ACTION_EXIT.equals(intent.getAction())) {
            stopAll();
            TelemetryService.stop(this);
            finishAndRemoveTask();
        }
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
        startAll();
        if (settings.keepRunning) TelemetryService.start(this);
    }

    @Override
    protected void onStop() {
        // With "keep running" on, telemetry, alarms, logging and the video receiver continue while
        // another app is in front or the screen is off; the foreground service keeps the process alive.
        if (!settings.keepRunning && !isChangingConfigurations()) stopAll();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopAll();
        TelemetryService.stop(this);
        alerts.shutdown();
        videoPanel.release();
        super.onDestroy();
    }

    private void startAll() {
        if (started) return;
        started = true;
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        mcLock = wm.createMulticastLock("elrs-telemetry");
        mcLock.setReferenceCounted(false);
        mcLock.acquire();  // some phones drop broadcast packets without this lock
        router.start();
        startReceiver();
        n3.start(launchIntent);
        ui.post(tick);
        ui.post(logTick);
    }

    private void stopAll() {
        if (!started) return;
        started = false;
        ui.removeCallbacks(tick);
        ui.removeCallbacks(logTick);
        stopReceiver();
        n3.stop();
        router.stop();
        logger.close();
        if (mcLock != null && mcLock.isHeld()) mcLock.release();
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
                if (running) netStatus = "Error: " + e.getMessage();
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
        sb.append(n).append("B ").append(frames).append(" frm: ");
        int show = Math.min(n, 40);
        for (int i = 0; i < show; i++) sb.append(String.format("%02X ", b[i] & 0xFF));
        if (n > show) sb.append('…');
        synchronized (rawLines) {
            rawLines.addFirst(sb.toString());
            while (rawLines.size() > 14) rawLines.removeLast();
        }
    }

    // ================================================================ Goggles video

    /** Connect button / camera panel: (re)connect the goggles or explain how to plug them in. */
    private void connectGoggles() {
        if (!n3.accessoryPresent()) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Connect the goggles")
                    .setMessage("No DJI goggles found on USB.\n\n"
                            + "1. Turn on DJI Goggles N3 and link them to the aircraft.\n"
                            + "2. Connect the goggles to this device with a USB-C data cable.\n"
                            + "3. When Android asks which app to open, choose ELRS Telemetry.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        n3.reconnect();
        Toast.makeText(this, "Connecting to the goggles…", Toast.LENGTH_SHORT).show();
    }

    private void setVideoMode(int mode) {
        settings.videoMode = mode;
        if (mode != Settings.VIDEO_OFF) settings.videoShownMode = mode;
        settings.save(prefs);
        applyVideoMode();
    }

    private void applyVideoMode() {
        boolean show = settings.videoMode != Settings.VIDEO_OFF;
        boolean replace = show && settings.videoMode == Settings.VIDEO_REPLACE_MAP;
        videoWindow.setVisibility(show ? View.VISIBLE : View.GONE);
        map.view.setVisibility(replace ? View.INVISIBLE : View.VISIBLE);
        if (bVideo != null) {
            ((GradientDrawable) bVideo.getBackground()).setColor(show ? Color.argb(220, 0, 140, 200) : C_PANEL);
            bExpand.setVisibility(show ? View.VISIBLE : View.GONE);
            bExpand.setText(replace ? "PIP" : "FULL");
        }
        if (!show) return;
        if (replace) {
            videoWindow.setFloating(false);
            videoWindow.fillParent();
            return;
        }
        videoWindow.setFloating(true);
        int pw = gcsRoot.getWidth(), ph = gcsRoot.getHeight();
        if (pw == 0 || ph == 0) {          // not laid out yet
            gcsRoot.post(this::applyVideoMode);
            return;
        }
        if (settings.videoW > 0 && settings.videoH > 0) {
            videoWindow.setGeometry(Math.round(settings.videoX * pw), Math.round(settings.videoY * ph),
                    Math.round(settings.videoW * pw), Math.round(settings.videoH * ph));
        } else {
            int[] p = Settings.VIDEO_PRESETS[1];  // default: Medium, centred above the buttons
            int w = dp(p[0]), h = dp(p[1]);
            videoWindow.setGeometry((pw - w) / 2, ph - h - dp(58), w, h);
        }
    }

    // ================================================================ Update loops

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
        List<String> notices = new ArrayList<>();
        List<String> alarmLines = new ArrayList<>();
        boolean linkFresh;
        AlarmSet al = settings.alarms;
        synchronized (state) {
            TelemetryState s = state;
            long lastTelemetry = Math.max(s.linkTime, Math.max(s.batteryTime, s.gpsTime));
            linkFresh = fresh(lastTelemetry, now);
            boolean ls = !fresh(s.linkTime, now);
            boolean gpsFresh = fresh(s.gpsTime, now);
            boolean attFresh = fresh(s.attitudeTime, now);
            boolean battFresh = fresh(s.batteryTime, now);
            boolean baroFresh = fresh(s.baroTime, now);
            boolean hasFix = s.gpsTime != 0 && s.sats >= 4 && (s.lat != 0 || s.lon != 0);
            if (linkFresh) hadData = true;

            // Packet rate
            if (rateStart == 0 || now - rateStart >= 1000) {
                if (rateStart != 0) rate = (s.datagramCount - rateCount) * 1000.0 / (now - rateStart);
                rateStart = now;
                rateCount = s.datagramCount;
            }

            // Heading: GPS course when moving, otherwise attitude yaw
            if (gpsFresh && s.groundSpeedKmh > 3) curHeading = s.headingDeg;
            else if (attFresh) curHeading = s.yawDeg;
            else if (gpsFresh) curHeading = s.headingDeg;

            // Relative altitude: baro if available (FC zeroes it at arming), else GPS minus home
            if (baroFresh) curRelAlt = s.baroAltM;
            else if (gpsFresh && s.homeSet) curRelAlt = s.gpsAltM - s.homeAltM;
            else if (!linkFresh) curRelAlt = Double.NaN;
            boolean altValid = !Double.isNaN(curRelAlt) && (baroFresh || gpsFresh);

            curDist = s.homeDistanceM();
            int cells = s.cellCount();
            curCell = cells > 0 ? s.voltage / cells : Double.NaN;

            // Arm state: Betaflight/INAV append '*' to the flight mode while disarmed
            String mode = s.flightMode == null ? "" : s.flightMode.trim();
            curArmedKnown = fresh(s.modeTime, now) && !mode.isEmpty();
            curArmed = curArmedKnown && !mode.endsWith("*");
            boolean flying = linkFresh && (!curArmedKnown || curArmed);

            stats.update(now, flying, altValid ? curRelAlt : Double.NaN, gpsFresh ? curDist : Double.NaN,
                    gpsFresh ? s.groundSpeedKmh : Double.NaN,
                    battFresh ? curCell : Double.NaN, battFresh ? s.voltage : Double.NaN,
                    battFresh ? s.current : Double.NaN);

            // ---- Alarms
            int bestRssi = s.rssi2 != 0 ? Math.max(s.rssi1, s.rssi2) : s.rssi1;
            al.get(AlarmSet.LOW_CELL).evaluate(curCell, battFresh && cells > 0, flying);
            al.get(AlarmSet.LOW_BATTERY_PCT).evaluate(s.batteryPct, battFresh && s.batteryPct > 0, flying);
            al.get(AlarmSet.LOW_LQ).evaluate(s.lq, !ls, flying);
            al.get(AlarmSet.LOW_RSSI).evaluate(bestRssi, !ls && s.rssi1 != 0, flying);
            al.get(AlarmSet.TELEMETRY_LOST).evaluate((now - lastTelemetry) / 1000.0, hadData, flying);
            al.get(AlarmSet.MIN_ALT).evaluate(curRelAlt, altValid, flying);
            al.get(AlarmSet.MAX_ALT).evaluate(curRelAlt, altValid, flying);
            al.get(AlarmSet.MIN_SPEED).evaluate(s.groundSpeedKmh, gpsFresh, flying);
            al.get(AlarmSet.MAX_SPEED).evaluate(s.groundSpeedKmh, gpsFresh, flying);
            al.get(AlarmSet.MAX_DISTANCE).evaluate(curDist, gpsFresh && s.homeSet, flying);
            al.get(AlarmSet.LOW_SATS).evaluate(s.sats, gpsFresh, flying);
            StringBuilder ids = new StringBuilder();
            for (AlarmSet.Alarm a : al.all()) {
                if (a.active) {
                    alarmLines.add(a.title.toUpperCase(Locale.US) + "  " + a.format(a.value) + " " + a.unit);
                    alerts.alarm(a.id, a.speech, settings.alarmRepeatSec * 1000L, settings.voice, settings.vibrate);
                    if (ids.length() > 0) ids.append(' ');
                    ids.append(a.id);
                } else {
                    alerts.clear(a.id);
                }
            }
            activeAlarmIds = ids.toString();

            // ---- Map
            if (hasFix && s.gpsTime != lastTrackGpsTime) {
                lastTrackGpsTime = s.gpsTime;
                map.addTrackPoint(s.lat, s.lon);
                settings.hasLastPos = true;
                settings.lastLat = s.lat;
                settings.lastLon = s.lon;
            }
            map.update(hasFix, s.lat, s.lon, curHeading, !gpsFresh, s.homeSet, s.homeLat, s.homeLon);

            // ---- Top bar
            setText(tMode, mode.isEmpty() ? "MODE —" : mode.replace("*", ""), C_TEXT, !fresh(s.modeTime, now));
            if (!curArmedKnown) styleChip(tArm, "ARM ?", C_STALE);
            else styleChip(tArm, curArmed ? "ARMED" : "DISARMED", curArmed ? C_BAD : C_OK);
            tTimer.setText("⏱ " + FlightStats.formatDuration(stats.flightMs));

            boolean lqAlarm = al.isActive(AlarmSet.LOW_LQ);
            setText(tLq, "LQ " + s.lq + "%", lqAlarm ? C_BAD : s.lq < 80 ? C_WARN : C_OK, ls);
            setText(tRssi, s.rssi1 + "/" + s.rssi2 + " dBm", al.isActive(AlarmSet.LOW_RSSI) ? C_BAD : C_TEXT, ls);
            setText(tSats, "🛰 " + s.sats, al.isActive(AlarmSet.LOW_SATS) ? C_BAD : C_OK, !gpsFresh);
            if (logger.isOpen()) {
                styleChip(tRec, "● REC " + FlightStats.formatDuration(now - logger.startTime), C_BAD);
            } else {
                styleChip(tRec, recordEnabled ? "○ REC armed" : "○ REC off", C_STALE);
            }
            String link;
            if (s.lastDatagramTime == 0) link = netStatus + " · no data · " + router.describe();
            else link = String.format(Locale.US, "%s · %.0f p/s · %s", s.lastSource, rate, router.describe());
            tLink.setText(link);
            tLink.setTextColor(fresh(s.lastDatagramTime, now) ? C_LABEL : C_BAD);

            // ---- Left panel
            horizon.set(s.pitchDeg, s.rollDeg, curHeading, !attFresh);
            boolean altAlarm = al.isActive(AlarmSet.MIN_ALT) || al.isActive(AlarmSet.MAX_ALT);
            setText(vAlt, Double.isNaN(curRelAlt) ? "—" : String.format(Locale.US, "%.0f m", curRelAlt),
                    altAlarm ? C_BAD : C_TEXT, !altValid);
            setText(vVario, String.format(Locale.US, "%+.1f m/s", s.varioMs), varioColor(s.varioMs),
                    !fresh(s.varioTime, now));
            boolean spdAlarm = al.isActive(AlarmSet.MIN_SPEED) || al.isActive(AlarmSet.MAX_SPEED);
            setText(vSpeed, String.format(Locale.US, "%.0f km/h", s.groundSpeedKmh), spdAlarm ? C_BAD : C_TEXT, !gpsFresh);
            setText(vDist, Double.isNaN(curDist) ? "no home" : formatDist(curDist),
                    al.isActive(AlarmSet.MAX_DISTANCE) ? C_BAD : C_TEXT, !gpsFresh);
            double homeDir = Double.isNaN(curDist) ? Double.NaN : (s.homeBearingDeg() + 180) % 360;
            setText(vHomeDir, Double.isNaN(homeDir) ? "—" : String.format(Locale.US, "%03.0f° %s", homeDir, arrow(homeDir - curHeading)),
                    C_ACCENT, !gpsFresh);
            setText(vGpsAlt, s.gpsAltM + " m", C_TEXT, !gpsFresh);

            // ---- Right panel
            double warnCell = al.get(AlarmSet.LOW_CELL).threshold;
            boolean battAlarm = al.isActive(AlarmSet.LOW_CELL) || al.isActive(AlarmSet.LOW_BATTERY_PCT);
            int battColor = battAlarm ? C_BAD : Double.isNaN(curCell) ? C_OK
                    : curCell < warnCell + 0.2 ? C_WARN : C_OK;
            float frac = s.batteryPct > 0 ? s.batteryPct / 100f
                    : Double.isNaN(curCell) ? 0 : (float) ((curCell - 3.3) / (4.2 - 3.3));
            battery.set(frac, String.format(Locale.US, "%.1fV  %d%%", s.voltage, s.batteryPct), battColor, !battFresh);
            setText(vCell, cells > 0 ? String.format(Locale.US, "%dS %.2fV", cells, curCell) : "—", battColor, !battFresh);
            setText(vCurr, String.format(Locale.US, "%.1f A", s.current), C_TEXT, !battFresh);
            setText(vMah, s.mah + " mAh", C_TEXT, !battFresh);
            setText(vPower, String.format(Locale.US, "%.0f W", s.voltage * s.current), C_TEXT, !battFresh);
            setText(vSnr, s.snr + " dB", C_TEXT, ls);
            setText(vTxPow, s.txPowerMw >= 0 ? s.txPowerMw + " mW" : "?", C_TEXT, ls);
            setText(vDown, s.downRssi + "dBm " + s.downLq + "%", C_TEXT, ls);
            setText(vRf, "A" + (s.antenna + 1) + " · RF" + s.rfMode, C_TEXT, ls);
            sparkAlt.set(stats.altHistory, stats.historyCount, stats.historyPos);
            sparkVolt.set(stats.voltHistory, stats.historyCount, stats.historyPos);

            // ---- Bottom info
            StringBuilder info = new StringBuilder();
            if (hasFix) info.append(String.format(Locale.US, "%.6f, %.6f", s.lat, s.lon));
            else info.append("No GPS position");
            if (logger.isOpen()) info.append("\n").append(logger.fileName).append(" (").append(logger.rows).append(" rows)");
            if (downloading) info.append("\n").append(downloadStatus);
            if (videoPanel.isLive()) info.append("\nVideo: ").append(videoPanel.describe());
            tInfo.setText(info);

            // ---- Notices (not alarms)
            if (s.lastDatagramTime == 0) {
                notices.add("Waiting for telemetry — is this device on the backpack WiFi? Radio: Backpack › Telemetry = WiFi");
            } else if (s.mavlinkSuspect) {
                notices.add("Receiving MAVLink — set Link Mode = Normal on the radio");
            } else if (s.datagramCount > 20 && s.frameCount == 0) {
                notices.add("Packets arrive but contain no CRSF frames — Settings › Show raw UDP data");
            }
        }

        videoPanel.setLinkStatus(n3.status());
        videoPanel.refresh(now);
        if (videoPanel.isStalled()) notices.add("No video from the goggles — check the goggles' link to the aircraft");

        // Logging on/off
        long lastData;
        synchronized (state) {
            lastData = state.lastDatagramTime;
        }
        if (recordEnabled && linkFresh && !logger.isOpen()) {
            if (!logger.open()) notices.add("Could not start log: " + logger.lastError);
        }
        if (logger.isOpen() && (!recordEnabled || now - lastData > LOG_CLOSE_AFTER_MS)) logger.close();

        // ---- Banner
        List<String> lines = new ArrayList<>(alarmLines);
        if (!alarmLines.isEmpty() && alerts.isMuted()) {
            lines.add("🔇 muted " + FlightStats.formatDuration(alerts.mutedRemainingMs()) + " · tap to unmute");
        } else if (!alarmLines.isEmpty()) {
            lines.add("(tap to mute for 2 min)");
        }
        lines.addAll(notices);
        if (lines.isEmpty()) {
            tWarn.setVisibility(View.GONE);
        } else {
            tWarn.setVisibility(View.VISIBLE);
            tWarn.setText(String.join("\n", lines));
            ((GradientDrawable) tWarn.getBackground()).setColor(
                    !alarmLines.isEmpty() ? Color.argb(225, 200, 30, 30) : Color.argb(210, 40, 45, 55));
        }
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
                    "%s,%d,%.7f,%.7f,%d,%s,%.1f,%.2f,%.1f,%.1f,%d,%.1f,%.1f,%.1f,%.2f,%s,%.2f,%d,%d,%.1f,%d,%d,%d,%d,%d,%d,%d,%s,%s,%s,%s",
                    isoFmt.format(new Date(now)), now - logger.startTime,
                    s.lat, s.lon, s.gpsAltM, num(curRelAlt, "%.1f"), s.baroAltM, s.varioMs, s.groundSpeedKmh,
                    s.headingDeg, s.sats, s.pitchDeg, s.rollDeg, s.yawDeg,
                    s.voltage, cells > 0 ? String.format(Locale.US, "%.3f", s.voltage / cells) : "",
                    s.current, s.mah, s.batteryPct, s.voltage * s.current,
                    s.rssi1, s.rssi2, s.lq, s.snr, s.txPowerMw, s.downRssi, s.downLq,
                    csv(s.flightMode), num(s.homeDistanceM(), "%.1f"), num(s.homeBearingDeg(), "%.0f"),
                    csv(activeAlarmIds));
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

    /** Which way to turn, relative to the aircraft's nose, to head home. */
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

    // ================================================================ Settings host

    @Override
    public Settings settings() {
        return settings;
    }

    @Override
    public GcsMap map() {
        return map;
    }

    @Override
    public void onSettingsSaved(int oldPort) {
        map.setMapType(settings.mapType);
        recordEnabled = settings.autoRecord;
        settings.save(prefs);
        if (settings.port != oldPort) {
            stopReceiver();
            startReceiver();
        }
        applyVideoMode();
    }

    @Override
    public String usbStatus() {
        return n3.status() + (n3.identity().isEmpty() ? "" : "\n" + n3.identity());
    }

    @Override
    public void usbReconnect() {
        connectGoggles();
    }

    @Override
    public void reloadSettings() {
        settings.load(prefs);
        map.setMapType(settings.mapType);
    }

    @Override
    public String startOfflineDownload(int maxZoom) {
        if (downloading) return "A download is already running";
        if (!router.hasInternet()) {
            return "No internet. The backpack WiFi has no internet: download offline maps before connecting "
                    + "to it, or enable mobile data.";
        }
        int n = map.tilesForVisibleArea(maxZoom);
        if (n > GcsMap.MAX_DOWNLOAD_TILES) {
            return "Area too large (" + n + " tiles). Zoom in or choose a lower detail level.";
        }
        String err = map.downloadVisibleArea(maxZoom, new CacheManager.CacheManagerCallback() {
            private int total = n;

            @Override
            public void downloadStarted() {
                downloading = true;
                downloadStatus = "Offline map: starting…";
            }

            @Override
            public void setPossibleTilesInArea(int t) {
                total = t;
            }

            @Override
            public void updateProgress(int progress, int zoom, int zoomMin, int zoomMax) {
                downloadStatus = String.format(Locale.US, "Offline map: %d / %d tiles (zoom %d of %d–%d)",
                        progress, total, zoom, zoomMin, zoomMax);
            }

            @Override
            public void onTaskComplete() {
                downloading = false;
                downloadStatus = "Offline map ready: " + total + " tiles";
                ui.post(() -> Toast.makeText(MainActivity.this, downloadStatus, Toast.LENGTH_LONG).show());
            }

            @Override
            public void onTaskFailed(int errors) {
                downloading = false;
                downloadStatus = "Offline map finished with " + errors + " failed tiles";
                ui.post(() -> Toast.makeText(MainActivity.this, downloadStatus, Toast.LENGTH_LONG).show());
            }
        });
        if (err == null) {
            downloading = true;
            downloadStatus = "Offline map: starting…";
        }
        return err;
    }

    @Override
    public String downloadStatus() {
        return downloadStatus;
    }

    // ================================================================ UI

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

    /** Label + value box; returns the value TextView. */
    private TextView stat(LinearLayout row, String label, float valueSp) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(3), dp(4), dp(3));
        row.addView(box, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.setMargins(dp(3), 0, dp(3), 0);
        parent.addView(t, lp);
        return t;
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(map.view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        // Video sits above the map and below the panels, so in full-screen mode telemetry overlays the video
        root.addView(videoWindow);
        gcsRoot = root;

        // ---- Top bar
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
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
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
            Toast.makeText(this, recordEnabled ? "Logging on (starts when telemetry arrives)" : "Logging stopped",
                    Toast.LENGTH_SHORT).show();
        });
        top.addView(tRec, cl);
        tLink = text(10, C_LABEL, false);
        top.addView(tLink);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(44), Gravity.TOP);
        tlp.setMargins(dp(8), dp(6), dp(8), 0);
        root.addView(top, tlp);

        // ---- Left panel: flight
        leftPanel = panel();
        horizon = new HorizonView(this);
        leftPanel.addView(horizon, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(118)));
        LinearLayout r = row(leftPanel);
        vAlt = stat(r, "ALTITUDE", 20);
        vVario = stat(r, "VARIO", 15);
        r = row(leftPanel);
        vSpeed = stat(r, "SPEED", 16);
        vDist = stat(r, "HOME DIST", 16);
        r = row(leftPanel);
        vHomeDir = stat(r, "TO HOME", 15);
        vGpsAlt = stat(r, "GPS ALT (MSL)", 15);
        // Position, log file, downloads and video info (the bottom edge is reserved for the buttons)
        tInfo = text(10, C_LABEL, false);
        tInfo.setSingleLine(false);
        tInfo.setMaxLines(4);
        tInfo.setPadding(dp(6), dp(4), dp(4), 0);
        leftPanel.addView(tInfo);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(dp(210), FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        llp.setMargins(dp(8), dp(56), 0, 0);
        root.addView(leftPanel, llp);

        // ---- Right panel: power + link
        rightPanel = panel();
        battery = new BatteryView(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        blp.setMargins(dp(2), dp(2), dp(2), dp(2));
        rightPanel.addView(battery, blp);
        r = row(rightPanel);
        vCell = stat(r, "CELL", 14);
        vCurr = stat(r, "CURRENT", 14);
        r = row(rightPanel);
        vMah = stat(r, "USED", 14);
        vPower = stat(r, "POWER", 14);
        sectionTitle(rightPanel, "LINK");
        r = row(rightPanel);
        vSnr = stat(r, "SNR", 13);
        vTxPow = stat(r, "TX POWER", 13);
        r = row(rightPanel);
        vDown = stat(r, "DOWNLINK", 12);
        vRf = stat(r, "ANT / RF", 12);
        sparkAlt = new SparklineView(this, "ALTITUDE (5 min)", "m", C_ACCENT);
        rightPanel.addView(sparkAlt, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));
        sparkVolt = new SparklineView(this, "VOLTAGE (5 min)", "V", Color.rgb(255, 200, 0));
        rightPanel.addView(sparkVolt, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(dp(210), FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        rlp.setMargins(0, dp(56), dp(8), 0);
        root.addView(rightPanel, rlp);

        // ---- Alarm / notice banner (tap to mute alarms)
        tWarn = new TextView(this);
        tWarn.setTextColor(Color.WHITE);
        tWarn.setTextSize(13);
        tWarn.setTypeface(Typeface.DEFAULT_BOLD);
        tWarn.setGravity(Gravity.CENTER);
        tWarn.setPadding(dp(14), dp(6), dp(14), dp(6));
        tWarn.setBackground(roundBg(Color.argb(210, 40, 45, 55), 10));
        tWarn.setVisibility(View.GONE);
        tWarn.setOnClickListener(v -> {
            if (alerts.isMuted()) {
                alerts.unmute();
                Toast.makeText(this, "Alarms unmuted", Toast.LENGTH_SHORT).show();
            } else if (!settings.alarms.active().isEmpty()) {
                alerts.mute(MUTE_MS);
                Toast.makeText(this, "Alarms muted for 2 minutes", Toast.LENGTH_SHORT).show();
            }
        });
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        wlp.setMargins(dp(230), dp(58), dp(230), 0);
        root.addView(tWarn, wlp);

        // ---- Bottom: map buttons

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        bFollow = mapButton(buttons, "⌖", v -> {
            map.setFollow(!map.isFollowing());
            centerOnPlane();
        });
        mapButton(buttons, "🗺", v -> {
            settings.mapType = (settings.mapType + 1) % Settings.MAP_NAMES.length;
            map.setMapType(settings.mapType);
            Toast.makeText(this, "Map: " + Settings.MAP_NAMES[settings.mapType], Toast.LENGTH_SHORT).show();
        });
        mapButton(buttons, "⌂", v -> {
            synchronized (state) {
                state.resetHome();
            }
            Toast.makeText(this, "Home will be reset at the next GPS fix", Toast.LENGTH_SHORT).show();
        });
        mapButton(buttons, "⌫", v -> {
            map.clearTrack();
            Toast.makeText(this, "Track cleared", Toast.LENGTH_SHORT).show();
        });
        // 📷 = goggles video panel on/off;  FULL/PIP = panel <-> instead of the map (shown while the video is on)
        bVideo = mapButton(buttons, "📷", v -> {
            boolean on = settings.videoMode == Settings.VIDEO_OFF;
            int shown = settings.videoShownMode == Settings.VIDEO_OFF ? Settings.VIDEO_PANEL : settings.videoShownMode;
            setVideoMode(on ? shown : Settings.VIDEO_OFF);
            if (on && !n3.isStreaming() && !n3.accessoryPresent()) {
                Toast.makeText(this, "Connect the goggles with a USB-C cable", Toast.LENGTH_SHORT).show();
            }
        });
        bExpand = mapButton(buttons, "FULL", v -> setVideoMode(settings.videoMode == Settings.VIDEO_REPLACE_MAP
                ? Settings.VIDEO_PANEL : Settings.VIDEO_REPLACE_MAP));
        bExpand.setTextSize(11);
        bExpand.setTypeface(Typeface.DEFAULT_BOLD);
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
        applyVideoMode();
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

    // ================================================================ Dialogs

    Dialog showSettings() {
        return new SettingsDialog(this, this).show();
    }

    private void showSummary() {
        String msg = String.format(Locale.US,
                "Flight time:  %s\nMax altitude:  %s\nMax distance:  %s\nMax speed:  %.0f km/h\n"
                        + "Max current:  %.1f A\nMin cell voltage:  %s\nTrack points:  %d\n\nLog: %s",
                FlightStats.formatDuration(stats.flightMs),
                Double.isNaN(stats.maxAltM) ? "—" : String.format(Locale.US, "%.0f m", stats.maxAltM),
                formatDist(stats.maxDistM), stats.maxSpeedKmh, stats.maxCurrentA,
                Double.isNaN(stats.minCellV) ? "—" : String.format(Locale.US, "%.2f V", stats.minCellV),
                map.trackSize(),
                logger.isOpen() ? "Downloads/" + FlightLogger.FOLDER + "/" + logger.fileName : "off");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Flight summary")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .setNeutralButton("Reset", (d, w) -> stats.reset())
                .show();
    }

    @Override
    public void showRawData() {
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
                t.setText(sb.length() == 0 ? "No packets yet…" : sb.toString());
                ui.postDelayed(this, 500);
            }
        };
        ui.post(upd);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Raw UDP packets")
                .setView(t)
                .setPositiveButton("Close", null)
                .setOnDismissListener(d -> {
                    rawEnabled = false;
                    ui.removeCallbacks(upd);
                })
                .show();
    }
}
