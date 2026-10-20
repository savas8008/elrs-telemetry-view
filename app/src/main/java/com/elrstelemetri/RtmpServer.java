package com.elrstelemetri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Minimal RTMP ingest server: accepts one publisher (e.g. DJI Fly "Live streaming → RTMP")
 * and delivers the video as Annex-B NAL units. Pure Java so it can be tested with ffmpeg.
 *
 * Supports the simple RTMP handshake, chunk streams, the AMF0 commands a publisher sends
 * (connect, releaseStream, FCPublish, createStream, publish), and FLV video tags carrying
 * H.264 (codec id 7) or H.265 (Enhanced RTMP "hvc1", or the non-standard codec id 12).
 * Audio is ignored.
 */
public final class RtmpServer {

    public interface Listener {
        void onPublish(String app, String streamKey, String remote);

        /** Decoder configuration. For H.264 csd1 holds the PPS; for H.265 csd0 holds VPS+SPS+PPS and csd1 is null. */
        void onVideoConfig(String mime, byte[] csd0, byte[] csd1);

        /** One access unit in Annex-B format. */
        void onVideoFrame(byte[] annexB, long ptsMs, boolean keyFrame);

        void onDisconnect(String reason);
    }

    public static final String MIME_AVC = "video/avc";
    public static final String MIME_HEVC = "video/hevc";

    private static final int HANDSHAKE_SIZE = 1536;
    private static final int OUT_CHUNK_SIZE = 4096;
    private static final int WINDOW_ACK_SIZE = 2_500_000;
    private static final byte[] START_CODE = {0, 0, 0, 1};

    private final int port;
    private final Listener listener;
    private volatile boolean running;
    private volatile ServerSocket server;
    private volatile Socket current;

    public RtmpServer(int port, Listener listener) {
        this.port = port;
        this.listener = listener;
    }

    public int port() {
        return port;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port));
        server = ss;
        running = true;
        Thread t = new Thread(this::acceptLoop, "rtmp-accept");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop() {
        running = false;
        closeQuietly(server);
        closeQuietly(current);
    }

    public boolean isRunning() {
        return running;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                Socket old = current;
                current = s;           // a new publisher replaces the old one
                closeQuietly(old);
                Thread t = new Thread(() -> serve(s), "rtmp-session");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void serve(Socket s) {
        String reason = "closed";
        try {
            s.setTcpNoDelay(true);
            new Session(s).run();
        } catch (EOFException e) {
            reason = "publisher disconnected";
        } catch (IOException e) {
            reason = String.valueOf(e.getMessage());
        } finally {
            closeQuietly(s);
            if (current == s) {
                current = null;
                listener.onDisconnect(reason);
            }
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    // ================================================================ Session

    private static final class ChunkState {
        int length, type, streamId;
        long timestamp, delta;
        boolean extended;
        byte[] buf;
        int pos;
    }

    private final class Session {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final Map<Integer, ChunkState> chunks = new HashMap<>();
        private int inChunkSize = 128;
        private long bytesIn, lastAck;
        private int peerWindow = WINDOW_ACK_SIZE;
        private String app = "";
        private int nalLengthSize = 4;
        private String mime;

        Session(Socket s) throws IOException {
            socket = s;
            in = new BufferedInputStream(s.getInputStream(), 65536);
            out = new BufferedOutputStream(s.getOutputStream(), 16384);
        }

        void run() throws IOException {
            handshake();
            while (running && current == socket) readChunk();
        }

        // ---- I/O helpers

        private int read() throws IOException {
            int b = in.read();
            if (b < 0) throw new EOFException();
            bytesIn++;
            return b;
        }

        private void readFully(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int n = in.read(b, off, len);
                if (n < 0) throw new EOFException();
                off += n;
                len -= n;
                bytesIn += n;
            }
        }

        private int read3() throws IOException {
            return (read() << 16) | (read() << 8) | read();
        }

        private void handshake() throws IOException {
            int version = read();
            if (version != 3) throw new IOException("unsupported RTMP version " + version);
            byte[] c1 = new byte[HANDSHAKE_SIZE];
            readFully(c1, 0, c1.length);
            byte[] s1 = new byte[HANDSHAKE_SIZE];
            new Random().nextBytes(s1);
            for (int i = 0; i < 8; i++) s1[i] = 0;  // time = 0, zero = 0
            out.write(3);
            out.write(s1);
            out.write(c1);                          // S2 echoes C1 (simple handshake)
            out.flush();
            byte[] c2 = new byte[HANDSHAKE_SIZE];
            readFully(c2, 0, c2.length);
        }

        private void readChunk() throws IOException {
            int b0 = read();
            int fmt = b0 >> 6;
            int csid = b0 & 0x3F;
            if (csid == 0) csid = 64 + read();
            else if (csid == 1) csid = 64 + read() + read() * 256;
            ChunkState st = chunks.get(csid);
            if (st == null) {
                st = new ChunkState();
                chunks.put(csid, st);
            }
            long ts = 0;
            if (fmt <= 2) {
                ts = read3();
                if (fmt <= 1) {
                    st.length = read3();
                    st.type = read();
                    if (fmt == 0) st.streamId = read() | (read() << 8) | (read() << 16) | (read() << 24);
                }
                st.extended = ts == 0xFFFFFF;
            }
            if (st.extended) {
                long ext = ((long) read() << 24) | (read() << 16) | (read() << 8) | read();
                if (fmt <= 2) ts = ext;
            }
            if (fmt == 0) {
                st.timestamp = ts;
                st.delta = 0;
            } else if (fmt == 1 || fmt == 2) {
                st.delta = ts;
                st.timestamp += ts;
            } else if (st.pos == 0) {
                st.timestamp += st.delta;          // type 3 starting a new message repeats the delta
            }

            if (st.pos == 0 || st.buf == null || st.buf.length != st.length) {
                if (st.pos != 0) st.pos = 0;
                st.buf = new byte[st.length];
            }
            int n = Math.min(inChunkSize, st.length - st.pos);
            readFully(st.buf, st.pos, n);
            st.pos += n;
            if (st.pos >= st.length) {
                st.pos = 0;
                handleMessage(st.type, st.streamId, st.timestamp, st.buf);
            }
            if (bytesIn - lastAck >= peerWindow) {
                lastAck = bytesIn;
                sendControl(3, int32((int) bytesIn));
            }
        }

        private void handleMessage(int type, int streamId, long ts, byte[] p) throws IOException {
            switch (type) {
                case 1:  // set chunk size
                    inChunkSize = (int) (u32(p, 0) & 0x7FFFFFFF);
                    break;
                case 2:  // abort
                    ChunkState st = chunks.get((int) u32(p, 0));
                    if (st != null) st.pos = 0;
                    break;
                case 4:  // user control: answer ping requests
                    if (p.length >= 6 && u16(p, 0) == 6) {
                        byte[] r = new byte[6];
                        r[1] = 7;
                        System.arraycopy(p, 2, r, 2, 4);
                        writeMessage(2, 4, 0, 0, r);
                    }
                    break;
                case 5:  // window acknowledgement size
                    peerWindow = (int) u32(p, 0);
                    break;
                case 17: // AMF3 command: leading 0 byte, then AMF0
                    handleCommand(java.util.Arrays.copyOfRange(p, 1, p.length));
                    break;
                case 20:
                    handleCommand(p);
                    break;
                case 9:
                    handleVideo(p, ts);
                    break;
                default:
                    break;  // audio, metadata, acknowledgements
            }
        }

        // ---- Commands

        private void handleCommand(byte[] p) throws IOException {
            Amf0Reader r = new Amf0Reader(p);
            List<Object> v = new ArrayList<>();
            while (r.hasMore()) v.add(r.read());
            if (v.isEmpty() || !(v.get(0) instanceof String)) return;
            String name = (String) v.get(0);
            double tx = v.size() > 1 && v.get(1) instanceof Double ? (Double) v.get(1) : 0;
            switch (name) {
                case "connect": {
                    if (v.size() > 2 && v.get(2) instanceof Map) {
                        Object a = ((Map<?, ?>) v.get(2)).get("app");
                        if (a != null) app = a.toString();
                    }
                    sendControl(5, int32(WINDOW_ACK_SIZE));
                    sendControl(6, concat(int32(WINDOW_ACK_SIZE), new byte[]{2}));
                    sendControl(1, int32(OUT_CHUNK_SIZE));
                    Map<String, Object> props = new LinkedHashMap<>();
                    props.put("fmsVer", "FMS/3,0,1,123");
                    props.put("capabilities", 31.0);
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("level", "status");
                    info.put("code", "NetConnection.Connect.Success");
                    info.put("description", "Connection succeeded.");
                    info.put("objectEncoding", 0.0);
                    command(0, "_result", tx, props, info);
                    break;
                }
                case "releaseStream":
                case "FCPublish":
                    command(0, "_result", tx, null, null);
                    break;
                case "createStream":
                    command(0, "_result", tx, null, 1.0);
                    break;
                case "publish": {
                    String key = v.size() > 3 && v.get(3) instanceof String ? (String) v.get(3) : "";
                    writeMessage(2, 4, 0, 0, new byte[]{0, 0, 0, 0, 0, 1});  // StreamBegin(1)
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("level", "status");
                    info.put("code", "NetStream.Publish.Start");
                    info.put("description", "Start publishing");
                    command(1, "onStatus", 0, null, info);
                    listener.onPublish(app, key, String.valueOf(socket.getRemoteSocketAddress()));
                    break;
                }
                case "deleteStream":
                case "FCUnpublish":
                    throw new EOFException();
                default:
                    break;
            }
        }

        private void command(int streamId, String name, double tx, Object a, Object b) throws IOException {
            Amf0Writer w = new Amf0Writer();
            w.write(name);
            w.write(tx);
            w.write(a);
            w.write(b);
            writeMessage(streamId == 0 ? 3 : 5, 20, streamId, 0, w.bytes());
        }

        private void sendControl(int type, byte[] payload) throws IOException {
            writeMessage(2, type, 0, 0, payload);
            if (type == 1) outChunkSize = OUT_CHUNK_SIZE;
        }

        private int outChunkSize = 128;

        private void writeMessage(int csid, int type, int streamId, long ts, byte[] payload) throws IOException {
            out.write(csid & 0x3F);  // fmt 0
            out.write((int) (ts >> 16) & 0xFF);
            out.write((int) (ts >> 8) & 0xFF);
            out.write((int) ts & 0xFF);
            out.write(payload.length >> 16 & 0xFF);
            out.write(payload.length >> 8 & 0xFF);
            out.write(payload.length & 0xFF);
            out.write(type);
            out.write(streamId & 0xFF);
            out.write(streamId >> 8 & 0xFF);
            out.write(streamId >> 16 & 0xFF);
            out.write(streamId >> 24 & 0xFF);
            int pos = 0;
            while (pos < payload.length) {
                if (pos > 0) out.write(0xC0 | (csid & 0x3F));  // fmt 3 continuation
                int n = Math.min(outChunkSize, payload.length - pos);
                out.write(payload, pos, n);
                pos += n;
            }
            out.flush();
        }

        // ---- Video

        private void handleVideo(byte[] p, long ts) {
            if (p.length < 2) return;
            int b0 = p[0] & 0xFF;
            boolean key;
            int packetType;
            int dataOffset;
            boolean hevc;
            if ((b0 & 0x80) != 0) {                      // Enhanced RTMP
                if (p.length < 5) return;
                key = ((b0 >> 4) & 7) == 1;
                packetType = b0 & 0x0F;
                String fourcc = new String(p, 1, 4, StandardCharsets.US_ASCII);
                if (fourcc.equals("hvc1")) hevc = true;
                else if (fourcc.equals("avc1")) hevc = false;
                else return;
                if (packetType == 0) {                   // SequenceStart
                    parseConfig(p, 5, hevc);
                    return;
                }
                if (packetType == 1) dataOffset = 8;      // CodedFrames (with composition time)
                else if (packetType == 3) dataOffset = 5; // CodedFramesX
                else return;
            } else {
                int codec = b0 & 0x0F;
                if (codec != 7 && codec != 12) return;
                hevc = codec == 12;
                key = (b0 >> 4) == 1;
                if (p.length < 5) return;
                packetType = p[1] & 0xFF;
                if (packetType == 0) {
                    parseConfig(p, 5, hevc);
                    return;
                }
                if (packetType != 1) return;
                dataOffset = 5;
            }
            if (mime == null) return;  // no configuration yet
            byte[] annexB = toAnnexB(p, dataOffset, nalLengthSize);
            if (annexB.length > 0) listener.onVideoFrame(annexB, ts, key);
        }

        private void parseConfig(byte[] p, int off, boolean hevc) {
            try {
                if (!hevc) {
                    // AVCDecoderConfigurationRecord
                    nalLengthSize = (p[off + 4] & 3) + 1;
                    int pos = off + 5;
                    int numSps = p[pos++] & 0x1F;
                    ByteArrayOutputStream sps = new ByteArrayOutputStream();
                    for (int i = 0; i < numSps; i++) {
                        int len = u16(p, pos);
                        pos += 2;
                        sps.write(START_CODE);
                        sps.write(p, pos, len);
                        pos += len;
                    }
                    int numPps = p[pos++] & 0xFF;
                    ByteArrayOutputStream pps = new ByteArrayOutputStream();
                    for (int i = 0; i < numPps; i++) {
                        int len = u16(p, pos);
                        pos += 2;
                        pps.write(START_CODE);
                        pps.write(p, pos, len);
                        pos += len;
                    }
                    mime = MIME_AVC;
                    listener.onVideoConfig(MIME_AVC, sps.toByteArray(), pps.toByteArray());
                } else {
                    // HEVCDecoderConfigurationRecord
                    nalLengthSize = (p[off + 21] & 3) + 1;
                    int pos = off + 22;
                    int arrays = p[pos++] & 0xFF;
                    ByteArrayOutputStream csd = new ByteArrayOutputStream();
                    for (int a = 0; a < arrays; a++) {
                        pos++;  // array_completeness | NAL type
                        int count = u16(p, pos);
                        pos += 2;
                        for (int i = 0; i < count; i++) {
                            int len = u16(p, pos);
                            pos += 2;
                            csd.write(START_CODE);
                            csd.write(p, pos, len);
                            pos += len;
                        }
                    }
                    mime = MIME_HEVC;
                    listener.onVideoConfig(MIME_HEVC, csd.toByteArray(), null);
                }
            } catch (IndexOutOfBoundsException | IOException e) {
                // malformed configuration record: wait for the next one
            }
        }
    }

    /** Converts length-prefixed NAL units (AVCC/HVCC sample format) to Annex-B. */
    static byte[] toAnnexB(byte[] p, int off, int lenSize) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(p.length + 16);
        int pos = off;
        while (pos + lenSize <= p.length) {
            int len = 0;
            for (int i = 0; i < lenSize; i++) len = (len << 8) | (p[pos + i] & 0xFF);
            pos += lenSize;
            if (len <= 0 || pos + len > p.length) break;
            o.write(START_CODE, 0, 4);
            o.write(p, pos, len);
            pos += len;
        }
        return o.toByteArray();
    }

    // ================================================================ Byte helpers

    static int u16(byte[] p, int o) {
        return ((p[o] & 0xFF) << 8) | (p[o + 1] & 0xFF);
    }

    static long u32(byte[] p, int o) {
        return ((long) (p[o] & 0xFF) << 24) | ((p[o + 1] & 0xFF) << 16) | ((p[o + 2] & 0xFF) << 8) | (p[o + 3] & 0xFF);
    }

    static byte[] int32(int v) {
        return new byte[]{(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    // ================================================================ AMF0

    static final class Amf0Reader {
        private final byte[] p;
        private int pos;

        Amf0Reader(byte[] p) {
            this.p = p;
        }

        boolean hasMore() {
            return pos < p.length;
        }

        private String str(int len) {
            String s = new String(p, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }

        Object read() {
            int type = p[pos++] & 0xFF;
            switch (type) {
                case 0: {
                    long bits = 0;
                    for (int i = 0; i < 8; i++) bits = (bits << 8) | (p[pos++] & 0xFF);
                    return Double.longBitsToDouble(bits);
                }
                case 1:
                    return p[pos++] != 0;
                case 2: {
                    int len = u16(p, pos);
                    pos += 2;
                    return str(len);
                }
                case 3:
                    return props();
                case 5:
                case 6:
                    return null;
                case 8:
                    pos += 4;  // approximate count; properties end with the object-end marker
                    return props();
                case 10: {
                    int n = (int) u32(p, pos);
                    pos += 4;
                    List<Object> l = new ArrayList<>();
                    for (int i = 0; i < n; i++) l.add(read());
                    return l;
                }
                case 11:
                    pos += 10;
                    return null;
                case 12: {
                    int len = (int) u32(p, pos);
                    pos += 4;
                    return str(len);
                }
                default:
                    pos = p.length;  // unsupported type: stop parsing
                    return null;
            }
        }

        private Map<String, Object> props() {
            Map<String, Object> m = new LinkedHashMap<>();
            while (pos + 3 <= p.length) {
                int len = u16(p, pos);
                pos += 2;
                if (len == 0 && (p[pos] & 0xFF) == 9) {
                    pos++;
                    break;
                }
                String k = str(len);
                m.put(k, read());
            }
            return m;
        }
    }

    static final class Amf0Writer {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream();

        void write(Object v) {
            if (v == null) {
                o.write(5);
            } else if (v instanceof Double) {
                o.write(0);
                long bits = Double.doubleToLongBits((Double) v);
                for (int i = 7; i >= 0; i--) o.write((int) (bits >> (i * 8)) & 0xFF);
            } else if (v instanceof Boolean) {
                o.write(1);
                o.write((Boolean) v ? 1 : 0);
            } else if (v instanceof String) {
                o.write(2);
                key((String) v);
            } else if (v instanceof Map) {
                o.write(3);
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    key(e.getKey().toString());
                    write(e.getValue());
                }
                o.write(0);
                o.write(0);
                o.write(9);
            } else {
                o.write(5);
            }
        }

        private void key(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            o.write(b.length >> 8);
            o.write(b.length & 0xFF);
            o.write(b, 0, b.length);
        }

        byte[] bytes() {
            return o.toByteArray();
        }
    }
}
