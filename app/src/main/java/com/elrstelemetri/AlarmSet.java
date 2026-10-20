package com.elrstelemetri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * User-configurable threshold alarms. Pure Java so it can be unit tested.
 *
 * Each alarm has an enable switch and a threshold. A hysteresis band keeps an alarm from
 * flickering when the value hovers around the threshold. "Arm after crossing" alarms
 * (minimum altitude / speed) only become active once the value has been above the
 * threshold during the current flight, so they stay quiet on the ground.
 */
public final class AlarmSet {

    public static final String LOW_CELL = "low_cell";
    public static final String LOW_BATTERY_PCT = "low_battery_pct";
    public static final String LOW_LQ = "low_lq";
    public static final String LOW_RSSI = "low_rssi";
    public static final String TELEMETRY_LOST = "telemetry_lost";
    public static final String MIN_ALT = "min_alt";
    public static final String MAX_ALT = "max_alt";
    public static final String MIN_SPEED = "min_speed";
    public static final String MAX_SPEED = "max_speed";
    public static final String MAX_DISTANCE = "max_distance";
    public static final String LOW_SATS = "low_sats";

    public static final class Alarm {
        public final String id;
        public final String title;      // shown in settings and banner
        public final String unit;
        public final String speech;     // spoken text
        public final boolean above;     // true: alarm when value > threshold; false: when value < threshold
        public final boolean armAfterCross;
        public final double hysteresis;
        public final boolean defaultEnabled;
        public final double defaultThreshold;
        public final boolean decimal;   // threshold edited as decimal number

        public boolean enabled;
        public double threshold;

        public boolean active;
        public double value = Double.NaN;
        private boolean crossed;

        Alarm(String id, String title, String unit, String speech, boolean above, boolean armAfterCross,
              double hysteresis, boolean enabled, double threshold, boolean decimal) {
            this.id = id;
            this.title = title;
            this.unit = unit;
            this.speech = speech;
            this.above = above;
            this.armAfterCross = armAfterCross;
            this.hysteresis = hysteresis;
            this.defaultEnabled = enabled;
            this.defaultThreshold = threshold;
            this.decimal = decimal;
            this.enabled = enabled;
            this.threshold = threshold;
        }

        /**
         * @param value  current value
         * @param valid  whether the value is fresh/meaningful
         * @param flying whether the aircraft is considered in flight (used by arm-after-crossing alarms)
         * @return true if the alarm just became active
         */
        public boolean evaluate(double value, boolean valid, boolean flying) {
            boolean was = active;
            this.value = value;
            if (!enabled || !valid || Double.isNaN(value)) {
                active = false;
                if (!flying) crossed = false;
                return false;
            }
            if (armAfterCross) {
                if (!flying) {
                    crossed = false;
                    active = false;
                    return false;
                }
                if (!crossed) {
                    boolean beyond = above ? value < threshold - hysteresis : value > threshold + hysteresis;
                    if (!beyond) {
                        active = false;
                        return false;
                    }
                    crossed = true;
                }
            }
            if (above) active = was ? value > threshold - hysteresis : value > threshold;
            else active = was ? value < threshold + hysteresis : value < threshold;
            return active && !was;
        }

        public String describe() {
            return (above ? "> " : "< ") + format(threshold) + " " + unit;
        }

        public String format(double v) {
            return decimal ? String.format(java.util.Locale.US, "%.2f", v) : String.format(java.util.Locale.US, "%.0f", v);
        }
    }

    private final Map<String, Alarm> alarms = new LinkedHashMap<>();

    public AlarmSet() {
        add(new Alarm(LOW_CELL, "Low cell voltage", "V", "Battery low", false, false, 0.05, true, 3.50, true));
        add(new Alarm(LOW_BATTERY_PCT, "Low battery capacity", "%", "Battery capacity low", false, false, 2, true, 20, false));
        add(new Alarm(LOW_LQ, "Low link quality", "%", "Link quality low", false, false, 5, true, 50, false));
        add(new Alarm(LOW_RSSI, "Low RSSI", "dBm", "Signal weak", false, false, 3, false, -100, false));
        add(new Alarm(TELEMETRY_LOST, "Telemetry lost", "s", "Telemetry lost", true, false, 0, true, 3, false));
        add(new Alarm(MIN_ALT, "Minimum altitude", "m", "Altitude low", false, true, 3, false, 20, false));
        add(new Alarm(MAX_ALT, "Maximum altitude", "m", "Altitude high", true, false, 3, true, 120, false));
        add(new Alarm(MIN_SPEED, "Minimum speed", "km/h", "Speed low", false, true, 3, false, 25, false));
        add(new Alarm(MAX_SPEED, "Maximum speed", "km/h", "Speed high", true, false, 3, false, 150, false));
        add(new Alarm(MAX_DISTANCE, "Maximum distance", "m", "Distance limit", true, false, 20, false, 1000, false));
        add(new Alarm(LOW_SATS, "Low GPS satellites", "sats", "GPS weak", false, false, 1, true, 6, false));
    }

    private void add(Alarm a) {
        alarms.put(a.id, a);
    }

    public Alarm get(String id) {
        return alarms.get(id);
    }

    public List<Alarm> all() {
        return Collections.unmodifiableList(new ArrayList<>(alarms.values()));
    }

    public List<Alarm> active() {
        List<Alarm> r = new ArrayList<>();
        for (Alarm a : alarms.values()) if (a.active) r.add(a);
        return r;
    }

    public boolean isActive(String id) {
        Alarm a = alarms.get(id);
        return a != null && a.active;
    }

    public void resetToDefaults() {
        for (Alarm a : alarms.values()) {
            a.enabled = a.defaultEnabled;
            a.threshold = a.defaultThreshold;
        }
    }
}
