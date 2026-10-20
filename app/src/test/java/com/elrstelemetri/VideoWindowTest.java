package com.elrstelemetri;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Touch gestures on the floating video window, dispatched through the parent like real touches. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class VideoWindowTest {

    private FrameLayout parent;
    private VideoWindow win;
    private long downTime;

    @Before
    public void setUp() {
        Context c = RuntimeEnvironment.getApplication();
        parent = new FrameLayout(c);
        win = new VideoWindow(c, new VideoPanel(c));
        parent.addView(win);
        layout();
        win.setGeometry(100, 100, 320, 180);
        layout();
    }

    private void layout() {
        parent.measure(View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        parent.layout(0, 0, 1600, 800);
    }

    private void send(int action, float[][] pts) {
        int n = pts.length;
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[n];
        for (int i = 0; i < n; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = i;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = pts[i][0];
            coords[i].y = pts[i][1];
            coords[i].pressure = 1;
            coords[i].size = 1;
        }
        MotionEvent e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, n, props, coords,
                0, 0, 1, 1, 0, 0, 0, 0);
        parent.dispatchTouchEvent(e);
        e.recycle();
        layout();
    }

    @Test
    public void pinchEnlargesAndKeepsAspectRatio() {
        downTime = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, new float[][]{{220, 190}});
        send(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                new float[][]{{220, 190}, {320, 190}});
        for (int i = 1; i <= 10; i++) {
            send(MotionEvent.ACTION_MOVE, new float[][]{{220 - 10 * i, 190}, {320 + 10 * i, 190}});
        }
        send(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                new float[][]{{120, 190}, {420, 190}});
        send(MotionEvent.ACTION_UP, new float[][]{{120, 190}});

        assertTrue("width grew: " + win.getWidth(), win.getWidth() > 500);
        assertEquals(320 / 180.0, win.getWidth() / (double) win.getHeight(), 0.02);
    }

    @Test
    public void cornerHandleResizesInsteadOfMoving() {
        downTime = SystemClock.uptimeMillis();
        // handle is at the bottom-right corner of the window (100..420, 100..280)
        send(MotionEvent.ACTION_DOWN, new float[][]{{410, 270}});
        for (int i = 1; i <= 10; i++) send(MotionEvent.ACTION_MOVE, new float[][]{{410 + 20 * i, 270 + 10 * i}});
        send(MotionEvent.ACTION_UP, new float[][]{{610, 370}});

        assertEquals(100, win.getLeft());
        assertEquals(100, win.getTop());
        assertEquals(520, win.getWidth());
        assertEquals(280, win.getHeight());
    }

    @Test
    public void oneFingerDragMoves() {
        downTime = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, new float[][]{{200, 150}});
        for (int i = 1; i <= 10; i++) send(MotionEvent.ACTION_MOVE, new float[][]{{200 + 30 * i, 150 + 10 * i}});
        send(MotionEvent.ACTION_UP, new float[][]{{500, 250}});

        assertEquals(320, win.getWidth());
        assertTrue("moved right: " + win.getLeft(), win.getLeft() > 300);
    }
}
