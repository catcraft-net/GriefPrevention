package me.ryanhamshire.GriefPrevention;

import me.ryanhamshire.GriefPrevention.catcrafttrust.CatCraftTrustKind;
import me.ryanhamshire.GriefPrevention.catcrafttrust.CatCraftTrustService;
import me.ryanhamshire.GriefPrevention.catcrafttrust.NativeTrustState;
import me.ryanhamshire.GriefPrevention.catcrafttrust.TemporaryTrustRecord;
import me.ryanhamshire.GriefPrevention.catcrafttrust.TrustDimension;
import me.ryanhamshire.GriefPrevention.events.TrustChangedEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrustCommandIntegrationTest
{
    private static final UUID PLAYER_ID = UUID.fromString("85801356-29c3-4f1f-b363-3114641675b1");
    private static final UUID OFFLINE_ID = UUID.fromString("16f54277-9f89-4d94-9f78-c876845219d7");
    private static final Duration MAXIMUM = Duration.ofDays(30);

    private GriefPrevention previousInstance;
    private GriefPrevention plugin;
    private DataStore dataStore;
    private CatCraftTrustService service;
    private Player player;
    private PluginManager pluginManager;
    private Command command;
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp()
    {
        previousInstance = GriefPrevention.instance;
        plugin = mock(GriefPrevention.class);
        dataStore = mock(DataStore.class);
        service = mock(CatCraftTrustService.class);
        player = mock(Player.class);
        pluginManager = mock(PluginManager.class);
        command = mock(Command.class);
        Claim claim = mockClaim(42L);

        plugin.dataStore = dataStore;
        plugin.catCraftTrustService = service;
        plugin.config_catCraftTrustMaximumDuration = MAXIMUM;
        when(service.isStarted()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getLocation()).thenReturn(null);
        when(dataStore.getClaimAt(any(), eq(true), isNull(Claim.class))).thenReturn(claim);
        when(dataStore.getMessage(any(Messages.class), any(String[].class))).thenReturn("message");
        doCallRealMethod().when(plugin).onCommand(any(), any(), anyString(), any());
        GriefPrevention.instance = plugin;
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
    }

    @AfterEach
    void tearDown()
    {
        if (bukkit != null) bukkit.close();
        GriefPrevention.instance = previousInstance;
    }

    @Test
    void allFiveGrantCommandsAcceptPermanentAndTemporaryForms()
            throws Exception
    {
        List<String> commandNames = List.of("buildtrust", "accesstrust", "containertrust", "trust", "permissiontrust");
        List<CatCraftTrustKind> kinds = List.of(CatCraftTrustKind.BUILD, CatCraftTrustKind.ACCESS,
                CatCraftTrustKind.CONTAINER, CatCraftTrustKind.FULL, CatCraftTrustKind.MANAGE);

        for (int index = 0; index < commandNames.size(); index++)
        {
            when(command.getName()).thenReturn(commandNames.get(index));
            assertTrue(plugin.onCommand(player, command, commandNames.get(index), new String[]{"[catcraft.builders]"}));
            verify(service).grant(anyCollection(), eq("[catcraft.builders]"), eq(kinds.get(index)), isNull(Duration.class));

            reset(service);
            when(service.isStarted()).thenReturn(true);
            assertTrue(plugin.onCommand(player, command, commandNames.get(index),
                    new String[]{"[catcraft.builders]", "1D"}));
            verify(service).grant(anyCollection(), eq("[catcraft.builders]"), eq(kinds.get(index)), eq(Duration.ofDays(1)));
        }
    }

    @Test
    void buildTrustRequiresCurrentManageableClaimWhileLegacyCommandsUseAllOwnedClaimsOutside()
            throws Exception
    {
        Claim current = mockClaim(42L);
        Claim first = mockClaim(43L);
        Claim second = mockClaim(44L);
        PlayerData data = mock(PlayerData.class);
        when(data.getClaims()).thenReturn(new Vector<>(List.of(first, second)));
        when(dataStore.getPlayerData(PLAYER_ID)).thenReturn(data);

        when(command.getName()).thenReturn("buildtrust");
        when(dataStore.getClaimAt(any(), eq(true), isNull(Claim.class))).thenReturn(null);
        assertTrue(plugin.onCommand(player, command, "buildtrust", new String[]{"public"}));
        verify(service, never()).grant(anyCollection(), anyString(), any(), any());

        when(command.getName()).thenReturn("trust");
        assertTrue(plugin.onCommand(player, command, "trust", new String[]{"public"}));
        verify(service).grant(argThat(claims -> claims.size() == 2 && claims.containsAll(List.of(first, second))),
                eq("public"), eq(CatCraftTrustKind.FULL), isNull(Duration.class));

        reset(service);
        when(service.isStarted()).thenReturn(true);
        when(dataStore.getClaimAt(any(), eq(true), isNull(Claim.class))).thenReturn(current);
        when(command.getName()).thenReturn("buildtrust");
        assertTrue(plugin.onCommand(player, command, "buildtrust", new String[]{"public"}));
        verify(service).grant(argThat(claims -> claims.size() == 1 && claims.contains(current)),
                eq("public"), eq(CatCraftTrustKind.BUILD), isNull(Duration.class));
    }

    @Test
    void buildTrustRequiresManagePermissionOnCurrentClaim()
            throws Exception
    {
        Claim current = mockClaim(45L);
        when(dataStore.getClaimAt(any(), eq(true), isNull(Claim.class))).thenReturn(current);
        when(current.checkPermission(player, ClaimPermission.Manage, null)).thenReturn(() -> "denied");
        when(command.getName()).thenReturn("buildtrust");

        assertTrue(plugin.onCommand(player, command, "buildtrust", new String[]{"public"}));
        verify(service, never()).grant(anyCollection(), anyString(), any(), any());
    }

    @Test
    void invalidDurationAndTargetFailBeforeResolutionEventOrMutation()
            throws Exception
    {
        when(command.getName()).thenReturn("trust");
        assertTrue(plugin.onCommand(player, command, "trust", new String[]{"[catcraft.builders]", "31d"}));
        assertTrue(plugin.onCommand(player, command, "trust", new String[]{"[catcraft.builders]", "0h"}));
        assertTrue(plugin.onCommand(player, command, "trust", new String[]{"x".repeat(257)}));

        verify(dataStore, never()).getClaimAt(any(), anyBoolean(), any());
        verify(plugin, never()).resolvePlayerByName(anyString());
        verify(pluginManager, never()).callEvent(any(TrustChangedEvent.class));
        verify(service, never()).grant(anyCollection(), anyString(), any(), any());
    }

    @Test
    void existingTargetFormsReachServiceWithCanonicalOfflineAndGroupIdentifiers()
            throws Exception
    {
        OfflinePlayer offline = mock(OfflinePlayer.class);
        when(offline.getUniqueId()).thenReturn(OFFLINE_ID);
        when(offline.getName()).thenReturn("LongOffline");
        doAnswer(invocation -> offline).when(plugin).resolvePlayerByName(OFFLINE_ID.toString());

        when(command.getName()).thenReturn("accesstrust");
        assertTrue(plugin.onCommand(player, command, "accesstrust", new String[]{OFFLINE_ID.toString(), "1h"}));
        verify(service).grant(anyCollection(), eq(OFFLINE_ID.toString()), eq(CatCraftTrustKind.ACCESS), eq(Duration.ofHours(1)));

        reset(service);
        when(service.isStarted()).thenReturn(true);
        doAnswer(invocation -> null).when(plugin).resolvePlayerByName("public");
        assertTrue(plugin.onCommand(player, command, "accesstrust", new String[]{"public"}));
        verify(service).grant(anyCollection(), eq("public"), eq(CatCraftTrustKind.ACCESS), isNull(Duration.class));

        reset(service);
        when(service.isStarted()).thenReturn(true);
        assertTrue(plugin.onCommand(player, command, "accesstrust", new String[]{"[CatCraft.Builders]"}));
        verify(service).grant(anyCollection(), eq("[CatCraft.Builders]"), eq(CatCraftTrustKind.ACCESS), isNull(Duration.class));

        reset(service);
        when(service.isStarted()).thenReturn(true);
        doAnswer(invocation -> null).when(plugin).resolvePlayerByName("catcraft.builders");
        assertTrue(plugin.onCommand(player, command, "accesstrust", new String[]{"catcraft.builders"}));
        verify(service).grant(anyCollection(), eq("[catcraft.builders]"), eq(CatCraftTrustKind.ACCESS), isNull(Duration.class));
    }

    @Test
    void cancelledTrustEventUsesNoMutationAndFinalClaimCollection()
            throws Exception
    {
        Claim first = mockClaim(51L);
        Claim second = mockClaim(52L);
        PlayerData data = mock(PlayerData.class);
        when(data.getClaims()).thenReturn(new Vector<>(List.of(first, second)));
        when(dataStore.getPlayerData(PLAYER_ID)).thenReturn(data);
        when(dataStore.getClaimAt(any(), eq(true), isNull(Claim.class))).thenReturn(null);
        when(command.getName()).thenReturn("trust");
        doAnswer(invocation -> {
            TrustChangedEvent event = invocation.getArgument(0);
            event.getClaims().remove(second);
            return null;
        }).when(pluginManager).callEvent(any(TrustChangedEvent.class));

        assertTrue(plugin.onCommand(player, command, "trust", new String[]{"public"}));
        verify(service).grant(argThat(claims -> claims.size() == 1 && claims.contains(first)),
                eq("public"), eq(CatCraftTrustKind.FULL), isNull(Duration.class));
    }

    @Test
    void cancelledTrustEventPreventsGrant()
            throws Exception
    {
        Claim current = mockClaim(53L);
        when(dataStore.getClaimAt(any(), eq(true), isNull(Claim.class))).thenReturn(current);
        when(command.getName()).thenReturn("trust");
        doAnswer(invocation -> {
            TrustChangedEvent event = invocation.getArgument(0);
            event.setCancelled(true);
            return null;
        }).when(pluginManager).callEvent(any(TrustChangedEvent.class));

        assertTrue(plugin.onCommand(player, command, "trust", new String[]{"public"}));
        verify(service, never()).grant(anyCollection(), anyString(), any(), any());
        verify(current, never()).setPermission(anyString(), any());
    }

    @Test
    void untrustUsesExistingDropPathAndTrustListLabelsSidecarRecords()
            throws Exception
    {
        Claim claim = mockClaim(61L);
        when(dataStore.getClaimAt(any(), eq(true), isNull(Claim.class))).thenReturn(claim);
        when(command.getName()).thenReturn("untrust");
        doAnswer(invocation -> null).when(plugin).resolvePlayerByName("public");
        assertTrue(plugin.onCommand(player, command, "untrust", new String[]{"public"}));
        verify(claim).dropPermission("public");
        verify(service, never()).revoke(anyCollection(), anyString());

        reset(service);
        when(service.isStarted()).thenReturn(true);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ArrayList<String> builders = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            ArrayList<String> containers = invocation.getArgument(1);
            @SuppressWarnings("unchecked")
            ArrayList<String> accessors = invocation.getArgument(2);
            @SuppressWarnings("unchecked")
            ArrayList<String> managers = invocation.getArgument(3);
            builders.add("public");
            containers.add("[catcraft.containers]");
            accessors.add("[catcraft.builders]");
            accessors.add("[catcraft.access]");
            managers.add("[catcraft.managers]");
            return null;
        }).when(claim).getPermissions(any(), any(), any(), any());
        TemporaryTrustRecord build = new TemporaryTrustRecord(61L, "[catcraft.builders]",
                CatCraftTrustKind.BUILD, TrustDimension.PERMISSION,
                new NativeTrustState(null, false, false),
                new NativeTrustState(me.ryanhamshire.GriefPrevention.ClaimPermission.Access, false, true),
                0L, 1L, null);
        TemporaryTrustRecord temporary = new TemporaryTrustRecord(61L, "public",
                CatCraftTrustKind.FULL, TrustDimension.PERMISSION,
                new NativeTrustState(null, false, false),
                new NativeTrustState(me.ryanhamshire.GriefPrevention.ClaimPermission.Build, false, false),
                System.currentTimeMillis() + Duration.ofDays(2).toMillis(), 2L, null);
        when(service.recordsForClaim(61L)).thenReturn(List.of(build, temporary));
        when(command.getName()).thenReturn("trustlist");
        assertTrue(plugin.onCommand(player, command, "trustlist", new String[0]));

        var messages = new ArrayList<String>();
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(player, org.mockito.Mockito.atLeast(1)).sendMessage(captor.capture());
        messages.addAll(captor.getAllValues());
        String output = String.join("\n", messages);
        assertTrue(output.contains("Build Trust"));
        assertTrue(output.contains("Forever"));
        assertTrue(output.matches("(?s).*public.*[0-9]+d.*"));
        assertTrue(output.contains("[catcraft.managers]"));
        assertTrue(output.contains("[catcraft.builders]"));
        assertTrue(output.indexOf("[catcraft.builders]") == output.lastIndexOf("[catcraft.builders]"));
    }

    private Claim mockClaim(long id)
    {
        Claim claim = mock(Claim.class);
        claim.managers = new ArrayList<>();
        when(claim.getID()).thenReturn(id);
        when(claim.getOwnerName()).thenReturn("Owner");
        return claim;
    }
}
