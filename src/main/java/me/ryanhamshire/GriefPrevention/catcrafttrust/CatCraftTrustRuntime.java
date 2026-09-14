package me.ryanhamshire.GriefPrevention.catcrafttrust;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Owns the integrated trust service, its single expiry task and listeners. */
public final class CatCraftTrustRuntime
{
    public static final Path STATE_FILE = Path.of("plugins", "GriefPreventionData",
            "CatCraftTrust", "temporary-trust.properties");
    public static final Path LEGACY_STATE_FILE = Path.of("plugins", "GPTrust",
            "temporary-trust.properties");

    private final GriefPrevention plugin;
    private final CatCraftTrustService service;
    private final ReadOnlyContainerListener containerListener;
    private boolean stopped;

    private CatCraftTrustRuntime(GriefPrevention plugin, CatCraftTrustService service,
                                 ReadOnlyContainerListener containerListener)
    {
        this.plugin = plugin;
        this.service = service;
        this.containerListener = containerListener;
    }

    public static CatCraftTrustRuntime start(GriefPrevention plugin, DataStore dataStore,
                                             CatCraftTrustSettings settings) throws IOException
    {
        return start(plugin, dataStore, settings, STATE_FILE, LEGACY_STATE_FILE);
    }

    static CatCraftTrustRuntime start(GriefPrevention plugin, DataStore dataStore,
                                      CatCraftTrustSettings settings, Path stateFile,
                                      Path legacyStateFile) throws IOException
    {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(dataStore, "dataStore");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(stateFile, "stateFile");
        Objects.requireNonNull(legacyStateFile, "legacyStateFile");
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(
                stateFile, settings.maximumRecords());
        if (!Files.isRegularFile(stateFile)
                && !Files.isRegularFile(stateFile.resolveSibling(stateFile.getFileName() + ".bak")))
        {
            int imported = GPTrustMigration.importIfPresent(
                    legacyStateFile, store, dataStore, settings.maximumRecords());
            if (imported > 0) plugin.getLogger().info("Imported " + imported + " GPTrust record(s).");
        }

        ClaimTrustAccess claimAccess = new DataStoreClaimTrustAccess(dataStore);
        TrustTaskScheduler scheduler = new BukkitTrustTaskScheduler(plugin);
        CatCraftTrustService service = new CatCraftTrustService(store, claimAccess, scheduler,
                System::currentTimeMillis, settings.maximumExpirationsPerTick());
        service.setExpirationListener(record -> notifyExpiration(plugin, record));
        service.start();

        SafeBuildTrustProvider provider = new SafeBuildTrustProvider()
        {
            @Override
            public boolean isSafeBuilder(Claim claim, UUID playerId, Player player)
            {
                return service.isSafeBuilder(claim, playerId, player);
            }

            @Override
            public boolean hasAnySafeBuilders()
            {
                return service.hasAnySafeBuilders();
            }

            @Override
            public boolean hasAnySafeBuilder(Claim claim)
            {
                return service.hasAnySafeBuilder(claim);
            }
        };
        long cooldownMillis = Math.multiplyExact(settings.denialMessageCooldownSeconds(), 1000L);
        ReadOnlyContainerListener listener = new ReadOnlyContainerListener(
                plugin, provider, System::currentTimeMillis, cooldownMillis);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        return new CatCraftTrustRuntime(plugin, service, listener);
    }

    public CatCraftTrustService service()
    {
        return service;
    }

    public void stop() throws IOException
    {
        if (stopped) return;
        stopped = true;
        List<TemporaryTrustRecord> active = service.recordsSnapshot();
        if (!active.isEmpty())
        {
            plugin.getLogger().warning("CatCraft trust state still contains " + active.size()
                    + " active record(s). Do not downgrade to an older GriefPrevention JAR until these grants are removed or expired.");
        }
        HandlerList.unregisterAll(containerListener);
        try
        {
            containerListener.shutdown();
        }
        finally
        {
            service.stop();
        }
    }

    private static void notifyExpiration(GriefPrevention plugin, TemporaryTrustRecord record)
    {
        UUID owner = record.ownerIdAtGrant();
        if (owner == null) return;
        Player player = plugin.getServer().getPlayer(owner);
        if (player == null || !player.isOnline()) return;
        String target = ChatColor.stripColor(record.target());
        String kind = switch (record.appliedKind())
        {
            case BUILD -> "Build Trust";
            case ACCESS -> "Access Trust";
            case CONTAINER -> "Container Trust";
            case FULL -> "Full Trust";
            case MANAGE -> "Permission Trust";
        };
        player.sendMessage(ChatColor.AQUA + "[CatCraft] " + ChatColor.YELLOW
                + target + "'s temporary " + kind + " has expired.");
    }

    private static final class BukkitTrustTaskScheduler implements TrustTaskScheduler
    {
        private final GriefPrevention plugin;

        private BukkitTrustTaskScheduler(GriefPrevention plugin)
        {
            this.plugin = plugin;
        }

        @Override
        public ScheduledHandle schedule(long delayTicks, Runnable task)
        {
            BukkitTask scheduled = plugin.getServer().getScheduler()
                    .runTaskLater(plugin, task, delayTicks);
            return scheduled::cancel;
        }

        @Override
        public void nextTick(Runnable task)
        {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    private static final class DataStoreClaimTrustAccess implements ClaimTrustAccess
    {
        private final DataStore dataStore;

        private DataStoreClaimTrustAccess(DataStore dataStore)
        {
            this.dataStore = dataStore;
        }

        @Override
        public ClaimSnapshot resolve(long claimId)
        {
            Claim claim = dataStore.getClaim(claimId);
            if (claim == null) return null;
            return new ClaimSnapshot(claimId, claim.getOwnerID(),
                    claim.parent == null ? null : claim.parent.getID(), claim.getSubclaimRestrictions());
        }

        @Override
        public NativeTrustState capture(long claimId, String target, TrustDimension dimension)
        {
            Claim claim = requiredClaim(claimId);
            String canonical = target.toLowerCase(Locale.ROOT);
            return new NativeTrustState(claim.getPermission(canonical),
                    claim.managers.contains(canonical), false);
        }

        @Override
        public void apply(long claimId, String target, NativeTrustState state, TrustDimension dimension)
        {
            Claim claim = requiredClaim(claimId);
            String canonical = target.toLowerCase(Locale.ROOT);
            if (dimension == TrustDimension.PERMISSION)
            {
                claim.setPermission(canonical, state.permission());
            }
            else if (state.manager())
            {
                claim.setPermission(canonical, ClaimPermission.Manage);
            }
            else
            {
                claim.managers.remove(canonical);
            }
        }

        @Override
        public void save(long claimId)
        {
            dataStore.saveClaim(requiredClaim(claimId));
        }

        private Claim requiredClaim(long claimId)
        {
            Claim claim = dataStore.getClaim(claimId);
            if (claim == null) throw new IllegalStateException("claim " + claimId + " no longer exists");
            return claim;
        }
    }
}
