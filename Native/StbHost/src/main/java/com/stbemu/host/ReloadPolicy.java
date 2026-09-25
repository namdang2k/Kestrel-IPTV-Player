package com.stbemu.host;

/** Retry budget belongs to one user-selected stream, not to each prepare() attempt. */
final class ReloadPolicy {
    enum Mode { NONE, ALWAYS, ONCE }
    private Mode mode = Mode.NONE;
    private int attempts;
    Mode mode() { return mode; }
    void cycle() {
        mode = mode == Mode.NONE ? Mode.ALWAYS : mode == Mode.ALWAYS ? Mode.ONCE : Mode.NONE;
        reset();
    }
    void reset() { attempts = 0; }
    long nextDelayMillis() {
        if (mode == Mode.NONE || (mode == Mode.ONCE && attempts != 0)) return -1;
        return Math.min(15000L, 2000L << Math.min(attempts++, 3));
    }
}
