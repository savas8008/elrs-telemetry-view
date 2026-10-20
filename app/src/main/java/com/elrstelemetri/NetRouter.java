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
 * The backpack WiFi has no internet. While the phone is on that network, the app's internet
 * traffic (map tiles) is routed over mobile data when it is available, and the telemetry UDP
 * socket is bound to WiFi. If there is no mobile data the map runs from the offline tile cache.
 * Nothing is rerouted when the WiFi itself has internet (e.g. testing at home).
 *
 * The backpack answers Android's internet check with a redirect to its web page, so Android sees a
 * WiFi without internet (captive portal). Some phones then drop such a network or switch away from
 * it. To keep it, the app holds a request for the WiFi network (a network an app is using is not
 * torn down by the system) and reports every drop, so the UDP socket can be re-bound to the new
 * network: a socket bound to a lost network would never receive again.
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
    private volatile int wifiDrops;
    private volatile long lastDropTime;

    /** @param onRouteChanged called on the main thread when the map route or the WiFi network changes */
    public NetRouter(Context c, Runnable onRouteChanged) {
        cm = (ConnectivityManager) c.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        this.onRouteChanged = onRouteChanged;
    }

    private final ConnectivityManager.NetworkCallback wifiCb = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(Network n, NetworkCapabilities nc) {
            boolean changed = !n.equals(wifi);
            wifi = n;
            wifiValidated = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            evaluate(changed);
        }

        @Override
        public void onLost(Network n) {
            if (n.equals(wifi)) {
                wifi = null;
                wifiValidated = false;
                wifiDrops++;
                lastDropTime = System.currentTimeMillis();
                evaluate(true);
            }
        }
    };

    private final ConnectivityManager.NetworkCallback cellCb = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network n) {
            cellular = n;
            evaluate(false);
        }

        @Override
        public void onLost(Network n) {
            if (n.equals(cellular)) {
                cellular = null;
                evaluate(false);
            }
        }
    };

    public void start() {
        if (started || cm == null) return;
        started = true;
        try {
            // a request (not just a listen) keeps the WiFi up although it has no internet
            cm.requestNetwork(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), wifiCb);
            cm.requestNetwork(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cellCb);
        } catch (RuntimeException e) {
            // permission or system restriction: continue without routing
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

    private void evaluate(boolean wifiChanged) {
        main.post(() -> {
            Network w = wifi, c = cellular;
            Network target = (w != null && !wifiValidated && c != null) ? c : null;
            boolean routeChanged = !Objects.equals(target, bound);
            if (routeChanged) {
                cm.bindProcessToNetwork(target);
                bound = target;
            }
            if ((routeChanged || wifiChanged) && onRouteChanged != null) onRouteChanged.run();
        });
    }

    /** Number of times the WiFi was lost since the app started. */
    public int wifiDrops() {
        return wifiDrops;
    }

    public long lastDropTime() {
        return lastDropTime;
    }

    /** Binds the telemetry socket to the WiFi network, if there is one. */
    public void bindToWifi(DatagramSocket s) {
        Network w = wifi;
        if (w == null) return;
        try {
            w.bindSocket(s);
        } catch (Exception ignored) {
            // if binding fails, keep listening on the default route
        }
    }

    public boolean hasInternet() {
        return bound != null || (wifi != null && wifiValidated) || (wifi == null && cellular != null);
    }

    public String describe() {
        if (bound != null) return "map: mobile data";
        if (wifi != null && wifiValidated) return "map: WiFi internet";
        if (wifi != null) return "map: offline cache";
        return cellular != null ? "no WiFi" : "no network";
    }
}
