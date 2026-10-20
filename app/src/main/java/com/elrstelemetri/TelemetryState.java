package com.elrstelemetri;

/** Latest telemetry values. Access with `synchronized (state)`. */
public class TelemetryState {

    // Link
    public int rssi1, rssi2, lq, snr, antenna, rfMode, txPowerMw;
    public int downRssi, downLq, downSnr;
    public long linkTime;

    // Battery
    public double voltage, current;
    public int mah, batteryPct;
    public long batteryTime;

    // GPS
    public double lat, lon, groundSpeedKmh, headingDeg;
    public int gpsAltM, sats;
    public long gpsTime;

    // Baro / vario
    public double baroAltM, varioMs;
    public long baroTime, varioTime;

    // Attitude
    public double pitchDeg, rollDeg, yawDeg;
    public long attitudeTime;

    // Flight mode
    public String flightMode = "";
    public long modeTime;

    // Home position (taken at the first solid GPS fix)
    public static final int HOME_MIN_SATS = 6;
    public boolean homeSet;
    public double homeLat, homeLon;
    public int homeAltM;

    // Statistics
    public long frameCount, datagramCount, lastDatagramTime;
    public String lastSource = "";
    public boolean mavlinkSuspect;

    void updateHome() {
        if (!homeSet && sats >= HOME_MIN_SATS && (lat != 0 || lon != 0)) {
            homeLat = lat;
            homeLon = lon;
            homeAltM = gpsAltM;
            homeSet = true;
        }
    }

    public void resetHome() {
        homeSet = false;
        updateHome();
    }

    /** Distance to home in metres (haversine). */
    public double homeDistanceM() {
        if (!homeSet) return Double.NaN;
        double r = 6371000.0;
        double dLat = Math.toRadians(lat - homeLat);
        double dLon = Math.toRadians(lon - homeLon);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(homeLat)) * Math.cos(Math.toRadians(lat))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Bearing from home to the aircraft (degrees, 0 = north). */
    public double homeBearingDeg() {
        if (!homeSet) return Double.NaN;
        double p1 = Math.toRadians(homeLat), p2 = Math.toRadians(lat);
        double dl = Math.toRadians(lon - homeLon);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** Estimated cell count (LiPo, at most 4.35 V per cell). */
    public int cellCount() {
        if (voltage < 2.5) return 0;
        return (int) Math.ceil(voltage / 4.35);
    }
}
