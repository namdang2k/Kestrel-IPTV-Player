package com.stbemu.host;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackInterruptionTest {
    @Test public void longAdDoesNotConsumeRecoveryDeadline() {
        PlaybackInterruption clock = new PlaybackInterruption();
        long recoveryStarted = clock.activeTime(1000);
        clock.setPaused(true, 6000);
        assertEquals(5000, clock.activeTime(66000) - recoveryStarted);
        clock.setPaused(false, 66000);
        assertEquals(6000, clock.activeTime(67000) - recoveryStarted);
    }

    @Test public void overlappingAdAndBackgroundCallbacksDoNotRestartPause() {
        PlaybackInterruption clock = new PlaybackInterruption();
        clock.setPaused(true, 1000); // ad begins
        clock.setPaused(true, 2000); // Activity stops
        clock.setPaused(true, 61000); // ad closes, Activity still stopped
        assertEquals(1000, clock.activeTime(62000));
        clock.setPaused(false, 63000); // foreground again
        clock.setPaused(false, 64000); // duplicate callback
        assertEquals(3000, clock.activeTime(65000));
    }

    @Test public void multipleAdsPreserveElapsedRecoveryBudget() {
        PlaybackInterruption clock = new PlaybackInterruption();
        clock.setPaused(true, 10000);
        clock.setPaused(false, 40000);
        clock.setPaused(true, 45000);
        clock.setPaused(false, 75000);
        assertEquals(20000, clock.activeTime(80000));
    }
}
