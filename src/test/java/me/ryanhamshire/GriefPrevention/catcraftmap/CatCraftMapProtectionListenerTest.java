package me.ryanhamshire.GriefPrevention.catcraftmap;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatCraftMapProtectionListenerTest
{
    private static final String OWNER_ONLY_MESSAGE = ChatColor.AQUA + "[CatCraft] "
            + ChatColor.RED + "You can only fill a map while standing inside your own claim.";
    private static final String TRADEMARK_MESSAGE = ChatColor.AQUA + "[CatCraft] "
            + ChatColor.YELLOW + "Use /trademark add while holding this map to prevent copies.";

    private final UUID playerId = UUID.randomUUID();
    private final UUID otherOwnerId = UUID.randomUUID();
    private final List<Runnable> scheduled = new ArrayList<>();

    private GriefPrevention plugin;
    private DataStore dataStore;
    private Player player;
    private Location location;
    private CatCraftMapProtectionListener listener;

    @BeforeEach
    void setUp()
    {
        plugin = mock(GriefPrevention.class);
        dataStore = mock(DataStore.class);
        player = mock(Player.class);
        World world = mock(World.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        location = new Location(world, 10, 64, 10);

        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getLocation()).thenReturn(location);
        when(player.isOnline()).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation ->
        {
            scheduled.add(invocation.getArgument(1));
            return mock(BukkitTask.class);
        });

        listener = new CatCraftMapProtectionListener(plugin, dataStore);
    }

    @Test
    void nonOwnerCannotFillMapEvenWithClaimBypass()
    {
        Claim claim = claimOwnedBy(otherOwnerId);
        when(dataStore.getClaimAt(location, false, null)).thenReturn(claim);
        when(player.hasPermission("griefprevention.ignoreclaims")).thenReturn(true);
        PlayerInteractEvent event = mapEvent(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, Material.MAP);

        listener.onMapFillAttempt(event);

        assertEquals(Event.Result.DENY, event.useItemInHand());
        verify(player).sendMessage(OWNER_ONLY_MESSAGE);
    }

    @Test
    void wildernessMapFillIsDenied()
    {
        PlayerInteractEvent event = mapEvent(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, Material.MAP);

        listener.onMapFillAttempt(event);

        assertEquals(Event.Result.DENY, event.useItemInHand());
        verify(player).sendMessage(OWNER_ONLY_MESSAGE);
    }

    @Test
    void administrativeClaimMapFillIsDenied()
    {
        Claim claim = claimOwnedBy(null);
        when(dataStore.getClaimAt(location, false, null)).thenReturn(claim);
        PlayerInteractEvent event = mapEvent(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, Material.MAP);

        listener.onMapFillAttempt(event);

        assertEquals(Event.Result.DENY, event.useItemInHand());
        verify(player).sendMessage(OWNER_ONLY_MESSAGE);
    }

    @ParameterizedTest
    @MethodSource("mapInteractions")
    void ownerCanFillMapFromEitherHandInAirOrAgainstBlock(Action action, EquipmentSlot hand)
    {
        Claim claim = claimOwnedBy(playerId);
        when(dataStore.getClaimAt(location, false, null)).thenReturn(claim);
        PlayerInteractEvent event = mapEvent(action, hand, Material.MAP);

        listener.onMapFillAttempt(event);

        assertEquals(Event.Result.DEFAULT, event.useItemInHand());
        verify(player, never()).sendMessage(OWNER_ONLY_MESSAGE);
    }

    @Test
    void existingFilledMapsAreUnaffected()
    {
        PlayerInteractEvent event = mapEvent(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, Material.FILLED_MAP);

        listener.onMapFillAttempt(event);

        assertEquals(Event.Result.DEFAULT, event.useItemInHand());
        verify(dataStore, never()).getClaimAt(any(Location.class), eq(false), any());
    }

    @Test
    void claimLookupFailureDeniesMapFill()
    {
        when(dataStore.getClaimAt(location, false, null)).thenThrow(new IllegalStateException("lookup failed"));
        PlayerInteractEvent event = mapEvent(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, Material.MAP);

        listener.onMapFillAttempt(event);

        assertEquals(Event.Result.DENY, event.useItemInHand());
        verify(player).sendMessage(OWNER_ONLY_MESSAGE);
    }

    @Test
    void reminderIsSentOnNextTickOnlyAfterMapUseStatisticIncreases()
    {
        Claim claim = claimOwnedBy(playerId);
        when(dataStore.getClaimAt(location, false, null)).thenReturn(claim);
        when(player.getStatistic(Statistic.USE_ITEM, Material.MAP)).thenReturn(4, 5);
        PlayerInteractEvent event = mapEvent(Action.RIGHT_CLICK_AIR, EquipmentSlot.OFF_HAND, Material.MAP);

        listener.onMapFillAttempt(event);
        listener.onMapFillComplete(event);

        verify(player, never()).sendMessage(TRADEMARK_MESSAGE);
        assertEquals(1, scheduled.size());
        scheduled.getFirst().run();
        verify(player).sendMessage(TRADEMARK_MESSAGE);
    }

    @Test
    void survivalFullInventoryMapFillStillTriggersReminder()
    {
        Claim claim = claimOwnedBy(playerId);
        when(dataStore.getClaimAt(location, false, null)).thenReturn(claim);
        when(player.getStatistic(Statistic.USE_ITEM, Material.MAP)).thenReturn(12, 13);
        PlayerInteractEvent event = mapEvent(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, Material.MAP);

        listener.onMapFillAttempt(event);
        listener.onMapFillComplete(event);
        scheduled.getFirst().run();

        verify(player).sendMessage(TRADEMARK_MESSAGE);
    }

    @Test
    void creativeFullInventoryMapFillStillTriggersReminder()
    {
        Claim claim = claimOwnedBy(playerId);
        when(dataStore.getClaimAt(location, false, null)).thenReturn(claim);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        when(player.getStatistic(Statistic.USE_ITEM, Material.MAP)).thenReturn(20, 21);
        PlayerInteractEvent event = mapEvent(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, Material.MAP);

        listener.onMapFillAttempt(event);
        listener.onMapFillComplete(event);
        scheduled.getFirst().run();

        verify(player).sendMessage(TRADEMARK_MESSAGE);
    }

    @Test
    void creativeUnrelatedFilledMapPickupDoesNotTriggerReminder()
    {
        Claim claim = claimOwnedBy(playerId);
        when(dataStore.getClaimAt(location, false, null)).thenReturn(claim);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        when(player.getStatistic(Statistic.USE_ITEM, Material.MAP)).thenReturn(20, 20);
        PlayerInteractEvent event = mapEvent(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, Material.MAP);

        listener.onMapFillAttempt(event);
        listener.onMapFillComplete(event);
        scheduled.getFirst().run();

        verify(player, never()).sendMessage(TRADEMARK_MESSAGE);
    }

    @Test
    void reminderIsNotSentWhenNoFilledMapWasCreated()
    {
        Claim claim = claimOwnedBy(playerId);
        when(dataStore.getClaimAt(location, false, null)).thenReturn(claim);
        PlayerInteractEvent event = mapEvent(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, Material.MAP);

        listener.onMapFillAttempt(event);
        listener.onMapFillComplete(event);
        assertEquals(1, scheduled.size());
        scheduled.getFirst().run();

        verify(player, never()).sendMessage(TRADEMARK_MESSAGE);
    }

    @Test
    void deniedMapFillDoesNotScheduleReminder()
    {
        Claim claim = claimOwnedBy(otherOwnerId);
        when(dataStore.getClaimAt(location, false, null)).thenReturn(claim);
        PlayerInteractEvent event = mapEvent(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, Material.MAP);

        listener.onMapFillAttempt(event);
        listener.onMapFillComplete(event);

        assertTrue(scheduled.isEmpty());
        verify(player, never()).sendMessage(TRADEMARK_MESSAGE);
    }

    private Claim claimOwnedBy(UUID ownerId)
    {
        Claim claim = mock(Claim.class);
        when(claim.getOwnerID()).thenReturn(ownerId);
        return claim;
    }

    private PlayerInteractEvent mapEvent(Action action, EquipmentSlot hand, Material material)
    {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(material);
        Block block = action == Action.RIGHT_CLICK_BLOCK ? mock(Block.class) : null;
        return new PlayerInteractEvent(player, action, item, block, BlockFace.UP, hand);
    }

    private static Stream<Arguments> mapInteractions()
    {
        return Stream.of(
                Arguments.of(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND),
                Arguments.of(Action.RIGHT_CLICK_AIR, EquipmentSlot.OFF_HAND),
                Arguments.of(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND),
                Arguments.of(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.OFF_HAND));
    }
}
