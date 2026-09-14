package me.ryanhamshire.GriefPrevention.catcrafttrust;

import me.ryanhamshire.GriefPrevention.ClaimPermission;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatCraftTrustStateStoreTest
{
    private static final UUID OWNER = UUID.fromString("16f54277-9f89-4d94-9f78-c876845219d7");
    private static final UUID TARGET = UUID.fromString("85801356-29c3-4f1f-b363-3114641675b1");
    private static final NativeTrustState NONE = new NativeTrustState(null, false, false);
    private static final NativeTrustState ACCESS = new NativeTrustState(ClaimPermission.Access, false, false);
    private static final NativeTrustState SAFE_BUILD = new NativeTrustState(ClaimPermission.Access, false, true);

    @TempDir
    Path directory;

    @Test
    void roundTripsRecordsAndIndexesCaseInsensitiveTargets() throws Exception
    {
        Path file = directory.resolve("temporary-trust.properties");
        CatCraftTrustStateStore first = new CatCraftTrustStateStore(file, 10);
        TemporaryTrustRecord record = record(42L, TARGET.toString().toUpperCase(), 1_700_000_000_000L, 1L);

        first.put(record);
        first.save();

        CatCraftTrustStateStore second = new CatCraftTrustStateStore(file, 10);
        second.load();

        assertEquals(Optional.of(record), second.get(record.key()));
        assertEquals(List.of(record), second.forClaim(42L));
        assertEquals(List.of(record), second.values());
        assertEquals(record, second.findSafeBuild(42L, TARGET, null,
                1_699_999_999_999L, OWNER).orElseThrow());
    }

    @Test
    void findsPublicAndPermissionNodeSafeBuildTargets()
    {
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(directory.resolve("state"), 10);
        store.put(record(42L, "public", 0L, 1L));
        store.put(record(42L, "[catcraft.builders]", 0L, 2L));

        Player player = mock(Player.class);
        when(player.hasPermission("catcraft.builders")).thenReturn(true);

        assertTrue(store.findSafeBuild(42L, null, null, System.currentTimeMillis(), OWNER).isPresent());
        assertTrue(store.findSafeBuild(42L, null, player, System.currentTimeMillis(), OWNER).isPresent());
    }

    @Test
    void replacesExistingKeysButRejectsNewRecordsAfterConfiguredBound()
    {
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(directory.resolve("state"), 1);
        TemporaryTrustRecord first = record(42L, "target", 0L, 1L);
        TemporaryTrustRecord replacement = record(42L, "TARGET", 0L, 2L);
        store.put(first);
        store.put(replacement);

        assertEquals(1, store.size());
        assertEquals(Optional.of(replacement), store.get(first.key()));
        assertThrows(IllegalStateException.class, () -> store.put(record(43L, "other", 0L, 3L)));
    }

    @Test
    void ordersExpirationQueueAndRemovesOnlyMatchingRevision()
    {
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(directory.resolve("state"), 10);
        TemporaryTrustRecord later = record(42L, "later", 200L, 2L);
        TemporaryTrustRecord sooner = record(42L, "sooner", 100L, 1L);
        store.put(later);
        store.put(sooner);

        assertEquals(sooner, store.nextExpiring().orElseThrow());
        assertTrue(store.removeIfRevision(sooner.key(), later.revision()).isEmpty());
        assertEquals(Optional.of(sooner), store.removeIfRevision(sooner.key(), sooner.revision()));
        assertEquals(later, store.nextExpiring().orElseThrow());
    }

    @Test
    void recoversFromBackupAndRejectsWhenBothCopiesAreCorrupt() throws Exception
    {
        Path file = directory.resolve("temporary-trust.properties");
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(file, 10);
        store.put(record(42L, "first", 0L, 1L));
        store.save();
        store.put(record(42L, "second", 0L, 2L));
        store.save();

        Files.writeString(file, "not a properties file");
        CatCraftTrustStateStore recovered = new CatCraftTrustStateStore(file, 10);
        recovered.load();
        assertEquals(List.of(record(42L, "first", 0L, 1L)), recovered.forClaim(42L));

        Files.writeString(file.resolveSibling("temporary-trust.properties.bak"), "also corrupt");
        assertThrows(IOException.class, () -> new CatCraftTrustStateStore(file, 10).load());
    }

    @Test
    void leavesNoTemporaryFileAfterAtomicSave() throws Exception
    {
        Path file = directory.resolve("temporary-trust.properties");
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(file, 10);
        store.put(record(42L, "target", 0L, 1L));
        store.save();

        assertTrue(Files.isRegularFile(file));
        assertFalse(Files.exists(file.resolveSibling("temporary-trust.properties.tmp")));
    }

    private static TemporaryTrustRecord record(long claimId, String target, long expiresAtMillis, long revision)
    {
        return new TemporaryTrustRecord(claimId, target, CatCraftTrustKind.BUILD,
                TrustDimension.PERMISSION, NONE, SAFE_BUILD, expiresAtMillis, revision, OWNER);
    }
}
