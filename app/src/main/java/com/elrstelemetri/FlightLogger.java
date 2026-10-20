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
 * Uçuş verisini CSV olarak İndirilenler/ELRS-Telemetri klasörüne yazar.
 * MediaStore kullanıldığı için depolama izni gerekmez (Android 10+).
 */
public class FlightLogger {

    public static final String FOLDER = "ELRS-Telemetri";

    public static final String HEADER =
            "zaman,t_ms,enlem,boylam,gps_irtifa_m,goreli_irtifa_m,baro_irtifa_m,vario_ms,hiz_kmsa,yon_deg,uydu,"
                    + "pitch_deg,roll_deg,yaw_deg,voltaj_v,hucre_v,akim_a,mah,kalan_yuzde,guc_w,"
                    + "rssi1_dbm,rssi2_dbm,lq,snr_db,tx_guc_mw,down_rssi_dbm,down_lq,"
                    + "ucus_modu,ev_uzaklik_m,ev_yon_deg";

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
        String name = "ucus_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".csv";
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
            ContentResolver r = ctx.getContentResolver();
            uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("MediaStore kaydı oluşturulamadı");
            OutputStream os = r.openOutputStream(uri);
            if (os == null) throw new IOException("Dosya açılamadı");
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
            if (now - lastFlush > 2000) {  // uygulama kapanırsa en fazla 2 sn veri kaybolur
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
