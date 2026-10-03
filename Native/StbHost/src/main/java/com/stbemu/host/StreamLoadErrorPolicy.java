package com.stbemu.host;

import androidx.media3.common.C;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;

/** The host owns the recovery ledger; loaders must not multiply its retry budget. */
final class StreamLoadErrorPolicy extends DefaultLoadErrorHandlingPolicy {
    StreamLoadErrorPolicy() { super(0); }

    @Override public long getRetryDelayMsFor(LoadErrorInfo error) { return C.TIME_UNSET; }

    @Override public FallbackSelection getFallbackSelectionFor(FallbackOptions options, LoadErrorInfo error) {
        return null;
    }
}
