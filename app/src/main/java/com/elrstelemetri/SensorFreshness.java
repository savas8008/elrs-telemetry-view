package com.elrstelemetri;

/**
 * Decides whether one telemetry sensor (GPS, battery, attitude, ...) is still current.
 *
 * The update interval of each sensor depends on the ELRS telemetry ratio: at 1:2–1:4 every sensor
 * arrives several times a second, at 1:32–1:128 the sensors share very little downlink bandwidth and
 * each one may only arrive every few seconds. A fixed timeout would make the display flicker between
 * live and stale at low ratios, so the timeout follows the interval actually observed for the sensor.
 */
public final class SensorFreshness {

    /** Shortest timeout (fast telemetry). */
    public static final long MIN_TIMEOUT_MS = 3000;
    /** Longest timeout, so a sensor that really stopped is still shown as stale eventually. */
    public static final long MAX_TIMEOUT_MS = 20000;
    private static final int HISTORY = 6;

    private final long[] intervals = new long[HISTORY];
    private int count, pos;
    private long last;

    /** Call regularly with the sensor's last update time (0 = never received). */
    public void observe(long updateTime) {
        if (updateTime == 0 || updateTime == last) return;
        if (last != 0 && updateTime > last) {
            intervals[pos] = updateTime - last;
            pos = (pos + 1) % HISTORY;
            if (count < HISTORY) count++;
        }
        last = updateTime;
    }

    /** Timeout: 1.5 × the longest recent interval + 1 s margin, within [MIN, MAX]. */
    public long timeoutMs() {
        long longest = 0;
        for (int i = 0; i < count; i++) longest = Math.max(longest, intervals[i]);
        long t = longest * 3 / 2 + 1000;
        return Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, t));
    }

    public boolean isFresh(long updateTime, long now) {
        observe(updateTime);
        return updateTime != 0 && now - updateTime <= timeoutMs();
    }
}
