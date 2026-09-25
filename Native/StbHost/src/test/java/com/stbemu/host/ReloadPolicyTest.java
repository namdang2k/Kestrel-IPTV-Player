package com.stbemu.host;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReloadPolicyTest {
    @Test public void disabledByDefaultAndCycles() {
        ReloadPolicy policy = new ReloadPolicy();
        assertEquals(-1, policy.nextDelayMillis());
        policy.cycle(); assertEquals(ReloadPolicy.Mode.ALWAYS, policy.mode());
        policy.cycle(); assertEquals(ReloadPolicy.Mode.ONCE, policy.mode());
        policy.cycle(); assertEquals(ReloadPolicy.Mode.NONE, policy.mode());
    }
    @Test public void oneRetryPerStream() {
        ReloadPolicy policy = new ReloadPolicy(); policy.cycle(); policy.cycle();
        assertEquals(2000, policy.nextDelayMillis());
        assertEquals(-1, policy.nextDelayMillis());
        policy.reset();
        assertEquals(2000, policy.nextDelayMillis());
    }
    @Test public void continuousRetryBacksOffAndCapsDelay() {
        ReloadPolicy policy = new ReloadPolicy(); policy.cycle();
        assertEquals(2000, policy.nextDelayMillis());
        assertEquals(4000, policy.nextDelayMillis());
        assertEquals(8000, policy.nextDelayMillis());
        for (int i = 0; i < 10; i++) assertEquals(15000, policy.nextDelayMillis());
    }
}
