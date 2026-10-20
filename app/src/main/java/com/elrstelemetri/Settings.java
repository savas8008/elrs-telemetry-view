package com.elrstelemetri;

import android.content.SharedPreferences;

/** User settings, their defaults and persistence. */
public class Settings {

    public static final int MAP_SATELLITE = 0;
    public static final int MAP_TOPO = 1;
    public static final int MAP_STREET = 2;
    public static final int MAP_OSM = 3;
    public static final String[] MAP_NAMES = {"Satellite", "Topographic", "Street", "OpenStreetMap (online only)"};

    public int port = 14550;
    public int mapType = MAP_SATELLITE;
    public boolean autoRecord = true;     // start logging when telemetry arrives
    public int recordHz = 5;
    public boolean voice = true;          // spoken alarms
    public boolean vibrate = true;
    public int alarmRepeatSec = 15;       // repeat interval for an active alarm
    public boolean showPanels = true;
    public int offlineMaxZoom = 17;       // detail level for offline map downloads

    public static final int VIDEO_OFF = 0;
    public static final int VIDEO_PANEL = 1;       // floating, movable, resizable panel
    public static final int VIDEO_REPLACE_MAP = 2; // video fills the screen instead of the map
    public static final String[] VIDEO_MODE_NAMES = {"Hidden", "Floating panel", "Replace the map"};
    public boolean videoEnabled = true;   // RTMP receiver for DJI Fly live streaming
    public int videoPort = 1935;
    public int videoMode = VIDEO_PANEL;
    public int videoShownMode = VIDEO_PANEL;  // mode restored when the video is switched back on
    public boolean keepRunning = true;        // foreground service: keep working while another app is open
    public boolean usbGoggles = true;         // video straight from Goggles N3 over USB (no DJI Fly)
    /** Floating panel geometry as fractions of the screen; negative = default. */
    public float videoX = -1, videoY = -1, videoW = -1, videoH = -1;

    /** Panel size presets in dp (16:9). */
    public static final int[][] VIDEO_PRESETS = {{240, 135}, {320, 180}, {480, 270}};
    public static final String[] VIDEO_PRESET_NAMES = {"Small", "Medium", "Large"};

    public final AlarmSet alarms = new AlarmSet();

    // Reopen the map where it was last used
    public boolean hasLastPos;
    public double lastLat, lastLon;

    public void load(SharedPreferences p) {
        port = p.getInt("port", port);
        mapType = Math.max(0, Math.min(MAP_OSM, p.getInt("mapType2", mapType)));
        autoRecord = p.getBoolean("autoRecord", autoRecord);
        recordHz = p.getInt("recordHz", recordHz);
        voice = p.getBoolean("voice", voice);
        vibrate = p.getBoolean("vibrate", vibrate);
        alarmRepeatSec = p.getInt("alarmRepeatSec", alarmRepeatSec);
        showPanels = p.getBoolean("showPanels", showPanels);
        offlineMaxZoom = p.getInt("offlineMaxZoom", offlineMaxZoom);
        videoEnabled = p.getBoolean("videoEnabled", videoEnabled);
        videoPort = p.getInt("videoPort", videoPort);
        videoMode = p.getInt("videoMode", videoMode);
        videoShownMode = p.getInt("videoShownMode", videoShownMode);
        keepRunning = p.getBoolean("keepRunning", keepRunning);
        usbGoggles = p.getBoolean("usbGoggles", usbGoggles);
        videoX = p.getFloat("videoX", -1);
        videoY = p.getFloat("videoY", -1);
        videoW = p.getFloat("videoW", -1);
        videoH = p.getFloat("videoH", -1);
        hasLastPos = p.getBoolean("hasLastPos", false);
        lastLat = Double.longBitsToDouble(p.getLong("lastLat", 0));
        lastLon = Double.longBitsToDouble(p.getLong("lastLon", 0));
        for (AlarmSet.Alarm a : alarms.all()) {
            a.enabled = p.getBoolean("alarm." + a.id + ".on", a.defaultEnabled);
            a.threshold = Double.longBitsToDouble(p.getLong("alarm." + a.id + ".value",
                    Double.doubleToLongBits(a.defaultThreshold)));
        }
    }

    public void save(SharedPreferences p) {
        SharedPreferences.Editor e = p.edit()
                .putInt("port", port)
                .putInt("mapType2", mapType)
                .putBoolean("autoRecord", autoRecord)
                .putInt("recordHz", recordHz)
                .putBoolean("voice", voice)
                .putBoolean("vibrate", vibrate)
                .putInt("alarmRepeatSec", alarmRepeatSec)
                .putBoolean("showPanels", showPanels)
                .putInt("offlineMaxZoom", offlineMaxZoom)
                .putBoolean("videoEnabled", videoEnabled)
                .putInt("videoPort", videoPort)
                .putInt("videoMode", videoMode)
                .putInt("videoShownMode", videoShownMode)
                .putBoolean("keepRunning", keepRunning)
                .putBoolean("usbGoggles", usbGoggles)
                .putFloat("videoX", videoX)
                .putFloat("videoY", videoY)
                .putFloat("videoW", videoW)
                .putFloat("videoH", videoH)
                .putBoolean("hasLastPos", hasLastPos)
                .putLong("lastLat", Double.doubleToLongBits(lastLat))
                .putLong("lastLon", Double.doubleToLongBits(lastLon));
        for (AlarmSet.Alarm a : alarms.all()) {
            e.putBoolean("alarm." + a.id + ".on", a.enabled);
            e.putLong("alarm." + a.id + ".value", Double.doubleToLongBits(a.threshold));
        }
        e.apply();
    }
}
