package me.ryanhamshire.GriefPrevention.catcrafttrust;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record TemporaryTrustRecord(
        long claimId,
        String target,
        CatCraftTrustKind appliedKind,
        TrustDimension dimension,
        NativeTrustState previousState,
        NativeTrustState expectedState,
        long expiresAtMillis,
        long revision,
        @Nullable UUID ownerIdAtGrant)
{
    public static final int MAX_TARGET_LENGTH = 256;

    public TemporaryTrustRecord
    {
        if (claimId < 0) throw new IllegalArgumentException("claimId must be non-negative");
        if (target == null) throw new IllegalArgumentException("target must not be null");
        target = target.trim().toLowerCase(Locale.ROOT);
        if (target.isEmpty()) throw new IllegalArgumentException("target must not be blank");
        if (target.length() > MAX_TARGET_LENGTH) throw new IllegalArgumentException("target is too long");
        Objects.requireNonNull(appliedKind, "appliedKind");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(previousState, "previousState");
        Objects.requireNonNull(expectedState, "expectedState");
        TrustDimension expectedDimension = appliedKind == CatCraftTrustKind.MANAGE
                ? TrustDimension.MANAGER
                : TrustDimension.PERMISSION;
        if (dimension != expectedDimension)
        {
            throw new IllegalArgumentException("trust kind and dimension do not match");
        }
        if (dimension == TrustDimension.PERMISSION && previousState.manager() != expectedState.manager())
        {
            throw new IllegalArgumentException("permission records must preserve manager state");
        }
        if (dimension == TrustDimension.MANAGER
                && (previousState.permission() != expectedState.permission()
                || previousState.safeBuild() != expectedState.safeBuild()))
        {
            throw new IllegalArgumentException("manager records must preserve permission state");
        }
        if (expiresAtMillis < 0) throw new IllegalArgumentException("expiresAtMillis must be non-negative");
        if (revision <= 0) throw new IllegalArgumentException("revision must be positive");
    }

    public String key()
    {
        return claimId + "|" + dimension + "|" + target.toLowerCase(Locale.ROOT);
    }
}
