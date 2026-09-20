package me.ryanhamshire.GriefPrevention;

import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrustTabCompletionIntegrationTest
{
    private GriefPrevention plugin;
    private Server server;
    private Command command;
    private Player viewer;

    @BeforeEach
    void setUp()
    {
        plugin = mock(GriefPrevention.class);
        server = mock(Server.class);
        command = mock(Command.class);
        viewer = mock(Player.class);
        plugin.config_catCraftTrustMaximumDuration = Duration.ofDays(2);
        doCallRealMethod().when(plugin).onTabComplete(any(), any(), anyString(), any());
        when(plugin.getServer()).thenReturn(server);
    }

    @Test
    void completesOnlyVisibleOnlinePlayersForEveryTrustCommand()
    {
        Player zed = mock(Player.class);
        Player alice = mock(Player.class);
        Player hidden = mock(Player.class);
        when(zed.getName()).thenReturn("Zed");
        when(alice.getName()).thenReturn("Alice");
        when(hidden.getName()).thenReturn("Zelda");
        when(viewer.canSee(zed)).thenReturn(true);
        when(viewer.canSee(alice)).thenReturn(true);
        when(viewer.canSee(hidden)).thenReturn(false);
        doReturn(List.of(zed, alice, hidden)).when(server).getOnlinePlayers();
        when(command.getName()).thenReturn("trust");

        for (String name : List.of("buildtrust", "trust", "accesstrust", "containertrust", "permissiontrust"))
        {
            when(command.getName()).thenReturn(name);
            String label = name.equals("buildtrust") ? "bt" : name;
            assertEquals(List.of("Zed"), plugin.onTabComplete(viewer, command, label, new String[]{"z"}));
        }

        verify(server, times(5)).getOnlinePlayers();
        verify(viewer, times(5)).canSee(zed);
        verify(viewer, times(5)).canSee(alice);
        verify(viewer, times(5)).canSee(hidden);
        verify(server, never()).getOfflinePlayers();
        verify(server, never()).getOfflinePlayer(any(java.util.UUID.class));
        verify(plugin, never()).resolvePlayerByName(anyString());
    }

    @Test
    void completionUsesConfiguredMaximumForDurationsAndConsoleVisibility()
    {
        CommandSender console = mock(CommandSender.class);
        when(command.getName()).thenReturn("buildtrust");
        assertEquals(List.of("1h", "1d", "forever"),
                plugin.onTabComplete(console, command, "buildtrust", new String[]{"target", ""}));
        assertEquals(List.of("1d"),
                plugin.onTabComplete(console, command, "buildtrust", new String[]{"target", "1D"}));
        assertEquals(List.of(),
                plugin.onTabComplete(console, command, "buildtrust", new String[]{"x", "1h", "extra"}));
        verify(server, never()).getOnlinePlayers();
    }

    @Test
    void nonTrustCommandsReturnNullForBukkitFallbackCompletion()
    {
        when(command.getName()).thenReturn("claimslist");
        assertEquals(null, plugin.onTabComplete(viewer, command, "claimslist", new String[]{"a"}));
        verify(server, never()).getOnlinePlayers();
    }

    @Test
    void trustAliasesStillUseTheirCanonicalCommandCompletion()
    {
        Player alice = mock(Player.class);
        when(alice.getName()).thenReturn("Alice");
        when(viewer.canSee(alice)).thenReturn(true);
        doReturn(List.of(alice)).when(server).getOnlinePlayers();
        when(command.getName()).thenReturn("permissiontrust");

        assertEquals(List.of("Alice"), plugin.onTabComplete(viewer, command, "pt", new String[]{"a"}));
        assertEquals(List.of("Alice"), plugin.onTabComplete(viewer, command, "managetrust", new String[]{"a"}));
    }
}
