package com.elrstelemetri;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Map and video share the screen 70/30 or 30/70 and never overlap. */
public class SplitLayoutTest {

    @Test
    public void withoutVideoTheMapFillsTheScreen() {
        SplitLayout l = SplitLayout.compute(1600, 800, false, false, 100, 120);
        assertArrayEquals(new int[]{0, 0, 1600, 800}, l.map);
        assertNull(l.video);
    }

    @Test
    public void smallVideoTakes30PercentOnTheRight() {
        SplitLayout l = SplitLayout.compute(1600, 800, true, false, 100, 120);
        assertArrayEquals(new int[]{0, 0, 1120, 800}, l.map);
        assertArrayEquals(new int[]{1120, 100, 1600, 680}, l.video);
    }

    @Test
    public void largeVideoTakes70PercentAndTheMapTheRest() {
        SplitLayout l = SplitLayout.compute(1600, 800, true, true, 100, 120);
        assertEquals(480, SplitLayout.width(l.map));
        assertEquals(1120, SplitLayout.width(l.video));
        assertEquals(800, SplitLayout.height(l.map));
    }

    @Test
    public void mapAndVideoNeverOverlap() {
        for (boolean large : new boolean[]{false, true}) {
            for (int w : new int[]{640, 1599, 2400}) {
                SplitLayout l = SplitLayout.compute(w, 700, true, large, 90, 110);
                assertTrue(l.map[2] <= l.video[0]);
                assertEquals(w, SplitLayout.width(l.map) + SplitLayout.width(l.video));
                assertTrue(SplitLayout.height(l.video) > 0);
            }
        }
    }
}
