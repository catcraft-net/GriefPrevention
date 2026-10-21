package me.ryanhamshire.GriefPrevention.catcrafttrust;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import me.ryanhamshire.GriefPrevention.SubdivisionFixture;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Red 2026-10-20: /untrust inside a claim with subdivisions aborted with "claim N no longer exists". */
class SubdivisionUntrustRegressionTest
{
    @BeforeAll
    static void loadRegistries()
    {
        BukkitRegistryBootstrap.initialize();
    }

    @TempDir
    Path directory;

    @Test
    void untrustInsideClaimWithSubdivisionsIsNotBlocked() throws Exception
    {
        SubdivisionFixture fixture = new SubdivisionFixture();
        Claim parent = fixture.add(1L, 0, 63, null);
        Claim child = fixture.add(2L, 8, 15, parent);
        child.setPermission("shortveggie", ClaimPermission.Inventory);

        CatCraftTrustRuntime runtime = start(fixture.store);
        try
        {
            assertTrue(runtime.service().onExternalTargetRemoved(parent, "shortveggie"));
            runtime.service().completeExternalTargetRemoved(parent, "shortveggie");
        }
        finally
        {
            runtime.stop();
        }
    }

    @Test
    void untrustSkipsAChildThatIsNoLongerInTheDataStore() throws Exception
    {
        SubdivisionFixture fixture = new SubdivisionFixture();
        Claim parent = fixture.add(1L, 0, 63, null);
        fixture.attachUnregistered(3L, 16, 23, parent);

        CatCraftTrustRuntime runtime = start(fixture.store);
        try
        {
            assertTrue(runtime.service().onExternalTargetRemoved(parent, "shortveggie"));
        }
        finally
        {
            runtime.stop();
        }
    }

    private CatCraftTrustRuntime start(DataStore store) throws Exception
    {
        GriefPrevention plugin = mock(GriefPrevention.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("SubdivisionUntrustRegressionTest"));
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(server.getScheduler()).thenReturn(mock(BukkitScheduler.class));
        return CatCraftTrustRuntime.start(plugin, store,
                CatCraftTrustSettings.bounded(true, 30, 100, 100, 3),
                directory.resolve("state.properties"), directory.resolve("legacy.properties"));
    }
}
