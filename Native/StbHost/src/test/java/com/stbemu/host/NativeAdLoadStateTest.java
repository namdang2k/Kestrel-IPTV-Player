package com.stbemu.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NativeAdLoadStateTest {
    @Test
    public void failureTriesNextUnitImmediatelyThenBacksOffAfterWrap() {
        NativeAdLoadState state = new NativeAdLoadState();
        state.begin();
        assertEquals(0L, state.onFailure(2));
        assertEquals(1, state.unitIndex());
        state.begin();
        assertEquals(2_000L, state.onFailure(2));
        assertEquals(0, state.unitIndex());
        assertFalse(state.isLoading());
    }

    @Test
    public void backoffGrowsAndIsCapped() {
        assertEquals(2_000L, NativeAdLoadState.backoffMs(1));
        assertEquals(32_000L, NativeAdLoadState.backoffMs(5));
        assertEquals(NativeAdLoadState.MAX_BACKOFF_MS, NativeAdLoadState.backoffMs(6));
        assertEquals(NativeAdLoadState.MAX_BACKOFF_MS, NativeAdLoadState.backoffMs(40));
    }

    @Test
    public void kickResetsFailureHistoryAndInvalidatesPendingRetry() {
        NativeAdLoadState state = new NativeAdLoadState();
        state.begin();
        state.onFailure(1);
        state.begin();
        assertEquals(4_000L, state.onFailure(1));
        int epoch = state.retryEpoch();
        state.kick();
        assertTrue(state.retryEpoch() != epoch);
        state.begin();
        assertEquals(2_000L, state.onFailure(1));
    }

    @Test
    public void kickKeepsRequestInFlight() {
        NativeAdLoadState state = new NativeAdLoadState();
        int id = state.begin();
        state.kick();
        assertTrue(state.isCurrent(id));
    }

    @Test
    public void kickDuringFlightDoesNotSkipUnits() {
        NativeAdLoadState state = new NativeAdLoadState();
        state.begin();
        assertEquals(0L, state.onFailure(3)); // unit 0 failed, unit 1 in flight next
        state.begin();
        state.kick();
        assertEquals(1, state.unitIndex());
        assertEquals(0L, state.onFailure(3)); // unit 1 failed, continue with unit 2
        assertEquals(2, state.unitIndex());
        state.begin();
        state.onFailure(3);
        state.kick(); // idle: next round restarts at unit 0
        assertEquals(0, state.unitIndex());
    }

    @Test
    public void resetDuringFlightRestartsAtFirstUnit() {
        NativeAdLoadState state = new NativeAdLoadState();
        state.begin();
        state.onFailure(3);
        state.begin();
        assertEquals(1, state.unitIndex());
        state.reset();
        assertEquals(0, state.unitIndex());
    }

    @Test
    public void resetAndTimeoutMakeLateCallbacksStale() {
        NativeAdLoadState state = new NativeAdLoadState();
        int first = state.begin();
        state.reset();
        assertFalse(state.isCurrent(first));
        assertFalse(state.isLoading());

        int second = state.begin();
        state.onFailure(1); // watchdog path
        assertFalse(state.isCurrent(second));
        int third = state.begin();
        assertFalse(state.isCurrent(second));
        assertTrue(state.isCurrent(third));
    }

    @Test
    public void successClearsRetryCount() {
        NativeAdLoadState state = new NativeAdLoadState();
        state.begin();
        state.onFailure(1);
        state.begin();
        state.onSuccess();
        state.begin();
        assertEquals(2_000L, state.onFailure(1));
    }
}
