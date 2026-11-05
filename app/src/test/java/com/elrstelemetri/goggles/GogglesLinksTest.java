package com.elrstelemetri.goggles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import org.junit.Test;

public class GogglesLinksTest {

    static final class Fake implements GogglesLink {
        final String name;
        final boolean osd;
        boolean present, streaming;
        int starts, stops, reconnects;

        Fake(String name, boolean osd) {
            this.name = name;
            this.osd = osd;
        }

        @Override public void start(Intent i) { starts++; }
        @Override public void stop() { stops++; }
        @Override public void handleIntent(Intent i) { }
        @Override public void reconnect() { reconnects++; }
        @Override public String status() { return "status of " + name; }
        @Override public String identity() { return "id of " + name; }
        @Override public boolean isStreaming() { return streaming; }
        @Override public boolean devicePresent() { return present; }
        @Override public boolean videoHasOsd() { return osd; }
        @Override public String name() { return name; }
    }

    @Test
    public void startAndStopReachEveryLink() {
        Fake n3 = new Fake("N3", true), v2 = new Fake("V2", false);
        GogglesLinks all = new GogglesLinks(n3, v2);
        all.start(null);
        all.stop();
        assertEquals(1, n3.starts);
        assertEquals(1, v2.starts);
        assertEquals(1, n3.stops);
        assertEquals(1, v2.stops);
    }

    @Test
    public void statusAndOsdComeFromTheActiveGoggles() {
        Fake n3 = new Fake("N3", true), v2 = new Fake("V2", false);
        GogglesLinks all = new GogglesLinks(n3, v2);
        assertNull(all.active());
        assertFalse(all.devicePresent());
        assertFalse(all.videoHasOsd());
        assertTrue(all.status().contains("N3") && all.status().contains("V2"));

        v2.present = true;
        assertSame(v2, all.active());
        assertEquals("V2: status of V2", all.status());
        assertEquals("id of V2", all.identity());
        all.reconnect();
        assertEquals(1, v2.reconnects);
        assertEquals(0, n3.reconnects);

        // the streaming link wins over one that is only plugged in
        n3.streaming = true;
        assertSame(n3, all.active());
        assertTrue(all.isStreaming());
        assertTrue(all.videoHasOsd());
    }
}
