package com.elrstelemetri;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AlarmSetTest {

    @Test
    public void maxAltitudeTriggersAndClearsWithHysteresis() {
        AlarmSet.Alarm a = new AlarmSet().get(AlarmSet.MAX_ALT);  // > 120 m, hysteresis 3
        assertFalse(a.evaluate(119, true, true));
        assertTrue(a.evaluate(121, true, true));      // just became active
        assertFalse(a.evaluate(122, true, true));     // still active, not "new"
        assertTrue(a.active);
        a.evaluate(118, true, true);                  // within hysteresis band: stays active
        assertTrue(a.active);
        a.evaluate(116, true, true);                  // below threshold - hysteresis: clears
        assertFalse(a.active);
    }

    @Test
    public void lowCellVoltage() {
        AlarmSet.Alarm a = new AlarmSet().get(AlarmSet.LOW_CELL);  // < 3.50 V
        assertFalse(a.evaluate(3.7, true, true));
        assertTrue(a.evaluate(3.45, true, true));
        a.evaluate(3.53, true, true);                 // within 0.05 V band
        assertTrue(a.active);
        a.evaluate(3.6, true, true);
        assertFalse(a.active);
    }

    @Test
    public void minimumAltitudeStaysQuietOnTheGround() {
        AlarmSet set = new AlarmSet();
        AlarmSet.Alarm a = set.get(AlarmSet.MIN_ALT);
        a.enabled = true;                             // < 20 m, arms after climbing above 23 m
        assertFalse(a.evaluate(0, true, true));       // on the ground after arming
        assertFalse(a.evaluate(15, true, true));      // climbing
        assertFalse(a.active);
        a.evaluate(40, true, true);                   // now airborne above threshold
        assertFalse(a.active);
        assertTrue(a.evaluate(12, true, true));       // descended below 20 m
        a.evaluate(5, true, false);                   // landed and disarmed: resets
        assertFalse(a.active);
        assertFalse(a.evaluate(5, true, true));       // next flight starts quiet again
    }

    @Test
    public void disabledOrInvalidValueNeverAlarms() {
        AlarmSet set = new AlarmSet();
        AlarmSet.Alarm speed = set.get(AlarmSet.MAX_SPEED);
        assertFalse(speed.enabled);
        assertFalse(speed.evaluate(500, true, true));
        AlarmSet.Alarm alt = set.get(AlarmSet.MAX_ALT);
        assertFalse(alt.evaluate(500, false, true));
        assertFalse(alt.evaluate(Double.NaN, true, true));
    }

    @Test
    public void activeListAndDefaults() {
        AlarmSet set = new AlarmSet();
        set.get(AlarmSet.MAX_ALT).evaluate(200, true, true);
        set.get(AlarmSet.LOW_LQ).evaluate(30, true, true);
        assertEquals(2, set.active().size());
        assertTrue(set.isActive(AlarmSet.LOW_LQ));
        set.get(AlarmSet.MAX_ALT).threshold = 400;
        set.get(AlarmSet.MAX_ALT).enabled = false;
        set.resetToDefaults();
        assertEquals(120, set.get(AlarmSet.MAX_ALT).threshold, 0);
        assertTrue(set.get(AlarmSet.MAX_ALT).enabled);
        assertEquals("> 120 m", set.get(AlarmSet.MAX_ALT).describe());
        assertEquals("< 3.50 V", set.get(AlarmSet.LOW_CELL).describe());
    }
}
