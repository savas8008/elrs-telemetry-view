package com.elrstelemetri;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int DEFAULT_PORT = 14550;
    private static final long STALE_MS = 3000;
    private static final int C_OK = Color.rgb(230, 230, 230);
    private static final int C_WARN = Color.rgb(255, 170, 0);
    private static final int C_BAD = Color.rgb(255, 70, 70);
    private static final int C_STALE = Color.rgb(100, 100, 100);

    private final TelemetryState state = new TelemetryState();
    private final CrsfParser parser = new CrsfParser(state);
    private final Handler ui = new Handler();
    private final ArrayDeque<String> rawLines = new ArrayDeque<>();

    private volatile boolean running;
    private volatile boolean rawEnabled;
    private volatile String status = "";
    private volatile DatagramSocket socket;
    private Thread rxThread;
    private WifiManager.MulticastLock mcLock;
    private SharedPreferences prefs;
    private int port;

    private long rateWindowStart, rateWindowCount;
    private double rate;

    private TextView tStatus, tLq, tRssi, tSnr, tPower, tDown;
    private TextView tVolt, tCell, tCurr, tMah, tPct;
    private TextView tSats, tSpeed, tGpsAlt, tBaro, tVario, tDist, tBearing, tPos;
    private TextView tMode, tRaw;
    private HorizonView horizon;
    private EditText ePort;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("ayarlar", MODE_PRIVATE);
        port = prefs.getInt("port", DEFAULT_PORT);
        setContentView(buildUi());
    }

    @Override
    protected void onStart() {
        super.onStart();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        mcLock = wm.createMulticastLock("elrs-telem");
        mcLock.setReferenceCounted(false);
        mcLock.acquire();  // bazı telefonlar broadcast paketlerini bu kilit olmadan düşürür
        startReceiver();
        ui.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(tick);
        stopReceiver();
        if (mcLock != null && mcLock.isHeld()) mcLock.release();
    }

    // ---------------------------------------------------------------- UDP

    private void startReceiver() {
        running = true;
        final int listenPort = port;
        rxThread = new Thread(new Runnable() {
            @Override
            public void run() {
                DatagramSocket s = null;
                try {
                    s = new DatagramSocket(null);
                    s.setReuseAddress(true);
                    s.setBroadcast(true);
                    s.setSoTimeout(1000);
                    s.bind(new InetSocketAddress(listenPort));
                    socket = s;
                    status = "UDP " + listenPort + " dinleniyor";
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
                            if (frames > 0) {
                                state.mavlinkSuspect = false;
                            } else if (n > 0 && (buf[0] == (byte) 0xFD || buf[0] == (byte) 0xFE)) {
                                state.mavlinkSuspect = true;  // MAVLink v2 / v1 başlangıç baytı
                            }
                        }
                        if (rawEnabled) addRaw(buf, n, frames);
                    }
                } catch (Exception e) {
                    if (running) status = "Hata: " + e.getMessage();
                } finally {
                    if (s != null) s.close();
                }
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
        int show = Math.min(n, 48);
        for (int i = 0; i < show; i++) sb.append(String.format("%02X ", b[i] & 0xFF));
        if (n > show) sb.append("…");
        synchronized (rawLines) {
            rawLines.addFirst(sb.toString());
            while (rawLines.size() > 12) rawLines.removeLast();
        }
    }

    // ---------------------------------------------------------------- Ekran güncelleme

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 200);
        }
    };

    private static boolean stale(long t, long now) {
        return now - t > STALE_MS;
    }

    private void set(TextView v, String text, int color, boolean isStale) {
        v.setText(text);
        v.setTextColor(isStale ? C_STALE : color);
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        synchronized (state) {
            // Paket hızı
            if (rateWindowStart == 0) {
                rateWindowStart = now;
                rateWindowCount = state.datagramCount;
            } else if (now - rateWindowStart >= 1000) {
                rate = (state.datagramCount - rateWindowCount) * 1000.0 / (now - rateWindowStart);
                rateWindowStart = now;
                rateWindowCount = state.datagramCount;
            }
            String st = status;
            if (state.lastDatagramTime == 0) {
                st += " — henüz paket yok. Tablet backpack ağına bağlı mı, Lua'da Telemetry = WiFi mi?";
            } else {
                st += String.format(Locale.US, " — %s | %.1f paket/s | son: %d ms önce | çerçeve: %d",
                        state.lastSource, rate, now - state.lastDatagramTime, state.frameCount);
            }
            if (state.mavlinkSuspect) {
                st += "\n⚠ Gelen veri MAVLink gibi görünüyor. Kumandada Link Mode = Normal (CRSF) olmalı.";
            } else if (state.datagramCount > 20 && state.frameCount == 0) {
                st += "\n⚠ Paket geliyor ama CRSF çerçevesi bulunamadı. 'Ham veri' ile içeriğe bakın.";
            }
            tStatus.setText(st);

            // Link
            boolean ls = stale(state.linkTime, now);
            int lqColor = state.lq < 50 ? C_BAD : state.lq < 80 ? C_WARN : C_OK;
            set(tLq, state.lq + "%", lqColor, ls);
            set(tRssi, state.rssi1 + " / " + state.rssi2 + " dBm", state.rssi1 < -100 ? C_WARN : C_OK, ls);
            set(tSnr, state.snr + " dB", C_OK, ls);
            set(tPower, state.txPowerMw >= 0 ? state.txPowerMw + " mW" : "?", C_OK, ls);
            set(tDown, state.downRssi + " dBm  LQ " + state.downLq + "%", C_OK, ls);

            // Batarya
            boolean bs = stale(state.batteryTime, now);
            int cells = state.cellCount();
            double perCell = cells > 0 ? state.voltage / cells : 0;
            int battColor = cells == 0 ? C_OK : perCell < 3.4 ? C_BAD : perCell < 3.6 ? C_WARN : C_OK;
            set(tVolt, String.format(Locale.US, "%.1f V", state.voltage), battColor, bs);
            set(tCell, cells > 0 ? String.format(Locale.US, "%dS  %.2f V", cells, perCell) : "-", battColor, bs);
            set(tCurr, String.format(Locale.US, "%.1f A", state.current), C_OK, bs);
            set(tMah, state.mah + " mAh", C_OK, bs);
            set(tPct, state.batteryPct + "%", state.batteryPct < 20 ? C_BAD : C_OK, bs);

            // GPS
            boolean gs = stale(state.gpsTime, now);
            set(tSats, String.valueOf(state.sats), state.sats < TelemetryState.HOME_MIN_SATS ? C_WARN : C_OK, gs);
            set(tSpeed, String.format(Locale.US, "%.0f km/sa", state.groundSpeedKmh), C_OK, gs);
            String relAlt = state.homeSet ? String.format(Locale.US, " (ev +%d)", state.gpsAltM - state.homeAltM) : "";
            set(tGpsAlt, state.gpsAltM + " m" + relAlt, C_OK, gs);
            double dist = state.homeDistanceM();
            set(tDist, Double.isNaN(dist) ? "ev yok" : dist < 1000
                    ? String.format(Locale.US, "%.0f m", dist)
                    : String.format(Locale.US, "%.2f km", dist / 1000), C_OK, gs);
            double brg = state.homeBearingDeg();
            set(tBearing, Double.isNaN(brg) ? "-" : String.format(Locale.US, "%03.0f°", brg), C_OK, gs);
            set(tPos, String.format(Locale.US, "%.6f, %.6f", state.lat, state.lon), C_OK, gs);

            // Baro / vario
            set(tBaro, String.format(Locale.US, "%.1f m", state.baroAltM), C_OK, stale(state.baroTime, now));
            set(tVario, String.format(Locale.US, "%+.1f m/s", state.varioMs), C_OK, stale(state.varioTime, now));

            // Mod + ufuk
            set(tMode, state.flightMode.isEmpty() ? "—" : state.flightMode, Color.CYAN, stale(state.modeTime, now));
            double hdg = !gs ? state.headingDeg : state.yawDeg;
            horizon.set(state.pitchDeg, state.rollDeg, hdg, stale(state.attitudeTime, now));
        }

        if (rawEnabled) {
            StringBuilder sb = new StringBuilder();
            synchronized (rawLines) {
                for (String l : rawLines) sb.append(l).append('\n');
            }
            tRaw.setText(sb.toString());
        }
    }

    // ---------------------------------------------------------------- Arayüz

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView tile(LinearLayout row, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.rgb(30, 30, 34));
        box.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        row.addView(box, lp);

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(12);
        l.setTextColor(Color.rgb(150, 150, 160));
        box.addView(l);

        TextView v = new TextView(this);
        v.setText("-");
        v.setTextSize(22);
        v.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        v.setTextColor(C_STALE);
        box.addView(v);
        return v;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(r);
        return r;
    }

    private TextView header(LinearLayout parent, String text) {
        TextView h = new TextView(this);
        h.setText(text);
        h.setTextSize(14);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextColor(Color.rgb(120, 180, 255));
        h.setPadding(dp(4), dp(10), 0, dp(2));
        parent.addView(h);
        return h;
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(14, 14, 16));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(24));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("ELRS Telemetri");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        tStatus = new TextView(this);
        tStatus.setTextSize(12);
        tStatus.setTextColor(Color.rgb(180, 180, 180));
        root.addView(tStatus);

        tMode = tile(row(root), "Uçuş modu");
        tMode.setTextSize(30);
        tMode.setGravity(Gravity.CENTER_HORIZONTAL);

        horizon = new HorizonView(this);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(200));
        hp.setMargins(dp(3), dp(3), dp(3), dp(3));
        root.addView(horizon, hp);

        header(root, "BAĞLANTI");
        LinearLayout r = row(root);
        tLq = tile(r, "LQ");
        tRssi = tile(r, "RSSI 1/2");
        r = row(root);
        tSnr = tile(r, "SNR");
        tPower = tile(r, "TX güç");
        tDown = tile(row(root), "Downlink (kumanda tarafı)");

        header(root, "BATARYA");
        r = row(root);
        tVolt = tile(r, "Voltaj");
        tCell = tile(r, "Hücre");
        r = row(root);
        tCurr = tile(r, "Akım");
        tMah = tile(r, "Harcanan");
        tPct = tile(r, "Kalan");

        header(root, "KONUM / İRTİFA");
        r = row(root);
        tDist = tile(r, "Ev uzaklığı");
        tBearing = tile(r, "Ev → uçak yönü");
        r = row(root);
        tSpeed = tile(r, "Yer hızı");
        tSats = tile(r, "Uydu");
        r = row(root);
        tGpsAlt = tile(r, "GPS irtifa");
        tBaro = tile(r, "Baro irtifa");
        tVario = tile(r, "Vario");
        tPos = tile(row(root), "Koordinat");
        tPos.setTextSize(16);

        header(root, "AYARLAR");
        LinearLayout ctl = row(root);
        Button bHome = new Button(this);
        bHome.setText("Evi sıfırla");
        bHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                synchronized (state) {
                    state.resetHome();
                }
            }
        });
        ctl.addView(bHome);

        final Button bRaw = new Button(this);
        bRaw.setText("Ham veri: kapalı");
        ctl.addView(bRaw);

        LinearLayout portRow = row(root);
        TextView pl = new TextView(this);
        pl.setText("UDP port: ");
        pl.setTextColor(C_OK);
        portRow.addView(pl);
        ePort = new EditText(this);
        ePort.setInputType(InputType.TYPE_CLASS_NUMBER);
        ePort.setText(String.valueOf(port));
        ePort.setTextColor(Color.WHITE);
        ePort.setEms(5);
        portRow.addView(ePort);
        Button bPort = new Button(this);
        bPort.setText("Uygula");
        bPort.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    int p = Integer.parseInt(ePort.getText().toString().trim());
                    if (p < 1 || p > 65535) throw new NumberFormatException();
                    port = p;
                    prefs.edit().putInt("port", p).apply();
                    stopReceiver();
                    startReceiver();
                } catch (NumberFormatException e) {
                    ePort.setError("1-65535");
                }
            }
        });
        portRow.addView(bPort);

        tRaw = new TextView(this);
        tRaw.setTextSize(10);
        tRaw.setTypeface(Typeface.MONOSPACE);
        tRaw.setTextColor(Color.rgb(120, 255, 120));
        tRaw.setVisibility(View.GONE);
        root.addView(tRaw);

        bRaw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rawEnabled = !rawEnabled;
                bRaw.setText(rawEnabled ? "Ham veri: açık" : "Ham veri: kapalı");
                tRaw.setVisibility(rawEnabled ? View.VISIBLE : View.GONE);
            }
        });

        return scroll;
    }
}
