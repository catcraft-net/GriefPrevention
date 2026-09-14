package me.ryanhamshire.GriefPrevention.catcrafttrust;

import me.ryanhamshire.GriefPrevention.Claim;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Read-only adapter used by the container listener. Implementations resolve
 * current trust on each call and must not hand Bukkit objects to the listener
 * for retention.
 */
public interface SafeBuildTrustProvider
{
    boolean isSafeBuilder(Claim claim, UUID playerId, @Nullable Player player);
}
