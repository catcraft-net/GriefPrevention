package me.ryanhamshire.GriefPrevention.catcrafttrust;

import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatCraftTrustRuntimeTest
{
    @TempDir
    Path directory;

    @Test
    void permanentBuildRecordsDoNotProduceTemporaryGrantRollbackWarning() throws Exception
    {
        GriefPrevention plugin = mock(GriefPrevention.class);
        DataStore dataStore = mock(DataStore.class);
        Server server = mock(Server.class);
        Logger logger = mock(Logger.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(logger);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(server.getScheduler()).thenReturn(mock(BukkitScheduler.class));
        me.ryanhamshire.GriefPrevention.Claim claim = mock(me.ryanhamshire.GriefPrevention.Claim.class);
        claim.managers = new java.util.ArrayList<>();
        when(claim.getID()).thenReturn(42L);
        when(dataStore.getClaim(42L)).thenReturn(claim);
        CatCraftTrustRuntime runtime = CatCraftTrustRuntime.start(plugin, dataStore,
                CatCraftTrustSettings.bounded(true, 30, 100, 100, 3),
                directory.resolve("warning.properties"), directory.resolve("missing.properties"));
        runtime.service().grant(java.util.List.of(claim), "public", CatCraftTrustKind.BUILD, null);
        runtime.stop();
        org.mockito.Mockito.verify(logger, org.mockito.Mockito.never()).warning(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void startsRegistersListenerAndStopsWithDurableEmptyState() throws Exception
    {
        GriefPrevention plugin = mock(GriefPrevention.class);
        DataStore dataStore = mock(DataStore.class);
        Server server = mock(Server.class);
        PluginManager pluginManager = mock(PluginManager.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CatCraftTrustRuntimeTest"));
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(server.getScheduler()).thenReturn(scheduler);
        Path state = directory.resolve("integrated.properties");
        Path legacy = directory.resolve("legacy.properties");

        CatCraftTrustRuntime runtime = CatCraftTrustRuntime.start(plugin, dataStore,
                CatCraftTrustSettings.bounded(true, 30, 100, 100, 3), state, legacy);

        assertTrue(runtime.service().isStarted());
        verify(pluginManager).registerEvents(any(ReadOnlyContainerListener.class), eq(plugin));
        runtime.stop();
        assertFalse(runtime.service().isStarted());
        assertTrue(Files.isRegularFile(state));
    }
}
