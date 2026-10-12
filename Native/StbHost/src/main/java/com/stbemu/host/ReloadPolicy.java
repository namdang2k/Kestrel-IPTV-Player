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

    static final String REPLAY = "REPLAY", RECONNECT = "RECONNECT";

    /**
     * A refused stream after authenticate() answered (errorCode null = success): REPLAY the same URL, RECONNECT the
     * server, or a failStream code. Xtream URLs are deterministic, so a second refusal with a valid account is final.
     */
    static String afterAccountCheck(boolean replayedOnce, String errorCode, int activeConnections, int maxConnections) {
        if (errorCode != null) {
            boolean retryable = "NETWORK".equals(errorCode) || errorCode.contains("TIMEOUT")
                    || errorCode.startsWith("HTTP_5") || "INVALID_RESPONSE".equals(errorCode);
            return retryable ? RECONNECT : errorCode;
        }
        // The server often still counts this device's just-dropped connection: replay once before blaming the limit.
        if (!replayedOnce) return REPLAY;
        return maxConnections > 0 && activeConnections >= maxConnections ? "MAX_CONNECTIONS" : "CHANNEL_UNAVAILABLE";
    }
}
