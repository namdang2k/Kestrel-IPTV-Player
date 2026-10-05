package com.stbemu.host;

/** Two transport retries and one link resolve; stream errors never authorize portal authentication. */
final class ReloadPolicy {
    enum Mode { NONE, ALWAYS, ONCE }
    private Mode mode = Mode.ALWAYS;
    private int attempts;
    private boolean linkResolveRequested;
    Mode mode() { return mode; }
    void cycle() {
        mode = mode == Mode.NONE ? Mode.ALWAYS : mode == Mode.ALWAYS ? Mode.ONCE : Mode.NONE;
        reset();
    }
    void reset() { attempts = 0; linkResolveRequested = false; }
    int attempts() { return attempts; }
    boolean linkResolveUsed() { return linkResolveRequested; }
    void retryStarted() { attempts++; }
    boolean requestLinkResolve() {
        if (mode != Mode.ALWAYS || linkResolveRequested) return false;
        linkResolveRequested = true;
        return true;
    }
    long nextDelayMillis() {
        if (mode == Mode.NONE || attempts >= (mode == Mode.ONCE ? 1 : 2)) return -1;
        return 2000L << attempts;
    }
}
