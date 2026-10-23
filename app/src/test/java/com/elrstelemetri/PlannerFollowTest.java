package com.elrstelemetri;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The offline map planner pauses map follow mode and must restore it when closed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w820dp-h390dp-land")
public class PlannerFollowTest {

    @Test
    public void followModeIsRestoredAfterThePlanner() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertTrue("follow is on by default", a.map().isFollowing());
        a.openOfflineMapPlanner();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse("planner keeps the map still", a.map().isFollowing());
        a.closePlanner();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue("follow is back on after Done", a.map().isFollowing());
    }

    @Test
    public void followStaysOffIfTheUserHadTurnedItOff() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        a.map().setFollow(false);
        a.openOfflineMapPlanner();
        shadowOf(Looper.getMainLooper()).idle();
        a.closePlanner();
        assertFalse(a.map().isFollowing());
    }
}
