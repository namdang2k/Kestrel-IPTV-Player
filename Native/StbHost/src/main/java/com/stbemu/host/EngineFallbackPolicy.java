package com.stbemu.host;

/** One-way, once per item. HTTP status failures also get one attempt with the resolved URL in MPV. */
final class EngineFallbackPolicy {
    private boolean attempted;

    boolean claim(int code) {
        if (attempted || !eligible(code)) return false;
        attempted = true;
        return true;
    }

    void reset() { attempted = false; }

    static boolean eligible(int code) {
        // Media3 PlaybackException: IO_BAD_HTTP_STATUS (2004), parsing (3001..3004), decoder (4001..4005).
        // This is a second request, not a way to bypass server authorization. Other network/DRM errors stay excluded.
        return code == 2004 || code >= 3001 && code <= 3004 || code >= 4001 && code <= 4005;
    }
}
