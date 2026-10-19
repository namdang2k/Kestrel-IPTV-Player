package com.stbemu.host;

/**
 * Load bookkeeping for one native ad placement: which request is current, which ad unit to try next, and the backoff
 * between failed rounds. Pure Java so it can be unit tested without AdLoader. Main thread only.
 */
final class NativeAdLoadState {
    static final long MAX_BACKOFF_MS = 60_000L;
    static final long LOAD_TIMEOUT_MS = 30_000L;

    private boolean loading;
    private int requestId;
    private int retryEpoch;
    private int unitIndex;
    private int retryCount;

    boolean isLoading() {
        return loading;
    }

    int unitIndex() {
        return unitIndex;
    }

    int retryEpoch() {
        return retryEpoch;
    }

    /** Marks a request as in flight and returns its id; any earlier request becomes stale. */
    int begin() {
        loading = true;
        return ++requestId;
    }

    /** True while {@code id} is the in-flight request (not superseded, timed out, or reset). */
    boolean isCurrent(int id) {
        return loading && id == requestId;
    }

    void onSuccess() {
        loading = false;
        retryCount = 0;
    }

    /**
     * Records a failed request. Returns 0 when the next ad unit should be tried immediately, otherwise the delay in
     * milliseconds before the next round (the unit list wrapped around).
     */
    long onFailure(int unitCount) {
        loading = false;
        if (unitIndex + 1 < unitCount) {
            unitIndex++;
            return 0L;
        }
        unitIndex = 0;
        return backoffMs(++retryCount);
    }

    /** Ends a request that already failed or timed out without scheduling a retry (demand went away). */
    void cancel() {
        loading = false;
    }

    /**
     * The placement became visible again: forget failures and invalidate pending retries. A request in flight is kept
     * together with its unit index, so its failure still advances from the unit it was issued for.
     */
    void kick() {
        if (!loading) unitIndex = 0;
        retryCount = 0;
        retryEpoch++;
    }

    /** Config changed: forget everything, including any request still in flight. */
    void reset() {
        kick();
        unitIndex = 0;
        loading = false;
        requestId++;
    }

    static long backoffMs(int retry) {
        return Math.min(MAX_BACKOFF_MS, (1L << Math.min(Math.max(retry, 0), 6)) * 1000L);
    }
}
