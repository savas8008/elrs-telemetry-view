package com.elrstelemetri;

/**
 * Splits the screen between the map and the goggles video so they never overlap: the video takes
 * 30 % or 70 % of the width on the right, the map the rest on the left. The map always keeps the full
 * height (the top bar and buttons float over it); the video stays clear of the top bar and the buttons.
 * Rectangles are {left, top, right, bottom} in pixels.
 */
public final class SplitLayout {

    public static final float SMALL = 0.30f;
    public static final float LARGE = 0.70f;

    public final int[] map;
    /** Null when the video is hidden. */
    public final int[] video;

    private SplitLayout(int[] map, int[] video) {
        this.map = map;
        this.video = video;
    }

    /**
     * @param videoLarge  video 70 % / map 30 % (otherwise video 30 % / map 70 %)
     * @param topReserve  height kept free above the video (top bar)
     * @param bottomReserve height kept free below the video (buttons)
     */
    public static SplitLayout compute(int w, int h, boolean videoShown, boolean videoLarge,
                                      int topReserve, int bottomReserve) {
        if (!videoShown) return new SplitLayout(new int[]{0, 0, w, h}, null);
        int videoW = Math.round(w * (videoLarge ? LARGE : SMALL));
        int split = w - videoW;
        int top = Math.min(topReserve, h / 4);
        int bottom = Math.max(top, h - Math.min(bottomReserve, h / 4));
        return new SplitLayout(new int[]{0, 0, split, h}, new int[]{split, top, w, bottom});
    }

    public static int width(int[] r) {
        return r[2] - r[0];
    }

    public static int height(int[] r) {
        return r[3] - r[1];
    }
}
