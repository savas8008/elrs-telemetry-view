package com.elrstelemetri.goggles;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * "Copy video log": the Goggles V2 and USB capture connection messages this app wrote to Android's
 * system log, copied to the clipboard so the user can paste them into a problem report. An app can
 * only read its own log lines; nothing is stored or sent by the app.
 */
public final class VideoLog {

    /** logcat tags of the video links that log (V2UsbLink, UvcLink). */
    static final String[] TAGS = {"V2Usb", "Uvc"};
    /** The most recent lines only: enough for a few connection attempts, small enough to paste. */
    static final int MAX_LINES = 500;

    private VideoLog() {
    }

    /** Reads the log on a background thread, then copies it to the clipboard. Main thread. */
    public static void copy(Activity a, String appVersion, String status) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String log;
            try {
                log = read();
            } catch (IOException | InterruptedException e) {
                log = "";
            }
            String text = report(header(appVersion, status), log);
            main.post(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                ClipboardManager cb = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                cb.setPrimaryClip(ClipData.newPlainText("ELRS Telemetry video log", text));
                // Android 13+ confirms copies itself
                if (Build.VERSION.SDK_INT < 33) {
                    Toast.makeText(a, "Video log copied — paste it into your problem report", Toast.LENGTH_LONG).show();
                }
            });
        }, "video-log").start();
    }

    /** logcat call: dump (no follow), timestamps, only the video link tags. */
    static List<String> command() {
        String[] base = {"logcat", "-d", "-v", "time", "-t", String.valueOf(MAX_LINES), "-s"};
        String[] cmd = Arrays.copyOf(base, base.length + TAGS.length);
        for (int i = 0; i < TAGS.length; i++) cmd[base.length + i] = TAGS[i] + ":V";
        return Arrays.asList(cmd);
    }

    private static String read() throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command()).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) sb.append(line).append('\n');
        } finally {
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroy();
        }
        return sb.toString();
    }

    private static String header(String appVersion, String status) {
        return "ELRS Telemetry " + appVersion + " video log, "
                + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()) + "\n"
                + "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), "
                + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "Status: " + status.replace('\n', ' ');
    }

    /** Header plus the log lines, without logcat's "--------- beginning of …" separators. */
    static String report(String header, String log) {
        StringBuilder sb = new StringBuilder(header).append("\n\n");
        int lines = 0;
        for (String line : log.split("\n")) {
            if (line.isEmpty() || line.startsWith("--------- ")) continue;
            sb.append(line).append('\n');
            lines++;
        }
        if (lines == 0) {
            sb.append("No video connection messages yet. Plug in the Goggles V2 or the capture device, "
                    + "try to connect, then copy the log again.\n");
        }
        return sb.toString();
    }
}
