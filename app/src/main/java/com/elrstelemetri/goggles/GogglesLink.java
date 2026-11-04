package com.elrstelemetri.goggles;

import android.content.Intent;

import com.elrstelemetri.n3.H264AnnexBAssembler;

/** One kind of DJI goggles that delivers live H.264 video over USB (Goggles N3, Goggles V2). */
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
}
