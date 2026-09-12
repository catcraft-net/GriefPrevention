package me.ryanhamshire.GriefPrevention;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineUntrustResolverTest
{
    private static final UUID OLD_PLAYER = UUID.fromString("16f54277-9f89-4d94-9f78-c876845219d7");

    @Test
    void findsPlayerTrustedOnlyInSubdivision()
    {
        Claim parent = new Claim();
        Claim child = new Claim();
        child.setPermission(OLD_PLAYER.toString(), ClaimPermission.Access);
        parent.children.add(child);

        assertEquals(OLD_PLAYER, OfflineUntrustResolver.findUniqueTrustedUuid(
                "OldPlayer", List.of(parent), ignored -> "oldplayer"));
    }

    @Test
    void searchesEveryTrustLevel()
    {
        for (ClaimPermission permission : List.of(
                ClaimPermission.Build,
                ClaimPermission.Inventory,
                ClaimPermission.Access))
        {
            Claim claim = new Claim();
            claim.setPermission(OLD_PLAYER.toString(), permission);
            assertEquals(OLD_PLAYER, OfflineUntrustResolver.findUniqueTrustedUuid(
                    "OldPlayer", List.of(claim), ignored -> "OldPlayer"));
        }

        Claim managedClaim = new Claim();
        managedClaim.managers.add(OLD_PLAYER.toString());
        assertEquals(OLD_PLAYER, OfflineUntrustResolver.findUniqueTrustedUuid(
                "OldPlayer", List.of(managedClaim), ignored -> "OldPlayer"));
    }

    @Test
    void looksUpEachUuidOnlyOnceAcrossClaims()
    {
        Claim first = new Claim();
        first.setPermission(OLD_PLAYER.toString(), ClaimPermission.Access);
        Claim second = new Claim();
        second.setPermission(OLD_PLAYER.toString(), ClaimPermission.Build);
        AtomicInteger lookups = new AtomicInteger();

        UUID result = OfflineUntrustResolver.findUniqueTrustedUuid(
                "OldPlayer", List.of(first, second), ignored -> {
                    lookups.incrementAndGet();
                    return "OldPlayer";
                });

        assertEquals(OLD_PLAYER, result);
        assertEquals(1, lookups.get());
    }

    @Test
    void refusesAmbiguousNameMatches()
    {
        UUID duplicateName = UUID.fromString("85801356-29c3-4f1f-b363-3114641675b1");
        Claim claim = new Claim();
        claim.setPermission(OLD_PLAYER.toString(), ClaimPermission.Access);
        claim.setPermission(duplicateName.toString(), ClaimPermission.Access);

        assertNull(OfflineUntrustResolver.findUniqueTrustedUuid(
                "OldPlayer", List.of(claim), ignored -> "OldPlayer"));
    }

    @Test
    void ignoresMalformedAndNonPlayerTrustEntries()
    {
        Claim claim = new Claim();
        claim.setPermission("not-a-uuid", ClaimPermission.Access);
        claim.setPermission("[some.permission]", ClaimPermission.Build);
        claim.managers.add(null);

        assertNull(OfflineUntrustResolver.findUniqueTrustedUuid(
                "OldPlayer", List.of(claim), ignored -> "OldPlayer"));
    }

    @Test
    void rejectsBlankSearches()
    {
        Claim claim = new Claim();
        claim.setPermission(OLD_PLAYER.toString(), ClaimPermission.Access);

        assertNull(OfflineUntrustResolver.findUniqueTrustedUuid(null, List.of(claim), ignored -> "OldPlayer"));
        assertNull(OfflineUntrustResolver.findUniqueTrustedUuid(" ", List.of(claim), ignored -> "OldPlayer"));
    }

    @Test
    void onlyFallsBackForPlayerNames()
    {
        assertFalse(OfflineUntrustResolver.shouldSearchTrustEntries(null));
        assertFalse(OfflineUntrustResolver.shouldSearchTrustEntries("public"));
        assertFalse(OfflineUntrustResolver.shouldSearchTrustEntries("some.permission"));
        assertTrue(OfflineUntrustResolver.shouldSearchTrustEntries("OldPlayer"));
    }
}
