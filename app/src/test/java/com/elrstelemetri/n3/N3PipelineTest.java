package com.elrstelemetri.n3;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Feeds a real H.264 stream through the N3 USB pipeline: the stream is wrapped in logiclink
 * packets (video port 0x574A) with control packets and garbage interleaved, delivered in
 * random-size "USB reads", reassembled into access units and decoded by ffmpeg.
 */
public class N3PipelineTest {

    static byte[] logiclink(int port, byte[] payload, int off, int len) {
        byte[] p = new byte[8 + len];
        p[0] = 0x55;
        p[1] = (byte) 0xCC;
        p[2] = (byte) port;
        p[3] = (byte) (port >> 8);
        p[4] = (byte) len;
        p[5] = (byte) (len >> 8);
        System.arraycopy(payload, off, p, 8, len);
        return p;
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
    public void controlPacketsMatchTheCapture() {
        byte[][] p = N3ControlPackets.copies();
        assertEquals(2, p.length);
        // logiclink header: 55 CC, port 0x5749 (LE), length excludes the trailing DUML CRC16
        assertEquals(0x55, p[0][0] & 0xFF);
        assertEquals(0xCC, p[0][1] & 0xFF);
        assertEquals(0x5749, (p[0][2] & 0xFF) | ((p[0][3] & 0xFF) << 8));
        assertEquals(p[0].length - 8 - 2, (p[0][4] & 0xFF) | ((p[0][5] & 0xFF) << 8));
        assertEquals(p[1].length - 8 - 2, (p[1][4] & 0xFF) | ((p[1][5] & 0xFF) << 8));
        // contains the "camcap_common" subscription string
        assertTrue(new String(p[0], java.nio.charset.StandardCharsets.ISO_8859_1).contains("camcap_common"));
    }

    @Test
    public void decoderResynchronisesAfterGarbage() {
        LogicLinkDecoder d = new LogicLinkDecoder();
        List<LogicLinkDecoder.Packet> got = new ArrayList<>();
        byte[] payload = {1, 2, 3, 4, 5};
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.write(new byte[]{0x13, 0x55, 0x00, 0x55}, 0, 4);
        byte[] pk = logiclink(LogicLinkDecoder.VIDEO_PORT, payload, 0, payload.length);
        s.write(pk, 0, pk.length);
        byte[] all = s.toByteArray();
        for (byte b : all) d.accept(new byte[]{b}, 0, 1, got::add);  // one byte at a time
        assertEquals(1, got.size());
        assertEquals(LogicLinkDecoder.VIDEO_PORT, got.get(0).port);
        assertArrayEquals(payload, got.get(0).payload);
    }

    @Test
    public void realH264StreamSurvivesTheUsbPipeline() throws Exception {
        String ff = ffmpeg();
        assumeTrue("ffmpeg not available", ff != null);

        File h264 = File.createTempFile("n3src", ".h264");
        Process enc = new ProcessBuilder(ff, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=60", "-t", "2",
                "-c:v", "libx264", "-preset", "ultrafast", "-g", "30", "-bf", "0", "-pix_fmt", "yuv420p",
                "-f", "h264", h264.getAbsolutePath()).redirectErrorStream(true).start();
        String encLog = drain(enc.getInputStream());
        assertEquals("encode failed: " + encLog, 0, enc.waitFor());
        byte[] stream = Files.readAllBytes(h264.toPath());

        // Wrap like the goggles: video chunks of varying size, control packets and garbage in between
        Random rnd = new Random(42);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        int pos = 0;
        while (pos < stream.length) {
            int n = Math.min(stream.length - pos, 200 + rnd.nextInt(20000));
            byte[] pk = logiclink(LogicLinkDecoder.VIDEO_PORT, stream, pos, n);
            wire.write(pk, 0, pk.length);
            pos += n;
            if (rnd.nextInt(5) == 0) {
                byte[] ctl = logiclink(0x7530, new byte[]{9, 9, 9}, 0, 3);
                wire.write(ctl, 0, ctl.length);
            }
            if (rnd.nextInt(10) == 0) wire.write(new byte[]{0x55, 0x01, 0x02}, 0, 3);
        }
        byte[] usb = wire.toByteArray();

        LogicLinkDecoder decoder = new LogicLinkDecoder();
        H264AnnexBAssembler assembler = new H264AnnexBAssembler();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] counts = new int[2];  // access units, key frames
        byte[][] csd = new byte[2][];
        int off = 0;
        while (off < usb.length) {
            int n = Math.min(usb.length - off, 1 + rnd.nextInt(16384));  // random USB read sizes
            decoder.accept(usb, off, n, p -> {
                if (p.port != LogicLinkDecoder.VIDEO_PORT) return;
                assembler.accept(p.payload, au -> {
                    counts[0]++;
                    if (au.keyFrame) counts[1]++;
                    csd[0] = au.sps;
                    csd[1] = au.pps;
                    out.write(au.data, 0, au.data.length);
                });
            });
            off += n;
        }

        // 120 frames encoded; the assembler holds the last access unit until the next one starts
        assertTrue("access units: " + counts[0], counts[0] >= 118);
        assertTrue("key frames: " + counts[1], counts[1] >= 4);
        assertNotNull(csd[0]);
        assertNotNull(csd[1]);
        assertArrayEquals(new byte[]{0, 0, 0, 1}, java.util.Arrays.copyOf(csd[0], 4));
        assertEquals(7, csd[0][4] & 0x1F);
        assertEquals(8, csd[1][4] & 0x1F);

        File rebuilt = File.createTempFile("n3out", ".h264");
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
