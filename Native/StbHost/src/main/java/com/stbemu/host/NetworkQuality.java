package com.stbemu.host;

import java.util.ArrayDeque;

/**
 * Rates the link for the current stream: measured bandwidth against the stream's bitrate, plus stalls in the last
 * five minutes. A worse tier buffers more before resuming after a stall (fewer, longer stalls instead of many short ones).
 */
final class NetworkQuality {
    enum Tier { GOOD, FAIR, WEAK, INSUFFICIENT }

    static final long WINDOW_MS = 300_000;
    private final ArrayDeque<Long> stalls = new ArrayDeque<>();

    void stallStarted(long nowMs) {
        stalls.addLast(nowMs);
        prune(nowMs);
    }

    int stalls(long nowMs) {
        prune(nowMs);
        return stalls.size();
    }

    /** @param bitrateBps stream bitrate; 0 or less when the source does not say (then only stalls count). */
    Tier tier(long nowMs, long bandwidthBps, long bitrateBps) {
        int recentStalls = stalls(nowMs);
        double headroom = bitrateBps > 0 && bandwidthBps > 0 ? (double) bandwidthBps / bitrateBps : Double.NaN;
        if (headroom < 1.1) return Tier.INSUFFICIENT; // NaN compares false: unknown bitrate never lands here
        if (headroom < 1.5 || recentStalls >= 3) return Tier.WEAK;
        if (headroom < 2.5 || recentStalls >= 1) return Tier.FAIR;
        return Tier.GOOD;
    }

    /** Buffer required to resume after a stall. Live streams are further capped by the player at half the live offset. */
    static long resumeAfterStallMs(Tier tier) {
        switch (tier) {
            case GOOD: return 3000;
            case FAIR: return 5000;
            case WEAK: return 8000;
            default: return 10000;
        }
    }

    private void prune(long nowMs) {
        while (!stalls.isEmpty() && nowMs - stalls.peekFirst() > WINDOW_MS) stalls.removeFirst();
    }
}
