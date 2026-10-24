package com.elrstelemetri;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Map and video share the screen at the user's ratio and never overlap. */
public class SplitLayoutTest {

    @Test
    public void withoutVideoTheMapFillsTheScreen() {
        SplitLayout l = SplitLayout.compute(1600, 800, false, 0.5f, 20, 100, 120);
        assertArrayEquals(new int[]{0, 0, 1600, 800}, l.map);
        assertNull(l.video);
        assertNull(l.divider);
    }

    @Test
    public void videoTakesTheChosenShareOnTheRight() {
        SplitLayout l = SplitLayout.compute(1620, 800, true, 0.30f, 20, 100, 120);
        assertArrayEquals(new int[]{0, 0, 1120, 800}, l.map);
        assertArrayEquals(new int[]{1120, 0, 1140, 800}, l.divider);
        assertArrayEquals(new int[]{1140, 100, 1620, 680}, l.video);
    }

    @Test
    public void ratioIsClampedSoNeitherSideDisappears() {
        SplitLayout l = SplitLayout.compute(1000, 600, true, 0.99f, 0, 0, 0);
        assertEquals(850, SplitLayout.width(l.video));
        l = SplitLayout.compute(1000, 600, true, 0.0f, 0, 0, 0);
        assertEquals(150, SplitLayout.width(l.video));
    }

    @Test
    public void draggingTheDividerMovesItUnderTheFinger() {
        int w = 1620, d = 20;
        for (float f : new float[]{0.2f, 0.45f, 0.7f}) {
            SplitLayout l = SplitLayout.compute(w, 800, true, f, d, 0, 0);
            float centre = (l.divider[0] + l.divider[2]) / 2f;
            assertEquals(f, SplitLayout.fractionAt(w, centre, d), 0.002f);
        }
        assertEquals(SplitLayout.MAX, SplitLayout.fractionAt(w, 0, d), 1e-6);
        assertEquals(SplitLayout.MIN, SplitLayout.fractionAt(w, w, d), 1e-6);
    }

    @Test
    public void mapAndVideoNeverOverlap() {
        for (float f : new float[]{0.15f, 0.3f, 0.5f, 0.85f}) {
            for (int w : new int[]{640, 1599, 2400}) {
                SplitLayout l = SplitLayout.compute(w, 700, true, f, 24, 90, 110);
                assertTrue(l.map[2] <= l.divider[0] && l.divider[2] <= l.video[0]);
                assertEquals(w, SplitLayout.width(l.map) + SplitLayout.width(l.divider) + SplitLayout.width(l.video));
                assertTrue(SplitLayout.height(l.video) > 0);
            }
        }
    }
}
