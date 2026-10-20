/*
 * USB accessory handling based on DJI Unchained VOC's UsbAccessoryController
 * (https://github.com/jtm2kfpv-git/DJI-Unchained-VOC), Copyright 2026 JTM2k FPV,
 * licensed under the Apache License, Version 2.0 (see LICENSE-APACHE-2.0.txt and NOTICE).
 * Changes: rewritten and simplified for this app (automatic connect, fixed retry interval,
 * built-in stall watchdog, access units delivered to a listener instead of a decoder).
 */
package com.elrstelemetri.n3;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * Live video straight from DJI Goggles N3 over USB, without DJI Fly. The goggles appear as an
 * Android USB accessory; after the subscription packets they send H.264 on logiclink port 0x574A.
 */
public final class N3UsbLink {

    public interface Listener {
        /** Video started (first data on a new connection). */
        void onStreamStarted();

        /** One H.264 access unit (Annex-B) with the latest SPS/PPS. */
        void onAccessUnit(H264AnnexBAssembler.AccessUnit au);

        /** Connection lost or closed. */
        void onStreamStopped(String reason);
    }

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
    private ParcelFileDescriptor descriptor;
    private volatile N3Session session;
    private long sessionStart;
    private volatile boolean streaming;
    private boolean keepaliveResent;
    private volatile String status = "off";
    private String identity = "";

    public N3UsbLink(Context c, Listener listener) {
        this.ctx = c.getApplicationContext();
        this.listener = listener;
        usb = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        permissionAction = ctx.getPackageName() + ".N3_USB_PERMISSION";
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            UsbAccessory a = accessoryExtra(intent);
            if (permissionAction.equals(action)) {
                permissionPending = false;
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && a != null) open(a);
                else status = "USB permission denied — unplug and reconnect the goggles to try again";
            } else if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(action)) {
                connect(a);
            } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
                closeTransport("goggles unplugged");
                status = "goggles unplugged";
            }
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!enabled) return;
            watchdog();
            main.postDelayed(this, 1000);
        }
    };

    /** Starts listening; {@code launchIntent} may carry the accessory if the app was opened by plugging in. */
    public void start(Intent launchIntent) {
        if (enabled) return;
        enabled = true;
        IntentFilter f = new IntentFilter(permissionAction);
        f.addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else ctx.registerReceiver(receiver, f);
        registered = true;
        UsbAccessory launched = accessoryExtra(launchIntent);
        connect(launched != null ? launched : firstDjiAccessory());
        main.post(tick);
    }

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

    /** Handles an ACTION_USB_ACCESSORY_ATTACHED intent delivered to the activity. */
    public void handleIntent(Intent intent) {
        UsbAccessory a = accessoryExtra(intent);
        if (a != null && enabled) connect(a);
    }

    /** Closes and reopens the USB connection. */
    public void reconnect() {
        closeTransport("reconnecting");
        connect(firstDjiAccessory());
    }

    public String status() {
        return status;
    }

    public String identity() {
        return identity;
    }

    public boolean isStreaming() {
        return streaming;
    }

    // ---------------------------------------------------------------- Connection

    private void connect(UsbAccessory a) {
        if (!enabled || session != null || permissionPending) return;
        if (a == null) {
            status = "waiting for Goggles N3 on USB";
            return;
        }
        identity = "manufacturer=" + a.getManufacturer() + " model=" + a.getModel() + " version=" + a.getVersion();
        if (!isDji(a)) {
            status = "USB accessory is not DJI: " + identity;
            return;
        }
        if (usb.hasPermission(a)) {
            open(a);
            return;
        }
        Intent i = new Intent(permissionAction).setPackage(ctx.getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
        permissionPending = true;
        status = "asking for USB permission";
        usb.requestPermission(a, pi);
    }

    private void open(UsbAccessory a) {
        closeTransport(null);
        descriptor = usb.openAccessory(a);
        if (descriptor == null) {
            status = "Android could not open the goggles' USB accessory";
            scheduleRetry();
            return;
        }
        assembler.reset();
        FileInputStream in = new FileInputStream(descriptor.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(descriptor.getFileDescriptor());
        final N3Session[] self = new N3Session[1];
        self[0] = new N3Session(in, out, new N3Session.Listener() {
            @Override
            public void onVideo(byte[] payload) {
                if (self[0] != session) return;
                assembler.accept(payload, listener::onAccessUnit);
                if (!streaming) {
                    streaming = true;
                    main.post(listener::onStreamStarted);
                }
            }

            @Override
            public void onFailure(String message) {
                main.post(() -> {
                    if (self[0] != session) return;
                    closeTransport(message);
                    status = message;
                    scheduleRetry();
                });
            }
        });
        session = self[0];
        sessionStart = System.currentTimeMillis();
        keepaliveResent = false;
        status = "connected, waiting for video (goggles must be linked to the aircraft)";
        session.start();
    }

    private void closeTransport(String reason) {
        boolean wasStreaming = streaming;
        streaming = false;
        N3Session s = session;
        session = null;
        if (s != null) s.close();
        if (descriptor != null) {
            try {
                descriptor.close();
            } catch (IOException ignored) {
            }
            descriptor = null;
        }
        if (wasStreaming && reason != null) listener.onStreamStopped(reason);
    }

    private void scheduleRetry() {
        if (!enabled) return;
        main.postDelayed(() -> {
            if (enabled && session == null) connect(firstDjiAccessory());
        }, RETRY_MS);
    }

    /** Recovers a stalled stream: resend the keepalive first, then reopen the accessory. */
    private void watchdog() {
        N3Session s = session;
        if (s == null) return;
        long now = System.currentTimeMillis();
        long last = Math.max(s.lastVideoAt.get(), sessionStart);
        long age = now - last;
        if (streaming) {
            status = String.format(Locale.US, "streaming (%.1f MB received)", s.videoBytes.get() / 1e6);
        }
        if (age > STALL_REOPEN_MS) {
            status = "no video for " + age / 1000 + " s — reopening USB";
            closeTransport("no video from the goggles");
            connect(firstDjiAccessory());
        } else if (age > STALL_KEEPALIVE_MS && !keepaliveResent) {
            keepaliveResent = true;
            s.requestKeepalive();
        } else if (age <= STALL_KEEPALIVE_MS) {
            keepaliveResent = false;
        }
    }

    private UsbAccessory firstDjiAccessory() {
        UsbAccessory[] list = usb.getAccessoryList();
        if (list == null) return null;
        for (UsbAccessory a : list) if (isDji(a)) return a;
        return list.length > 0 ? list[0] : null;
    }

    private static boolean isDji(UsbAccessory a) {
        String m = a.getManufacturer(), model = a.getModel();
        return (m != null && m.toLowerCase(Locale.ROOT).contains("dji"))
                || (model != null && model.toLowerCase(Locale.ROOT).contains("logiclink"));
    }

    @SuppressWarnings("deprecation")
    private static UsbAccessory accessoryExtra(Intent intent) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory.class);
        return intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
    }
}
