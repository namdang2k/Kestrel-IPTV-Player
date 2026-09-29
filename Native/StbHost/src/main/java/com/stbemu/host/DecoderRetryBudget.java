package com.stbemu.host;

import java.util.HashSet;
import java.util.Set;

/** Per-play decoder exclusions. Shared by the application and ExoPlayer playback loopers. */
final class DecoderRetryBudget {
    private final Set<String> failed = new HashSet<>();
    private static final int MAX_SWITCHES = 2;

    synchronized void reset() { failed.clear(); }
    synchronized boolean allows(String name) { return !failed.contains(name); }
    synchronized int attempts() { return failed.size(); }
    synchronized boolean exclude(String name) {
        if (name == null || name.isEmpty() || failed.size() >= MAX_SWITCHES) return false;
        return failed.add(name);
    }
}
