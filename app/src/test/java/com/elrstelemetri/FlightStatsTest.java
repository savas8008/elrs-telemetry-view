package com.elrstelemetri;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FlightStatsTest {

    @Test
    public void ucusSuresiYalnizcaUcarkenSayilir() {
        FlightStats f = new FlightStats();
        f.update(1000, true, 10, 5, 20, 4.0, 16.0, 5);
        f.update(1200, true, 30, 50, 60, 3.9, 15.6, 12);
        f.update(1400, false, 25, 40, 10, 3.95, 15.8, 2);   // disarm: süre artmaz
        f.update(1600, true, 20, 30, 40, 3.7, 14.8, 8);
        assertEquals(400, f.flightMs);
        assertEquals(30, f.maxAltM, 1e-9);
        assertEquals(50, f.maxDistM, 1e-9);
        assertEquals(60, f.maxSpeedKmh, 1e-9);
        assertEquals(3.7, f.minCellV, 1e-9);
        assertEquals(12, f.maxCurrentA, 1e-9);
    }

    @Test
    public void uzunBoslukSureyeEklenmez() {
        FlightStats f = new FlightStats();
        f.update(1000, true, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        f.update(60000, true, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        assertEquals(0, f.flightMs);
        assertEquals(Double.NaN, f.maxAltM, 0);
    }

    @Test
    public void gecmisHalkaTamponu() {
        FlightStats f = new FlightStats();
        for (int i = 0; i < FlightStats.HISTORY + 10; i++) {
            f.update(1000L * (i + 1), true, i, 0, 0, 4, 16, 0);
        }
        assertEquals(FlightStats.HISTORY, f.historyCount);
        int newest = (f.historyPos - 1 + FlightStats.HISTORY) % FlightStats.HISTORY;
        assertEquals(FlightStats.HISTORY + 9, f.altHistory[newest], 1e-6);
    }

    @Test
    public void sureBicimi() {
        assertEquals("02:05", FlightStats.formatDuration(125_000));
        assertEquals("1:01:01", FlightStats.formatDuration(3_661_000));
    }
}
