package me.ryanhamshire.GriefPrevention.catcrafttrust;

import me.ryanhamshire.GriefPrevention.ClaimPermission;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TemporaryTrustRecordTest
{
    private static final NativeTrustState NONE = new NativeTrustState(null, false, false);
    private static final NativeTrustState ACCESS = new NativeTrustState(ClaimPermission.Access, false, false);

    @Test
    void storesImmutableRecordAndBuildsCanonicalKey()
    {
        TemporaryTrustRecord record = new TemporaryTrustRecord(
                0L,
                "  Player  ",
                CatCraftTrustKind.BUILD,
                TrustDimension.PERMISSION,
                NONE,
                ACCESS,
                1_700_000_000_000L,
                1L,
                UUID.fromString("16f54277-9f89-4d94-9f78-c876845219d7"));

        assertEquals("player", record.target());
        assertEquals("0|PERMISSION|player", record.key());
        assertEquals(CatCraftTrustKind.BUILD, record.appliedKind());
        assertEquals(TrustDimension.PERMISSION, record.dimension());
    }

    @Test
    void acceptsManagerRecordsOnlyForManageKind()
    {
        TemporaryTrustRecord record = new TemporaryTrustRecord(
                42L, "Player", CatCraftTrustKind.MANAGE, TrustDimension.MANAGER,
                NONE, new NativeTrustState(null, true, false), 0L, 1L, null);

        assertEquals("42|MANAGER|player", record.key());
    }

    @Test
    void rejectsInvalidIdentityAndLifetimeFields()
    {
        assertThrows(IllegalArgumentException.class, () -> record(-1L, "Player", 1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> record(1L, "   ", 1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> record(1L, "x".repeat(257), 1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> record(1L, "Player", 0L, 0L));
        assertThrows(IllegalArgumentException.class, () -> record(1L, "Player", 1L, -1L));
    }

    @Test
    void rejectsKindAndDimensionMismatches()
    {
        assertThrows(IllegalArgumentException.class, () -> new TemporaryTrustRecord(
                1L, "Player", CatCraftTrustKind.MANAGE, TrustDimension.PERMISSION,
                NONE, NONE, 0L, 1L, null));
        assertThrows(IllegalArgumentException.class, () -> new TemporaryTrustRecord(
                1L, "Player", CatCraftTrustKind.FULL, TrustDimension.MANAGER,
                NONE, NONE, 0L, 1L, null));
    }

    @Test
    void rejectsStateChangesOutsideTheSelectedDimension()
    {
        assertThrows(IllegalArgumentException.class, () -> new TemporaryTrustRecord(
                1L, "Player", CatCraftTrustKind.FULL, TrustDimension.PERMISSION,
                NONE, new NativeTrustState(null, true, false), 0L, 1L, null));
        assertThrows(IllegalArgumentException.class, () -> new TemporaryTrustRecord(
                1L, "Player", CatCraftTrustKind.MANAGE, TrustDimension.MANAGER,
                NONE, new NativeTrustState(ClaimPermission.Access, true, false), 0L, 1L, null));
    }

    private static TemporaryTrustRecord record(long claimId, String target, long revision, long expiresAtMillis)
    {
        return new TemporaryTrustRecord(
                claimId, target, CatCraftTrustKind.FULL, TrustDimension.PERMISSION,
                NONE, ACCESS, expiresAtMillis, revision, null);
    }
}
