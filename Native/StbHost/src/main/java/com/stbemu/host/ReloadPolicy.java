package com.stbemu.host;

/** Three stream retries, then one portal refresh per recovery cycle. */
final class ReloadPolicy {
    enum Mode { NONE, ALWAYS, ONCE }
    private Mode mode = Mode.ALWAYS;
    private int attempts;
    private boolean portalReloadRequested;
    Mode mode() { return mode; }
    void cycle() {
        mode = mode == Mode.NONE ? Mode.ALWAYS : mode == Mode.ALWAYS ? Mode.ONCE : Mode.NONE;
        reset();
    }
    void reset() { attempts = 0; portalReloadRequested = false; }
    int attempts() { return attempts; }
    void retryStarted() { attempts++; }
    boolean requestPortalReload() {
        if (mode != Mode.ALWAYS || attempts < 3 || portalReloadRequested) return false;
        portalReloadRequested = true;
        return true;
    }
    long nextDelayMillis() {
        if (mode == Mode.NONE || attempts >= (mode == Mode.ONCE ? 1 : 3)) return -1;
        return 2000L << attempts;
    }
}
