package com.elrstelemetri;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;

import org.junit.Test;

public class CrsfParserTest {

    /** Builds a CRSF frame for tests: [0xEA][len][type][payload][crc]. */
    static byte[] frame(int type, int... payload) {
        byte[] f = new byte[payload.length + 4];
        f[0] = (byte) 0xEA;
        f[1] = (byte) (payload.length + 2);
        f[2] = (byte) type;
        for (int i = 0; i < payload.length; i++) f[3 + i] = (byte) payload[i];
        f[f.length - 1] = (byte) CrsfParser.crc8(f, 2, payload.length + 1);
        return f;
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    static int[] be(long v, int n) {
        int[] r = new int[n];
        for (int i = 0; i < n; i++) r[i] = (int) ((v >> (8 * (n - 1 - i))) & 0xFF);
        return r;
    }

    static int[] join(int[]... a) {
        int n = 0;
        for (int[] x : a) n += x.length;
        int[] r = new int[n];
        int k = 0;
        for (int[] x : a) for (int v : x) r[k++] = v;
        return r;
    }

    // sample position
    static byte[] gpsFrame(double lat, double lon, int sats) {
        return frame(CrsfParser.GPS, join(
                be(Math.round(lat * 1e7), 4), be(Math.round(lon * 1e7), 4),
                be(523, 2),          // 52.3 km/h
                be(9050, 2),         // 90.50°
                be(1000 + 120, 2),   // 120 m
                new int[]{sats}));
    }

    @Test
    public void crcKnownValue() {
        // Betaflight/ELRS crc8_dvb_s2: "123456789" → 0xBC
        byte[] d = "123456789".getBytes();
        assertEquals(0xBC, CrsfParser.crc8(d, 0, d.length));
    }

    @Test
    public void decodesAllTypes() {
        TelemetryState s = new TelemetryState();
        CrsfParser p = new CrsfParser(s);
        byte[] dg = cat(
                frame(CrsfParser.BATTERY, join(be(168, 2), be(123, 2), be(1500, 3), new int[]{76})),
                frame(CrsfParser.LINK_STATS, 0x45, 0x50, 99, 0xF6, 1, 7, 3, 0x40, 100, 9),
                frame(CrsfParser.ATTITUDE, join(be(1745, 2), be((-3491) & 0xFFFF, 2), be(0, 2))),
                frame(CrsfParser.FLIGHT_MODE, 'A', 'N', 'G', 'L', 0),
                frame(CrsfParser.VARIO, be((-150) & 0xFFFF, 2)),
                frame(CrsfParser.BARO_ALT, be(10000 + 455, 2)),
                gpsFrame(37.0742, 36.2464, 9));
        assertEquals(7, p.feed(dg, dg.length));

        assertEquals(16.8, s.voltage, 1e-9);
        assertEquals(12.3, s.current, 1e-9);
        assertEquals(1500, s.mah);
        assertEquals(76, s.batteryPct);
        assertEquals(4, s.cellCount());

        assertEquals(-69, s.rssi1);
        assertEquals(-80, s.rssi2);
        assertEquals(99, s.lq);
        assertEquals(-10, s.snr);
        assertEquals(100, s.txPowerMw);

        assertEquals(10.0, s.pitchDeg, 0.01);
        assertEquals(-20.0, s.rollDeg, 0.01);
        assertEquals("ANGL", s.flightMode);
        assertEquals(-1.5, s.varioMs, 1e-9);
        assertEquals(45.5, s.baroAltM, 1e-9);

        assertEquals(37.0742, s.lat, 1e-7);
        assertEquals(36.2464, s.lon, 1e-7);
        assertEquals(52.3, s.groundSpeedKmh, 1e-9);
        assertEquals(90.5, s.headingDeg, 1e-9);
        assertEquals(120, s.gpsAltM);
        assertEquals(9, s.sats);
        assertTrue(s.homeSet);
    }

    @Test
    public void skipsGarbageAndBadCrc() {
        TelemetryState s = new TelemetryState();
        CrsfParser p = new CrsfParser(s);
        byte[] bad = frame(CrsfParser.BATTERY, join(be(111, 2), be(0, 2), be(0, 3), new int[]{0}));
        bad[5] ^= 0x55;  // corrupts the CRC
        byte[] good = frame(CrsfParser.BATTERY, join(be(252, 2), be(0, 2), be(0, 3), new int[]{50}));
        byte[] dg = cat(new byte[]{1, 2, 3, (byte) 0xFD, 9, 0, 0}, bad, new byte[]{0x55, (byte) 0xAA}, good);
        assertEquals(1, p.feed(dg, dg.length));
        assertEquals(25.2, s.voltage, 1e-9);
    }

    @Test
    public void frameSplitAcrossDatagrams() {
        TelemetryState s = new TelemetryState();
        CrsfParser p = new CrsfParser(s);
        byte[] f = gpsFrame(37.1, 36.2, 10);
        byte[] a = java.util.Arrays.copyOfRange(f, 0, 7);
        byte[] b = java.util.Arrays.copyOfRange(f, 7, f.length);
        assertEquals(0, p.feed(a, a.length));
        assertEquals(1, p.feed(b, b.length));
        assertEquals(10, s.sats);
    }

    @Test
    public void homeDistance() {
        TelemetryState s = new TelemetryState();
        CrsfParser p = new CrsfParser(s);
        byte[] f1 = gpsFrame(37.0, 36.0, 8);
        p.feed(f1, f1.length);
        byte[] f2 = gpsFrame(37.009, 36.0, 8);  // ~1 km north
        p.feed(f2, f2.length);
        assertEquals(1000.8, s.homeDistanceM(), 2.0);
        assertEquals(0.0, s.homeBearingDeg(), 0.5);
    }

    /**
     * The backpack's real UDP format (Backpack Tx_main.cpp / msp.cpp):
     * '$' 'X' '<' flags func(LE16=0x0011) size(LE16) [complete CRSF frame] crc8
     */
    @Test
    public void backpackMspWrappedUdpPacket() {
        TelemetryState s = new TelemetryState();
        CrsfParser p = new CrsfParser(s);
        byte[] crsf = frame(CrsfParser.LINK_STATS, 40, 45, 100, 8, 0, 7, 2, 50, 100, 7);
        byte[] dg = new byte[8 + crsf.length + 1];
        dg[0] = '$'; dg[1] = 'X'; dg[2] = '<'; dg[3] = 0;
        dg[4] = 0x11; dg[5] = 0;
        dg[6] = (byte) crsf.length; dg[7] = 0;
        System.arraycopy(crsf, 0, dg, 8, crsf.length);
        dg[dg.length - 1] = (byte) CrsfParser.crc8(dg, 3, 5 + crsf.length);
        assertEquals(1, p.feed(dg, dg.length));
        assertEquals(100, s.lq);
        assertEquals(-40, s.rssi1);
        assertEquals(25, s.txPowerMw);
    }
}
