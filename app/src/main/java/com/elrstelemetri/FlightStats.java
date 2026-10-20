package com.elrstelemetri;

/** Uçuş süresi, uçuş boyunca en yüksek/en düşük değerler ve grafik geçmişi. Yalnızca UI iş parçacığından kullanılır. */
public class FlightStats {

    public static final int HISTORY = 300;  // 1 sn aralıkla 5 dakika

    public long flightMs;
    public double maxAltM = Double.NaN, maxDistM, maxSpeedKmh, minCellV = Double.NaN, maxCurrentA;

    public final float[] altHistory = new float[HISTORY];
    public final float[] voltHistory = new float[HISTORY];
    public int historyCount;  // dolu örnek sayısı
    public int historyPos;    // bir sonraki yazılacak indeks

    private long lastUpdate;
    private long lastSample;

    /**
     * @param flying    uçuş süresi sayılsın mı (link taze ve disarm değil)
     * @param relAlt    göreli irtifa (geçersizse NaN)
     * @param dist      ev uzaklığı (geçersizse NaN)
     * @param speed     yer hızı km/sa (geçersizse NaN)
     * @param cellV     hücre voltajı (geçersizse NaN)
     * @param voltage   toplam voltaj (geçersizse NaN)
     * @param current   akım (geçersizse NaN)
     */
    public void update(long now, boolean flying, double relAlt, double dist, double speed,
                       double cellV, double voltage, double current) {
        if (lastUpdate != 0 && flying) {
            long dt = now - lastUpdate;
            if (dt > 0 && dt < 5000) flightMs += dt;
        }
        lastUpdate = now;

        if (!Double.isNaN(relAlt) && (Double.isNaN(maxAltM) || relAlt > maxAltM)) maxAltM = relAlt;
        if (!Double.isNaN(dist) && dist > maxDistM) maxDistM = dist;
        if (!Double.isNaN(speed) && speed > maxSpeedKmh) maxSpeedKmh = speed;
        if (!Double.isNaN(cellV) && cellV > 0.5 && (Double.isNaN(minCellV) || cellV < minCellV)) minCellV = cellV;
        if (!Double.isNaN(current) && current > maxCurrentA) maxCurrentA = current;

        if (now - lastSample >= 1000) {
            lastSample = now;
            altHistory[historyPos] = (float) relAlt;
            voltHistory[historyPos] = (float) voltage;
            historyPos = (historyPos + 1) % HISTORY;
            if (historyCount < HISTORY) historyCount++;
        }
    }

    public void reset() {
        flightMs = 0;
        maxAltM = Double.NaN;
        minCellV = Double.NaN;
        maxDistM = maxSpeedKmh = maxCurrentA = 0;
        historyCount = historyPos = 0;
    }

    public static String formatDuration(long ms) {
        long s = ms / 1000;
        if (s >= 3600) return String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60);
    }
}
