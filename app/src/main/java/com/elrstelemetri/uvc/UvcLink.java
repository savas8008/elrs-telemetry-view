package com.elrstelemetri.uvc;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import com.elrstelemetri.goggles.GogglesLink;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usb.UVCParam;

import java.util.List;
import java.util.Locale;

/**
 * Video from a USB video class (UVC) capture device, e.g. an "AV to USB" dongle fed by an analog
 * FPV receiver. These devices send over isochronous USB, which Android's Java API cannot read, so
 * the UVCAndroid library (libuvc + libusb) receives and decodes the video and draws it straight
 * into the video panel's surface.
 *
 * Android grants USB access to a video class device only if the app also holds the CAMERA
 * permission; {@code askCameraPermission} asks the activity for it.
 */
public final class UvcLink implements GogglesLink {

    /** logcat tag: adb logcat -s Uvc */
    private static final String TAG = "Uvc";

    /** Display size and format of the started video, so the panel can letterbox it. */
    public interface Display {
        void onVideoFormat(String format, int displayW, int displayH);
    }

    private final Context ctx;
    private final Listener listener;
    private final Display display;
    private final Runnable askCameraPermission;
    private final UsbManager usb;
    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean enabled;
    private HandlerThread thread;
    private Handler worker;            // every UVCCamera call runs here, except stopping for a lost surface
    private USBMonitor monitor;
    private volatile UVCCamera camera;
    private volatile Surface surface;
    private volatile boolean previewing;
    private volatile boolean streaming;
    private volatile String status = "off";
    private volatile String identity = "";

    public UvcLink(Context c, Listener listener, Display display, Runnable askCameraPermission) {
        this.ctx = c.getApplicationContext();
        this.listener = listener;
        this.display = display;
        this.askCameraPermission = askCameraPermission;
        usb = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
    }

    private final USBMonitor.OnDeviceConnectListener usbListener = new USBMonitor.OnDeviceConnectListener() {
        @Override
        public void onAttach(UsbDevice d) {
            if (isCapture(d)) {
                Log.i(TAG, "attached " + describe(d));
                main.post(() -> connect(d));
            }
        }

        @Override
        public void onDetach(UsbDevice d) {
            if (isCapture(d)) {
                Log.i(TAG, "detached " + describe(d));
                worker.post(() -> close("capture device unplugged"));
            }
        }

        @Override
        public void onDeviceOpen(UsbDevice d, USBMonitor.UsbControlBlock ctrl, boolean createNew) {
            if (isCapture(d)) worker.post(() -> open(ctrl));
        }

        @Override
        public void onDeviceClose(UsbDevice d, USBMonitor.UsbControlBlock ctrl) {
            if (isCapture(d)) worker.post(() -> close("capture device closed"));
        }

        @Override
        public void onCancel(UsbDevice d) {
            if (isCapture(d)) status = "USB permission denied — unplug and reconnect the capture device to try again";
        }
    };

    // ---------------------------------------------------------------- GogglesLink

    @Override
    public void start(Intent launchIntent) {
        if (enabled) return;
        enabled = true;
        thread = new HandlerThread("uvc");
        thread.start();
        worker = new Handler(thread.getLooper());
        // callbacks on the worker thread: opening the device blocks
        monitor = new USBMonitor(ctx, usbListener, worker);
        monitor.register();
        connect(findCapture());
    }

    @Override
    public void stop() {
        if (!enabled) return;
        enabled = false;
        Handler w = worker;
        w.post(() -> close("stopped"));
        w.post(() -> {
            monitor.unregister();
            monitor.destroy();
        });
        thread.quitSafely();
        status = "off";
    }

    @Override
    public void handleIntent(Intent intent) {
        if (enabled) connect(findCapture());
    }

    @Override
    public void reconnect() {
        if (!enabled) return;
        Log.i(TAG, "reconnect requested");
        worker.post(() -> {
            close("reconnecting");
            main.post(() -> connect(findCapture()));
        });
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public String identity() {
        return identity;
    }

    @Override
    public boolean isStreaming() {
        return streaming;
    }

    @Override
    public boolean devicePresent() {
        return findCapture() != null;
    }

    /**
     * Analog video usually carries the VTX's OSD, but false keeps the app's settings as they are:
     * true would hide the telemetry panel and save that when the video starts (as for the N3).
     */
    @Override
    public boolean videoHasOsd() {
        return false;
    }

    @Override
    public String name() {
        return "USB capture";
    }

    /**
     * A new surface is used from the worker thread. When the surface goes away (null), drawing must
     * stop before this returns, so that one stops here at once.
     */
    @Override
    public void setVideoSurface(Surface s) {
        surface = s;  // also while stopped: never keep a surface that went away
        if (s == null) {
            UVCCamera c = camera;
            if (c != null && previewing) {
                try {
                    c.stopPreview();
                } catch (RuntimeException e) {
                    Log.w(TAG, "stopPreview failed", e);
                }
                previewing = false;
                Log.i(TAG, "surface gone: preview stopped");
            }
            return;
        }
        Handler w = worker;
        if (enabled && w != null) w.post(this::startPreviewIfReady);
    }

    // ---------------------------------------------------------------- Connection (main thread)

    private void connect(UsbDevice d) {
        if (!enabled || camera != null) return;
        if (d == null) {
            status = "waiting for a USB capture device";
            return;
        }
        identity = describe(d);
        if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status = "needs the camera permission (Android requires it for USB video devices)";
            Log.i(TAG, "asking for the camera permission");
            askCameraPermission.run();
            return;
        }
        status = usb.hasPermission(d) ? "opening " + identity : "asking for USB permission";
        // opens straight away (onDeviceOpen) if the permission is already granted
        monitor.requestPermission(d);
    }

    // ---------------------------------------------------------------- Camera (worker thread)

    private void open(USBMonitor.UsbControlBlock ctrl) {
        if (!enabled || camera != null) return;
        UVCCamera c = new UVCCamera(new UVCParam());
        int rc = c.open(ctrl);
        if (rc != 0) {
            Log.w(TAG, "open failed: " + rc);
            status = "could not open the capture device (error " + rc + ")";
            c.destroy();
            return;
        }
        Log.i(TAG, "opened " + identity + ", formats: " + c.getSupportedSize());
        List<Size> sizes = c.getSupportedSizeList();
        int[][] options = new int[sizes.size()][];
        for (int i = 0; i < options.length; i++) {
            Size s = sizes.get(i);
            options[i] = new int[]{s.type, s.width, s.height};
        }
        int pick = pickSize(options);
        if (pick < 0) {
            status = "the capture device offers no YUYV or MJPEG video";
            c.destroy();
            return;
        }
        Size size = sizes.get(pick);
        try {
            c.setPreviewSize(size);
        } catch (RuntimeException e) {
            Log.w(TAG, "setPreviewSize " + size + " failed", e);
            status = "the capture device refused " + size.width + "x" + size.height;
            c.destroy();
            return;
        }
        camera = c;
        String format = String.format(Locale.US, "%s %dx%d", size.type == UVCCamera.UVC_VS_FRAME_MJPEG ? "MJPEG" : "YUYV",
                size.width, size.height);
        int[] disp = displaySize(size.width, size.height);
        Log.i(TAG, "using " + format + " @ " + size.fps + " fps, shown as " + disp[0] + "x" + disp[1]);
        status = "streaming " + format;
        main.post(() -> {
            if (camera != c) return;
            display.onVideoFormat("USB capture " + format, disp[0], disp[1]);  // stops the H.264 decoder
            streaming = true;
            listener.onStreamStarted();  // shows the video window, so its surface becomes available
            worker.post(this::startPreviewIfReady);
        });
    }

    private void startPreviewIfReady() {
        UVCCamera c = camera;
        Surface s = surface;
        if (c == null || s == null || previewing || !streaming) return;
        try {
            c.setPreviewDisplay(s);
            c.startPreview();
            previewing = true;
            Log.i(TAG, "preview started");
        } catch (RuntimeException e) {
            Log.w(TAG, "startPreview failed", e);
            status = "could not start the video: " + e.getMessage();
        }
    }

    private void close(String reason) {
        UVCCamera c = camera;
        if (c == null && !streaming) return;  // already closed (destroy() also reports onDeviceClose)
        camera = null;
        boolean wasStreaming = streaming;
        streaming = false;
        previewing = false;
        if (c != null) {
            Log.i(TAG, "closing (" + reason + ")");
            try {
                c.stopPreview();
            } catch (RuntimeException ignored) {
            }
            c.destroy();
        }
        if (enabled) status = reason;
        if (wasStreaming) listener.onStreamStopped(reason);
    }

    private UsbDevice findCapture() {
        if (usb == null) return null;
        for (UsbDevice d : usb.getDeviceList().values()) if (isCapture(d)) return d;
        return null;
    }

    // ---------------------------------------------------------------- Pure helpers (unit tested)

    private static boolean isCapture(UsbDevice d) {
        if (d == null) return false;
        int[] classes = new int[d.getInterfaceCount()];
        for (int i = 0; i < classes.length; i++) classes[i] = d.getInterface(i).getInterfaceClass();
        return hasVideoInterface(classes);
    }

    /** A UVC device has at least one video class interface. */
    static boolean hasVideoInterface(int[] interfaceClasses) {
        for (int c : interfaceClasses) if (c == UsbConstants.USB_CLASS_VIDEO) return true;
        return false;
    }

    /**
     * Chooses the video mode from {type, width, height} options: the largest picture up to 1920x1080.
     * At standard definition (analog video) YUYV is preferred: it needs no JPEG decoding, which
     * keeps slow phones smooth. Above that MJPEG is preferred, because raw video would not fit USB 2.0.
     *
     * @return the index of the chosen option, or -1 if there is no YUYV or MJPEG option
     */
    static int pickSize(int[][] options) {
        int best = -1;
        long bestScore = -1;
        for (int i = 0; i < options.length; i++) {
            int type = options[i][0], w = options[i][1], h = options[i][2];
            boolean mjpeg = type == UVCCamera.UVC_VS_FRAME_MJPEG;
            boolean yuyv = type == UVCCamera.UVC_VS_FRAME_UNCOMPRESSED;
            if (!mjpeg && !yuyv) continue;
            if (w > 1920 || h > 1080) continue;
            long pixels = (long) w * h;
            boolean sd = w <= 720 && h <= 576;
            boolean preferred = sd ? yuyv : mjpeg;
            long score = pixels * 2 + (preferred ? 1 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /**
     * Display size for letterboxing. Standard definition capture (720x480 NTSC, 720x576 PAL,
     * 640x480) is a 4:3 picture even when the pixels are not square; wider modes are shown as they are.
     */
    static int[] displaySize(int w, int h) {
        if (h > 0 && w / (double) h < 1.6) return new int[]{Math.round(h * 4 / 3f), h};
        return new int[]{w, h};
    }

    private static String describe(UsbDevice d) {
        String m = d.getManufacturerName(), p = d.getProductName();
        return String.format(Locale.US, "%s %s (%04X:%04X)", m == null ? "" : m, p == null ? "" : p,
                d.getVendorId(), d.getProductId()).trim();
    }
}
