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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;

/**
 * Goggles video: decodes H.264 with MediaCodec (low-latency mode where supported) onto a
 * TextureView, letterboxed. While no video arrives it shows what to do and a Connect button.
 */
public final class VideoPanel extends FrameLayout implements TextureView.SurfaceTextureListener {

    public static final String MIME_AVC = "video/avc";

    public interface Callback {
        /** The user pressed Connect. */
        void onConnectClicked();
    }

    private final TextureView texture;
    private final LinearLayout overlay;
    private final TextView message, detail, connect;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private Callback callback;

    private Surface surface;
    private volatile MediaCodec codec;
    private byte[] sps, pps;
    private boolean waitForKey = true;
    private volatile int videoW, videoH;
    private volatile long lastFrameTime;   // last frame rendered by the decoder
    private volatile long lastDataTime;    // last video data received from the goggles
    private volatile boolean hadVideo;     // video arrived on the current connection
    private volatile boolean connected;
    private volatile String lastEnd = "";
    private String linkStatus = "";

    public VideoPanel(Context c) {
        super(c);
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.BLACK);
        border.setStroke(2, Color.argb(90, 255, 255, 255));
        setBackground(border);
        texture = new TextureView(c);
        texture.setSurfaceTextureListener(this);
        addView(texture, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        overlay = new LinearLayout(c);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(Color.BLACK);  // opaque: never leave a frozen last frame visible
        overlay.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView icon = new TextView(c);
        icon.setText("📷");
        icon.setTextSize(26);
        icon.setGravity(Gravity.CENTER);
        overlay.addView(icon);
        message = new TextView(c);
        message.setTextColor(Color.rgb(225, 230, 238));
        message.setTextSize(13);
        message.setTypeface(Typeface.DEFAULT_BOLD);
        message.setGravity(Gravity.CENTER);
        overlay.addView(message);
        detail = new TextView(c);
        detail.setTextColor(Color.rgb(140, 150, 165));
        detail.setTextSize(11);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, dp(2), 0, dp(6));
        overlay.addView(detail);
        connect = new TextView(c);
        connect.setText("Connect");
        connect.setTextColor(Color.WHITE);
        connect.setTextSize(14);
        connect.setTypeface(Typeface.DEFAULT_BOLD);
        connect.setGravity(Gravity.CENTER);
        connect.setPadding(dp(18), dp(8), dp(18), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(0, 130, 190));
        bg.setCornerRadius(dp(8));
        connect.setBackground(bg);
        connect.setOnClickListener(v -> {
            if (callback != null) callback.onConnectClicked();
        });
        overlay.addView(connect, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        addView(overlay, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    public void setCallback(Callback cb) {
        callback = cb;
    }

    /** USB link status shown while no video arrives. */
    public void setLinkStatus(String s) {
        linkStatus = s;
    }

    /** Updates the overlay; call periodically from the UI thread. */
    public void refresh(long now) {
        if (now - lastFrameTime < 2000) {
            overlay.setVisibility(View.GONE);
            return;
        }
        overlay.setVisibility(View.VISIBLE);
        if (connected && hadVideo) {
            message.setText("No video from the goggles for " + ((now - lastDataTime) / 1000) + " s");
            detail.setText("Check the goggles' link to the aircraft — recovering automatically");
            connect.setVisibility(View.GONE);
        } else if (connected) {
            message.setText("Goggles connected — waiting for video");
            detail.setText("The goggles must be linked to the aircraft");
            connect.setVisibility(View.GONE);
        } else {
            message.setText("Connect DJI Goggles N3 to this device with a USB-C cable");
            String d = linkStatus.isEmpty() ? "" : "Status: " + linkStatus;
            if (!lastEnd.isEmpty()) d += (d.isEmpty() ? "" : "\n") + lastEnd;
            detail.setText(d);
            connect.setVisibility(View.VISIBLE);
        }
    }

    /** True while connected but the goggles stopped sending video. */
    public boolean isStalled() {
        return connected && hadVideo && System.currentTimeMillis() - lastDataTime > 3000;
    }

    public boolean isLive() {
        return System.currentTimeMillis() - lastFrameTime < 2000;
    }

    public String describe() {
        return isLive() ? String.format(Locale.US, "H.264 %dx%d", videoW, videoH) : connected ? "connected, no video" : "not connected";
    }

    // ---------------------------------------------------------------- Video input (USB read thread)

    public void onConnected() {
        connected = true;
        hadVideo = false;
        synchronized (lock) {
            waitForKey = true;
        }
    }

    /** SPS/PPS with 4-byte start codes. */
    public void onVideoConfig(byte[] newSps, byte[] newPps) {
        synchronized (lock) {
            boolean same = Arrays.equals(newSps, sps) && Arrays.equals(newPps, pps) && codec != null;
            sps = newSps;
            pps = newPps;
            if (!same) restartDecoder();
        }
    }

    /** One access unit in Annex-B format. */
    public void onVideoFrame(byte[] annexB, long ptsMs, boolean keyFrame) {
        long now = System.currentTimeMillis();
        boolean gap = hadVideo && now - lastDataTime > 1000;
        lastDataTime = now;
        hadVideo = true;
        synchronized (lock) {
            // after a pause the decoder needs a key frame, otherwise it shows corrupted images
            if (gap) waitForKey = true;
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

    public void onDisconnected(String reason) {
        connected = false;
        hadVideo = false;
        lastEnd = "Last video ended " + new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date())
                + " (" + reason + ")";
    }

    // ---------------------------------------------------------------- Decoder

    /** Must hold {@link #lock}. */
    private void restartDecoder() {
        releaseDecoder();
        if (surface == null || sps == null || pps == null) return;
        try {
            MediaCodec c = MediaCodec.createDecoderByType(MIME_AVC);
            MediaFormat f = MediaFormat.createVideoFormat(MIME_AVC, 1920, 1080);
            f.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            f.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
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
            String msg = e.getMessage();
            ui.post(() -> detail.setText("Video decoder error: " + msg));
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
}
