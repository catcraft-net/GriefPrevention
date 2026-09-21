package me.ryanhamshire.GriefPrevention.catcrafttrust;

import java.time.Duration;
import java.util.Objects;

public record CatCraftTrustSettings(boolean enabled,
                                    Duration maximumDuration,
                                    int maximumRecords,
                                    int maximumExpirationsPerTick,
                                    int denialMessageCooldownSeconds)
{
    public CatCraftTrustSettings
    {
        Objects.requireNonNull(maximumDuration, "maximumDuration");
        if (maximumDuration.isZero() || maximumDuration.isNegative())
            throw new IllegalArgumentException("maximum duration must be positive");
        if (maximumRecords <= 0) throw new IllegalArgumentException("maximum records must be positive");
        if (maximumExpirationsPerTick <= 0)
            throw new IllegalArgumentException("maximum expirations must be positive");
        if (denialMessageCooldownSeconds <= 0)
            throw new IllegalArgumentException("denial cooldown must be positive");
    }

    public static CatCraftTrustSettings bounded(boolean enabled, long maximumDays,
                                                 int maximumRecords,
                                                 int maximumExpirationsPerTick,
                                                 int denialCooldownSeconds)
    {
        long days = Math.max(1L, Math.min(3650L, maximumDays));
        int records = Math.max(100, Math.min(100_000, maximumRecords));
        int expirations = Math.max(1, Math.min(1_000, maximumExpirationsPerTick));
        int cooldown = Math.max(1, Math.min(30, denialCooldownSeconds));
        return new CatCraftTrustSettings(enabled, Duration.ofDays(days), records, expirations, cooldown);
    }
}
