package com.elrstelemetri;

/**
 * Splits the screen between the map and the goggles video so they never overlap: map on the left,
 * a draggable divider, the video on the right. The user sets the split by dragging the divider
 * (video share of the width, {@link #MIN}..{@link #MAX}). The map keeps the full height (the top bar
 * and buttons float over it); the video stays clear of the top bar and the buttons.
 * Rectangles are {left, top, right, bottom} in pixels.
 */
public final class SplitLayout {

    public static final float MIN = 0.15f;
    public static final float MAX = 0.85f;
    public static final float DEFAULT = 0.30f;

    public final int[] map;
    /** Null when the video is hidden (the map then fills the screen). */
    public final int[] video, divider;

    private SplitLayout(int[] map, int[] divider, int[] video) {
        this.map = map;
        this.divider = divider;
        this.video = video;
    }

    public static float clamp(float videoFraction) {
        if (Float.isNaN(videoFraction)) return DEFAULT;
        return Math.max(MIN, Math.min(MAX, videoFraction));
    }

    /**
     * @param videoFraction video share of the width without the divider (clamped)
     * @param topReserve    height kept free above the video (top bar)
     * @param bottomReserve height kept free below the video (buttons)
     */
    public static SplitLayout compute(int w, int h, boolean videoShown, float videoFraction, int dividerW,
                                      int topReserve, int bottomReserve) {
        if (!videoShown) return new SplitLayout(new int[]{0, 0, w, h}, null, null);
        int videoW = Math.round((w - dividerW) * clamp(videoFraction));
        int split = w - dividerW - videoW;
        int top = Math.min(topReserve, h / 4);
        int bottom = Math.max(top, h - Math.min(bottomReserve, h / 4));
        return new SplitLayout(new int[]{0, 0, split, h}, new int[]{split, 0, split + dividerW, h},
                new int[]{split + dividerW, top, w, bottom});
    }

    /** Video fraction that puts the divider's centre at {@code x} (a drag position), clamped. */
    public static float fractionAt(int w, float x, int dividerW) {
        int usable = Math.max(1, w - dividerW);
        return clamp((w - x - dividerW / 2f) / usable);
    }

    public static int width(int[] r) {
        return r[2] - r[0];
    }

    public static int height(int[] r) {
        return r[3] - r[1];
    }
}
