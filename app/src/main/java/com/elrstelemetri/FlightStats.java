package com.elrstelemetri;

/** Flight time, min/max values during the flight and chart history. UI thread only. */
public class FlightStats {

    public static final int HISTORY = 300;  // 5 minutes at 1 s intervals

    public long flightMs;
    public double maxAltM = Double.NaN, maxDistM, maxSpeedKmh, minCellV = Double.NaN, maxCurrentA;

    public final float[] altHistory = new float[HISTORY];
    public final float[] voltHistory = new float[HISTORY];
    public int historyCount;  // number of filled samples
    public int historyPos;    // next index to write

    private long lastUpdate;
    private long lastSample;

    /**
     * @param flying    whether flight time runs (link fresh and not disarmed)
     * @param relAlt    relative altitude (NaN if invalid)
     * @param dist      distance to home (NaN if invalid)
     * @param speed     ground speed km/h (NaN if invalid)
     * @param cellV     cell voltage (NaN if invalid)
     * @param voltage   pack voltage (NaN if invalid)
     * @param current   current (NaN if invalid)
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
