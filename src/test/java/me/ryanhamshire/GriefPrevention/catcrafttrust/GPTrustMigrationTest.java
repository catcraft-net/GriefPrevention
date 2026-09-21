package me.ryanhamshire.GriefPrevention.catcrafttrust;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.DataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GPTrustMigrationTest
{
    private static final UUID OWNER = UUID.fromString("16f54277-9f89-4d94-9f78-c876845219d7");
    private static final String TARGET = "85801356-29c3-4f1f-b363-3114641675b1";

    @TempDir
    Path directory;

    @Test
    void importsCompatibleBuildMarkerAndRenamesLegacyFile() throws Exception
    {
        Path legacy = directory.resolve("temporary-trust.properties");
        Path integrated = directory.resolve("integrated.properties");
        writeLegacy(legacy, legacyRecord("BUILD", "PERMISSION"));
        DataStore dataStore = mock(DataStore.class);
        Claim claim = claim(ClaimPermission.Access);
        when(dataStore.getClaim(42L)).thenReturn(claim);
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(integrated, 10);

        int imported = GPTrustMigration.importIfPresent(legacy, store, dataStore, 10);

        assertEquals(1, imported);
        assertFalse(Files.exists(legacy));
        assertTrue(Files.isRegularFile(legacy.resolveSibling("temporary-trust.properties.migrated")));
        CatCraftTrustStateStore loaded = new CatCraftTrustStateStore(integrated, 10);
        loaded.load();
        TemporaryTrustRecord record = loaded.values().getFirst();
        assertEquals(CatCraftTrustKind.BUILD, record.appliedKind());
        assertEquals(new NativeTrustState(ClaimPermission.Access, false, true), record.expectedState());
    }

    @Test
    void skipsRecordWhenNativeTrustHasAlreadyChanged() throws Exception
    {
        Path legacy = directory.resolve("stale.properties");
        Path integrated = directory.resolve("stale-integrated.properties");
        writeLegacy(legacy, legacyRecord("BUILD", "PERMISSION"));
        DataStore dataStore = mock(DataStore.class);
        Claim changedClaim = claim(ClaimPermission.Inventory);
        when(dataStore.getClaim(42L)).thenReturn(changedClaim);
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(integrated, 10);

        assertEquals(0, GPTrustMigration.importIfPresent(legacy, store, dataStore, 10));
        CatCraftTrustStateStore loaded = new CatCraftTrustStateStore(integrated, 10);
        loaded.load();
        assertTrue(loaded.values().isEmpty());
    }

    @Test
    void malformedLegacyStateFailsWithoutRenamingOrCreatingDestination() throws Exception
    {
        Path legacy = directory.resolve("broken.properties");
        Path integrated = directory.resolve("broken-integrated.properties");
        Files.writeString(legacy, "version=2\ncount=1\nrecord.0=broken\n");

        assertThrows(Exception.class, () -> GPTrustMigration.importIfPresent(
                legacy, new CatCraftTrustStateStore(integrated, 10), mock(DataStore.class), 10));
        assertTrue(Files.isRegularFile(legacy));
        assertFalse(Files.exists(integrated));
    }

    private static Claim claim(ClaimPermission permission)
    {
        Claim claim = mock(Claim.class);
        claim.managers = new ArrayList<>();
        when(claim.getOwnerID()).thenReturn(OWNER);
        when(claim.getPermission(TARGET)).thenReturn(permission);
        return claim;
    }

    private static void writeLegacy(Path path, String record) throws Exception
    {
        Properties properties = new Properties();
        properties.setProperty("version", "2");
        properties.setProperty("count", "1");
        properties.setProperty("record.0", record);
        try (java.io.OutputStream output = Files.newOutputStream(path))
        {
            properties.store(output, "test");
        }
    }

    private static String legacyRecord(String kind, String dimension)
    {
        return String.join("|", "42", b64(TARGET), kind, dimension, "-", "false", "false",
                "1700000100000", "7", b64(OWNER.toString()));
    }

    private static String b64(String text)
    {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
