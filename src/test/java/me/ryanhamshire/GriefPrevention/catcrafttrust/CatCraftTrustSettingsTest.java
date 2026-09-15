package me.ryanhamshire.GriefPrevention.catcrafttrust;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CatCraftTrustSettingsTest
{
    @Test
    void clampsEveryExternallyConfiguredBound()
    {
        CatCraftTrustSettings low = CatCraftTrustSettings.bounded(true, 0, 0, 0, 0);
        assertEquals(Duration.ofDays(1), low.maximumDuration());
        assertEquals(100, low.maximumRecords());
        assertEquals(1, low.maximumExpirationsPerTick());
        assertEquals(1, low.denialMessageCooldownSeconds());

        CatCraftTrustSettings high = CatCraftTrustSettings.bounded(true,
                Long.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(Duration.ofDays(3650), high.maximumDuration());
        assertEquals(100_000, high.maximumRecords());
        assertEquals(1_000, high.maximumExpirationsPerTick());
        assertEquals(30, high.denialMessageCooldownSeconds());
    }
}
