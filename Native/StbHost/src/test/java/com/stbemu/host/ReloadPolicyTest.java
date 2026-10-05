package com.stbemu.host;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReloadPolicyTest {
    @Test public void automaticByDefaultAndCyclesThroughUserOverrides() {
        ReloadPolicy policy = new ReloadPolicy();
        assertEquals(ReloadPolicy.Mode.ALWAYS, policy.mode());
        policy.cycle(); assertEquals(ReloadPolicy.Mode.ONCE, policy.mode());
        policy.cycle(); assertEquals(ReloadPolicy.Mode.NONE, policy.mode());
        assertEquals(-1, policy.nextDelayMillis());
        assertFalse(policy.requestLinkResolve());
        policy.cycle(); assertEquals(ReloadPolicy.Mode.ALWAYS, policy.mode());
    }
    @Test public void oneRetryDoesNotResolveOrRefreshPortal() {
        ReloadPolicy policy = new ReloadPolicy(); policy.cycle();
        assertEquals(2000, policy.nextDelayMillis());
        policy.retryStarted();
        assertEquals(-1, policy.nextDelayMillis());
        assertFalse(policy.requestLinkResolve());
    }
    @Test public void twoTransportRetriesThenOneResolve() {
        ReloadPolicy policy = new ReloadPolicy();
        assertEquals(2000, policy.nextDelayMillis());
        policy.retryStarted();
        assertEquals(4000, policy.nextDelayMillis());
        policy.retryStarted();
        assertEquals(-1, policy.nextDelayMillis());
        assertTrue(policy.requestLinkResolve());
        assertFalse(policy.requestLinkResolve());
        assertEquals(-1, policy.nextDelayMillis());
        assertEquals(2, policy.attempts());
    }
    @Test public void cdnRefusalCanResolveImmediatelyOnlyOnce() {
        ReloadPolicy policy = new ReloadPolicy();
        assertFalse(policy.linkResolveUsed());
        assertTrue(policy.requestLinkResolve());
        assertTrue(policy.linkResolveUsed()); // a refusal of the resolved link then reconnects the portal
        assertFalse(policy.requestLinkResolve());
        assertEquals(0, policy.attempts());
        policy.retryStarted();
        assertFalse(policy.requestLinkResolve());
    }
    @Test public void stablePlaybackOrNewSelectionRestoresBudget() {
        ReloadPolicy policy = new ReloadPolicy();
        policy.retryStarted(); policy.retryStarted();
        assertTrue(policy.requestLinkResolve());
        policy.reset();
        assertEquals(2000, policy.nextDelayMillis());
        assertTrue(policy.requestLinkResolve());
    }
    @Test public void cancelledTimersDoNotConsumeRetryBudget() {
        ReloadPolicy policy = new ReloadPolicy();
        for (int i = 0; i < 5; i++) assertEquals(2000, policy.nextDelayMillis());
        assertEquals(0, policy.attempts());
    }
}
