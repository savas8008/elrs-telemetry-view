/*
 * Adapted from DJI Unchained VOC (https://github.com/jtm2kfpv-git/DJI-Unchained-VOC),
 * Copyright 2026 JTM2k FPV, licensed under the Apache License, Version 2.0
 * (see LICENSE-APACHE-2.0.txt and NOTICE in the repository root).
 * Changes: moved to package com.elrstelemetri.n3, record replaced by a plain class,
 * SPS/PPS normalised to 4-byte start codes for MediaCodec csd buffers.
 */
package com.elrstelemetri.n3;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Splits a fragmented Annex-B byte stream into access units. Uses AUD NALs when present
 * and first_mb_in_slice when they are absent.
 */
public final class H264AnnexBAssembler {

    public static final class AccessUnit {
        public final byte[] data;
        public final boolean keyFrame;
        /** Latest SPS/PPS seen so far, with a 4-byte start code (null until received). */
        public final byte[] sps, pps;

        AccessUnit(byte[] data, boolean keyFrame, byte[] sps, byte[] pps) {
            this.data = data;
            this.keyFrame = keyFrame;
            this.sps = sps;
            this.pps = pps;
        }
    }

    public interface Sink {
        void accept(AccessUnit au);
    }

    private byte[] pending = new byte[0];
    private final ByteArrayOutputStream accessUnit = new ByteArrayOutputStream(256 * 1024);
    private boolean hasVcl;
    private boolean keyFrame;
    private byte[] sps;
    private byte[] pps;

    public void accept(byte[] bytes, Sink sink) {
        byte[] joined = new byte[pending.length + bytes.length];
        System.arraycopy(pending, 0, joined, 0, pending.length);
        System.arraycopy(bytes, 0, joined, pending.length, bytes.length);

        int first = findStartCode(joined, 0);
        if (first < 0) {
            pending = joined.length > 4 ? Arrays.copyOfRange(joined, joined.length - 4, joined.length) : joined;
            return;
        }
        int current = first;
        while (true) {
            int next = findStartCode(joined, current + startCodeLength(joined, current));
            if (next < 0) {
                pending = Arrays.copyOfRange(joined, current, joined.length);
                return;
            }
            consumeNal(Arrays.copyOfRange(joined, current, next), sink);
            current = next;
        }
    }

    public void reset() {
        pending = new byte[0];
        accessUnit.reset();
        hasVcl = false;
        keyFrame = false;
        sps = null;
        pps = null;
    }

    private void consumeNal(byte[] nal, Sink sink) {
        int prefix = startCodeLength(nal, 0);
        if (prefix == 0 || prefix >= nal.length) return;
        int type = nal[prefix] & 0x1F;
        if (type == 9) {
            emitIfComplete(sink);
        } else if (type == 7 || type == 8) {
            if (hasVcl) emitIfComplete(sink);
            byte[] withStart = withFourByteStart(nal, prefix);
            if (type == 7) sps = withStart;
            else pps = withStart;
        } else if (type == 1 || type == 5) {
            int firstMb = firstMbInSlice(nal, prefix + 1);
            if (hasVcl && firstMb == 0) emitIfComplete(sink);
            hasVcl = true;
            keyFrame |= type == 5;
        }
        accessUnit.write(nal, 0, nal.length);
    }

    private static byte[] withFourByteStart(byte[] nal, int prefix) {
        byte[] r = new byte[4 + nal.length - prefix];
        r[3] = 1;
        System.arraycopy(nal, prefix, r, 4, nal.length - prefix);
        return r;
    }

    private void emitIfComplete(Sink sink) {
        if (hasVcl && accessUnit.size() > 0) {
            sink.accept(new AccessUnit(accessUnit.toByteArray(), keyFrame,
                    sps == null ? null : sps.clone(), pps == null ? null : pps.clone()));
        }
        accessUnit.reset();
        hasVcl = false;
        keyFrame = false;
    }

    private static int firstMbInSlice(byte[] nal, int offset) {
        if (offset >= nal.length) return -1;
        byte[] rbsp = new byte[nal.length - offset];
        int out = 0;
        int zeros = 0;
        for (int i = offset; i < nal.length; i++) {
            int value = nal[i] & 0xFF;
            if (zeros >= 2 && value == 0x03) {
                zeros = 0;
                continue;
            }
            rbsp[out++] = nal[i];
            zeros = value == 0 ? zeros + 1 : 0;
        }
        return readUnsignedExpGolomb(rbsp, out);
    }

    private static int readUnsignedExpGolomb(byte[] data, int length) {
        int bit = 0;
        int zeros = 0;
        while (bit < length * 8 && !bitAt(data, bit)) {
            zeros++;
            bit++;
            if (zeros > 30) return -1;
        }
        if (bit >= length * 8) return -1;
        bit++;
        int value = 1;
        for (int i = 0; i < zeros; i++, bit++) {
            if (bit >= length * 8) return -1;
            value = (value << 1) | (bitAt(data, bit) ? 1 : 0);
        }
        return value - 1;
    }

    private static boolean bitAt(byte[] data, int bit) {
        return ((data[bit / 8] >> (7 - (bit % 8))) & 1) != 0;
    }

    private static int findStartCode(byte[] data, int from) {
        for (int i = Math.max(0, from); i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) return i;
                if (i + 3 < data.length && data[i + 2] == 0 && data[i + 3] == 1) return i;
            }
        }
        return -1;
    }

    private static int startCodeLength(byte[] data, int index) {
        if (index + 2 < data.length && data[index] == 0 && data[index + 1] == 0 && data[index + 2] == 1) return 3;
        if (index + 3 < data.length && data[index] == 0 && data[index + 1] == 0 && data[index + 2] == 0
                && data[index + 3] == 1) return 4;
        return 0;
    }
}
