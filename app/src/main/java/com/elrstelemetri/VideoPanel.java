package com.elrstelemetri;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Shows the RTMP video stream: decodes H.264/H.265 with MediaCodec (low-latency mode where
 * supported) onto a TextureView, letterboxed to keep the aspect ratio.
 */
public final class VideoPanel extends FrameLayout implements RtmpServer.Listener, TextureView.SurfaceTextureListener {

    public interface Callback {
        void onStreamStarted(String remote);

        void onStreamStopped();
    }

    private final TextureView texture;
    private final TextView status;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private Callback callback;

    private Surface surface;
    private volatile MediaCodec codec;
    private String mime;
    private byte[] csd0, csd1;
    private boolean waitForKey = true;
    private volatile int videoW, videoH;
    private volatile long lastFrameTime;
    private volatile long frameCount;
    private volatile boolean connected;
    private volatile String remote = "";
    private String idleText = "";

    public VideoPanel(Context c) {
        super(c);
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.BLACK);
        border.setStroke(2, Color.argb(90, 255, 255, 255));
        setBackground(border);
        texture = new TextureView(c);
        texture.setSurfaceTextureListener(this);
        addView(texture, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        status = new TextView(c);
        status.setTextColor(Color.rgb(200, 205, 215));
        status.setTextSize(12);
        status.setGravity(Gravity.CENTER);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setPadding(16, 16, 16, 16);
        addView(status, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public void setCallback(Callback cb) {
        callback = cb;
    }

    /** Text shown while no video is arriving (e.g. the stream URL). */
    public void setIdleText(String t) {
        idleText = t;
    }

    /** Updates the status overlay; call periodically from the UI thread. */
    public void refresh(long now) {
        boolean live = now - lastFrameTime < 2000;
        if (live) {
            status.setText("");
            status.setBackgroundColor(Color.TRANSPARENT);
        } else {
            status.setText(connected ? "Stream connected from " + remote + "\nwaiting for video…" : idleText);
            status.setBackgroundColor(Color.argb(170, 0, 0, 0));
        }
    }

    public boolean isLive() {
        return System.currentTimeMillis() - lastFrameTime < 2000;
    }

    public String describe() {
        if (!isLive()) return connected ? "connected, no video" : "no stream";
        return String.format(Locale.US, "%s %dx%d", codecName(mime), videoW, videoH);
    }

    private static String codecName(String m) {
        return RtmpServer.MIME_HEVC.equals(m) ? "H.265" : "H.264";
    }

    // ---------------------------------------------------------------- RtmpServer.Listener (network thread)

    @Override
    public void onPublish(String app, String streamKey, String remoteAddr) {
        connected = true;
        remote = remoteAddr.replace("/", "");
        synchronized (lock) {
            waitForKey = true;
        }
        ui.post(() -> {
            if (callback != null) callback.onStreamStarted(remote);
        });
    }

    @Override
    public void onVideoConfig(String m, byte[] c0, byte[] c1) {
        synchronized (lock) {
            boolean same = m.equals(mime) && Arrays.equals(c0, csd0) && Arrays.equals(c1, csd1) && codec != null;
            mime = m;
            csd0 = c0;
            csd1 = c1;
            if (!same) restartDecoder();
        }
    }

    @Override
    public void onVideoFrame(byte[] annexB, long ptsMs, boolean keyFrame) {
        synchronized (lock) {
            if (codec == null) return;
            if (waitForKey && !keyFrame) return;
            try {
                int idx = codec.dequeueInputBuffer(20000);
                if (idx < 0) return;  // decoder busy: drop the frame to keep latency low
                ByteBuffer b = codec.getInputBuffer(idx);
                if (b == null || annexB.length > b.capacity()) {
                    codec.queueInputBuffer(idx, 0, 0, ptsMs * 1000, 0);
                    waitForKey = true;
                    return;
                }
                b.clear();
                b.put(annexB);
                codec.queueInputBuffer(idx, 0, annexB.length, ptsMs * 1000,
                        keyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                waitForKey = false;
            } catch (IllegalStateException e) {
                restartDecoder();
            }
        }
    }

    @Override
    public void onDisconnect(String reason) {
        connected = false;
        ui.post(() -> {
            if (callback != null) callback.onStreamStopped();
        });
    }

    // ---------------------------------------------------------------- Decoder

    /** Must hold {@link #lock}. */
    private void restartDecoder() {
        releaseDecoder();
        if (surface == null || mime == null || csd0 == null) return;
        try {
            MediaCodec c = MediaCodec.createDecoderByType(mime);
            MediaFormat f = MediaFormat.createVideoFormat(mime, 1920, 1080);
            f.setByteBuffer("csd-0", ByteBuffer.wrap(csd0));
            if (csd1 != null) f.setByteBuffer("csd-1", ByteBuffer.wrap(csd1));
            boolean lowLatency = Build.VERSION.SDK_INT >= 30;
            if (lowLatency) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            try {
                c.configure(f, surface, null, 0);
            } catch (RuntimeException e) {
                if (!lowLatency) throw e;
                f.removeKey(MediaFormat.KEY_LOW_LATENCY);
                c.configure(f, surface, null, 0);
            }
            c.start();
            codec = c;
            waitForKey = true;
            Thread t = new Thread(() -> drain(c), "video-out");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            codec = null;
            ui.post(() -> status.setText("Video decoder error: " + e.getMessage()));
        }
    }

    /** Must hold {@link #lock}. */
    private void releaseDecoder() {
        MediaCodec c = codec;
        codec = null;
        if (c == null) return;
        try {
            c.stop();
        } catch (RuntimeException ignored) {
        }
        c.release();
    }

    private void drain(MediaCodec c) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            if (codec != c) return;
            try {
                int idx = c.dequeueOutputBuffer(info, 20000);
                if (idx >= 0) {
                    c.releaseOutputBuffer(idx, true);  // render immediately
                    lastFrameTime = System.currentTimeMillis();
                    frameCount++;
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = c.getOutputFormat();
                    int w = f.getInteger(MediaFormat.KEY_WIDTH);
                    int h = f.getInteger(MediaFormat.KEY_HEIGHT);
                    if (f.containsKey("crop-right") && f.containsKey("crop-left")) {
                        w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
                        h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
                    }
                    videoW = w;
                    videoH = h;
                    ui.post(this::updateTransform);
                }
            } catch (IllegalStateException e) {
                return;  // decoder was released
            }
        }
    }

    private void updateTransform() {
        int vw = getWidth(), vh = getHeight();
        if (vw == 0 || vh == 0 || videoW == 0 || videoH == 0) return;
        float scale = Math.min(vw / (float) videoW, vh / (float) videoH);
        Matrix m = new Matrix();
        m.setScale(videoW * scale / vw, videoH * scale / vh, vw / 2f, vh / 2f);
        texture.setTransform(m);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        updateTransform();
    }

    // ---------------------------------------------------------------- Surface

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        synchronized (lock) {
            surface = new Surface(st);
            restartDecoder();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        updateTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        synchronized (lock) {
            releaseDecoder();
            if (surface != null) surface.release();
            surface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }

    public void release() {
        synchronized (lock) {
            releaseDecoder();
        }
    }

    // ---------------------------------------------------------------- Stream URLs

    /** RTMP URLs this device can be reached at, one per IPv4 interface, plus localhost. */
    public static List<String> streamUrls(int port) {
        List<String> r = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) {
                        r.add("rtmp://" + a.getHostAddress() + ":" + port + "/live/dji   (" + ni.getName() + ")");
                    }
                }
            }
        } catch (Exception ignored) {
        }
        r.add("rtmp://127.0.0.1:" + port + "/live/dji   (DJI Fly on this device)");
        return r;
    }
}
