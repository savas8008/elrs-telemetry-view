package com.elrstelemetri.goggles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** "Copy video log": only the video link tags are read, and the copied text stays readable. */
public class VideoLogTest {

    @Test
    public void readsOnlyTheVideoLinkTags() {
        List<String> cmd = VideoLog.command();
        assertEquals(Arrays.asList("logcat", "-d", "-v", "time", "-t", "500", "-s", "V2Usb:V", "Uvc:V"), cmd);
    }

    @Test
    public void reportKeepsLogLinesAndDropsSeparators() {
        String log = "--------- beginning of main\n"
                + "11-04 10:00:00.000 I/V2Usb( 123): opened DJI FPV Goggles (2CA3:001F)\n"
                + "--------- beginning of system\n"
                + "11-04 10:00:01.000 I/Uvc( 123): preview started\n";
        String r = VideoLog.report("HEADER", log);
        assertTrue(r.startsWith("HEADER\n\n"));
        assertTrue(r.contains("I/V2Usb( 123): opened DJI FPV Goggles (2CA3:001F)\n"));
        assertTrue(r.contains("I/Uvc( 123): preview started\n"));
        assertFalse(r.contains("beginning of"));
        assertFalse(r.contains("No video connection messages yet"));
    }

    @Test
    public void emptyLogExplainsWhatToDo() {
        String r = VideoLog.report("HEADER", "--------- beginning of main\n");
        assertTrue(r.contains("No video connection messages yet"));
        assertTrue(VideoLog.report("HEADER", "").contains("No video connection messages yet"));
    }
}
