package me.ryanhamshire.GriefPrevention.catcrafttrust;

import com.griefprevention.test.ServerMocks;
import org.bukkit.Bukkit;
import org.bukkit.event.inventory.InventoryType;

/**
 * Since the 26.2 API, InventoryType's constants load from the server's registries on first use. Tests that
 * replace Bukkit with mockStatic would otherwise fail that one-time initialization, so load it against a
 * mocked server first.
 */
final class BukkitRegistryBootstrap
{

    static synchronized void initialize()
    {
        if (Bukkit.getServer() != null)
        {
            InventoryType.values();
            return;
        }

        Bukkit.setServer(ServerMocks.newServer());
        try
        {
            InventoryType.values();
        }
        finally
        {
            ServerMocks.unsetBukkitServer();
        }
    }

    private BukkitRegistryBootstrap() {}

}
