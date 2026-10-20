package com.elrstelemetri;

import android.content.SharedPreferences;

/** Kullanıcı ayarları ve varsayılanları. */
public class Settings {

    public static final int MAP_SATELLITE = 0;
    public static final int MAP_STREET = 1;
    public static final int MAP_TOPO = 2;

    public int port = 14550;
    public int mapType = MAP_SATELLITE;
    public boolean autoRecord = true;     // veri gelince otomatik kaydet
    public int recordHz = 5;              // kayıt sıklığı
    public boolean voice = true;          // sesli uyarılar
    public float warnCellV = 3.5f;        // hücre başı düşük voltaj uyarısı
    public int warnLq = 50;               // LQ uyarı eşiği (%)
    public boolean showPanels = true;

    // Uygulama tekrar açıldığında harita son konumdan başlasın
    public boolean hasLastPos;
    public double lastLat, lastLon;

    public void load(SharedPreferences p) {
        port = p.getInt("port", port);
        mapType = p.getInt("mapType", mapType);
        autoRecord = p.getBoolean("autoRecord", autoRecord);
        recordHz = p.getInt("recordHz", recordHz);
        voice = p.getBoolean("voice", voice);
        warnCellV = p.getFloat("warnCellV", warnCellV);
        warnLq = p.getInt("warnLq", warnLq);
        showPanels = p.getBoolean("showPanels", showPanels);
        hasLastPos = p.getBoolean("hasLastPos", false);
        lastLat = Double.longBitsToDouble(p.getLong("lastLat", 0));
        lastLon = Double.longBitsToDouble(p.getLong("lastLon", 0));
    }

    public void save(SharedPreferences p) {
        p.edit()
                .putInt("port", port)
                .putInt("mapType", mapType)
                .putBoolean("autoRecord", autoRecord)
                .putInt("recordHz", recordHz)
                .putBoolean("voice", voice)
                .putFloat("warnCellV", warnCellV)
                .putInt("warnLq", warnLq)
                .putBoolean("showPanels", showPanels)
                .putBoolean("hasLastPos", hasLastPos)
                .putLong("lastLat", Double.doubleToLongBits(lastLat))
                .putLong("lastLon", Double.doubleToLongBits(lastLon))
                .apply();
    }
}
