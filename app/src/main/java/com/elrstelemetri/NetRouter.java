package com.elrstelemetri;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;

import java.net.DatagramSocket;
import java.util.Objects;

/**
 * Backpack WiFi'ında internet yoktur. Telefon bu ağa bağlıyken harita karolarının inebilmesi için
 * uygulamanın internet trafiği mobil veriye yönlendirilir; telemetri UDP soketi ise WiFi'a bağlanır.
 * WiFi'da internet varsa (ör. evde test) hiçbir yönlendirme yapılmaz.
 */
public final class NetRouter {

    private final ConnectivityManager cm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable onRouteChanged;

    private volatile Network wifi;
    private volatile boolean wifiValidated;
    private volatile Network cellular;
    private Network bound;
    private boolean started;

    public NetRouter(Context c, Runnable onRouteChanged) {
        cm = (ConnectivityManager) c.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        this.onRouteChanged = onRouteChanged;
    }

    private final ConnectivityManager.NetworkCallback wifiCb = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(Network n, NetworkCapabilities nc) {
            wifi = n;
            wifiValidated = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            evaluate();
        }

        @Override
        public void onLost(Network n) {
            if (n.equals(wifi)) {
                wifi = null;
                wifiValidated = false;
                evaluate();
            }
        }
    };

    private final ConnectivityManager.NetworkCallback cellCb = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network n) {
            cellular = n;
            evaluate();
        }

        @Override
        public void onLost(Network n) {
            if (n.equals(cellular)) {
                cellular = null;
                evaluate();
            }
        }
    };

    public void start() {
        if (started || cm == null) return;
        started = true;
        try {
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCb);
            cm.requestNetwork(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cellCb);
        } catch (RuntimeException e) {
            // İzin/sistem kısıtı: yönlendirme olmadan devam et
        }
    }

    public void stop() {
        if (!started) return;
        started = false;
        try {
            cm.unregisterNetworkCallback(wifiCb);
            cm.unregisterNetworkCallback(cellCb);
        } catch (RuntimeException ignored) {
        }
        main.post(() -> {
            cm.bindProcessToNetwork(null);
            bound = null;
        });
    }

    private void evaluate() {
        main.post(() -> {
            Network w = wifi, c = cellular;
            Network target = (w != null && !wifiValidated && c != null) ? c : null;
            if (!Objects.equals(target, bound)) {
                cm.bindProcessToNetwork(target);
                bound = target;
                if (onRouteChanged != null) onRouteChanged.run();
            }
        });
    }

    /** Telemetri soketini (varsa) WiFi ağına bağlar. */
    public void bindToWifi(DatagramSocket s) {
        Network w = wifi;
        if (w == null) return;
        try {
            w.bindSocket(s);
        } catch (Exception ignored) {
            // bağlanamazsa varsayılan yönlendirmeyle dinlemeye devam eder
        }
    }

    public String describe() {
        if (bound != null) return "harita: mobil veri";
        if (wifi != null && wifiValidated) return "WiFi (internetli)";
        if (wifi != null) return cellular == null ? "harita: internet yok" : "WiFi";
        return "WiFi yok";
    }
}
