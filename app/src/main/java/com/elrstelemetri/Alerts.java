package com.elrstelemetri;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Türkçe sesli uyarılar; aynı uyarı belirli aralıkla tekrarlanır. */
public final class Alerts implements TextToSpeech.OnInitListener {

    private final TextToSpeech tts;
    private boolean ready;
    private final Map<String, Long> lastSpoken = new HashMap<>();

    public Alerts(Context c) {
        tts = new TextToSpeech(c.getApplicationContext(), this);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int r = tts.setLanguage(new Locale("tr", "TR"));
            ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
            if (!ready) ready = tts.setLanguage(Locale.getDefault()) >= TextToSpeech.LANG_AVAILABLE;
        }
    }

    public void say(String key, String text, long repeatMs) {
        if (!ready) return;
        long now = System.currentTimeMillis();
        Long last = lastSpoken.get(key);
        if (last != null && now - last < repeatMs) return;
        lastSpoken.put(key, now);
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, key);
    }

    /** Uyarı durumu düzelince sıfırla ki tekrar oluşursa hemen söylensin. */
    public void clear(String key) {
        lastSpoken.remove(key);
    }

    public void shutdown() {
        tts.shutdown();
    }
}
