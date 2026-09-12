package me.ryanhamshire.GriefPrevention;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

final class OfflineUntrustResolver
{
    private OfflineUntrustResolver()
    {
    }

    static OfflinePlayer resolve(GriefPrevention plugin, Player player, Claim currentClaim, String requestedName)
    {
        OfflinePlayer normallyResolved = plugin.resolvePlayerByName(requestedName);
        if (normallyResolved != null)
        {
            return normallyResolved;
        }
        if (!shouldSearchTrustEntries(requestedName))
        {
            return null;
        }

        Iterable<Claim> roots = currentClaim != null
                ? List.of(currentClaim)
                : plugin.dataStore.getPlayerData(player.getUniqueId()).getClaims();
        UUID playerId = findUniqueTrustedUuid(
                requestedName,
                roots,
                id -> plugin.getServer().getOfflinePlayer(id).getName());
        return playerId == null ? null : plugin.getServer().getOfflinePlayer(playerId);
    }

    static boolean shouldSearchTrustEntries(String requestedName)
    {
        return requestedName != null
                && !requestedName.equals("public")
                && !requestedName.contains(".");
    }

    static UUID findUniqueTrustedUuid(
            String requestedName,
            Iterable<Claim> roots,
            Function<UUID, String> nameLookup)
    {
        if (requestedName == null || requestedName.isBlank())
        {
            return null;
        }

        Search search = new Search(requestedName, nameLookup);
        for (Claim root : roots)
        {
            if (root != null && !search.scan(root))
            {
                return null;
            }
        }
        return search.match;
    }

    private static final class Search
    {
        private final String requestedName;
        private final Function<UUID, String> nameLookup;
        private final Set<UUID> checked = new HashSet<>();
        private final ArrayList<String> builders = new ArrayList<>();
        private final ArrayList<String> containers = new ArrayList<>();
        private final ArrayList<String> accessors = new ArrayList<>();
        private final ArrayList<String> managers = new ArrayList<>();
        private UUID match;

        private Search(String requestedName, Function<UUID, String> nameLookup)
        {
            this.requestedName = requestedName;
            this.nameLookup = nameLookup;
        }

        private boolean scan(Claim claim)
        {
            builders.clear();
            containers.clear();
            accessors.clear();
            managers.clear();
            claim.getPermissions(builders, containers, accessors, managers);

            if (!scanEntries(builders)
                    || !scanEntries(containers)
                    || !scanEntries(accessors)
                    || !scanEntries(managers))
            {
                return false;
            }
            for (Claim child : claim.children)
            {
                if (child != null && !scan(child))
                {
                    return false;
                }
            }
            return true;
        }

        private boolean scanEntries(Iterable<String> entries)
        {
            for (String entry : entries)
            {
                UUID playerId;
                try
                {
                    playerId = UUID.fromString(entry);
                }
                catch (IllegalArgumentException | NullPointerException ignored)
                {
                    continue;
                }
                if (!checked.add(playerId))
                {
                    continue;
                }

                String playerName = nameLookup.apply(playerId);
                if (playerName == null || !playerName.equalsIgnoreCase(requestedName))
                {
                    continue;
                }
                if (match != null && !match.equals(playerId))
                {
                    return false;
                }
                match = playerId;
            }
            return true;
        }
    }
}
