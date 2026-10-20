package com.elrstelemetri;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SensorFreshnessTest {

    /** Simulates the UI refresh (every 200 ms) while a sensor arrives every {@code periodMs}. */
    private static int staleTicks(long periodMs, long durationMs) {
        SensorFreshness f = new SensorFreshness();
        long update = 0;
        int stale = 0;
        for (long now = 1000; now < durationMs; now += 200) {
            if (now % periodMs < 200) update = now;
            if (!f.isFresh(update, now) && now > 3 * periodMs) stale++;
        }
        return stale;
    }

    @Test
    public void fastTelemetryKeepsShortTimeout() {
        SensorFreshness f = new SensorFreshness();
        for (long t = 1000; t < 5000; t += 250) f.observe(t);
        assertEquals(SensorFreshness.MIN_TIMEOUT_MS, f.timeoutMs());
    }

    @Test
    public void slowTelemetryDoesNotFlicker() {
        // 1:32 and lower: a sensor may arrive only every 4–8 s
        assertEquals(0, staleTicks(4000, 60000));
        assertEquals(0, staleTicks(8000, 90000));
    }

    @Test
    public void stoppedSensorBecomesStale() {
        SensorFreshness f = new SensorFreshness();
        long t = 0;
        for (t = 1000; t <= 20000; t += 4000) assertTrue(f.isFresh(t, t + 100));
        long last = t - 4000;
        assertTrue(f.isFresh(last, last + 6000));
        assertFalse(f.isFresh(last, last + SensorFreshness.MAX_TIMEOUT_MS + 1));
    }

    @Test
    public void neverReceivedIsStale() {
        assertFalse(new SensorFreshness().isFresh(0, 5000));
    }
}
