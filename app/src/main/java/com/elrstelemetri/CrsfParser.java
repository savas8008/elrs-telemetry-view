package com.elrstelemetri;

/**
 * UDP datagramlarından CRSF telemetri çerçevelerini ayıklar.
 *
 * CRSF çerçevesi: [adres][uzunluk][tip][payload...][crc8]
 *   uzunluk = tip + payload + crc bayt sayısı
 *   crc8    = DVB-S2 (polinom 0xD5), tip + payload üzerinden
 *
 * Backpack'in UDP paketinin içinde CRSF'i tam olarak nasıl paketlediği
 * doğrulanmadığı için ayrıştırıcı paketi bayt bayt tarar ve yalnızca
 * CRC'si tutan, uzunluğu tipine uyan çerçeveleri kabul eder.
 * Çerçeveler iki datagrama bölünürse diye artık baytlar saklanır.
 */
public class CrsfParser {

    public static final int GPS = 0x02;
    public static final int VARIO = 0x07;
    public static final int BATTERY = 0x08;
    public static final int BARO_ALT = 0x09;
    public static final int LINK_STATS = 0x14;
    public static final int ATTITUDE = 0x1E;
    public static final int FLIGHT_MODE = 0x21;

    private static final int MAX_FRAME = 64;

    private final TelemetryState state;
    private final byte[] buf = new byte[4096];
    private int bufLen = 0;

    public CrsfParser(TelemetryState state) {
        this.state = state;
    }

    public static int crc8(byte[] data, int off, int len) {
        int crc = 0;
        for (int i = off; i < off + len; i++) {
            crc ^= data[i] & 0xFF;
            for (int b = 0; b < 8; b++) {
                crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0xD5) & 0xFF : (crc << 1) & 0xFF;
            }
        }
        return crc;
    }

    /** Tipine göre payload uzunluğu makul mü? Rastgele baytların CRC'yi tutturmasına karşı ikinci filtre. */
    static boolean plausible(int type, int payloadLen) {
        switch (type) {
            case GPS: return payloadLen == 15;
            case VARIO: return payloadLen == 2;
            case BATTERY: return payloadLen == 8;
            case BARO_ALT: return payloadLen >= 2 && payloadLen <= 4;
            case LINK_STATS: return payloadLen == 10;
            case ATTITUDE: return payloadLen == 6;
            case FLIGHT_MODE: return payloadLen >= 1 && payloadLen <= 20;
            default: return false;
        }
    }

    /** Bir datagramı işler; bulunan geçerli çerçeve sayısını döndürür. */
    public int feed(byte[] data, int len) {
        if (len > buf.length) len = buf.length;
        if (bufLen + len > buf.length) bufLen = 0; // senkron kaybı: baştan başla
        System.arraycopy(data, 0, buf, bufLen, len);
        bufLen += len;

        int found = 0;
        int i = 0;
        int keepFrom = bufLen;
        while (i + 4 <= bufLen) {
            int frameLen = buf[i + 1] & 0xFF;          // tip + payload + crc
            int type = buf[i + 2] & 0xFF;
            int payloadLen = frameLen - 2;
            if (frameLen < 3 || frameLen + 2 > MAX_FRAME || !plausible(type, payloadLen)) {
                i++;
                continue;
            }
            int end = i + 2 + frameLen;                // crc'den sonraki indeks
            if (end > bufLen) {                        // eksik çerçeve: sonraki datagramı bekle
                keepFrom = i;
                break;
            }
            if (crc8(buf, i + 2, frameLen - 1) == (buf[end - 1] & 0xFF)) {
                decode(type, buf, i + 3, payloadLen);
                found++;
                i = end;
            } else {
                i++;
            }
        }
        if (keepFrom == bufLen && i < bufLen) keepFrom = i;  // son 0-3 bayt
        int rest = bufLen - keepFrom;
        System.arraycopy(buf, keepFrom, buf, 0, rest);
        bufLen = rest;
        return found;
    }

    private static int u8(byte[] b, int o) { return b[o] & 0xFF; }
    private static int u16(byte[] b, int o) { return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF); }
    private static int s16(byte[] b, int o) { return (short) u16(b, o); }
    private static int u24(byte[] b, int o) { return ((b[o] & 0xFF) << 16) | ((b[o + 1] & 0xFF) << 8) | (b[o + 2] & 0xFF); }
    private static int s32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    // CRSF uplink TX güç kodu → mW
    private static final int[] TX_POWER_MW = {0, 10, 25, 100, 500, 1000, 2000, 250, 50};

    private void decode(int type, byte[] b, int p, int n) {
        long now = System.currentTimeMillis();
        synchronized (state) {
            switch (type) {
                case GPS:
                    state.lat = s32(b, p) / 1e7;
                    state.lon = s32(b, p + 4) / 1e7;
                    state.groundSpeedKmh = u16(b, p + 8) / 10.0;
                    state.headingDeg = u16(b, p + 10) / 100.0;
                    state.gpsAltM = u16(b, p + 12) - 1000;
                    state.sats = u8(b, p + 14);
                    state.gpsTime = now;
                    state.updateHome();
                    break;
                case VARIO:
                    state.varioMs = s16(b, p) / 100.0;
                    state.varioTime = now;
                    break;
                case BATTERY:
                    state.voltage = u16(b, p) / 10.0;
                    state.current = u16(b, p + 2) / 10.0;
                    state.mah = u24(b, p + 4);
                    state.batteryPct = u8(b, p + 7);
                    state.batteryTime = now;
                    break;
                case BARO_ALT: {
                    int v = u16(b, p);
                    // MSB 0: desimetre + 10000 ofset; MSB 1: metre
                    state.baroAltM = (v & 0x8000) == 0 ? (v - 10000) / 10.0 : (v & 0x7FFF);
                    state.baroTime = now;
                    break;
                }
                case LINK_STATS: {
                    state.rssi1 = -u8(b, p);
                    state.rssi2 = -u8(b, p + 1);
                    state.lq = u8(b, p + 2);
                    state.snr = b[p + 3];
                    state.antenna = u8(b, p + 4);
                    state.rfMode = u8(b, p + 5);
                    int pw = u8(b, p + 6);
                    state.txPowerMw = pw < TX_POWER_MW.length ? TX_POWER_MW[pw] : -1;
                    state.downRssi = -u8(b, p + 7);
                    state.downLq = u8(b, p + 8);
                    state.downSnr = b[p + 9];
                    state.linkTime = now;
                    break;
                }
                case ATTITUDE:
                    // rad * 10000 → derece
                    state.pitchDeg = Math.toDegrees(s16(b, p) / 10000.0);
                    state.rollDeg = Math.toDegrees(s16(b, p + 2) / 10000.0);
                    state.yawDeg = Math.toDegrees(s16(b, p + 4) / 10000.0);
                    state.attitudeTime = now;
                    break;
                case FLIGHT_MODE: {
                    int end = p;
                    while (end < p + n && b[end] != 0) end++;
                    state.flightMode = new String(b, p, end - p, java.nio.charset.Charset.forName("US-ASCII"));
                    state.modeTime = now;
                    break;
                }
            }
            state.frameCount++;
        }
    }
}
