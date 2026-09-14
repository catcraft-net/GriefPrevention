package me.ryanhamshire.GriefPrevention.catcrafttrust;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatCraftTrustCommandSupportTest
{
    private static final Duration MAXIMUM = Duration.ofDays(30);

    private final CatCraftTrustCommandSupport support = new CatCraftTrustCommandSupport();

    @Test
    void mapsTrustCommandsAndPreservesTheTarget()
    {
        for (var command : List.of(
                new Object[]{"buildtrust", CatCraftTrustKind.BUILD},
                new Object[]{"accesstrust", CatCraftTrustKind.ACCESS},
                new Object[]{"containertrust", CatCraftTrustKind.CONTAINER},
                new Object[]{"trust", CatCraftTrustKind.FULL},
                new Object[]{"permissiontrust", CatCraftTrustKind.MANAGE},
                new Object[]{"managetrust", CatCraftTrustKind.MANAGE}))
        {
            List<String> errors = new ArrayList<>();
            Optional<TrustCommandRequest> request = support.parse(
                    (String) command[0], new String[]{"  Player  "}, MAXIMUM, errors::add);

            assertTrue(request.isPresent(), (String) command[0]);
            assertEquals(command[1], request.orElseThrow().kind());
            assertEquals("  Player  ", request.orElseThrow().target());
            assertEquals(null, request.orElseThrow().duration());
            assertTrue(errors.isEmpty());
        }
    }

    @Test
    void parsesCaseInsensitiveDurationAndNormalizesForeverToPermanent()
    {
        List<String> errors = new ArrayList<>();

        TrustCommandRequest timed = support.parse(
                "BUILDTRUST", new String[]{"Steve", "1D"}, MAXIMUM, errors::add).orElseThrow();
        TrustCommandRequest permanent = support.parse(
                "trust", new String[]{"Steve", "FOREVER"}, MAXIMUM, errors::add).orElseThrow();

        assertEquals(CatCraftTrustKind.BUILD, timed.kind());
        assertEquals(Duration.ofDays(1), timed.duration());
        assertEquals(null, permanent.duration());
        assertTrue(errors.isEmpty());
    }

    @Test
    void rejectsWrongArityAndInvalidDurationWithOnePrefixedErrorEach()
    {
        List<String> arityErrors = new ArrayList<>();
        assertFalse(support.parse("trust", new String[]{"Steve", "1h", "extra"},
                MAXIMUM, arityErrors::add).isPresent());
        assertEquals(List.of("&b[CatCraft] &eUse /trust <player> [time]."), arityErrors);

        List<String> durationErrors = new ArrayList<>();
        assertFalse(support.parse("trust", new String[]{"Steve", "2W"},
                Duration.ofDays(1), durationErrors::add).isPresent());
        assertEquals(1, durationErrors.size());
        assertTrue(durationErrors.get(0).startsWith("&b[CatCraft]"));
    }

    @Test
    void completesVisiblePlayersByCaseInsensitivePrefixAndReturnsImmutableNames()
    {
        Player sender = mock(Player.class);
        Player visible = mock(Player.class);
        Player invisible = mock(Player.class);
        when(visible.getName()).thenReturn("Zed");
        when(invisible.getName()).thenReturn("Alice");
        when(sender.canSee(visible)).thenReturn(true);
        when(sender.canSee(invisible)).thenReturn(false);

        List<String> result = support.completePlayers(sender, "z", List.of(invisible, visible));

        assertEquals(List.of("Zed"), result);
        assertThrows(UnsupportedOperationException.class, () -> result.add("other"));
        verify(sender).canSee(visible);
        verify(sender).canSee(invisible);
    }

    @Test
    void consoleCompletionIncludesAllMatchingOnlinePlayers()
    {
        CommandSender console = mock(CommandSender.class);
        Player first = mock(Player.class);
        Player second = mock(Player.class);
        when(first.getName()).thenReturn("zulu");
        when(second.getName()).thenReturn("Alpha");

        assertEquals(List.of("Alpha", "zulu"),
                support.completePlayers(console, "", List.of(first, second)));
    }

    @Test
    void completesOnlyDurationSuggestionsWithinConfiguredMaximum()
    {
        assertEquals(List.of("1h", "1d", "forever"),
                support.completeDurations("", Duration.ofDays(2)));
        assertEquals(List.of("1d"),
                support.completeDurations("1D", Duration.ofDays(2)));
        assertThrows(UnsupportedOperationException.class,
                () -> support.completeDurations("", Duration.ofDays(2)).add("2d"));
    }
}
