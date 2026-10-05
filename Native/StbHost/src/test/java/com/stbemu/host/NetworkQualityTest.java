package com.stbemu.host;

import org.junit.Test;

import static org.junit.Assert.*;

public class NetworkQualityTest {
    @Test public void headroomSetsTier() {
        NetworkQuality q = new NetworkQuality();
        assertEquals(NetworkQuality.Tier.GOOD, q.tier(0, 30_000_000, 10_000_000));
        assertEquals(NetworkQuality.Tier.FAIR, q.tier(0, 20_000_000, 10_000_000));
        assertEquals(NetworkQuality.Tier.WEAK, q.tier(0, 12_000_000, 10_000_000));
        assertEquals(NetworkQuality.Tier.INSUFFICIENT, q.tier(0, 10_500_000, 10_000_000));
    }

    @Test public void unknownBitrateRatesByStallsOnly() {
        NetworkQuality q = new NetworkQuality();
        assertEquals(NetworkQuality.Tier.GOOD, q.tier(0, 1_000_000, 0));
        q.stallStarted(0);
        assertEquals(NetworkQuality.Tier.FAIR, q.tier(0, 1_000_000, 0));
        q.stallStarted(1);
        q.stallStarted(2);
        assertEquals(NetworkQuality.Tier.WEAK, q.tier(2, 1_000_000, 0));
    }

    @Test public void stallsExpireAfterFiveMinutes() {
        NetworkQuality q = new NetworkQuality();
        q.stallStarted(0);
        q.stallStarted(1000);
        assertEquals(2, q.stalls(NetworkQuality.WINDOW_MS));
        assertEquals(1, q.stalls(NetworkQuality.WINDOW_MS + 1));
        assertEquals(NetworkQuality.Tier.GOOD, q.tier(NetworkQuality.WINDOW_MS + 1001, 30_000_000, 10_000_000));
    }
}
