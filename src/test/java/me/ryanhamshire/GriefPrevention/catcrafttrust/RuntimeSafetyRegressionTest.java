package me.ryanhamshire.GriefPrevention.catcrafttrust;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import me.ryanhamshire.GriefPrevention.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Uses the actual runtime adapter and actual Claim objects, not a stand-in adapter. */
class RuntimeSafetyRegressionTest
{
    private static final UUID OWNER = UUID.fromString("7419db16-37c5-41ef-8fd2-f3b4a2938b35");
    private static final UUID HELPER = UUID.fromString("dbb9f20c-ed34-4f32-ae29-5d2527016c57");
    @TempDir Path directory;
    private final Map<Long, Claim> claims = new HashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private GriefPrevention previous;
    private GriefPrevention plugin;
    private DataStore dataStore;
    private CatCraftTrustRuntime runtime;
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void startRuntime() throws Exception
    {
        previous = GriefPrevention.instance;
        plugin = mock(GriefPrevention.class);
        dataStore = mock(DataStore.class);
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("RuntimeSafetyRegressionTest"));
        when(server.getPluginManager()).thenReturn(manager);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong()))
                .thenReturn(mock(BukkitTask.class));
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenReturn(mock(BukkitTask.class));
        when(dataStore.getClaim(anyLong())).thenAnswer(call -> claims.get(call.getArgument(0, Long.class)));
        when(dataStore.getPlayerData(any(UUID.class))).thenAnswer(call -> new PlayerData());
        doAnswer(call -> { listeners.add(call.getArgument(0)); return null; })
                .when(manager).registerEvents(any(Listener.class), eq(plugin));
        plugin.dataStore = dataStore;
        GriefPrevention.instance = plugin;
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
        runtime = CatCraftTrustRuntime.start(plugin, dataStore,
                CatCraftTrustSettings.bounded(true, 30, 100, 100, 3),
                directory.resolve("state.properties"), directory.resolve("legacy.properties"));
        plugin.catCraftTrustService = runtime.service();
    }

    @AfterEach
    void stopRuntime() throws Exception
    {
        try { if (runtime != null) runtime.stop(); }
        finally
        {
            GriefPrevention.instance = previous;
            if (bukkit != null) bukkit.close();
        }
    }

    @ParameterizedTest
    @EnumSource(value = CatCraftTrustKind.class, names = {"ACCESS", "BUILD", "CONTAINER", "FULL"})
    void expiryPreservesSeparateManagerAndSubdivisionPermissions(CatCraftTrustKind kind) throws Exception
    {
        Claim parent = claim(42L, OWNER);
        Claim child = claim(43L, null);
        child.parent = parent;
        parent.children.add(child);
        parent.managers.add(HELPER.toString());
        child.setPermission(HELPER.toString(), ClaimPermission.Inventory);
        child.managers.add(HELPER.toString());

        runtime.service().grant(List.of(parent), HELPER.toString(), kind, Duration.ofMinutes(1));
        runtime.service().processDue(Long.MAX_VALUE);

        assertAll(
                () -> assertNull(parent.getPermission(HELPER.toString())),
                () -> assertTrue(parent.managers.contains(HELPER.toString())),
                () -> assertEquals(ClaimPermission.Inventory, child.getPermission(HELPER.toString())),
                () -> assertTrue(child.managers.contains(HELPER.toString())));
    }

    @Test
    void unavailableTrustServicePreventsOwnershipTransferFromOrphaningTemporaryGrants() throws Exception
    {
        Claim claim = claim(42L, OWNER);
        runtime.service().grant(List.of(claim), HELPER.toString(), CatCraftTrustKind.CONTAINER, Duration.ofDays(1));
        runtime.service().stop();
        doCallRealMethod().when(dataStore).changeClaimOwner(eq(claim), any(UUID.class));
        assertThrows(DataStore.NoTransferException.class, () -> dataStore.changeClaimOwner(claim, UUID.randomUUID()));
        assertEquals(OWNER, claim.getOwnerID());
        assertNotNull(claim.checkPermission(HELPER, ClaimPermission.Inventory, null));
    }

    @Test
    void expiryAlsoPreservesManagerGrantedAfterTheTemporaryPermission() throws Exception
    {
        Claim claim = claim(42L, OWNER);
        runtime.service().grant(List.of(claim), HELPER.toString(),
                CatCraftTrustKind.CONTAINER, Duration.ofMinutes(1));
        claim.setPermission(HELPER.toString(), ClaimPermission.Manage);

        runtime.service().processDue(Long.MAX_VALUE);

        assertNull(claim.getPermission(HELPER.toString()));
        assertTrue(claim.managers.contains(HELPER.toString()));
    }

    @Test
    void managerExpiryDoesNotRemoveOrdinaryPermission() throws Exception
    {
        Claim claim = claim(42L, OWNER);
        claim.setPermission(HELPER.toString(), ClaimPermission.Inventory);
        runtime.service().grant(List.of(claim), HELPER.toString(),
                CatCraftTrustKind.MANAGE, Duration.ofMinutes(1));

        runtime.service().processDue(Long.MAX_VALUE);

        assertEquals(ClaimPermission.Inventory, claim.getPermission(HELPER.toString()));
        assertFalse(claim.managers.contains(HELPER.toString()));
    }

    @Test
    void publicSafeBuildDoesNotMakeTheOwnerReadOnly() throws Exception
    {
        Claim claim = claim(42L, OWNER);
        runtime.service().grant(List.of(claim), "public", CatCraftTrustKind.BUILD, null);

        assertFalse(breakStoredChest(claim, player(OWNER)).isCancelled());
    }

    @Test
    void publicSafeBuildDoesNotOverrideStrongerIndividualTrust() throws Exception
    {
        Claim claim = claim(42L, OWNER);
        runtime.service().grant(List.of(claim), "public", CatCraftTrustKind.BUILD, null);
        claim.setPermission(HELPER.toString(), ClaimPermission.Build);

        assertFalse(breakStoredChest(claim, player(HELPER)).isCancelled());
    }

    @Test
    void inheritedSafeBuildDoesNotOverrideStrongerSubdivisionTrust() throws Exception
    {
        Claim parent = claim(42L, OWNER);
        Claim child = claim(43L, null);
        child.parent = parent;
        parent.children.add(child);
        runtime.service().grant(List.of(parent), "public", CatCraftTrustKind.BUILD, null);
        child.setPermission(HELPER.toString(), ClaimPermission.Build);

        assertFalse(breakStoredChest(child, player(HELPER)).isCancelled());
    }

    @Test
    void publicSafeBuildDoesNotOverrideStrongerPermissionGroupTrust() throws Exception
    {
        Claim claim = claim(42L, OWNER);
        runtime.service().grant(List.of(claim), "public", CatCraftTrustKind.BUILD, null);
        claim.setPermission("[catcraft.test.builders]", ClaimPermission.Build);
        Player helper = player(HELPER);
        when(helper.hasPermission("catcraft.test.builders")).thenReturn(true);

        assertFalse(breakStoredChest(claim, helper).isCancelled());
    }

    @Test
    void ordinarySafeBuilderStillCannotBreakStoredItems() throws Exception
    {
        Claim claim = claim(42L, OWNER);
        runtime.service().grant(List.of(claim), "public", CatCraftTrustKind.BUILD, null);

        assertTrue(breakStoredChest(claim, player(HELPER)).isCancelled());
    }

    private Claim claim(long id, UUID owner) throws Exception
    {
        Constructor<Claim> constructor = Claim.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Claim claim = constructor.newInstance();
        Field idField = Claim.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(claim, id);
        claim.ownerID = owner;
        claim.inDataStore = true;
        claims.put(id, claim);
        return claim;
    }

    private Player player(UUID id)
    {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    private BlockBreakEvent breakStoredChest(Claim claim, Player player)
    {
        Block block = mock(Block.class);
        Chest chest = mock(Chest.class);
        Inventory inventory = mock(Inventory.class);
        when(block.getType()).thenReturn(Material.CHEST);
        when(block.getState()).thenReturn(chest);
        when(block.getLocation()).thenReturn(new Location(mock(World.class), 0, 64, 0));
        when(chest.getInventory()).thenReturn(inventory);
        when(inventory.isEmpty()).thenReturn(false);
        when(dataStore.getClaimAt(any(Location.class), eq(true), isNull())).thenReturn(claim);
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        listeners.stream().filter(ReadOnlyContainerListener.class::isInstance)
                .map(ReadOnlyContainerListener.class::cast).forEach(listener -> listener.onBlockBreak(event));
        return event;
    }
}
