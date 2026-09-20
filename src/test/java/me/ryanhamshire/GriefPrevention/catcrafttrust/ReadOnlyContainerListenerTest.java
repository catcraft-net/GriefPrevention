package me.ryanhamshire.GriefPrevention.catcrafttrust;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Hopper;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadOnlyContainerListenerTest
{
    private final UUID playerId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final UUID worldId = UUID.randomUUID();
    private JavaPlugin plugin;
    private Server server;
    private BukkitScheduler scheduler;
    private Player player;
    private Claim claim;
    private DataStore dataStore;
    private World world;
    private Block block;
    private Chest state;
    private Inventory source;
    private Inventory playerInventory;
    private Inventory preview;
    private SafeBuildTrustProvider trusts;
    private ReadOnlyContainerListener listener;
    private GriefPrevention previousInstance;
    private MockedStatic<Bukkit> bukkit;
    private final List<Runnable> scheduled = new ArrayList<>();
    private final AtomicReference<InventoryHolder> previewHolder = new AtomicReference<>();

    @BeforeEach
    void setUp()
    {
        plugin = mock(JavaPlugin.class);
        server = mock(Server.class);
        scheduler = mock(BukkitScheduler.class);
        player = mock(Player.class);
        claim = mock(Claim.class);
        dataStore = mock(DataStore.class);
        world = mock(World.class);
        block = mock(Block.class);
        state = mock(Chest.class);
        source = mock(Inventory.class);
        playerInventory = mock(Inventory.class);
        preview = mock(Inventory.class);
        trusts = mock(SafeBuildTrustProvider.class);
        previousInstance = GriefPrevention.instance;
        GriefPrevention.instance = mock(GriefPrevention.class);
        GriefPrevention.instance.dataStore = dataStore;

        Location location = new Location(world, 10, 64, 10);
        when(world.getUID()).thenReturn(worldId);
        when(world.getBlockAt(10, 64, 10)).thenReturn(block);
        when(block.getType()).thenReturn(Material.CHEST);
        when(block.getWorld()).thenReturn(world);
        when(block.getLocation()).thenReturn(location);
        when(block.getState()).thenReturn(state);
        when(state.getBlock()).thenReturn(block);
        when(state.getType()).thenReturn(Material.CHEST);
        when(state.getInventory()).thenReturn(source);
        when(source.getSize()).thenReturn(9);
        when(source.getContents()).thenReturn(new ItemStack[9]);
        when(source.getHolder()).thenReturn(state);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getLocation()).thenReturn(location);
        when(player.isOnline()).thenReturn(true);
        when(claim.getID()).thenReturn(42L);
        when(claim.getOwnerID()).thenReturn(ownerId);
        when(dataStore.getClaimAt(any(Location.class), eq(true), isNull())).thenReturn(claim);
        when(trusts.isSafeBuilder(claim, playerId, player)).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getWorld(worldId)).thenReturn(world);
        when(server.getPlayer(playerId)).thenReturn(player);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            scheduled.add(invocation.getArgument(1));
            return mock(BukkitTask.class);
        });
        when(server.createInventory(any(InventoryHolder.class), anyInt(), anyString()))
                .thenAnswer(invocation -> {
                    previewHolder.set(invocation.getArgument(0));
                    when(preview.getHolder()).thenAnswer(ignored -> previewHolder.get());
                    when(preview.getSize()).thenReturn(9);
                    return preview;
                });
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(java.util.Set.of(player));
        listener = new ReadOnlyContainerListener(plugin, trusts);
    }

    @AfterEach
    void tearDown()
    {
        if (listener != null) listener.shutdown();
        GriefPrevention.instance = previousInstance;
        if (bukkit != null) bukkit.close();
    }

    @ParameterizedTest
    @EnumSource(value = InventoryType.class, names = {"WORKBENCH", "ANVIL", "ENCHANTING", "GRINDSTONE", "LOOM", "CARTOGRAPHY", "STONECUTTER", "SMITHING"})
    void safeBuilderCanOpenTransientWorkstations(InventoryType type)
    {
        Inventory workstation = mock(Inventory.class);
        when(workstation.getType()).thenReturn(type);
        InventoryOpenEvent event = new InventoryOpenEvent(view(workstation));
        listener.onInventoryOpen(event);
        assertFalse(event.isCancelled());
    }

    @Test
    void realStorageAccessFailsClosedWhenClaimLookupThrows()
    {
        when(dataStore.getClaimAt(any(Location.class), eq(true), isNull())).thenThrow(new IllegalStateException("lookup failed"));
        InventoryClickEvent event = new InventoryClickEvent(view(source),
                InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onInventoryClick(event);
        assertTrue(event.isCancelled());
    }

    @ParameterizedTest
    @EnumSource(ClickType.class)
    void realContainerClicksAreDeniedAfterPermissionIsLostIncludingBottomSlots(ClickType click)
    {
        when(trusts.isSafeBuilder(claim, playerId, player)).thenReturn(false);
        when(claim.checkPermission(eq(player), eq(me.ryanhamshire.GriefPrevention.ClaimPermission.Inventory), any()))
                .thenReturn(() -> "trust expired");
        InventoryView open = view(source);
        when(player.getOpenInventory()).thenReturn(open);
        InventoryClickEvent event = new InventoryClickEvent(open,
                InventoryType.SlotType.CONTAINER, 10, click, InventoryAction.UNKNOWN);
        listener.onInventoryClick(event);
        assertTrue(event.isCancelled());
        verify(player, never()).closeInventory();
        scheduled.removeFirst().run();
        verify(player).closeInventory();
    }

    @Test
    void realContainerDragIsDeniedAfterDowngradeToSafeBuild()
    {
        when(claim.checkPermission(eq(player), eq(me.ryanhamshire.GriefPrevention.ClaimPermission.Inventory), any()))
                .thenReturn(() -> "container trust revoked");
        ItemStack cursor = mock(ItemStack.class);
        InventoryDragEvent event = new InventoryDragEvent(view(source), cursor, cursor, true, java.util.Map.of(0, cursor));
        listener.onInventoryDrag(event);
        assertTrue(event.isCancelled());
    }

    @Test
    void realContainerRemainsUsableWithCurrentInventoryPermission()
    {
        InventoryClickEvent event = new InventoryClickEvent(view(source),
                InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onInventoryClick(event);
        assertFalse(event.isCancelled());
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void doubleChestChecksBothSidesForRevokedTrust()
    {
        org.bukkit.block.DoubleChest doubleChest = mock(org.bukkit.block.DoubleChest.class);
        Chest right = mock(Chest.class);
        Block rightBlock = mock(Block.class);
        Claim denied = mock(Claim.class);
        Location rightLocation = new Location(world, 20, 64, 20);
        when(source.getHolder()).thenReturn(doubleChest);
        when(doubleChest.getLeftSide()).thenReturn(state);
        when(doubleChest.getRightSide()).thenReturn(right);
        when(right.getBlock()).thenReturn(rightBlock);
        when(rightBlock.getLocation()).thenReturn(rightLocation);
        when(dataStore.getClaimAt(eq(rightLocation), eq(true), isNull())).thenReturn(denied);
        when(denied.checkPermission(eq(player), eq(me.ryanhamshire.GriefPrevention.ClaimPermission.Inventory), any()))
                .thenReturn(() -> "no right-side trust");
        InventoryClickEvent event = new InventoryClickEvent(view(source),
                InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onInventoryClick(event);
        assertTrue(event.isCancelled());
    }

    @Test
    void entityStorageChecksCurrentClaimTrust()
    {
        org.bukkit.entity.minecart.StorageMinecart cart = mock(org.bukkit.entity.minecart.StorageMinecart.class);
        when(source.getHolder()).thenReturn(cart);
        Location cartLocation = player.getLocation();
        when(cart.getLocation()).thenReturn(cartLocation);
        when(claim.checkPermission(eq(player), eq(me.ryanhamshire.GriefPrevention.ClaimPermission.Inventory), any()))
                .thenReturn(() -> "trust revoked");
        InventoryClickEvent event = new InventoryClickEvent(view(source),
                InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onInventoryClick(event);
        assertTrue(event.isCancelled());
    }

    @Test
    void blockInteractionCancelsLiveOpenAndOpensDetachedCloneNextTick()
    {
        ItemStack live = mock(ItemStack.class);
        ItemStack detached = mock(ItemStack.class);
        when(live.clone()).thenReturn(detached);
        when(source.getContents()).thenReturn(new ItemStack[]{live});
        PlayerInteractEvent event = interact();

        listener.onPlayerInteract(event);

        assertTrue(event.isCancelled());
        assertEquals(Event.Result.DENY, event.useInteractedBlock());
        assertEquals(Event.Result.DENY, event.useItemInHand());
        verify(player, never()).openInventory(any(Inventory.class));
        assertEquals(1, scheduled.size());

        scheduled.removeFirst().run();

        verify(player).openInventory(preview);
        verify(source, never()).setItem(anyInt(), any(ItemStack.class));
        verify(source, never()).setContents(any(ItemStack[].class));
    }

    @Test
    void closingPreviousViewDuringPreviewOpenDoesNotClearNewSession()
    {
        Inventory previous = mock(Inventory.class);
        InventoryView previousView = view(previous);
        InventoryView previewView = view(preview);
        when(player.openInventory(preview)).thenAnswer(invocation -> {
            listener.onInventoryClose(new InventoryCloseEvent(previousView));
            return previewView;
        });

        PlayerInteractEvent event = interact();
        listener.onPlayerInteract(event);
        scheduled.removeFirst().run();

        InventoryOpenEvent opened = new InventoryOpenEvent(previewView);
        listener.onInventoryOpen(opened);

        assertFalse(opened.isCancelled());
    }

    @Test
    void thirdPartyInventoryOpenIsCancelledAndUsesDetachedSnapshot()
    {
        InventoryOpenEvent event = new InventoryOpenEvent(view(source));

        listener.onInventoryOpen(event);

        assertTrue(event.isCancelled());
        assertEquals(1, scheduled.size());
    }

    @Test
    void automationContainerCanBeViewedButRemainsProtectedFromMutation()
    {
        Hopper hopper = mock(Hopper.class);
        Inventory hopperInventory = mock(Inventory.class);
        when(block.getType()).thenReturn(Material.HOPPER);
        when(block.getState()).thenReturn(hopper);
        when(hopper.getInventory()).thenReturn(hopperInventory);
        when(hopperInventory.getContents()).thenReturn(new ItemStack[5]);
        when(hopperInventory.getSize()).thenReturn(5);

        PlayerInteractEvent event = interact();
        listener.onPlayerInteract(event);

        assertTrue(event.isCancelled());
        assertEquals(1, scheduled.size());
    }

    @ParameterizedTest
    @EnumSource(ClickType.class)
    void everyClickTypeIsCancelledWithoutRewritingCursorOrSlots(ClickType click)
    {
        openPreview();
        InventoryClickEvent event = new InventoryClickEvent(
                view(preview), InventoryType.SlotType.CONTAINER, 0, click, InventoryAction.UNKNOWN);

        listener.onInventoryClick(event);

        assertTrue(event.isCancelled(), click.name());
        verify(preview, never()).setItem(anyInt(), any(ItemStack.class));
        verify(player, never()).setItemOnCursor(any(ItemStack.class));
        verify(player, never()).setItemInHand(any(ItemStack.class));
    }

    @Test
    void dragIsCancelledWithoutRewritingCursorOrSlots()
    {
        openPreview();
        ItemStack cursor = mock(ItemStack.class);
        InventoryDragEvent event = new InventoryDragEvent(
                view(preview), cursor, cursor, true, java.util.Map.of(0, cursor));

        listener.onInventoryDrag(event);

        assertTrue(event.isCancelled());
        verify(preview, never()).setItem(anyInt(), any(ItemStack.class));
    }

    @Test
    void staleTrustClosesPreviewBeforeAnyMutation()
    {
        openPreview();
        when(trusts.isSafeBuilder(claim, playerId, player)).thenReturn(false);
        InventoryClickEvent event = new InventoryClickEvent(
                view(preview), InventoryType.SlotType.CONTAINER, 0,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);

        listener.onInventoryClick(event);

        assertTrue(event.isCancelled());
        verify(player).closeInventory();
        verify(preview, never()).setItem(anyInt(), any(ItemStack.class));
    }

    @Test
    void changedBlockAndOwnerInvalidateTheSession()
    {
        openPreview();
        when(block.getType()).thenReturn(Material.STONE);
        InventoryClickEvent changedBlock = new InventoryClickEvent(
                view(preview), InventoryType.SlotType.CONTAINER, 0,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onInventoryClick(changedBlock);
        assertTrue(changedBlock.isCancelled());

        when(block.getType()).thenReturn(Material.CHEST);
        openPreview();
        when(claim.getOwnerID()).thenReturn(UUID.randomUUID());
        InventoryClickEvent changedOwner = new InventoryClickEvent(
                view(preview), InventoryType.SlotType.CONTAINER, 0,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onInventoryClick(changedOwner);
        assertTrue(changedOwner.isCancelled());
    }

    @Test
    void topInventoryMismatchDoesNotCancelAnUnrelatedView()
    {
        openPreview();
        Inventory other = mock(Inventory.class);
        InventoryClickEvent event = new InventoryClickEvent(
                view(other), InventoryType.SlotType.CONTAINER, 0,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);

        listener.onInventoryClick(event);

        assertFalse(event.isCancelled());
        verify(other, never()).setItem(anyInt(), any(ItemStack.class));
    }

    @Test
    void closeDisconnectAndKickRemoveSessionsAndPendingOpens()
    {
        PlayerInteractEvent open = interact();
        listener.onPlayerInteract(open);
        listener.onPlayerQuit(new PlayerQuitEvent(player, "quit"));
        assertTrue(scheduled.size() == 1);
        scheduled.removeFirst().run();
        clearInvocations(player);

        openPreview();
        listener.onInventoryClose(new InventoryCloseEvent(view(preview)));
        listener.onPlayerKick(new PlayerKickEvent(player, "kick", "leave"));
        InventoryClickEvent stale = new InventoryClickEvent(
                view(preview), InventoryType.SlotType.CONTAINER, 0,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onInventoryClick(stale);
        assertTrue(stale.isCancelled());
    }

    @Test
    void inventoryMinecartOpenAndBreakAreDeniedForSafeBuilder()
    {
        Inventory minecartInventory = mock(Inventory.class);
        InventoryHolder minecart = mock(InventoryHolder.class);
        when(minecartInventory.getHolder()).thenReturn(minecart);
        when(minecart.getInventory()).thenReturn(minecartInventory);
        when(minecartInventory.getType()).thenReturn(InventoryType.CHEST);
        InventoryOpenEvent open = new InventoryOpenEvent(view(minecartInventory));

        listener.onInventoryOpen(open);

        assertTrue(open.isCancelled());
        verify(player).sendMessage(anyString());

        Vehicle vehicle = mock(Vehicle.class,
                org.mockito.Mockito.withSettings().extraInterfaces(InventoryHolder.class));
        Location blockLocation = block.getLocation();
        when(vehicle.getLocation()).thenReturn(blockLocation);
        when(((InventoryHolder) vehicle).getInventory()).thenReturn(minecartInventory);
        VehicleDestroyEvent destroy = new VehicleDestroyEvent(vehicle, player);

        listener.onVehicleDestroy(destroy);

        assertTrue(destroy.isCancelled());
    }

    @Test
    void safeBuilderCannotPlaceStorageMinecartsFromEitherHandOnAnOrdinaryRail()
    {
        Block rail = mock(Block.class);
        BlockState railState = mock(BlockState.class);
        Location railLocation = block.getLocation();
        when(rail.getType()).thenReturn(Material.RAIL);
        when(rail.getLocation()).thenReturn(railLocation);
        when(rail.getState()).thenReturn(railState);

        for (Material material : List.of(Material.HOPPER_MINECART, Material.CHEST_MINECART))
        {
            ItemStack item = mock(ItemStack.class);
            when(item.getType()).thenReturn(material);
            for (EquipmentSlot hand : List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND))
            {
                PlayerInteractEvent event = new PlayerInteractEvent(
                        player, Action.RIGHT_CLICK_BLOCK, item, rail, BlockFace.UP, hand);
                listener.onPlayerInteract(event);
                assertTrue(event.isCancelled(), material + "/" + hand);
                assertEquals(Event.Result.DENY, event.useInteractedBlock());
                assertEquals(Event.Result.DENY, event.useItemInHand());
            }
        }

        verify(player, times(1)).sendMessage(anyString());
    }

    @Test
    void safeBuilderCannotEmptyBucketsOrIgniteBlocks()
    {
        PlayerBucketEmptyEvent bucket = new PlayerBucketEmptyEvent(
                player, block, block, BlockFace.UP, Material.WATER_BUCKET, null);
        listener.onPlayerBucketEmpty(bucket);
        assertTrue(bucket.isCancelled());

        BlockIgniteEvent ignite = new BlockIgniteEvent(
                block, BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, player);
        listener.onBlockIgnite(ignite);
        assertTrue(ignite.isCancelled());
    }

    @Test
    void safeBuilderCannotUseRedstoneControlsButCanUseOrdinaryDoors()
    {
        for (Material control : List.of(Material.LEVER, Material.STONE_BUTTON,
                Material.STONE_PRESSURE_PLATE))
        {
            when(block.getType()).thenReturn(control);
            Action action = control.name().endsWith("PRESSURE_PLATE")
                    ? Action.PHYSICAL : Action.RIGHT_CLICK_BLOCK;
            PlayerInteractEvent event = new PlayerInteractEvent(
                    player, action, null, block, BlockFace.UP, EquipmentSlot.HAND);
            listener.onPlayerInteract(event);
            assertTrue(event.isCancelled(), control.name());
        }

        when(block.getType()).thenReturn(Material.OAK_DOOR);
        when(block.getState()).thenReturn(mock(BlockState.class));
        PlayerInteractEvent door = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.UP, EquipmentSlot.HAND);
        listener.onPlayerInteract(door);
        assertFalse(door.isCancelled());
    }

    @Test
    void safeBuilderPausesBoundedAutomationAndFluidPathsInClaim()
    {
        when(trusts.hasAnySafeBuilders()).thenReturn(true);
        when(trusts.hasAnySafeBuilder(claim)).thenReturn(true);

        InventoryMoveItemEvent move = new InventoryMoveItemEvent(
                source, new ItemStack(Material.STONE), source, true);
        listener.onInventoryMove(move);
        assertTrue(move.isCancelled());

        Item dropped = mock(Item.class);
        Location droppedLocation = block.getLocation();
        when(dropped.getLocation()).thenReturn(droppedLocation);
        InventoryPickupItemEvent pickup = new InventoryPickupItemEvent(source, dropped);
        listener.onInventoryPickup(pickup);
        assertTrue(pickup.isCancelled());

        BlockDispenseEvent dispense = new BlockDispenseEvent(
                block, new ItemStack(Material.STONE), new org.bukkit.util.Vector());
        listener.onBlockDispense(dispense);
        assertTrue(dispense.isCancelled());

        BlockPistonExtendEvent piston = new BlockPistonExtendEvent(
                block, List.of(block), BlockFace.NORTH);
        listener.onPistonExtend(piston);
        assertTrue(piston.isCancelled());

        BlockRedstoneEvent redstone = new BlockRedstoneEvent(block, 0, 15);
        listener.onRedstone(redstone);
        assertEquals(redstone.getOldCurrent(), redstone.getNewCurrent());

        BlockFromToEvent flow = new BlockFromToEvent(block, block);
        listener.onBlockFromTo(flow);
        assertTrue(flow.isCancelled());
    }

    @Test
    void safeBuilderStorageIsProtectedFromBurnAndExplosion()
    {
        when(trusts.hasAnySafeBuilders()).thenReturn(true);
        when(trusts.hasAnySafeBuilder(claim)).thenReturn(true);
        when(source.isEmpty()).thenReturn(false);

        BlockBurnEvent burn = new BlockBurnEvent(block);
        listener.onBlockBurn(burn);
        assertTrue(burn.isCancelled());

        BlockExplodeEvent explosion = new BlockExplodeEvent(
                block, state, new ArrayList<>(List.of(block)), 1.0f,
                org.bukkit.ExplosionResult.DESTROY);
        listener.onBlockExplode(explosion);
        assertTrue(explosion.blockList().isEmpty());
    }

    @Test
    void environmentalDamageToInventoryEntityIsCancelled()
    {
        when(trusts.hasAnySafeBuilders()).thenReturn(true);
        when(trusts.hasAnySafeBuilder(claim)).thenReturn(true);
        Entity entity = mock(Entity.class,
                org.mockito.Mockito.withSettings().extraInterfaces(InventoryHolder.class));
        Location entityLocation = block.getLocation();
        when(entity.getLocation()).thenReturn(entityLocation);
        when(((InventoryHolder) entity).getInventory()).thenReturn(source);

        EntityDamageEvent damage = mock(EntityDamageEvent.class);
        when(damage.getEntity()).thenReturn(entity);
        java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> {
            cancelled.set(invocation.getArgument(0));
            return null;
        }).when(damage).setCancelled(any(Boolean.class));
        when(damage.isCancelled()).thenAnswer(invocation -> cancelled.get());
        listener.onEntityDamage(damage);

        assertTrue(damage.isCancelled());
    }

    @Test
    void doubleChestOpenUsesDetachedPreviewAndUnknownPluginHolderIsDenied()
    {
        Chest left = mock(Chest.class);
        Chest right = mock(Chest.class);
        when(left.getBlock()).thenReturn(block);
        when(right.getBlock()).thenReturn(block);
        org.bukkit.block.DoubleChest doubleChest = mock(org.bukkit.block.DoubleChest.class);
        when(doubleChest.getLeftSide()).thenReturn(left);
        when(doubleChest.getRightSide()).thenReturn(right);
        Inventory doubleInventory = mock(Inventory.class);
        when(doubleInventory.getHolder()).thenReturn(doubleChest);
        when(doubleInventory.getSize()).thenReturn(18);
        when(doubleInventory.getContents()).thenReturn(new ItemStack[18]);
        when(doubleChest.getInventory()).thenReturn(doubleInventory);
        InventoryOpenEvent doubleOpen = new InventoryOpenEvent(view(doubleInventory));

        listener.onInventoryOpen(doubleOpen);

        assertTrue(doubleOpen.isCancelled());
        assertEquals(1, scheduled.size());

        InventoryHolder unknown = mock(InventoryHolder.class);
        Inventory unknownInventory = mock(Inventory.class);
        when(unknownInventory.getHolder()).thenReturn(unknown);
        when(unknownInventory.getType()).thenReturn(InventoryType.CHEST);
        InventoryOpenEvent unknownOpen = new InventoryOpenEvent(view(unknownInventory));
        listener.onInventoryOpen(unknownOpen);
        assertTrue(unknownOpen.isCancelled());
    }

    @Test
    void projectileDamageAndVehicleDestroyResolveItsPlayerShooter()
    {
        Entity storageEntity = mock(Entity.class,
                org.mockito.Mockito.withSettings().extraInterfaces(InventoryHolder.class));
        Location storageLocation = block.getLocation();
        when(storageEntity.getLocation()).thenReturn(storageLocation);
        when(storageEntity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(((InventoryHolder) storageEntity).getInventory()).thenReturn(source);

        Projectile projectile = mock(Projectile.class);
        when(projectile.getShooter()).thenReturn(player);
        EntityDamageByEntityEvent damage = mock(EntityDamageByEntityEvent.class);
        when(damage.getDamager()).thenReturn(projectile);
        when(damage.getEntity()).thenReturn(storageEntity);
        java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> {
            cancelled.set(invocation.getArgument(0));
            return null;
        }).when(damage).setCancelled(any(Boolean.class));
        when(damage.isCancelled()).thenAnswer(invocation -> cancelled.get());
        listener.onEntityDamageByEntity(damage);
        assertTrue(damage.isCancelled());

        Vehicle vehicle = mock(Vehicle.class,
                org.mockito.Mockito.withSettings().extraInterfaces(InventoryHolder.class));
        when(vehicle.getLocation()).thenReturn(storageLocation);
        when(((InventoryHolder) vehicle).getInventory()).thenReturn(source);
        VehicleDestroyEvent destroy = new VehicleDestroyEvent(vehicle, projectile);
        listener.onVehicleDestroy(destroy);
        assertTrue(destroy.isCancelled());
    }

    @Test
    void movingViewerToAnotherWorldOrClaimInvalidatesTheSession()
    {
        openPreview();
        UUID otherWorldId = UUID.randomUUID();
        World otherWorld = mock(World.class);
        Claim otherClaim = mock(Claim.class);
        Location otherLocation = new Location(otherWorld, 100, 70, 100);
        when(otherWorld.getUID()).thenReturn(otherWorldId);
        when(player.getLocation()).thenReturn(otherLocation);
        when(dataStore.getClaimAt(any(Location.class), eq(true), isNull()))
                .thenAnswer(invocation -> invocation.getArgument(0, Location.class).getWorld() == otherWorld
                        ? otherClaim : claim);
        when(otherClaim.getID()).thenReturn(99L);
        when(otherClaim.getOwnerID()).thenReturn(ownerId);
        when(trusts.isSafeBuilder(otherClaim, playerId, player)).thenReturn(true);

        InventoryClickEvent worldChanged = new InventoryClickEvent(
                view(preview), InventoryType.SlotType.CONTAINER, 0,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onInventoryClick(worldChanged);
        assertTrue(worldChanged.isCancelled());
        verify(player).closeInventory();

        Location sourceLocation = block.getLocation();
        when(player.getLocation()).thenReturn(sourceLocation);
        openPreview();
        Claim differentClaim = mock(Claim.class);
        when(differentClaim.getID()).thenReturn(100L);
        when(differentClaim.getOwnerID()).thenReturn(ownerId);
        when(dataStore.getClaimAt(any(Location.class), eq(true), isNull()))
                .thenAnswer(invocation -> invocation.getArgument(0, Location.class).getBlockX() == 10
                        ? claim : differentClaim);
        when(trusts.isSafeBuilder(differentClaim, playerId, player)).thenReturn(true);
        when(player.getLocation()).thenReturn(new Location(world, 20, 64, 20));

        InventoryClickEvent claimChanged = new InventoryClickEvent(
                view(preview), InventoryType.SlotType.CONTAINER, 0,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onInventoryClick(claimChanged);
        assertTrue(claimChanged.isCancelled());
        verify(player, times(2)).closeInventory();
    }

    @Test
    void safeBuilderCannotBreakOrPlaceAutomationAndCannotDamageStorageEntity()
    {
        Block hopper = mock(Block.class);
        when(hopper.getType()).thenReturn(Material.HOPPER);
        Location blockLocation = block.getLocation();
        when(hopper.getLocation()).thenReturn(blockLocation);
        when(hopper.getState()).thenReturn(state);
        BlockBreakEvent breakEvent = new BlockBreakEvent(hopper, player);
        listener.onBlockBreak(breakEvent);
        assertTrue(breakEvent.isCancelled());

        ItemStack hopperItem = mock(ItemStack.class);
        when(hopperItem.getType()).thenReturn(Material.HOPPER);
        BlockPlaceEvent placeEvent = new BlockPlaceEvent(
                hopper, state, block, hopperItem, player, true, EquipmentSlot.HAND);
        listener.onBlockPlace(placeEvent);
        assertTrue(placeEvent.isCancelled());

        Entity storageEntity = mock(Entity.class,
                org.mockito.Mockito.withSettings().extraInterfaces(InventoryHolder.class));
        when(storageEntity.getLocation()).thenReturn(blockLocation);
        when(storageEntity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(((InventoryHolder) storageEntity).getInventory()).thenReturn(source);
        PlayerInteractEntityEvent interactEntity = new PlayerInteractEntityEvent(player, storageEntity);
        listener.onPlayerInteractEntity(interactEntity);
        assertTrue(interactEntity.isCancelled());
    }

    @Test
    void inventoryBearingEntityDamageIsDeniedForSafeBuilder()
    {
        Entity entity = mock(Entity.class,
                org.mockito.Mockito.withSettings().extraInterfaces(InventoryHolder.class));
        Location blockLocation = block.getLocation();
        when(entity.getLocation()).thenReturn(blockLocation);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(((InventoryHolder) entity).getInventory()).thenReturn(source);
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(player);
        when(event.getEntity()).thenReturn(entity);
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> {
            cancelled.set(invocation.getArgument(0));
            return null;
        }).when(event).setCancelled(any(Boolean.class));
        when(event.isCancelled()).thenAnswer(invocation -> cancelled.get());

        listener.onEntityDamageByEntity(event);

        assertTrue(event.isCancelled());
    }

    private PlayerInteractEvent interact()
    {
        return new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, block,
                BlockFace.UP, EquipmentSlot.HAND);
    }

    private void openPreview()
    {
        PlayerInteractEvent event = interact();
        listener.onPlayerInteract(event);
        assertTrue(event.isCancelled());
        scheduled.removeFirst().run();
    }

    private InventoryView view(Inventory top)
    {
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(top);
        when(view.getBottomInventory()).thenReturn(playerInventory);
        when(view.getPlayer()).thenReturn(player);
        return view;
    }
}
