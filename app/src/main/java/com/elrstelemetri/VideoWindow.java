package com.elrstelemetri;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Floating container for the video: drag the window to move it; resize it with two fingers (pinch)
 * or by dragging the ◢ corner.
 * Buttons on the window: fullscreen/picture-in-picture switches between the panel and "replace the map", ✕ hides the video.
 * Stays inside its parent.
 */
public final class VideoWindow extends FrameLayout {

    public interface Listener {
        /** Fullscreen / picture-in-picture pressed: switch between the floating panel and replacing the map. */
        void onToggleFull();

        /** ✕ pressed: hide the video. */
        void onClose();

        /** Position or size changed by the user (to persist it). */
        void onGeometryChanged();
    }

    public final VideoPanel video;
    private final android.widget.ImageView handle;
    private final android.widget.LinearLayout controls;
    private final android.widget.ImageView fullButton;
    private final int slop;
    private final int minW, minH;
    private Listener listener;
    private boolean floating = true;

    private float downX, downY;
    private int startL, startT, startW, startH;
    private boolean dragging;
    private boolean resizing;   // corner handle drag in progress
    private boolean pinching;   // two-finger resize in progress
    private final ScaleGestureDetector pinch;
    private float pinchScale;
    private float pinchFocusX, pinchFocusY;  // focus point, relative to the window at the start

    @SuppressLint("ClickableViewAccessibility")
    public VideoWindow(Context c, VideoPanel video) {
        super(c);
        this.video = video;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        minW = dp(160);
        minH = dp(90);
        addView(video, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        handle = new android.widget.ImageView(c);
        handle.setImageResource(R.drawable.ic_resize);
        handle.setPadding(dp(5), dp(5), dp(5), dp(5));
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(Color.argb(150, 0, 0, 0));
        hb.setCornerRadius(dp(6));
        handle.setBackground(hb);
        LayoutParams hl = new LayoutParams(dp(40), dp(40), Gravity.BOTTOM | Gravity.END);
        addView(handle, hl);
        handle.setOnTouchListener(this::onResizeTouch);

        pinch = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                if (!floating) return false;
                beginGesture(null);
                pinching = true;
                dragging = false;
                pinchScale = 1f;
                pinchFocusX = d.getFocusX() - getLeft();
                pinchFocusY = d.getFocusY() - getTop();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                pinchScale *= d.getScaleFactor();
                applyPinch();
                return true;
            }
        });
        pinch.setQuickScaleEnabled(false);

        controls = new android.widget.LinearLayout(c);
        controls.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        fullButton = controlButton(c, R.drawable.ic_fullscreen, () -> {
            if (listener != null) listener.onToggleFull();
        });
        controls.addView(fullButton);
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

    /** Floating panel (movable/resizable) or filling the parent. */
    public void setFloating(boolean f) {
        floating = f;
        handle.setVisibility(f ? View.VISIBLE : View.GONE);
        // Panel: buttons top-right. Replacing the map: bottom-left (free while the map is hidden),
        // because the top and sides are covered by the telemetry panels.
        fullButton.setImageResource(f ? R.drawable.ic_fullscreen : R.drawable.ic_pip);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                f ? Gravity.TOP | Gravity.END : Gravity.BOTTOM | Gravity.START);
        if (!f) lp.setMargins(dp(8), 0, 0, dp(10));
        controls.setLayoutParams(lp);
    }

    public boolean isFloating() {
        return floating;
    }

    private int parentW() {
        View p = (View) getParent();
        return p == null ? 0 : p.getWidth();
    }

    private int parentH() {
        View p = (View) getParent();
        return p == null ? 0 : p.getHeight();
    }

    /** Places the floating window in pixels, clamped to the parent. */
    public void setGeometry(int left, int top, int w, int h) {
        int pw = parentW(), ph = parentH();
        if (pw > 0 && ph > 0) {
            w = Math.max(minW, Math.min(w, pw));
            h = Math.max(minH, Math.min(h, ph));
            left = Math.max(0, Math.min(left, pw - w));
            top = Math.max(0, Math.min(top, ph - h));
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.START);
        lp.leftMargin = left;
        lp.topMargin = top;
        setLayoutParams(lp);
    }

    /** Fills the parent (video replaces the map). */
    public void fillParent() {
        setLayoutParams(new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    /** Current geometry as fractions of the parent: {x, y, w, h}. */
    public float[] geometryFractions() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        int pw = Math.max(1, parentW()), ph = Math.max(1, parentH());
        int w = lp.width > 0 ? lp.width : getWidth();
        int h = lp.height > 0 ? lp.height : getHeight();
        return new float[]{lp.leftMargin / (float) pw, lp.topMargin / (float) ph, w / (float) pw, h / (float) ph};
    }

    // ---------------------------------------------------------------- Move / tap

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginGesture(e);
                return false;
            case MotionEvent.ACTION_POINTER_DOWN:
                return floating && !resizing;  // second finger: pinch to resize
            case MotionEvent.ACTION_MOVE:
                return floating && !resizing && moved(e);
            default:
                return false;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // the detector needs raw (screen) coordinates because the window moves while pinching
        MotionEvent raw = MotionEvent.obtain(e);
        raw.offsetLocation(getLeft(), getTop());
        pinch.onTouchEvent(raw);
        raw.recycle();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginGesture(e);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pinching || e.getPointerCount() > 1) return true;
                if (floating && (dragging || moved(e))) {
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    setGeometry(startL + Math.round(e.getRawX() - downX), startT + Math.round(e.getRawY() - downY),
                            startW, startH);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if ((dragging || pinching) && listener != null) listener.onGeometryChanged();
                dragging = false;
                pinching = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                pinching = false;
                return true;
            default:
                return true;
        }
    }

    /** Scales the window around the pinch focus, keeping its aspect ratio and staying inside the parent. */
    private void applyPinch() {
        int pw = parentW(), ph = parentH();
        if (startW <= 0 || startH <= 0) return;
        float s = pinchScale;
        s = Math.max(s, Math.max(minW / (float) startW, minH / (float) startH));
        if (pw > 0 && ph > 0) s = Math.min(s, Math.min(pw / (float) startW, ph / (float) startH));
        int w = Math.round(startW * s), h = Math.round(startH * s);
        int left = Math.round(startL + pinchFocusX - pinchFocusX * s);
        int top = Math.round(startT + pinchFocusY - pinchFocusY * s);
        setGeometry(left, top, w, h);
    }

    private void beginGesture(MotionEvent e) {
        if (e != null) {
            downX = e.getRawX();
            downY = e.getRawY();
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        startL = lp.leftMargin;
        startT = lp.topMargin;
        startW = getWidth();
        startH = getHeight();
        dragging = false;
    }

    private boolean moved(MotionEvent e) {
        return Math.abs(e.getRawX() - downX) > slop || Math.abs(e.getRawY() - downY) > slop;
    }

    // ---------------------------------------------------------------- Resize

    private boolean onResizeTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginGesture(e);
                resizing = true;
                // v's parent is this window: stops both this window (move) and the map from taking the gesture
                v.getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                setGeometry(startL, startT, startW + Math.round(e.getRawX() - downX),
                        startH + Math.round(e.getRawY() - downY));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                resizing = false;
                if (listener != null) listener.onGeometryChanged();
                return true;
            default:
                return true;
        }
    }
}
