package com.stbemu.host;

import org.junit.Test;
import static org.junit.Assert.*;

public class DecoderRetryBudgetTest {
    @Test public void failedDecoderIsNotRetriedAndSwitchesAreBounded() {
        DecoderRetryBudget budget = new DecoderRetryBudget();
        assertTrue(budget.exclude("hardware.hevc"));
        assertFalse(budget.allows("hardware.hevc"));
        assertTrue(budget.allows("software.hevc"));
        assertFalse(budget.exclude("hardware.hevc"));
        assertTrue(budget.exclude("software.hevc"));
        assertFalse(budget.exclude("another.hevc"));
        assertEquals(2, budget.attempts());
    }

    @Test public void unknownDecoderCannotStartARecoveryLoop() {
        DecoderRetryBudget budget = new DecoderRetryBudget();
        assertFalse(budget.exclude(null));
        assertFalse(budget.exclude(""));
        assertEquals(0, budget.attempts());
    }

    @Test public void newPlaybackGetsFreshBudget() {
        DecoderRetryBudget budget = new DecoderRetryBudget();
        budget.exclude("hardware.hevc");
        budget.reset();
        assertTrue(budget.allows("hardware.hevc"));
        assertTrue(budget.exclude("hardware.hevc"));
    }
}
