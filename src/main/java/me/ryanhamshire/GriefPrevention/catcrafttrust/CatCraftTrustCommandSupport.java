package me.ryanhamshire.GriefPrevention.catcrafttrust;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public final class CatCraftTrustCommandSupport
{
    private static final Map<String, CatCraftTrustKind> COMMAND_KINDS = Map.of(
            "buildtrust", CatCraftTrustKind.BUILD,
            "accesstrust", CatCraftTrustKind.ACCESS,
            "containertrust", CatCraftTrustKind.CONTAINER,
            "trust", CatCraftTrustKind.FULL,
            "permissiontrust", CatCraftTrustKind.MANAGE,
            "managetrust", CatCraftTrustKind.MANAGE);
    private static final List<String> DURATION_SUGGESTIONS = List.of(
            "1h", "1d", "1w", "4w", "forever");
    private static final Comparator<String> PLAYER_NAME_ORDER = Comparator
            .comparing((String name) -> name.toLowerCase(Locale.ROOT))
            .thenComparing(Comparator.naturalOrder());

    public Optional<TrustCommandRequest> parse(String commandName,
                                                String[] args,
                                                Duration maximum,
                                                Consumer<String> error)
    {
        Objects.requireNonNull(error, "error");
        if (commandName == null || args == null || args.length < 1 || args.length > 2)
        {
            error.accept(CatCraftMessages.usage(commandName));
            return Optional.empty();
        }

        CatCraftTrustKind kind = COMMAND_KINDS.get(commandName.toLowerCase(Locale.ROOT));
        if (kind == null)
        {
            error.accept(CatCraftMessages.usage(commandName));
            return Optional.empty();
        }

        Duration duration = null;
        if (args.length == 2)
        {
            try
            {
                Duration parsed = DurationParser.parse(args[1], maximum);
                duration = parsed.isZero() ? null : parsed;
            }
            catch (InvalidDurationException | IllegalArgumentException ex)
            {
                error.accept(CatCraftMessages.invalidDuration());
                return Optional.empty();
            }
        }

        return Optional.of(new TrustCommandRequest(args[0], kind, duration));
    }

    public List<String> completePlayers(CommandSender sender,
                                        String prefix,
                                        Collection<? extends Player> onlinePlayers)
    {
        Objects.requireNonNull(onlinePlayers, "onlinePlayers");
        String normalizedPrefix = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Player player : onlinePlayers)
        {
            if (player == null) continue;
            if (sender instanceof Player viewer && !viewer.canSee(player)) continue;
            String name = player.getName();
            if (name != null && name.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
            {
                names.add(name);
            }
        }
        names.sort(PLAYER_NAME_ORDER);
        return List.copyOf(names);
    }

    public List<String> completeDurations(String prefix, Duration maximum)
    {
        Objects.requireNonNull(maximum, "maximum");
        String normalizedPrefix = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String suggestion : DURATION_SUGGESTIONS)
        {
            if (!suggestion.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix)) continue;
            try
            {
                DurationParser.parse(suggestion, maximum);
                matches.add(suggestion);
            }
            catch (InvalidDurationException ignored)
            {
                // A suggestion above the configured maximum is omitted.
            }
        }
        return List.copyOf(matches);
    }
}
