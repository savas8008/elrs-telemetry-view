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
    public static final int VIDEO_ON = 1;      // split screen next to the map while the goggles stream
    public int videoMode = VIDEO_OFF;     // starts closed; opens by itself when the goggles connect (see MainActivity)
    /** Video share of the screen width, set by dragging the divider (see SplitLayout). */
    public float videoFraction = SplitLayout.DEFAULT;
    public boolean keepRunning = true;        // foreground service: keep working while another app is open
    public boolean checkUpdates = true;       // look for a new GitHub release when internet is available

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
        videoMode = p.getInt("videoMode", videoMode) != VIDEO_OFF ? VIDEO_ON : VIDEO_OFF;
        videoFraction = SplitLayout.clamp(p.getFloat("videoFraction", videoFraction));
        keepRunning = p.getBoolean("keepRunning", keepRunning);
        checkUpdates = p.getBoolean("checkUpdates", checkUpdates);
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
                .putInt("videoMode", videoMode)
                .putFloat("videoFraction", videoFraction)
                .putBoolean("keepRunning", keepRunning)
                .putBoolean("checkUpdates", checkUpdates)
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
