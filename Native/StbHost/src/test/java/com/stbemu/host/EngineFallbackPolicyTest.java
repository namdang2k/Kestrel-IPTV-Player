package com.stbemu.host;

import org.junit.Test;
import static org.junit.Assert.*;

public class EngineFallbackPolicyTest {
    @Test public void triesMpvOnceThenResetsForNextChannel() {
        EngineFallbackPolicy policy = new EngineFallbackPolicy();
        assertTrue(policy.claim(4001));
        assertFalse(policy.claim(4003));
        assertFalse(policy.claim(3003));
        policy.reset();
        assertTrue(policy.claim(3003));
    }

    @Test public void networkDrmAndLiveWindowErrorsDoNotSpendFallback() {
        EngineFallbackPolicy policy = new EngineFallbackPolicy();
        for (int code : new int[]{1002, 1003, 2001, 2002, 2006, 6001, 6006, 1000})
            assertFalse("code=" + code, policy.claim(code));
        assertTrue(policy.claim(4005));
    }

    @Test public void badHttpStatusTriesMpvOnlyOnceForTheResolvedItem() {
        EngineFallbackPolicy policy = new EngineFallbackPolicy();
        assertTrue(policy.claim(2004));
        assertFalse(policy.claim(2004));
        assertFalse(policy.claim(4003));
        policy.reset();
        assertTrue(policy.claim(2004));
    }

    @Test public void allParsingAndDecoderFailuresAreEligible() {
        for (int code : new int[]{3001, 3002, 3003, 3004, 4001, 4002, 4003, 4004, 4005})
            assertTrue("code=" + code, new EngineFallbackPolicy().claim(code));
    }
}
