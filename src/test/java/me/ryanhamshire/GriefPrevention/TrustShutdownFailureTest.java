package me.ryanhamshire.GriefPrevention;

import me.ryanhamshire.GriefPrevention.catcrafttrust.CatCraftTrustRuntime;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrustShutdownFailureTest
{
    @Test
    void uncheckedTrustShutdownFailureStillClosesNativeDataStoreAndFlushesLogs() throws Exception
    {
        GriefPrevention plugin = mock(GriefPrevention.class);
        Server server = mock(Server.class);
        CatCraftTrustRuntime runtime = mock(CatCraftTrustRuntime.class);
        plugin.dataStore = mock(DataStore.class);
        plugin.customLogger = mock(CustomLogger.class);
        var field = GriefPrevention.class.getDeclaredField("catCraftTrustRuntime");
        field.setAccessible(true);
        field.set(plugin, runtime);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TrustShutdownFailureTest"));
        doReturn(List.of()).when(server).getOnlinePlayers();
        doThrow(new IllegalStateException("listener failure")).when(runtime).stop();
        doCallRealMethod().when(plugin).onDisable();
        try (var ignored = mockStatic(GriefPrevention.class))
        {
            assertDoesNotThrow(plugin::onDisable);
            verify(plugin.dataStore).close();
            verify(plugin.customLogger).WriteEntries();
        }
    }
}
