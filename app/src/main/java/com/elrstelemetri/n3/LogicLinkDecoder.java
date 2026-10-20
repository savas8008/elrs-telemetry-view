/*
 * Adapted from DJI Unchained VOC (https://github.com/jtm2kfpv-git/DJI-Unchained-VOC),
 * Copyright 2026 JTM2k FPV, licensed under the Apache License, Version 2.0
 * (see LICENSE-APACHE-2.0.txt and NOTICE in the repository root).
 * Changes: moved to package com.elrstelemetri.n3, record replaced by a plain class,
 * functional interface for the packet sink.
 */
package com.elrstelemetri.n3;

import java.util.Arrays;

/** Incremental parser for the Goggles N3 mobile "logiclink" envelope: 55 CC port(LE16) length(LE16) 00 00 payload. */
public final class LogicLinkDecoder {

    public static final int HEADER_SIZE = 8;
    public static final int VIDEO_PORT = 0x574A;

    public static final class Packet {
        public final int port;
        public final byte[] payload;

        Packet(int port, byte[] payload) {
            this.port = port;
            this.payload = payload;
        }
    }

    public interface Sink {
        void accept(Packet p);
    }

    private byte[] buffer = new byte[256 * 1024];
    private int start;
    private int end;
    private long discardedBytes;

    public void accept(byte[] data, int offset, int length, Sink sink) {
        if (length < 0 || offset < 0 || offset + length > data.length) {
            throw new IndexOutOfBoundsException("Invalid input range");
        }
        ensureCapacity(length);
        System.arraycopy(data, offset, buffer, end, length);
        end += length;

        while (end - start >= HEADER_SIZE) {
            if ((buffer[start] & 0xFF) != 0x55 || (buffer[start + 1] & 0xFF) != 0xCC) {
                resynchronize();
                continue;
            }
            if (buffer[start + 6] != 0 || buffer[start + 7] != 0) {
                start++;
                discardedBytes++;
                continue;
            }
            int port = u16le(start + 2);
            int payloadLength = u16le(start + 4);
            int packetLength = HEADER_SIZE + payloadLength;
            if (end - start < packetLength) break;
            byte[] payload = Arrays.copyOfRange(buffer, start + HEADER_SIZE, start + packetLength);
            start += packetLength;
            sink.accept(new Packet(port, payload));
        }
        compactIfUseful();
    }

    public long discardedBytes() {
        return discardedBytes;
    }

    public void reset() {
        start = 0;
        end = 0;
        discardedBytes = 0;
    }

    private int u16le(int index) {
        return (buffer[index] & 0xFF) | ((buffer[index + 1] & 0xFF) << 8);
    }

    private void resynchronize() {
        int candidate = start + 1;
        while (candidate + 1 < end) {
            if ((buffer[candidate] & 0xFF) == 0x55 && (buffer[candidate + 1] & 0xFF) == 0xCC) {
                discardedBytes += candidate - start;
                start = candidate;
                return;
            }
            candidate++;
        }
        int keep = end > start && (buffer[end - 1] & 0xFF) == 0x55 ? 1 : 0;
        discardedBytes += (end - start) - keep;
        if (keep == 1) buffer[0] = buffer[end - 1];
        start = 0;
        end = keep;
    }

    private void ensureCapacity(int incoming) {
        if (end + incoming <= buffer.length) return;
        compact();
        int required = end + incoming;
        if (required > buffer.length) buffer = Arrays.copyOf(buffer, Math.max(required, buffer.length * 2));
    }

    private void compactIfUseful() {
        if (start > buffer.length / 2 || (start == end && start != 0)) compact();
    }

    private void compact() {
        if (start == 0) return;
        int remaining = end - start;
        if (remaining > 0) System.arraycopy(buffer, start, buffer, 0, remaining);
        start = 0;
        end = remaining;
    }
}
