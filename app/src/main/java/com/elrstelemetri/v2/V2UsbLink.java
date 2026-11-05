/*
 * Goggles V2 USB protocol (USB IDs, interface 3, the "RMVT" start packet and re-sending it when
 * the stream stalls) as found by the DigiView-Android project
 * (https://github.com/fpvout/DigiView-Android, MIT License) and fpv.wtf. Written for this app;
 * no DigiView code is copied.
 */
package com.elrstelemetri.v2;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.elrstelemetri.goggles.GogglesLink;
import com.elrstelemetri.n3.H264AnnexBAssembler;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live video straight from DJI FPV Goggles V2 (and V1) over USB. The goggles are a USB device
 * (the phone is the host, so an OTG cable or adapter may be needed). After the 4-byte "RMVT"
 * packet on the bulk OUT endpoint of interface 3 they send the air unit's raw H.264 (Annex-B)
 * on the bulk IN endpoint. The goggles' OSD is drawn on their screen only and is not part of it.
 */
public final class V2UsbLink implements GogglesLink {

    public static final int VENDOR_ID = 0x2CA3;   // DJI (11427)
    public static final int PRODUCT_ID = 0x001F;  // FPV Goggles V1/V2 (31)
    static final int VIDEO_INTERFACE = 3;
    static final byte[] MAGIC = "RMVT".getBytes(StandardCharsets.US_ASCII);

    /** logcat tag: adb logcat -s V2Usb */
    private static final String TAG = "V2Usb";
    private static final int READ_TIMEOUT_MS = 100;
    /**
     * Short: the same thread reads the video, so a write the goggles do not take (they may ignore the
     * start packet while they already stream) must not hold up the reads for long.
     */
    private static final int WRITE_TIMEOUT_MS = 300;
    /** Empty reads in a row (~1 s) before the start packet is sent again, as DigiView does. */
    private static final int EMPTY_READS_BEFORE_MAGIC = 10;
    private static final long RETRY_MS = 2000;
    private static final long STALL_KEEPALIVE_MS = 3000;
    private static final long STALL_REOPEN_MS = 8000;

    private final Context ctx;
    private final UsbManager usb;
    private final Listener listener;
    private final String permissionAction;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final H264AnnexBAssembler assembler = new H264AnnexBAssembler();

    private boolean enabled;
    private boolean registered;
    private boolean permissionPending;
    private long permissionAskedAt;
    /** Ask again if Android has not answered a permission request after this long. */
    private static final long PERMISSION_REASK_MS = 15000;
    private volatile Session session;
    private long sessionStart;
    private volatile boolean streaming;
    private boolean keepaliveResent;
    private volatile String status = "off";
    private String identity = "";

    public V2UsbLink(Context c, Listener listener) {
        this.ctx = c.getApplicationContext();
        this.listener = listener;
        usb = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        permissionAction = ctx.getPackageName() + ".V2_USB_PERMISSION";
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            UsbDevice d = deviceExtra(intent);
            if (permissionAction.equals(action)) {
                permissionPending = false;
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && d != null) open(d);
                // Android also answers "not granted" to a pending request when the goggles are unplugged
                else if (findGoggles() == null) status = "goggles unplugged";
                else if (session == null) status = "USB permission denied — unplug and reconnect the goggles to try again";
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                if (isGoggles(d)) connect(d);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                Session s = session;
                if (isGoggles(d) && (s == null || s.device.getDeviceName().equals(d.getDeviceName()))) {
                    Log.i(TAG, "goggles detached");
                    closeTransport("goggles unplugged");
                    status = "goggles unplugged";
                }
            }
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!enabled) return;
            if (session == null && permissionPending) connect(findGoggles());
            watchdog();
            logStatus();
            main.postDelayed(this, 1000);
        }
    };

    private String loggedStatus = "";

    /** Logs status changes (not the MB counter while streaming). */
    private void logStatus() {
        String s = status;
        String key = s.startsWith("streaming") ? "streaming" : s;
        if (!key.equals(loggedStatus)) {
            loggedStatus = key;
            Log.i(TAG, "status: " + s);
        }
    }

    @Override
    public void start(Intent launchIntent) {
        if (enabled) return;
        enabled = true;
        IntentFilter f = new IntentFilter(permissionAction);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else ctx.registerReceiver(receiver, f);
        registered = true;
        UsbDevice launched = deviceExtra(launchIntent);
        connect(isGoggles(launched) ? launched : findGoggles());
        main.post(tick);
    }

    @Override
    public void stop() {
        enabled = false;
        main.removeCallbacks(tick);
        closeTransport("stopped");
        if (registered) {
            try {
                ctx.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
            }
            registered = false;
        }
        status = "off";
    }

    @Override
    public void handleIntent(Intent intent) {
        UsbDevice d = deviceExtra(intent);
        if (enabled && isGoggles(d)) connect(d);
    }

    @Override
    public void reconnect() {
        Log.i(TAG, "reconnect requested by the user");
        closeTransport("reconnecting");
        connect(findGoggles());
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
        return findGoggles() != null;
    }

    /** The USB stream is the air unit's video; the goggles draw their OSD (stock or MSP-OSD) on their screen only. */
    @Override
    public boolean videoHasOsd() {
        return false;
    }

    @Override
    public String name() {
        return "Goggles V2";
    }

    // ---------------------------------------------------------------- Connection

    private void connect(UsbDevice d) {
        if (!enabled || session != null) return;
        if (d == null) {
            permissionPending = false;
            status = "waiting for Goggles V2 on USB";
            return;
        }
        identity = String.format(Locale.US, "%s %s (%04X:%04X)", nn(d.getManufacturerName()), nn(d.getProductName()),
                d.getVendorId(), d.getProductId()).trim();
        // Check the permission itself, not only the answer to our request: when the user picks this
        // app in Android's "open with" prompt for the goggles, Android grants the permission and the
        // answer to a request made at the same time may never arrive.
        if (usb.hasPermission(d)) {
            permissionPending = false;
            open(d);
            return;
        }
        if (permissionPending && System.currentTimeMillis() - permissionAskedAt < PERMISSION_REASK_MS) return;
        permissionAskedAt = System.currentTimeMillis();
        Intent i = new Intent(permissionAction).setPackage(ctx.getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
        permissionPending = true;
        status = "asking for USB permission";
        usb.requestPermission(d, pi);
    }

    private void open(UsbDevice d) {
        closeTransport(null);
        if (d.getInterfaceCount() <= VIDEO_INTERFACE) {
            status = "the goggles have no video interface (" + d.getInterfaceCount() + " USB interfaces)";
            scheduleRetry();
            return;
        }
        UsbInterface intf = d.getInterface(VIDEO_INTERFACE);
        int[] dirs = new int[intf.getEndpointCount()];
        int[] types = new int[intf.getEndpointCount()];
        for (int i = 0; i < dirs.length; i++) {
            dirs[i] = intf.getEndpoint(i).getDirection();
            types[i] = intf.getEndpoint(i).getType();
        }
        int[] ep = pickBulkEndpoints(dirs, types);
        if (ep == null) {
            status = "the goggles' video interface has no bulk endpoints";
            scheduleRetry();
            return;
        }
        UsbDeviceConnection conn = usb.openDevice(d);
        if (conn == null) {
            status = "Android could not open the goggles' USB device";
            scheduleRetry();
            return;
        }
        if (!conn.claimInterface(intf, true)) {
            conn.close();
            status = "could not claim the goggles' video interface";
            scheduleRetry();
            return;
        }
        assembler.reset();
        Session s = new Session(d, conn, intf, intf.getEndpoint(ep[0]), intf.getEndpoint(ep[1]));
        Log.i(TAG, "opened " + identity + ": OUT ep 0x" + Integer.toHexString(s.out.getAddress())
                + " (" + s.out.getMaxPacketSize() + " B), IN ep 0x" + Integer.toHexString(s.in.getAddress())
                + " (" + s.in.getMaxPacketSize() + " B)");
        session = s;
        sessionStart = System.currentTimeMillis();
        keepaliveResent = false;
        status = "connected, waiting for video (goggles must be linked to the aircraft)";
        s.start();
    }

    private void onVideo(Session s, byte[] data) {
        if (s != session) return;
        assembler.accept(data, listener::onAccessUnit);
        if (!streaming) {
            streaming = true;
            Log.i(TAG, "video started (" + (System.currentTimeMillis() - sessionStart) + " ms after open)");
            main.post(listener::onStreamStarted);
        }
    }

    private void onFailure(Session s, String message) {
        main.post(() -> {
            if (s != session) return;
            Log.w(TAG, "session failed: " + message);
            closeTransport(message);
            status = message;
            scheduleRetry();
        });
    }

    private void closeTransport(String reason) {
        boolean wasStreaming = streaming;
        streaming = false;
        Session s = session;
        session = null;
        if (s != null) {
            Log.i(TAG, "closing session (" + reason + "), " + s.bytes.get() + " bytes received");
            s.close();
        }
        if (wasStreaming && reason != null) listener.onStreamStopped(reason);
    }

    private void scheduleRetry() {
        if (!enabled) return;
        main.postDelayed(() -> {
            if (enabled && session == null) connect(findGoggles());
        }, RETRY_MS);
    }

    /** Recovers a stalled stream: send the start packet again first, then reopen the device. */
    private void watchdog() {
        Session s = session;
        if (s == null) return;
        long now = System.currentTimeMillis();
        long age = now - Math.max(s.lastDataAt.get(), sessionStart);
        if (streaming) {
            status = String.format(Locale.US, "streaming (%.1f MB received)", s.bytes.get() / 1e6);
        }
        if (age > STALL_REOPEN_MS) {
            Log.w(TAG, "no video for " + age + " ms: reopening USB");
            status = "no video for " + age / 1000 + " s — reopening USB";
            closeTransport("no video from the goggles");
            connect(findGoggles());
        } else if (age > STALL_KEEPALIVE_MS && !keepaliveResent) {
            Log.w(TAG, "no video for " + age + " ms: re-sending the start packet");
            keepaliveResent = true;
            s.resendMagic = true;
        } else if (age <= STALL_KEEPALIVE_MS) {
            keepaliveResent = false;
        }
    }

    private UsbDevice findGoggles() {
        if (usb == null) return null;
        for (UsbDevice d : usb.getDeviceList().values()) if (isGoggles(d)) return d;
        return null;
    }

    // ---------------------------------------------------------------- Pure helpers (unit tested)

    static boolean isGoggles(int vendorId, int productId) {
        return vendorId == VENDOR_ID && productId == PRODUCT_ID;
    }

    private static boolean isGoggles(UsbDevice d) {
        return d != null && isGoggles(d.getVendorId(), d.getProductId());
    }

    /**
     * Picks the bulk OUT and bulk IN endpoints of the video interface by direction.
     * Falls back to DigiView's fixed order (0 = OUT, 1 = IN) when the descriptors do not say.
     *
     * @return {outIndex, inIndex}, or null if the interface has fewer than two endpoints
     */
    static int[] pickBulkEndpoints(int[] directions, int[] types) {
        int out = -1, in = -1;
        for (int i = 0; i < directions.length; i++) {
            if (types[i] != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (directions[i] == UsbConstants.USB_DIR_OUT && out < 0) out = i;
            else if (directions[i] == UsbConstants.USB_DIR_IN && in < 0) in = i;
        }
        if (out >= 0 && in >= 0) return new int[]{out, in};
        return directions.length >= 2 ? new int[]{0, 1} : null;
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }

    @SuppressWarnings("deprecation")
    private static UsbDevice deviceExtra(Intent intent) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    // ---------------------------------------------------------------- Session

    /** One open USB connection: a read thread that sends "RMVT" and passes the H.264 bytes on. */
    private final class Session {
        final UsbDevice device;
        private final UsbDeviceConnection conn;
        private final UsbInterface intf;
        final UsbEndpoint out, in;
        private final Thread reader;
        private volatile boolean closed;
        volatile boolean resendMagic;
        final AtomicLong bytes = new AtomicLong();
        final AtomicLong lastDataAt = new AtomicLong();

        Session(UsbDevice device, UsbDeviceConnection conn, UsbInterface intf, UsbEndpoint out, UsbEndpoint in) {
            this.device = device;
            this.conn = conn;
            this.intf = intf;
            this.out = out;
            this.in = in;
            reader = new Thread(this::readLoop, "v2-usb-rx");
            reader.setDaemon(true);
        }

        void start() {
            reader.start();
        }

        private boolean sendMagic(String why) {
            long t0 = System.nanoTime();
            int rc = conn.bulkTransfer(out, MAGIC, MAGIC.length, WRITE_TIMEOUT_MS);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (rc == MAGIC.length) Log.d(TAG, "start packet sent (" + why + ", " + ms + " ms)");
            else Log.w(TAG, "start packet NOT taken (" + why + "): rc=" + rc + " after " + ms + " ms");
            return rc == MAGIC.length;
        }

        /**
         * A failed start-packet write does not end the session: the goggles may not take it while they
         * stream, and a stream that really stopped is reopened by the watchdog (no data for 8 s).
         */
        private void readLoop() {
            byte[] buf = new byte[128 * 1024];
            int empty = 0;
            boolean sendNow = true, first = true, failingFast = false;
            try {
                while (!closed) {
                    if (sendNow || resendMagic) {
                        sendMagic(first ? "open" : resendMagic ? "watchdog" : "1 s without data");
                        first = false;
                        sendNow = false;
                        resendMagic = false;
                    }
                    long t0 = System.nanoTime();
                    int n = conn.bulkTransfer(in, buf, buf.length, READ_TIMEOUT_MS);
                    if (closed) return;
                    if (n > 0) {
                        if (empty >= 3 || failingFast) Log.d(TAG, "data again after " + empty + " empty reads");
                        failingFast = false;
                        empty = 0;
                        bytes.addAndGet(n);
                        lastDataAt.set(System.currentTimeMillis());
                        onVideo(this, Arrays.copyOf(buf, n));
                        continue;
                    }
                    // a read that fails at once (USB error, not a timeout) would spin: wait like a timeout
                    long waitedMs = (System.nanoTime() - t0) / 1_000_000;
                    if (waitedMs < READ_TIMEOUT_MS / 2) {
                        if (!failingFast) Log.w(TAG, "read failed at once: rc=" + n + " (USB error, not a timeout)");
                        failingFast = true;
                        Thread.sleep(READ_TIMEOUT_MS - waitedMs);
                    }
                    if (++empty >= EMPTY_READS_BEFORE_MAGIC) {
                        empty = 0;
                        sendNow = true;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                Log.e(TAG, "read loop crashed", e);
                if (!closed) onFailure(this, "USB read failed: " + e.getMessage());
            }
        }

        void close() {
            if (closed) return;
            closed = true;
            try {
                reader.join(READ_TIMEOUT_MS * 3);  // let a pending read time out before closing
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                conn.releaseInterface(intf);
            } catch (RuntimeException ignored) {
            }
            conn.close();
        }
    }
}
