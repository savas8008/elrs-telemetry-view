package com.elrstelemetri;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The "receiving MAVLink" notice must not appear while CRSF telemetry is decoded. */
public class MavlinkNoticeTest {

    @Test
    public void onlyMavlinkPacketsAreReported() {
        TelemetryState s = new TelemetryState();
        long t = 1000;
        for (int i = 0; i < 50; i++) s.noteDatagram(0xFD, 0, t += 100);
        assertTrue(s.mavlinkSuspect);
    }

    @Test
    public void strayMavlinkLikePacketsDuringCrsfTelemetryAreIgnored() {
        TelemetryState s = new TelemetryState();
        long t = 1000;
        for (int i = 0; i < 200; i++) {
            s.noteDatagram('$', 1, t += 50);
            s.noteDatagram(0xFE, 0, t += 50);   // e.g. a partial datagram that starts with 0xFE
            assertFalse("i=" + i, s.mavlinkSuspect);
        }
    }

    @Test
    public void crsfFrameClearsTheNotice() {
        TelemetryState s = new TelemetryState();
        long t = 1000;
        for (int i = 0; i < 50; i++) s.noteDatagram(0xFE, 0, t += 100);
        s.noteDatagram('$', 2, t += 100);
        assertFalse(s.mavlinkSuspect);
    }
}
