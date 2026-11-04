package com.elrstelemetri.goggles;

import android.content.Intent;

import java.util.Arrays;
import java.util.List;

/**
 * All supported goggles behind one {@link GogglesLink}: every call goes to each link, and the
 * status comes from the goggles that are streaming or plugged in. Only one pair of goggles is
 * used at a time.
 */
public final class GogglesLinks implements GogglesLink {

    private final List<GogglesLink> links;

    public GogglesLinks(GogglesLink... links) {
        this.links = Arrays.asList(links);
    }

    /** The goggles that stream, else the ones plugged in, else null. */
    GogglesLink active() {
        for (GogglesLink l : links) if (l.isStreaming()) return l;
        for (GogglesLink l : links) if (l.devicePresent()) return l;
        return null;
    }

    @Override
    public void start(Intent launchIntent) {
        for (GogglesLink l : links) l.start(launchIntent);
    }

    @Override
    public void stop() {
        for (GogglesLink l : links) l.stop();
    }

    @Override
    public void handleIntent(Intent intent) {
        for (GogglesLink l : links) l.handleIntent(intent);
    }

    @Override
    public void reconnect() {
        GogglesLink a = active();
        if (a != null) a.reconnect();
    }

    @Override
    public String status() {
        GogglesLink a = active();
        if (a != null) return a.name() + ": " + a.status();
        StringBuilder sb = new StringBuilder();
        for (GogglesLink l : links) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(l.name()).append(": ").append(l.status());
        }
        return sb.toString();
    }

    @Override
    public String identity() {
        GogglesLink a = active();
        return a != null ? a.identity() : "";
    }

    @Override
    public boolean isStreaming() {
        for (GogglesLink l : links) if (l.isStreaming()) return true;
        return false;
    }

    @Override
    public boolean devicePresent() {
        for (GogglesLink l : links) if (l.devicePresent()) return true;
        return false;
    }

    @Override
    public boolean videoHasOsd() {
        GogglesLink a = active();
        return a != null && a.videoHasOsd();
    }

    @Override
    public String name() {
        GogglesLink a = active();
        return a != null ? a.name() : "Goggles";
    }
}
