package com.elrstelemetri.v2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.hardware.usb.UsbConstants;

import com.elrstelemetri.n3.H264AnnexBAssembler;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Goggles V2 send the raw H.264 Annex-B stream over USB with no framing. Feeds a real stream in
 * random-size "USB reads" through the assembler and decodes the result with ffmpeg.
 */
public class V2PipelineTest {

    private static final int BULK = UsbConstants.USB_ENDPOINT_XFER_BULK;
    private static final int INT = UsbConstants.USB_ENDPOINT_XFER_INT;
    private static final int OUT = UsbConstants.USB_DIR_OUT;
    private static final int IN = UsbConstants.USB_DIR_IN;

    @Test
    public void recognisesTheGoggles() {
        assertTrue(V2UsbLink.isGoggles(11427, 31));
        assertFalse(V2UsbLink.isGoggles(11427, 32));
        assertFalse(V2UsbLink.isGoggles(0x0483, 31));
        assertArrayEquals("RMVT".getBytes(), V2UsbLink.MAGIC);
    }

    @Test
    public void picksBulkEndpointsByDirection() {
        // DigiView's order: 0 = OUT, 1 = IN
        assertArrayEquals(new int[]{0, 1}, V2UsbLink.pickBulkEndpoints(new int[]{OUT, IN}, new int[]{BULK, BULK}));
        // reversed order and an interrupt endpoint in front
        assertArrayEquals(new int[]{2, 1},
                V2UsbLink.pickBulkEndpoints(new int[]{IN, IN, OUT}, new int[]{INT, BULK, BULK}));
        // no usable descriptors: fall back to DigiView's fixed order
        assertArrayEquals(new int[]{0, 1}, V2UsbLink.pickBulkEndpoints(new int[]{IN, IN}, new int[]{BULK, BULK}));
        assertNull(V2UsbLink.pickBulkEndpoints(new int[]{IN}, new int[]{BULK}));
    }

    static String ffmpeg() {
        String p = System.getProperty("ffmpeg", System.getenv().getOrDefault("FFMPEG", "ffmpeg"));
        try {
            Process pr = new ProcessBuilder(p, "-hide_banner", "-version").redirectErrorStream(true).start();
            drain(pr.getInputStream());
            return pr.waitFor() == 0 ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String drain(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toString("UTF-8");
    }

    @Test
    public void rawH264StreamSurvivesTheUsbPipeline() throws Exception {
        String ff = ffmpeg();
        assumeTrue("ffmpeg not available", ff != null);

        File h264 = File.createTempFile("v2src", ".h264");
        Process enc = new ProcessBuilder(ff, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=60", "-t", "2",
                "-c:v", "libx264", "-preset", "ultrafast", "-g", "30", "-bf", "0", "-pix_fmt", "yuv420p",
                "-f", "h264", h264.getAbsolutePath()).redirectErrorStream(true).start();
        String encLog = drain(enc.getInputStream());
        assertEquals("encode failed: " + encLog, 0, enc.waitFor());
        byte[] stream = Files.readAllBytes(h264.toPath());

        H264AnnexBAssembler assembler = new H264AnnexBAssembler();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] counts = new int[2];  // access units, key frames
        byte[][] csd = new byte[2][];
        Random rnd = new Random(7);
        int off = 0;
        while (off < stream.length) {
            int n = Math.min(stream.length - off, 1 + rnd.nextInt(128 * 1024));  // bulk reads up to 128 KB
            byte[] chunk = java.util.Arrays.copyOfRange(stream, off, off + n);
            assembler.accept(chunk, au -> {
                counts[0]++;
                if (au.keyFrame) counts[1]++;
                csd[0] = au.sps;
                csd[1] = au.pps;
                out.write(au.data, 0, au.data.length);
            });
            off += n;
        }

        // 120 frames encoded; the assembler holds the last access unit until the next one starts
        assertTrue("access units: " + counts[0], counts[0] >= 118);
        assertTrue("key frames: " + counts[1], counts[1] >= 4);
        assertNotNull(csd[0]);
        assertNotNull(csd[1]);
        assertEquals(7, csd[0][4] & 0x1F);
        assertEquals(8, csd[1][4] & 0x1F);

        File rebuilt = File.createTempFile("v2out", ".h264");
        try (FileOutputStream fo = new FileOutputStream(rebuilt)) {
            out.writeTo(fo);
        }
        Process dec = new ProcessBuilder(ff, "-hide_banner", "-f", "h264", "-i", rebuilt.getAbsolutePath(),
                "-f", "null", "-").redirectErrorStream(true).start();
        String log = drain(dec.getInputStream());
        assertEquals("decode failed: " + log, 0, dec.waitFor());
        Matcher m = Pattern.compile("frame=\\s*(\\d+)").matcher(log);
        int decoded = 0;
        while (m.find()) decoded = Integer.parseInt(m.group(1));
        assertTrue("decoded frames: " + decoded, decoded >= 118);
        h264.delete();
        rebuilt.delete();
    }
}
