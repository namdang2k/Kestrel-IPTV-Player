package com.stbemu.host;

/** Recovery uses active time: an ad/background pause must not exhaust a stream's deadline. */
final class PlaybackInterruption {
    private long pausedAt = -1;
    private long pausedMillis;

    void setPaused(boolean paused, long now) {
        if (paused && pausedAt < 0) pausedAt = now;
        else if (!paused && pausedAt >= 0) {
            pausedMillis += Math.max(0, now - pausedAt);
            pausedAt = -1;
        }
    }

    long activeTime(long now) {
        return (pausedAt >= 0 ? pausedAt : now) - pausedMillis;
    }
}
