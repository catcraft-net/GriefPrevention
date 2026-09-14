package me.ryanhamshire.GriefPrevention;

import me.ryanhamshire.GriefPrevention.catcrafttrust.CatCraftTrustService;
import me.ryanhamshire.GriefPrevention.catcrafttrust.SafeBuildIntegrationHarness;
import me.ryanhamshire.GriefPrevention.catcrafttrust.CatCraftTrustKind;
import me.ryanhamshire.GriefPrevention.catcrafttrust.TrustDimension;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
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

    @TempDir
    Path directory;

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
        when(service.isStarted()).thenReturn(true);
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
    void publicSafeBuildMarkerFlowsThroughClaimPermissionMatrix() throws Exception
    {
        Claim claim = claim(44L, OWNER);
        CatCraftTrustService realService = startRealService(claim);
        realService.grant(List.of(claim), "public", CatCraftTrustKind.BUILD, null);

        assertSafeBuildMatrix(claim, BUILDER);
    }

    @Test
    void permissionNodeMarkerFlowsThroughOnlineClaimPermissionMatrix() throws Exception
    {
        Claim claim = claim(45L, OWNER);
        CatCraftTrustService realService = startRealService(claim);
        realService.grant(List.of(claim), "[catcraft.builders]", CatCraftTrustKind.BUILD, null);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(BUILDER);
        when(player.hasPermission("catcraft.builders")).thenReturn(true);

        assertNull(check(claim, player, ClaimPermission.Build));
        assertNull(check(claim, player, ClaimPermission.Access));
        assertNotNull(check(claim, player, ClaimPermission.Inventory));
        assertNotNull(check(claim, player, ClaimPermission.Manage));
        assertNotNull(check(claim, player, ClaimPermission.Edit));
    }

    @Test
    void unrestrictedSubdivisionInheritsParentSafeBuildMarker() throws Exception
    {
        Claim parent = claim(46L, OWNER);
        Claim child = claim(47L, null);
        child.parent = parent;
        CatCraftTrustService realService = startRealService(parent, child);
        realService.grant(List.of(parent), BUILDER.toString(), CatCraftTrustKind.BUILD, null);

        assertSafeBuildMatrix(child, BUILDER);
    }

    @Test
    void restrictedSubdivisionStopsParentSafeBuildMarker() throws Exception
    {
        Claim parent = claim(48L, OWNER);
        Claim child = claim(49L, null);
        child.parent = parent;
        child.setSubclaimRestrictions(true);
        CatCraftTrustService realService = startRealService(parent, child);
        realService.grant(List.of(parent), BUILDER.toString(), CatCraftTrustKind.BUILD, null);

        assertNotNull(check(child, BUILDER, ClaimPermission.Build));
        assertNotNull(check(child, BUILDER, ClaimPermission.Access));
        assertNotNull(check(child, BUILDER, ClaimPermission.Inventory));
        assertNotNull(check(child, BUILDER, ClaimPermission.Manage));
        assertNotNull(check(child, BUILDER, ClaimPermission.Edit));
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

        verify(service).onExternalTargetRemoved(claim, target);
    }

    @Test
    void externalManagerAssignmentAlsoReplacesSafeBuildMetadata()
    {
        Claim claim = claim(42L, OWNER);
        claim.inDataStore = true;
        String target = BUILDER.toString();

        claim.setPermission(target, ClaimPermission.Manage);

        verify(service).onExternalTargetMutation(claim, target);
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
        verify(service, never()).onExternalPermissionsCleared(child);
    }

    @Test
    void stoppedServiceCannotGrantOverlayOrInvalidateMetadata()
    {
        Claim claim = claim(42L, OWNER);
        claim.inDataStore = true;
        when(service.isStarted()).thenReturn(false);
        when(service.isSafeBuilder(claim, BUILDER, null)).thenReturn(true);

        assertNotNull(check(claim, BUILDER, ClaimPermission.Build));
        claim.setPermission(BUILDER.toString(), ClaimPermission.Access);
        claim.dropPermission(BUILDER.toString());
        claim.clearPermissions();

        verify(service, never()).isSafeBuilder(any(), any(), any());
        verify(service, never()).onExternalPermissionMutation(any(), any(), any());
        verify(service, never()).onExternalTargetMutation(any(), any());
        verify(service, never()).onExternalTargetRemoved(any(), any());
        verify(service, never()).onExternalPermissionsCleared(any());
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

    private CatCraftTrustService startRealService(Claim... claims) throws Exception
    {
        CatCraftTrustService realService = SafeBuildIntegrationHarness.start(directory, claims);
        plugin.catCraftTrustService = realService;
        return realService;
    }

    private void assertSafeBuildMatrix(Claim claim, UUID playerId)
    {
        assertNull(check(claim, playerId, ClaimPermission.Build));
        assertNull(check(claim, playerId, ClaimPermission.Access));
        assertNotNull(check(claim, playerId, ClaimPermission.Inventory));
        assertNotNull(check(claim, playerId, ClaimPermission.Manage));
        assertNotNull(check(claim, playerId, ClaimPermission.Edit));
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
