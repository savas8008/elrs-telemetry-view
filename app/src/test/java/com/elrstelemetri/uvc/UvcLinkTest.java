package com.elrstelemetri.uvc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UvcLinkTest {

    private static final int YUYV = 5, MJPEG = 7, H264 = 17;

    @Test
    public void recognisesVideoClassDevices() {
        // Macrosilicon AV TO USB2.0: video control, video streaming, audio, audio, HID
        assertTrue(UvcLink.hasVideoInterface(new int[]{14, 14, 1, 1, 3}));
        // DJI FPV Goggles V2: RNDIS, CDC data, mass storage, vendor bulk, CDC ACM, CDC data, ADB
        assertFalse(UvcLink.hasVideoInterface(new int[]{224, 10, 8, 255, 2, 10, 255}));
        assertFalse(UvcLink.hasVideoInterface(new int[0]));
    }

    @Test
    public void prefersYuyvAtStandardDefinition() {
        int[][] options = {{MJPEG, 720, 480}, {YUYV, 720, 480}, {YUYV, 640, 480}};
        assertEquals(1, UvcLink.pickSize(options));
    }

    @Test
    public void takesTheLargestPicture() {
        int[][] options = {{YUYV, 640, 480}, {MJPEG, 720, 576}, {YUYV, 320, 240}};
        assertEquals(1, UvcLink.pickSize(options));
    }

    @Test
    public void prefersMjpegAboveStandardDefinition() {
        int[][] options = {{YUYV, 1280, 720}, {MJPEG, 1280, 720}, {MJPEG, 3840, 2160}};
        assertEquals(1, UvcLink.pickSize(options));
    }

    @Test
    public void ignoresFormatsItCannotShow() {
        assertEquals(-1, UvcLink.pickSize(new int[][]{{H264, 1920, 1080}}));
        assertEquals(-1, UvcLink.pickSize(new int[0][]));
    }

    @Test
    public void showsAnalogVideoAsFourThree() {
        assertArrayEquals(new int[]{640, 480}, UvcLink.displaySize(720, 480));   // NTSC
        assertArrayEquals(new int[]{768, 576}, UvcLink.displaySize(720, 576));   // PAL
        assertArrayEquals(new int[]{640, 480}, UvcLink.displaySize(640, 480));
        assertArrayEquals(new int[]{1280, 720}, UvcLink.displaySize(1280, 720)); // 16:9 stays
    }
}
