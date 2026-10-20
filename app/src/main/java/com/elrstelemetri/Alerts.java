package com.elrstelemetri;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.os.Build;
import android.speech.tts.TextToSpeech;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Spoken alarms (English, uses the on-device TTS engine so no internet is needed)
 * and vibration. Each alarm key repeats at most once per interval.
 */
public final class Alerts implements TextToSpeech.OnInitListener {

    private final TextToSpeech tts;
    private final Vibrator vibrator;
    private boolean ready;
    private final Map<String, Long> lastSpoken = new HashMap<>();
    private long mutedUntil;

    @SuppressWarnings("deprecation")  // VIBRATOR_SERVICE is needed on Android 10-11
    public Alerts(Context c) {
        Context app = c.getApplicationContext();
        tts = new TextToSpeech(app, this);
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int r = tts.setLanguage(Locale.US);
            ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
            if (!ready) ready = tts.setLanguage(Locale.ENGLISH) >= TextToSpeech.LANG_AVAILABLE;
        }
    }

    /** Announces an alarm if it has not been announced within {@code repeatMs}. */
    public void alarm(String key, String text, long repeatMs, boolean speak, boolean vibrate) {
        long now = System.currentTimeMillis();
        Long last = lastSpoken.get(key);
        if (last != null && now - last < repeatMs) return;
        lastSpoken.put(key, now);
        if (now < mutedUntil) return;
        if (speak && ready) tts.speak(text, TextToSpeech.QUEUE_ADD, null, key);
        if (vibrate && vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 250, 120, 250}, -1));
        }
    }

    /** Resets an alarm so it is announced immediately if it comes back. */
    public void clear(String key) {
        lastSpoken.remove(key);
    }

    public void mute(long ms) {
        mutedUntil = System.currentTimeMillis() + ms;
        tts.stop();
    }

    public boolean isMuted() {
        return System.currentTimeMillis() < mutedUntil;
    }

    public long mutedRemainingMs() {
        return Math.max(0, mutedUntil - System.currentTimeMillis());
    }

    public void unmute() {
        mutedUntil = 0;
    }

    public void shutdown() {
        tts.shutdown();
    }
}
