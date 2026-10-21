package me.ryanhamshire.GriefPrevention;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.UUID;

import static org.mockito.Mockito.mock;

/**
 * A real DataStore with in-memory storage, for tests that need GriefPrevention's own claim bookkeeping
 * instead of a mocked {@link DataStore#getClaim(long)}.
 */
public final class SubdivisionFixture
{
    private static final World WORLD = mock(World.class);

    public final DataStore store = new StubDataStore();

    /** Adds a top-level claim, or a subdivision when {@code parent} is set, the way the loader and /claim do. */
    public Claim add(long id, int min, int max, Claim parent)
    {
        Claim claim = new Claim(new Location(WORLD, min, 0, min), new Location(WORLD, max, 255, max),
                null, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), id);
        claim.parent = parent;
        store.addClaim(claim, false);
        return claim;
    }

    /** Links a child to its parent without telling the DataStore, like a stale in-memory reference. */
    public Claim attachUnregistered(long id, int min, int max, Claim parent)
    {
        Claim claim = new Claim(new Location(WORLD, min, 0, min), new Location(WORLD, max, 255, max),
                null, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), id);
        claim.parent = parent;
        parent.children.add(claim);
        return claim;
    }

    public void delete(Claim claim)
    {
        store.deleteClaim(claim, false, false);
    }

    private static final class StubDataStore extends DataStore
    {
        @Override int getSchemaVersionFromStorage() { return latestSchemaVersion; }
        @Override void updateSchemaVersionInStorage(int versionToSet) { }
        @Override void saveGroupBonusBlocks(String groupName, int amount) { }
        @Override void writeClaimToStorage(Claim claim) { }
        @Override void incrementNextClaimID() { nextClaimID++; }
        @Override PlayerData getPlayerDataFromStorage(UUID playerID) { return new PlayerData(); }
        @Override void deleteClaimFromSecondaryStorage(Claim claim) { }
        @Override void overrideSavePlayerData(UUID playerID, PlayerData playerData) { }
        @Override void close() { }
    }
}
