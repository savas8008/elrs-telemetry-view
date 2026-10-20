/*
 * Adapted from DJI Unchained VOC (https://github.com/jtm2kfpv-git/DJI-Unchained-VOC),
 * Copyright 2026 JTM2k FPV, licensed under the Apache License, Version 2.0
 * (see LICENSE-APACHE-2.0.txt and NOTICE in the repository root).
 * Changes: moved to package com.elrstelemetri.n3, statistics simplified, last-video timestamp
 * exposed for the stall watchdog.
 */
package com.elrstelemetri.n3;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** One USB accessory session: reads logiclink packets and sends the video subscription/keepalive every 5 s. */
final class N3Session implements Closeable {

    interface Listener {
        void onVideo(byte[] payload);

        void onFailure(String message);
    }

    private final InputStream input;
    private final OutputStream output;
    private final Listener listener;
    private final LogicLinkDecoder decoder = new LogicLinkDecoder();
    private final ScheduledExecutorService workers = Executors.newScheduledThreadPool(2);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object writeLock = new Object();
    final AtomicLong videoBytes = new AtomicLong();
    final AtomicLong lastVideoAt = new AtomicLong();

    N3Session(InputStream input, OutputStream output, Listener listener) {
        this.input = input;
        this.output = output;
        this.listener = listener;
    }

    void start() {
        workers.execute(this::readLoop);
        workers.scheduleWithFixedDelay(this::sendKeepaliveSafely, 0, 5, TimeUnit.SECONDS);
    }

    private void readLoop() {
        byte[] readBuffer = new byte[64 * 1024];
        try {
            while (!closed.get()) {
                int count = input.read(readBuffer);
                if (count < 0) throw new IOException("USB accessory reached end of stream");
                if (count == 0) continue;
                decoder.accept(readBuffer, 0, count, p -> {
                    if (p.port == LogicLinkDecoder.VIDEO_PORT) {
                        videoBytes.addAndGet(p.payload.length);
                        lastVideoAt.set(System.currentTimeMillis());
                        listener.onVideo(p.payload);
                    }
                });
            }
        } catch (IOException | RuntimeException error) {
            if (!closed.get()) {
                listener.onFailure("USB read failed: " + error.getMessage());
                close();
            }
        }
    }

    private void sendKeepaliveSafely() {
        if (closed.get()) return;
        try {
            synchronized (writeLock) {
                for (byte[] packet : N3ControlPackets.copies()) output.write(packet);
                output.flush();
            }
        } catch (IOException error) {
            if (!closed.get()) {
                listener.onFailure("N3 video-start/keepalive write failed: " + error.getMessage());
                close();
            }
        }
    }

    void requestKeepalive() {
        if (closed.get()) return;
        try {
            workers.execute(this::sendKeepaliveSafely);
        } catch (RejectedExecutionException ignored) {
            // closed between the check and submission
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        workers.shutdownNow();
        try {
            input.close();
        } catch (IOException ignored) {
        }
        try {
            output.close();
        } catch (IOException ignored) {
        }
    }
}
