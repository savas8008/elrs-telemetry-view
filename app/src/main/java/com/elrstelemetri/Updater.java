package com.elrstelemetri;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * In-app updates from the GitHub releases of this project (no server, no account): checks the
 * latest release, and on request downloads its APK and hands it to Android's package installer.
 * Android only accepts the update if it is signed with the same key as the installed app, so a
 * tampered APK cannot replace it.
 *
 * Not for a Google Play build: Play does not allow apps to update themselves.
 */
public final class Updater {

    public static final String REPO = "savas8008/elrs-telemetry-view";
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String ACTION_STATUS = "com.elrstelemetri.INSTALL_STATUS";

    /** A published release. */
    public static final class Release {
        public String tag;       // e.g. "v1.1"
        public String version;   // e.g. "1.1"
        public String notes;
        public String apkUrl;
        public long apkSize;
        public String pageUrl;
    }

    public interface Progress {
        /** Called from the download thread. */
        void onProgress(long done, long total);
    }

    public interface Result {
        /** Called on an arbitrary thread; {@code error} is null on success (installer shown). */
        void onDone(String error);
    }

    private Updater() {
    }

    // ---------------------------------------------------------------- Check

    /** Fetches the latest release (blocking; call off the main thread). */
    public static Release fetchLatest() throws IOException {
        HttpURLConnection c = open(LATEST);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (c.getResponseCode() != 200) throw new IOException("GitHub answered " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                return parse(new String(readAll(in), StandardCharsets.UTF_8));
            }
        } finally {
            c.disconnect();
        }
    }

    /** Parses the GitHub "latest release" JSON; null if it has no APK. */
    static Release parse(String json) throws IOException {
        try {
            JSONObject o = new JSONObject(json);
            Release r = new Release();
            r.tag = o.optString("tag_name", "");
            r.version = r.tag.startsWith("v") ? r.tag.substring(1) : r.tag;
            r.notes = o.optString("body", "");
            r.pageUrl = o.optString("html_url", "https://github.com/" + REPO + "/releases");
            JSONArray assets = o.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name").endsWith(".apk")) {
                    r.apkUrl = a.optString("browser_download_url");
                    r.apkSize = a.optLong("size");
                    break;
                }
            }
            return r.apkUrl == null || r.version.isEmpty() ? null : r;
        } catch (org.json.JSONException e) {
            throw new IOException("Unexpected answer from GitHub", e);
        }
    }

    /** Compares dotted versions ("1.10" > "1.9"); non-numeric parts count as 0. */
    public static int compareVersions(String a, String b) {
        String[] x = a.split("[.\\-]"), y = b.split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String installedVersion(Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return v == null ? "0" : v;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** True if {@code r} is newer than the installed app. */
    public static boolean isNewer(Context c, Release r) {
        return r != null && compareVersions(r.version, installedVersion(c)) > 0;
    }

    /** Android requires the user to allow "install unknown apps" for this app once. */
    public static boolean canInstall(Context c) {
        return c.getPackageManager().canRequestPackageInstalls();
    }

    public static Intent allowInstallIntent(Context c) {
        return new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:" + c.getPackageName()));
    }

    // ---------------------------------------------------------------- Download and install

    /**
     * Downloads the APK straight into a package installer session and commits it; Android then
     * shows its own confirmation screen. Runs on a background thread.
     */
    public static void downloadAndInstall(Context ctx, Release r, Progress progress, Result result) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            PackageInstaller pi = app.getPackageManager().getPackageInstaller();
            int sessionId = -1;
            try {
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(app.getPackageName());
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                }
                sessionId = pi.createSession(params);
                try (PackageInstaller.Session s = pi.openSession(sessionId)) {
                    HttpURLConnection c = open(r.apkUrl);
                    try {
                        if (c.getResponseCode() != 200) throw new IOException("Download failed: HTTP " + c.getResponseCode());
                        long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : r.apkSize;
                        try (InputStream in = c.getInputStream();
                             OutputStream out = s.openWrite("app.apk", 0, total > 0 ? total : -1)) {
                            byte[] buf = new byte[64 * 1024];
                            long done = 0;
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                out.write(buf, 0, n);
                                done += n;
                                if (progress != null) progress.onProgress(done, total);
                            }
                            s.fsync(out);
                        }
                    } finally {
                        c.disconnect();
                    }
                    registerStatusReceiver(app, result);
                    Intent status = new Intent(ACTION_STATUS).setPackage(app.getPackageName());
                    PendingIntent pending = PendingIntent.getBroadcast(app, sessionId, status,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                    s.commit(pending.getIntentSender());
                }
            } catch (Exception e) {
                if (sessionId >= 0) {
                    try {
                        pi.abandonSession(sessionId);
                    } catch (RuntimeException ignored) {
                    }
                }
                result.onDone(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        }, "updater").start();
    }

    /** Shows Android's confirmation screen when the installer asks for it and reports the outcome. */
    private static void registerStatusReceiver(Context app, Result result) {
        BroadcastReceiver rx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    @SuppressWarnings("deprecation")
                    Intent confirm = Build.VERSION.SDK_INT >= 33 ? i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                            : (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        c.startActivity(confirm);
                    }
                    result.onDone(null);
                    return;  // the next status (success/failure) arrives after the user's choice
                }
                try {
                    c.unregisterReceiver(this);
                } catch (RuntimeException ignored) {
                }
                if (st != PackageInstaller.STATUS_SUCCESS) {
                    String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    result.onDone(st == PackageInstaller.STATUS_FAILURE_ABORTED ? "Update cancelled"
                            : "Install failed" + (msg != null ? ": " + msg : ""));
                }
            }
        };
        IntentFilter f = new IntentFilter(ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(rx, f);
    }

    // ---------------------------------------------------------------- HTTP

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);  // release assets redirect to GitHub's download host
        c.setRequestProperty("User-Agent", "ELRS-Telemetry-Android");
        return c;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }
}
