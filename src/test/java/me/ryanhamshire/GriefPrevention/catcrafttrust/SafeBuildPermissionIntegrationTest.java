package me.ryanhamshire.GriefPrevention;

import me.ryanhamshire.GriefPrevention.catcrafttrust.CatCraftTrustService;
import me.ryanhamshire.GriefPrevention.catcrafttrust.TrustDimension;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SafeBuildPermissionIntegrationTest
{
    private static final UUID OWNER = UUID.fromString("16f54277-9f89-4d94-9f78-c876845219d7");
    private static final UUID BUILDER = UUID.fromString("85801356-29c3-4f1f-b363-3114641675b1");

    private GriefPrevention previousInstance;
    private GriefPrevention plugin;
    private CatCraftTrustService service;
    private PluginManager pluginManager;

    @BeforeEach
    void setUp()
    {
        previousInstance = GriefPrevention.instance;
        plugin = mock(GriefPrevention.class);
        service = mock(CatCraftTrustService.class);
        pluginManager = mock(PluginManager.class);
        plugin.dataStore = mock(DataStore.class);
        when(plugin.dataStore.getPlayerData(any(UUID.class))).thenReturn(new PlayerData());
        plugin.catCraftTrustService = service;
        GriefPrevention.instance = plugin;
    }

    @AfterEach
    void tearDown()
    {
        GriefPrevention.instance = previousInstance;
    }

    @Test
    void safeBuildOverlayAllowsOnlyBuildAndAccess()
    {
        Claim claim = claim(42L, OWNER);
        when(service.isSafeBuilder(claim, BUILDER, null)).thenReturn(true);

        assertNull(check(claim, BUILDER, ClaimPermission.Build));
        assertNull(check(claim, BUILDER, ClaimPermission.Access));
        assertNotNull(check(claim, BUILDER, ClaimPermission.Inventory));
        assertNotNull(check(claim, BUILDER, ClaimPermission.Manage));
        assertNotNull(check(claim, BUILDER, ClaimPermission.Edit));
    }

    @Test
    void onlineSafeBuilderIsPassedToOverlayProvider()
    {
        Claim claim = claim(42L, OWNER);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(BUILDER);
        when(service.isSafeBuilder(claim, BUILDER, player)).thenReturn(true);

        assertNull(check(claim, player, ClaimPermission.Build));
        verify(service).isSafeBuilder(claim, BUILDER, player);
    }

    @Test
    void overlayFailureFailsClosedWithoutBreakingNormalPermissionChecks()
    {
        Claim claim = claim(42L, OWNER);
        when(service.isSafeBuilder(claim, BUILDER, null))
                .thenThrow(new IllegalStateException("unavailable"));

        assertNotNull(check(claim, BUILDER, ClaimPermission.Build));
        assertNotNull(check(claim, BUILDER, ClaimPermission.Access));
    }

    @Test
    void normalPermissionHierarchyRemainsUnchanged()
    {
        Claim full = claim(42L, OWNER);
        full.setPermission(BUILDER.toString(), ClaimPermission.Build);
        assertNull(check(full, BUILDER, ClaimPermission.Build));
        assertNull(check(full, BUILDER, ClaimPermission.Inventory));
        assertNull(check(full, BUILDER, ClaimPermission.Access));

        Claim container = claim(43L, OWNER);
        container.setPermission(BUILDER.toString(), ClaimPermission.Inventory);
        assertNotNull(check(container, BUILDER, ClaimPermission.Build));
        assertNull(check(container, BUILDER, ClaimPermission.Inventory));
        assertNull(check(container, BUILDER, ClaimPermission.Access));
    }

    @Test
    void liveExternalPermissionMutationsInvalidateMetadataBeforeChangingClaim()
    {
        Claim claim = claim(42L, OWNER);
        claim.inDataStore = true;
        String target = BUILDER.toString();
        doAnswer(invocation -> {
            assertNull(claim.getPermission(target));
            return null;
        }).when(service).onExternalPermissionMutation(claim, target, TrustDimension.PERMISSION);

        claim.setPermission(target, ClaimPermission.Access);

        verify(service).onExternalPermissionMutation(claim, target, TrustDimension.PERMISSION);
        clearInvocations(service);
        doAnswer(invocation -> {
            assertNotNull(claim.getPermission(target));
            return null;
        }).when(service).onExternalPermissionMutation(claim, target, TrustDimension.PERMISSION);

        claim.dropPermission(target);

        verify(service).onExternalPermissionMutation(claim, target, TrustDimension.PERMISSION);
        verify(service).onExternalPermissionMutation(claim, target, TrustDimension.MANAGER);
    }

    @Test
    void clearPermissionsInvalidatesEachLiveClaimIncludingChildren()
    {
        Claim parent = claim(42L, OWNER);
        Claim child = claim(43L, null);
        parent.inDataStore = true;
        child.inDataStore = true;
        parent.children.add(child);
        child.parent = parent;

        parent.clearPermissions();

        verify(service).onExternalPermissionsCleared(parent);
        verify(service).onExternalPermissionsCleared(child);
    }

    @Test
    void internalAndDetachedMutationsDoNotInvalidateMetadata()
    {
        String target = BUILDER.toString();
        Claim detached = claim(42L, OWNER);
        detached.setPermission(target, ClaimPermission.Access);
        verify(service, never()).onExternalPermissionMutation(any(), any(), any());

        Claim live = claim(43L, OWNER);
        live.inDataStore = true;
        when(service.isInternalMutation()).thenReturn(true);
        live.setPermission(target, ClaimPermission.Access);
        live.dropPermission(target);
        live.clearPermissions();

        verify(service, never()).onExternalPermissionMutation(any(), any(), any());
        verify(service, never()).onExternalPermissionsCleared(any());
    }

    private Object check(Claim claim, UUID playerId, ClaimPermission permission)
    {
        try (var bukkit = mockStatic(Bukkit.class))
        {
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
            return claim.checkPermission(playerId, permission, null);
        }
    }

    private Object check(Claim claim, Player player, ClaimPermission permission)
    {
        try (var bukkit = mockStatic(Bukkit.class))
        {
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
            return claim.checkPermission(player, permission, null);
        }
    }

    private static Claim claim(long id, UUID owner)
    {
        Claim claim = new Claim();
        claim.id = id;
        claim.ownerID = owner;
        return claim;
    }
}
