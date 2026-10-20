package com.elrstelemetri;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * End-to-end test of the RTMP ingest: ffmpeg publishes a test pattern (like DJI Fly would),
 * the server extracts Annex-B video, and ffmpeg decodes the result to prove it is valid.
 * Skipped when ffmpeg is not installed (set -Dffmpeg=/path/to/ffmpeg to override).
 */
public class RtmpServerTest {

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

    static String drain(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toString(StandardCharsets.UTF_8.name());
    }

    static final class Capture implements RtmpServer.Listener {
        final CountDownLatch published = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        final ByteArrayOutputStream stream = new ByteArrayOutputStream();
        final AtomicInteger frames = new AtomicInteger();
        final AtomicInteger keyFrames = new AtomicInteger();
        volatile String mime, app, key;

        @Override
        public void onPublish(String app, String streamKey, String remote) {
            this.app = app;
            this.key = streamKey;
            published.countDown();
        }

        @Override
        public synchronized void onVideoConfig(String mime, byte[] csd0, byte[] csd1) {
            this.mime = mime;
            stream.write(csd0, 0, csd0.length);
            if (csd1 != null) stream.write(csd1, 0, csd1.length);
        }

        @Override
        public synchronized void onVideoFrame(byte[] annexB, long ptsMs, boolean keyFrame) {
            frames.incrementAndGet();
            if (keyFrame) keyFrames.incrementAndGet();
            stream.write(annexB, 0, annexB.length);
        }

        @Override
        public void onDisconnect(String reason) {
            disconnected.countDown();
        }
    }

    private void publishAndDecode(String encoder, String format, String expectedMime, int port) throws Exception {
        String ff = ffmpeg();
        assumeTrue("ffmpeg not available", ff != null);

        Capture cap = new Capture();
        RtmpServer server = new RtmpServer(port, cap);
        server.start();
        try {
            Process pub = new ProcessBuilder(ff, "-hide_banner", "-loglevel", "error", "-re",
                    "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30",
                    "-t", "3", "-c:v", encoder, "-preset", "ultrafast", "-g", "30", "-pix_fmt", "yuv420p",
                    "-an", "-f", "flv", "rtmp://127.0.0.1:" + port + "/live/dji")
                    .redirectErrorStream(true).start();
            String pubLog = drain(pub.getInputStream());
            assertEquals("ffmpeg publish failed: " + pubLog, 0, pub.waitFor());
            assertTrue(cap.published.await(5, TimeUnit.SECONDS));
            cap.disconnected.await(5, TimeUnit.SECONDS);
        } finally {
            server.stop();
        }

        assertEquals("live", cap.app);
        assertEquals("dji", cap.key);
        assertEquals(expectedMime, cap.mime);
        assertTrue("frames: " + cap.frames.get(), cap.frames.get() >= 80);   // ~90 frames in 3 s
        assertTrue("key frames: " + cap.keyFrames.get(), cap.keyFrames.get() >= 3);

        File f = File.createTempFile("rtmp", "." + format);
        try (FileOutputStream fo = new FileOutputStream(f)) {
            cap.stream.writeTo(fo);
        }
        Process dec = new ProcessBuilder(ff, "-hide_banner", "-f", format, "-i", f.getAbsolutePath(), "-f", "null", "-")
                .redirectErrorStream(true).start();
        String log = drain(dec.getInputStream());
        assertEquals("decode failed: " + log, 0, dec.waitFor());
        Matcher m = Pattern.compile("frame=\\s*(\\d+)").matcher(log);
        int decoded = 0;
        while (m.find()) decoded = Integer.parseInt(m.group(1));
        assertTrue("decoded frames: " + decoded + "\n" + log, decoded >= 80);
        f.delete();
    }

    @Test
    public void h264FromFfmpegIsReceivedAndDecodable() throws Exception {
        publishAndDecode("libx264", "h264", RtmpServer.MIME_AVC, 19351);
    }

    @Test
    public void h265EnhancedRtmpIsReceivedAndDecodable() throws Exception {
        publishAndDecode("libx265", "hevc", RtmpServer.MIME_HEVC, 19352);
    }

    @Test
    public void lengthPrefixedToAnnexB() {
        byte[] in = {0, 0, 0, 2, 0x65, 0x11, 0, 0, 0, 1, 0x41};
        byte[] out = RtmpServer.toAnnexB(in, 0, 4);
        assertArrayEquals(new byte[]{0, 0, 0, 1, 0x65, 0x11, 0, 0, 0, 1, 0x41}, out);
    }

    @Test
    public void amf0RoundTrip() {
        RtmpServer.Amf0Writer w = new RtmpServer.Amf0Writer();
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("app", "live");
        m.put("n", 3.0);
        w.write("connect");
        w.write(1.0);
        w.write(m);
        w.write(null);
        RtmpServer.Amf0Reader r = new RtmpServer.Amf0Reader(w.bytes());
        assertEquals("connect", r.read());
        assertEquals(1.0, (Double) r.read(), 0);
        Object o = r.read();
        assertNotNull(o);
        assertEquals("live", ((java.util.Map<?, ?>) o).get("app"));
        assertEquals(null, r.read());
        assertTrue(!r.hasMore());
    }
}
