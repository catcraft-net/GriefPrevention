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

    /**
     * Fast global hint used to avoid claim resolution on servers with no Safe
     * Build grants. The runtime implementation should keep this indexed.
     */
    default boolean hasAnySafeBuilders()
    {
        return false;
    }

    /**
     * Fast exact-claim lookup used by event-time automation guards. The
     * runtime implementation must not scan claims or perform I/O here.
     */
    default boolean hasAnySafeBuilder(Claim claim)
    {
        return false;
    }
}
