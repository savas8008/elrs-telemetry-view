package com.elrstelemetri;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;

/**
 * Container for the video in the split screen (see {@link SplitLayout}): the video fills it, letterboxed.
 * Buttons on the window: enlarge/shrink switches the video between 30 % and 70 % of the screen width
 * (the map takes the rest), ✕ hides the video.
 */
public final class VideoWindow extends FrameLayout {

    public interface Listener {
        /** Enlarge / shrink pressed: swap the 30 % / 70 % split between video and map. */
        void onToggleSize();

        /** ✕ pressed: hide the video. */
        void onClose();
    }

    public final VideoPanel video;
    private final android.widget.ImageView sizeButton;
    private Listener listener;

    public VideoWindow(Context c, VideoPanel video) {
        super(c);
        this.video = video;
        setBackgroundColor(Color.BLACK);
        addView(video, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        android.widget.LinearLayout controls = new android.widget.LinearLayout(c);
        controls.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        sizeButton = controlButton(c, R.drawable.ic_fullscreen, () -> {
            if (listener != null) listener.onToggleSize();
        });
        controls.addView(sizeButton);
        controls.addView(controlButton(c, R.drawable.ic_close, () -> {
            if (listener != null) listener.onClose();
        }));
        addView(controls, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));
    }

    private android.widget.ImageView controlButton(Context c, int icon, Runnable action) {
        android.widget.ImageView b = new android.widget.ImageView(c);
        b.setImageResource(icon);
        b.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(160, 0, 0, 0));
        bg.setCornerRadius(dp(6));
        b.setBackground(bg);
        b.setOnClickListener(v -> action.run());
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(dp(34), dp(34));
        lp.setMargins(dp(3), dp(3), dp(3), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Large = 70 % of the width (button shrinks it), otherwise 30 % (button enlarges it). */
    public void setLarge(boolean large) {
        sizeButton.setImageResource(large ? R.drawable.ic_pip : R.drawable.ic_fullscreen);
    }
}
