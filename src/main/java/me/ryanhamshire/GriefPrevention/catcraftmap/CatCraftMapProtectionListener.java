package me.ryanhamshire.GriefPrevention.catcraftmap;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import me.ryanhamshire.GriefPrevention.catcrafttrust.CatCraftMessages;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import java.util.logging.Level;

public final class CatCraftMapProtectionListener implements Listener
{
    private final GriefPrevention plugin;
    private final DataStore dataStore;

    public CatCraftMapProtectionListener(GriefPrevention plugin, DataStore dataStore)
    {
        this.plugin = plugin;
        this.dataStore = dataStore;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    void onMapFillAttempt(PlayerInteractEvent event)
    {
        if (!isMapFillInteraction(event) || event.useItemInHand() == Event.Result.DENY) return;

        Player player = event.getPlayer();
        Claim claim;
        try
        {
            claim = this.dataStore.getClaimAt(player.getLocation(), false, null);
        }
        catch (RuntimeException failure)
        {
            this.plugin.getLogger().log(Level.WARNING,
                    "Unable to check claim ownership while filling a map; denying the map fill.", failure);
            denyMapFill(event, player);
            return;
        }

        UUID ownerId = claim == null ? null : claim.getOwnerID();
        if (!player.getUniqueId().equals(ownerId))
        {
            denyMapFill(event, player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onMapFillComplete(PlayerInteractEvent event)
    {
        if (!isMapFillInteraction(event)
                || event.useItemInHand() == Event.Result.DENY)
        {
            return;
        }

        Player player = event.getPlayer();
        int filledMapsBefore = countFilledMaps(player);
        this.plugin.getServer().getScheduler().runTask(this.plugin, () ->
        {
            if (player.isOnline() && countFilledMaps(player) > filledMapsBefore)
            {
                sendMessage(player, CatCraftMessages.mapTrademarkReminder());
            }
        });
    }

    private static boolean isMapFillInteraction(PlayerInteractEvent event)
    {
        Action action = event.getAction();
        ItemStack item = event.getItem();
        return item != null
                && item.getType() == Material.MAP
                && (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK);
    }

    private static void denyMapFill(PlayerInteractEvent event, Player player)
    {
        event.setUseItemInHand(Event.Result.DENY);
        sendMessage(player, CatCraftMessages.mapFillOwnerOnly());
    }

    private static int countFilledMaps(Player player)
    {
        int total = 0;
        ItemStack[] contents = player.getInventory().getContents();
        if (contents == null) return total;

        for (ItemStack item : contents)
        {
            if (item != null && item.getType() == Material.FILLED_MAP)
            {
                total += item.getAmount();
            }
        }
        return total;
    }

    private static void sendMessage(Player player, String message)
    {
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }
}
