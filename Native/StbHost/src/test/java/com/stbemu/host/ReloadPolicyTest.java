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
    @Test public void refusedStreamReplaysOnceEvenAtTheConnectionLimit() {
        assertEquals(ReloadPolicy.REPLAY, ReloadPolicy.afterAccountCheck(false, null, 1, 1));
        assertEquals(ReloadPolicy.REPLAY, ReloadPolicy.afterAccountCheck(false, null, 0, 1));
    }
    @Test public void secondRefusalWithValidAccountFailsInsteadOfReconnecting() {
        assertEquals("MAX_CONNECTIONS", ReloadPolicy.afterAccountCheck(true, null, 1, 1));
        assertEquals("CHANNEL_UNAVAILABLE", ReloadPolicy.afterAccountCheck(true, null, 0, 1));
        assertEquals("CHANNEL_UNAVAILABLE", ReloadPolicy.afterAccountCheck(true, null, 5, 0)); // 0 = unlimited
    }
    @Test public void accountCheckErrorsFailOrReconnect() {
        for (boolean replayed : new boolean[] {false, true}) {
            assertEquals("ACCOUNT_EXPIRED:2026-01-01", ReloadPolicy.afterAccountCheck(replayed, "ACCOUNT_EXPIRED:2026-01-01", 0, 0));
            assertEquals("ACCOUNT_DISABLED", ReloadPolicy.afterAccountCheck(replayed, "ACCOUNT_DISABLED", 0, 0));
            assertEquals("AUTHENTICATION_REJECTED", ReloadPolicy.afterAccountCheck(replayed, "AUTHENTICATION_REJECTED", 0, 0));
            assertEquals("FORBIDDEN", ReloadPolicy.afterAccountCheck(replayed, "FORBIDDEN", 0, 0));
            for (String code : new String[] {"NETWORK", "TIMEOUT", "HTTP_503", "INVALID_RESPONSE"})
                assertEquals(code, ReloadPolicy.RECONNECT, ReloadPolicy.afterAccountCheck(replayed, code, 0, 0));
        }
    }
}
