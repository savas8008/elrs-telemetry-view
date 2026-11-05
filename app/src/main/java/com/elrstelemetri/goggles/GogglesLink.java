package com.elrstelemetri.goggles;

import android.content.Intent;
import android.view.Surface;

import com.elrstelemetri.n3.H264AnnexBAssembler;

/**
 * One USB video source: DJI goggles that deliver H.264 (Goggles N3, Goggles V2), or a USB capture
 * device that draws its video into the panel itself.
 */
public interface GogglesLink {

    interface Listener {
        /** Video started (first data on a new connection). Called on the main thread. */
        void onStreamStarted();

        /** One H.264 access unit (Annex-B) with the latest SPS/PPS. Called on the USB read thread. */
        void onAccessUnit(H264AnnexBAssembler.AccessUnit au);

        /** Connection lost or closed. */
        void onStreamStopped(String reason);
    }

    /** Starts listening; {@code launchIntent} may carry the device if the app was opened by plugging in. */
    void start(Intent launchIntent);

    void stop();

    /** Handles a USB attach intent delivered to the activity. */
    void handleIntent(Intent intent);

    /** Closes and reopens the USB connection. */
    void reconnect();

    String status();

    String identity();

    boolean isStreaming();

    /** True if these goggles are currently plugged in. */
    boolean devicePresent();

    /** True if the goggles' OSD is part of the video (then the app's telemetry panel can be hidden). */
    boolean videoHasOsd();

    /** Short name for status texts, e.g. "Goggles N3". */
    String name();

    /**
     * The video panel's surface, or null when it goes away (then a link must stop drawing before it
     * returns). Only links that draw the video themselves (USB capture) use it; the goggles send H.264.
     */
    default void setVideoSurface(Surface surface) {
    }
}
