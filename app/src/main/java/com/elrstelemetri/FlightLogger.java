package com.elrstelemetri;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes flight data as CSV to Downloads/ELRS-Telemetry.
 * Uses MediaStore, so no storage permission is needed (Android 10+).
 */
public class FlightLogger {

    public static final String FOLDER = "ELRS-Telemetry";

    public static final String HEADER =
            "time,t_ms,lat,lon,gps_alt_m,rel_alt_m,baro_alt_m,vario_ms,speed_kmh,heading_deg,sats,"
                    + "pitch_deg,roll_deg,yaw_deg,voltage_v,cell_v,current_a,mah,remaining_pct,power_w,"
                    + "rssi1_dbm,rssi2_dbm,lq,snr_db,tx_power_mw,down_rssi_dbm,down_lq,"
                    + "flight_mode,home_dist_m,home_bearing_deg,active_alarms";

    private final Context ctx;
    private Uri uri;
    private Writer writer;
    private long lastFlush;

    public String fileName = "";
    public long startTime;
    public int rows;
    public String lastError = "";

    public FlightLogger(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public boolean isOpen() {
        return writer != null;
    }

    public boolean open() {
        if (writer != null) return true;
        String name = "flight_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".csv";
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
            ContentResolver r = ctx.getContentResolver();
            uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("could not create MediaStore entry");
            OutputStream os = r.openOutputStream(uri);
            if (os == null) throw new IOException("could not open file");
            writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
            writer.write(HEADER);
            writer.write('\n');
            fileName = name;
            startTime = System.currentTimeMillis();
            rows = 0;
            lastError = "";
            return true;
        } catch (Exception e) {
            lastError = String.valueOf(e.getMessage());
            writer = null;
            return false;
        }
    }

    public void write(String row) {
        if (writer == null) return;
        try {
            writer.write(row);
            writer.write('\n');
            rows++;
            long now = System.currentTimeMillis();
            if (now - lastFlush > 2000) {  // at most 2 s of data is lost if the app is killed
                writer.flush();
                lastFlush = now;
            }
        } catch (IOException e) {
            lastError = String.valueOf(e.getMessage());
            close();
        }
    }

    public void close() {
        if (writer == null) return;
        try {
            writer.flush();
            writer.close();
        } catch (IOException ignored) {
        }
        writer = null;
    }
}
