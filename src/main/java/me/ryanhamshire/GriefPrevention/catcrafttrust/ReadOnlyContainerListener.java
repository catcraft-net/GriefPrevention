package me.ryanhamshire.GriefPrevention.catcrafttrust;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Opens detached, read-only storage previews for safe builders and closes the
 * storage-extraction bypasses that BuildTrust would otherwise introduce.
 * Sessions contain only primitive claim and location identity; no Bukkit
 * object is retained in session state.
 */
public final class ReadOnlyContainerListener implements Listener
{
    private static final String DENIAL_MESSAGE =
            "§b[CatCraft] §eBuild Trust allows read-only container viewing.";
    private static final long DENIAL_COOLDOWN_MILLIS = 1000L;

    private final JavaPlugin plugin;
    private final SafeBuildTrustProvider trusts;
    private final LongSupplier nowMillis;
    private final Map<UUID, ViewSession> sessions = new HashMap<>();
    private final Map<UUID, Integer> pendingTasks = new HashMap<>();
    private final Map<UUID, Long> denialTimestamps = new HashMap<>();

    public ReadOnlyContainerListener(JavaPlugin plugin, SafeBuildTrustProvider trusts)
    {
        this(plugin, trusts, System::currentTimeMillis);
    }

    ReadOnlyContainerListener(JavaPlugin plugin, SafeBuildTrustProvider trusts,
                              LongSupplier nowMillis)
    {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.trusts = Objects.requireNonNull(trusts, "trusts");
        this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent event)
    {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                && event.getAction() != Action.PHYSICAL) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        Player player = event.getPlayer();
        Claim claim = claimAt(block.getLocation());
        if (!safeBuilder(claim, player)) return;

        StorageProtectionPolicy.StorageDecision decision = StorageProtectionPolicy.classifyBreak(block);
        if (decision == StorageProtectionPolicy.StorageDecision.ORDINARY)
        {
            if (StorageProtectionPolicy.isDirectRedstoneControl(block.getType()))
            {
                cancelInteraction(event);
                deny(player);
                return;
            }
            if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
            ItemStack item = event.getItem();
            if (item != null && StorageProtectionPolicy.denyPlacement(item))
            {
                cancelInteraction(event);
                deny(player);
            }
            return;
        }
        cancelInteraction(event);
        if (decision == StorageProtectionPolicy.StorageDecision.AUTOMATION_DENIED
                || decision == StorageProtectionPolicy.StorageDecision.AMBIGUOUS_DENIED)
        {
            deny(player);
            return;
        }
        BlockState state = safeState(block);
        if (!StorageProtectionPolicy.supportsDetachedView(state))
        {
            deny(player);
            return;
        }
        Inventory inventory = safeInventory(state);
        ItemStack[] snapshot = cloneContents(inventory);
        if (snapshot == null)
        {
            deny(player);
            return;
        }
        ViewSession session = ViewSession.block(player.getUniqueId(), claim, block);
        if (session == null)
        {
            deny(player);
            return;
        }
        schedulePreview(session, snapshot);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryOpen(InventoryOpenEvent event)
    {
        if (!(event.getPlayer() instanceof Player player)) return;
        Inventory top = event.getInventory();
        InventoryHolder holder = safeHolder(top);
        if (holder instanceof SessionHolder sessionHolder)
        {
            ViewSession current = sessions.get(player.getUniqueId());
            if (current == null || !current.token().equals(sessionHolder.token())
                    || !sessionStillValid(current, player))
            {
                event.setCancelled(true);
                clearViewer(player.getUniqueId(), true);
            }
            return;
        }

        Block block = blockForHolder(holder);
        if (block == null)
        {
            Claim nearbyClaim = claimAt(player.getLocation());
            if (safeBuilder(nearbyClaim, player) && isPotentialStorage(top, holder))
            {
                event.setCancelled(true);
                deny(player);
            }
            return;
        }

        Claim claim = claimAt(block.getLocation());
        if (!safeBuilder(claim, player)) return;
        StorageProtectionPolicy.StorageDecision decision =
                StorageProtectionPolicy.classifyBreak(block);
        if (decision == StorageProtectionPolicy.StorageDecision.ORDINARY) return;
        event.setCancelled(true);
        if (decision == StorageProtectionPolicy.StorageDecision.AUTOMATION_DENIED
                || decision == StorageProtectionPolicy.StorageDecision.AMBIGUOUS_DENIED
                || !StorageProtectionPolicy.supportsDetachedInventory(holder))
        {
            deny(player);
            return;
        }
        ItemStack[] snapshot = cloneContents(top);
        if (snapshot == null)
        {
            deny(player);
            return;
        }
        ViewSession session = ViewSession.block(player.getUniqueId(), claim, block);
        if (session == null)
        {
            deny(player);
            return;
        }
        schedulePreview(session, snapshot);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event)
    {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = safeTop(event);
        SessionHolder holder = sessionHolder(top);
        if (holder == null) return;
        event.setCancelled(true);
        ViewSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.token().equals(holder.token())
                || !sessionStillValid(session, player))
        {
            clearViewer(player.getUniqueId(), true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event)
    {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = safeTop(event);
        SessionHolder holder = sessionHolder(top);
        if (holder == null) return;
        event.setCancelled(true);
        ViewSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.token().equals(holder.token())
                || !sessionStillValid(session, player))
        {
            clearViewer(player.getUniqueId(), true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event)
    {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Claim claim = claimAt(block.getLocation());
        if (!safeBuilder(claim, player)) return;
        StorageProtectionPolicy.StorageDecision decision = StorageProtectionPolicy.classifyBreak(block);
        if (decision == StorageProtectionPolicy.StorageDecision.PROTECTED_NONEMPTY
                || decision == StorageProtectionPolicy.StorageDecision.AMBIGUOUS_DENIED
                || decision == StorageProtectionPolicy.StorageDecision.AUTOMATION_DENIED)
        {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockPlace(BlockPlaceEvent event)
    {
        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();
        Claim claim = claimAt(block.getLocation());
        if (!safeBuilder(claim, player)) return;
        if (StorageProtectionPolicy.denyPlacement(block.getType())
                || StorageProtectionPolicy.denyPlacement(event.getItemInHand()))
        {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerBucketEmpty(PlayerBucketEmptyEvent event)
    {
        Block block = event.getBlock();
        if (block == null) block = event.getBlockClicked();
        Claim claim = block == null ? null : claimAt(block.getLocation());
        if (safeBuilder(claim, event.getPlayer()))
        {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockIgnite(BlockIgniteEvent event)
    {
        Player player = event.getPlayer();
        if (player == null) player = responsiblePlayer(event.getIgnitingEntity());
        if (player == null) return;
        Block block = event.getBlock();
        Claim claim = block == null ? null : claimAt(block.getLocation());
        if (safeBuilder(claim, player))
        {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventoryMove(InventoryMoveItemEvent event)
    {
        if (safeBuildExistsAt(event.getSource()) || safeBuildExistsAt(event.getDestination())
                || safeBuildExistsAt(event.getInitiator())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventoryPickup(InventoryPickupItemEvent event)
    {
        if (safeBuildExistsAt(event.getInventory())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockDispense(BlockDispenseEvent event)
    {
        if (safeBuildExistsAt(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPistonExtend(BlockPistonExtendEvent event)
    {
        if (safeBuildExistsAt(event.getBlock()) || safeBuildExistsAt(event.getBlocks()))
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPistonRetract(BlockPistonRetractEvent event)
    {
        if (safeBuildExistsAt(event.getBlock()) || safeBuildExistsAt(event.getBlocks()))
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onRedstone(BlockRedstoneEvent event)
    {
        if (safeBuildExistsAt(event.getBlock())) event.setNewCurrent(event.getOldCurrent());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockFromTo(BlockFromToEvent event)
    {
        if (safeBuildExistsAt(event.getBlock()) || safeBuildExistsAt(event.getToBlock()))
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockBurn(BlockBurnEvent event)
    {
        Block block = event.getBlock();
        if (isProtectedStorage(block) && safeBuildExistsAt(block)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockExplode(BlockExplodeEvent event)
    {
        protectExplosionBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityExplode(EntityExplodeEvent event)
    {
        protectExplosionBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityDamage(EntityDamageEvent event)
    {
        if (event instanceof EntityDamageByEntityEvent) return;
        Entity entity = event.getEntity();
        if (!(entity instanceof InventoryHolder)
                || entity instanceof HumanEntity || entity instanceof Merchant) return;
        if (safeBuildExistsAt(entity.getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event)
    {
        Player player = event.getPlayer();
        Entity entity = event.getRightClicked();
        if (entity instanceof HumanEntity || entity instanceof Merchant
                || !(entity instanceof InventoryHolder)) return;
        Claim claim = claimAt(entity.getLocation());
        if (safeBuilder(claim, player))
        {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event)
    {
        Player player = responsiblePlayer(event.getDamager());
        if (player == null) return;
        Entity entity = event.getEntity();
        if (entity instanceof HumanEntity || entity instanceof Merchant
                || !(entity instanceof InventoryHolder)) return;
        Claim claim = claimAt(entity.getLocation());
        if (safeBuilder(claim, player))
        {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onVehicleDestroy(VehicleDestroyEvent event)
    {
        if (!(event.getVehicle() instanceof InventoryHolder holder)
                || holder instanceof HumanEntity || holder instanceof Merchant) return;
        Player player = responsiblePlayer(event.getAttacker());
        if (player == null) return;
        Claim claim = claimAt(event.getVehicle().getLocation());
        if (safeBuilder(claim, player))
        {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event)
    {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID playerId = player.getUniqueId();
        InventoryHolder holder = safeHolder(event.getInventory());
        if (holder instanceof SessionHolder closed)
        {
            ViewSession current = sessions.get(playerId);
            if (current != null && current.token().equals(closed.token()))
            {
                clearViewer(playerId, false);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        clearViewer(event.getPlayer().getUniqueId(), false);
    }

    @EventHandler
    public void onPlayerKick(PlayerKickEvent event)
    {
        clearViewer(event.getPlayer().getUniqueId(), false);
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event)
    {
        if (event.getPlugin() == plugin) shutdown();
    }

    public void invalidateTrust(UUID playerId)
    {
        if (playerId != null) clearViewer(playerId, true);
    }

    public void invalidateClaim(long claimId)
    {
        for (ViewSession session : List.copyOf(sessions.values()))
        {
            if (session.claimId() == claimId) clearViewer(session.viewerId(), true);
        }
    }

    public void shutdown()
    {
        for (UUID playerId : List.copyOf(sessions.keySet()))
        {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) player.closeInventory();
        }
        for (Integer taskId : List.copyOf(pendingTasks.values()))
        {
            plugin.getServer().getScheduler().cancelTask(taskId);
        }
        sessions.clear();
        pendingTasks.clear();
        denialTimestamps.clear();
    }

    private void schedulePreview(ViewSession session, ItemStack[] snapshot)
    {
        UUID playerId = session.viewerId();
        Integer oldTask = pendingTasks.get(playerId);
        if (oldTask != null) return;
        BukkitTask task = plugin.getServer().getScheduler().runTask(plugin, () -> {
            pendingTasks.remove(playerId);
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null || !player.isOnline() || !sessionStillValid(session, player)) return;
            int size = Math.max(9, ((snapshot.length + 8) / 9) * 9);
            if (size > 54) return;
            try
            {
                SessionHolder holder = new SessionHolder(session.token());
                Inventory preview = plugin.getServer().createInventory(holder, size, "Container (view only)");
                if (preview == null) return;
                for (int index = 0; index < snapshot.length; index++)
                {
                    preview.setItem(index, snapshot[index]);
                }
                sessions.put(playerId, session);
                player.openInventory(preview);
            }
            catch (RuntimeException failure)
            {
                clearViewer(playerId, false);
                deny(player);
            }
        });
        pendingTasks.put(playerId, task.getTaskId());
    }

    private boolean sessionStillValid(ViewSession session, Player player)
    {
        try
        {
            World world = plugin.getServer().getWorld(session.worldId());
            if (world == null) return false;
            Location currentLocation = player.getLocation();
            World currentWorld = currentLocation == null ? null : currentLocation.getWorld();
            if (currentWorld == null || !Objects.equals(currentWorld.getUID(), session.worldId()))
            {
                return false;
            }
            Block block = world.getBlockAt(session.x(), session.y(), session.z());
            if (block == null) return false;
            StorageProtectionPolicy.StorageDecision decision =
                    StorageProtectionPolicy.classifyBreak(block);
            if (decision == StorageProtectionPolicy.StorageDecision.ORDINARY
                    || decision == StorageProtectionPolicy.StorageDecision.AUTOMATION_DENIED
                    || decision == StorageProtectionPolicy.StorageDecision.AMBIGUOUS_DENIED
                    || !StorageProtectionPolicy.supportsDetachedView(safeState(block))) return false;
            Claim sourceClaim = claimAt(new Location(world, session.x(), session.y(), session.z()));
            Claim currentClaim = claimAt(currentLocation);
            return sourceClaim != null && currentClaim != null
                    && Objects.equals(sourceClaim.getID(), session.claimId())
                    && Objects.equals(sourceClaim.getOwnerID(), session.ownerId())
                    && Objects.equals(currentClaim.getID(), sourceClaim.getID())
                    && Objects.equals(currentClaim.getOwnerID(), session.ownerId())
                    && safeBuilder(currentClaim, player);
        }
        catch (RuntimeException failure)
        {
            return false;
        }
    }

    private Claim claimAt(@Nullable Location location)
    {
        if (location == null) return null;
        try
        {
            GriefPrevention instance = GriefPrevention.instance;
            DataStore dataStore = instance == null ? null : instance.dataStore;
            return dataStore == null ? null : dataStore.getClaimAt(location, true, null);
        }
        catch (RuntimeException failure)
        {
            return null;
        }
    }

    private boolean safeBuilder(@Nullable Claim claim, Player player)
    {
        if (claim == null || player == null) return false;
        try
        {
            UUID playerId = player.getUniqueId();
            return playerId != null && trusts.isSafeBuilder(claim, playerId, player);
        }
        catch (RuntimeException failure)
        {
            return false;
        }
    }

    private void cancelInteraction(PlayerInteractEvent event)
    {
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
    }

    private void deny(Player player)
    {
        long now = nowMillis.getAsLong();
        Long previous = denialTimestamps.get(player.getUniqueId());
        if (previous == null || now - previous >= DENIAL_COOLDOWN_MILLIS)
        {
            denialTimestamps.put(player.getUniqueId(), now);
            player.sendMessage(DENIAL_MESSAGE);
        }
    }

    private void clearViewer(UUID playerId, boolean close)
    {
        if (playerId == null) return;
        Integer taskId = pendingTasks.remove(playerId);
        if (taskId != null) plugin.getServer().getScheduler().cancelTask(taskId);
        sessions.remove(playerId);
        denialTimestamps.remove(playerId);
        if (close)
        {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) player.closeInventory();
        }
    }

    private static BlockState safeState(Block block)
    {
        try
        {
            return block == null ? null : block.getState();
        }
        catch (RuntimeException failure)
        {
            return null;
        }
    }

    private static Inventory safeInventory(BlockState state)
    {
        try
        {
            return state instanceof InventoryHolder holder ? holder.getInventory() : null;
        }
        catch (RuntimeException failure)
        {
            return null;
        }
    }

    private static InventoryHolder safeHolder(Inventory inventory)
    {
        try
        {
            return inventory == null ? null : inventory.getHolder();
        }
        catch (RuntimeException failure)
        {
            return null;
        }
    }

    private static Block safeBlock(BlockInventoryHolder holder)
    {
        try
        {
            return holder.getBlock();
        }
        catch (RuntimeException failure)
        {
            return null;
        }
    }

    private static Block blockForHolder(@Nullable InventoryHolder holder)
    {
        if (holder instanceof BlockInventoryHolder blockHolder) return safeBlock(blockHolder);
        if (holder instanceof DoubleChest doubleChest)
        {
            try
            {
                InventoryHolder left = doubleChest.getLeftSide();
                Block leftBlock = left instanceof BlockInventoryHolder blockHolder
                        ? safeBlock(blockHolder) : null;
                if (leftBlock != null) return leftBlock;
                InventoryHolder right = doubleChest.getRightSide();
                return right instanceof BlockInventoryHolder blockHolder
                        ? safeBlock(blockHolder) : null;
            }
            catch (RuntimeException failure)
            {
                return null;
            }
        }
        return null;
    }

    private boolean safeBuildExistsAt(@Nullable Block block)
    {
        return block != null && safeBuildExistsAt(block.getLocation());
    }

    private boolean safeBuildExistsAt(@Nullable List<Block> blocks)
    {
        if (blocks == null) return false;
        for (Block block : blocks)
        {
            if (safeBuildExistsAt(block)) return true;
        }
        return false;
    }

    private boolean safeBuildExistsAt(@Nullable Inventory inventory)
    {
        if (inventory == null || !hasAnySafeBuilders()) return false;
        return safeBuildExistsAt(holderLocation(safeHolder(inventory)));
    }

    private boolean safeBuildExistsAt(@Nullable Location location)
    {
        if (location == null || !hasAnySafeBuilders()) return false;
        Claim claim = claimAt(location);
        if (claim == null) return false;
        try
        {
            return trusts.hasAnySafeBuilder(claim);
        }
        catch (RuntimeException failure)
        {
            return false;
        }
    }

    private boolean hasAnySafeBuilders()
    {
        try
        {
            return trusts.hasAnySafeBuilders();
        }
        catch (RuntimeException failure)
        {
            return false;
        }
    }

    private static @Nullable Location holderLocation(@Nullable InventoryHolder holder)
    {
        try
        {
            Block block = holder instanceof BlockInventoryHolder blockHolder
                    ? safeBlock(blockHolder) : null;
            if (block != null) return block.getLocation();
            return holder instanceof Entity entity ? entity.getLocation() : null;
        }
        catch (RuntimeException failure)
        {
            return null;
        }
    }

    private boolean isProtectedStorage(@Nullable Block block)
    {
        if (block == null) return false;
        StorageProtectionPolicy.StorageDecision decision =
                StorageProtectionPolicy.classifyBreak(block);
        return decision == StorageProtectionPolicy.StorageDecision.PROTECTED_NONEMPTY
                || decision == StorageProtectionPolicy.StorageDecision.AMBIGUOUS_DENIED
                || decision == StorageProtectionPolicy.StorageDecision.AUTOMATION_DENIED;
    }

    private void protectExplosionBlocks(@Nullable List<Block> blocks)
    {
        if (blocks == null || !hasAnySafeBuilders()) return;
        for (int index = blocks.size() - 1; index >= 0; index--)
        {
            Block block = blocks.get(index);
            if (isProtectedStorage(block) && safeBuildExistsAt(block)) blocks.remove(index);
        }
    }

    private static ItemStack[] cloneContents(@Nullable Inventory inventory)
    {
        if (inventory == null) return null;
        try
        {
            if (inventory.getSize() > 54) return null;
            ItemStack[] contents = inventory.getContents();
            if (contents == null || contents.length > 54) return null;
            ItemStack[] copy = new ItemStack[contents.length];
            for (int index = 0; index < contents.length; index++)
            {
                copy[index] = contents[index] == null ? null : contents[index].clone();
            }
            return copy;
        }
        catch (RuntimeException failure)
        {
            return null;
        }
    }

    private static @Nullable Player responsiblePlayer(@Nullable Entity source)
    {
        try
        {
            if (source instanceof Player player) return player;
            if (source instanceof Projectile projectile
                    && projectile.getShooter() instanceof Player player) return player;
        }
        catch (RuntimeException ignored)
        {
            // An unreadable shooter cannot establish authorization.
        }
        return null;
    }

    private static Inventory safeTop(InventoryEvent event)
    {
        try
        {
            InventoryView view = event.getView();
            return view == null ? null : view.getTopInventory();
        }
        catch (RuntimeException failure)
        {
            return null;
        }
    }

    private static SessionHolder sessionHolder(@Nullable Inventory inventory)
    {
        InventoryHolder holder = safeHolder(inventory);
        return holder instanceof SessionHolder sessionHolder ? sessionHolder : null;
    }

    private static boolean isPotentialStorage(Inventory inventory, InventoryHolder holder)
    {
        if (holder instanceof HumanEntity || holder instanceof Merchant) return true;
        if (holder != null) return true;
        try
        {
            InventoryType type = inventory == null ? null : inventory.getType();
            return type != null && type != InventoryType.PLAYER && type != InventoryType.CRAFTING;
        }
        catch (RuntimeException failure)
        {
            return true;
        }
    }

    private record ViewSession(UUID viewerId, long claimId, UUID worldId,
                               int x, int y, int z, @Nullable UUID ownerId,
                               UUID token)
    {
        private static @Nullable ViewSession block(UUID viewerId, Claim claim, Block block)
        {
            try
            {
                Long claimId = claim.getID();
                Location location = block.getLocation();
                World world = block.getWorld();
                if (viewerId == null || claimId == null || location == null || world == null
                        || world.getUID() == null) return null;
                return new ViewSession(viewerId, claimId, world.getUID(),
                        location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                        claim.getOwnerID(), UUID.randomUUID());
            }
            catch (RuntimeException failure)
            {
                return null;
            }
        }
    }

    private static final class SessionHolder implements InventoryHolder
    {
        private final UUID token;

        private SessionHolder(UUID token)
        {
            this.token = token;
        }

        private UUID token()
        {
            return token;
        }

        @Override
        public Inventory getInventory()
        {
            return null;
        }
    }
}
