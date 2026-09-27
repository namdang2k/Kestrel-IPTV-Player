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
        assertFalse(policy.requestPortalReload());
        policy.cycle(); assertEquals(ReloadPolicy.Mode.ALWAYS, policy.mode());
    }
    @Test public void oneRetryPerStream() {
        ReloadPolicy policy = new ReloadPolicy(); policy.cycle();
        assertEquals(2000, policy.nextDelayMillis());
        policy.retryStarted();
        assertEquals(-1, policy.nextDelayMillis());
        assertFalse(policy.requestPortalReload());
        policy.reset();
        assertEquals(2000, policy.nextDelayMillis());
    }
    @Test public void threeRetriesThenExactlyOnePortalReload() {
        ReloadPolicy policy = new ReloadPolicy();
        assertFalse(policy.requestPortalReload());
        assertEquals(2000, policy.nextDelayMillis());
        policy.retryStarted();
        assertFalse(policy.requestPortalReload());
        assertEquals(4000, policy.nextDelayMillis());
        policy.retryStarted();
        assertFalse(policy.requestPortalReload());
        assertEquals(8000, policy.nextDelayMillis());
        policy.retryStarted();
        assertEquals(-1, policy.nextDelayMillis());
        assertTrue(policy.requestPortalReload());
        assertFalse(policy.requestPortalReload());
        assertEquals(-1, policy.nextDelayMillis());
        assertEquals(3, policy.attempts());
    }

    @Test public void recoveryOrNewSelectionRestoresTheFullBudget() {
        ReloadPolicy policy = new ReloadPolicy();
        for (int outage = 0; outage < 2; outage++) {
            assertEquals(2000, policy.nextDelayMillis());
            policy.retryStarted();
            assertEquals(4000, policy.nextDelayMillis());
            policy.retryStarted();
            assertEquals(8000, policy.nextDelayMillis());
            policy.retryStarted();
            assertTrue(policy.requestPortalReload());
            policy.reset();
            assertFalse(policy.requestPortalReload());
        }
    }

    @Test public void disablingAfterThirdFailurePreventsPortalReload() {
        ReloadPolicy policy = new ReloadPolicy();
        for (int i = 0; i < 3; i++) policy.retryStarted();
        policy.cycle();
        policy.cycle();
        assertEquals(-1, policy.nextDelayMillis());
        assertFalse(policy.requestPortalReload());
    }

    @Test public void cancelledTimersDoNotConsumeRetryBudget() {
        ReloadPolicy policy = new ReloadPolicy();
        for (int i = 0; i < 5; i++) assertEquals(2000, policy.nextDelayMillis());
        assertEquals(0, policy.attempts());
        assertFalse(policy.requestPortalReload());
        policy.retryStarted();
        assertEquals(4000, policy.nextDelayMillis());
    }
}
