package me.ryanhamshire.GriefPrevention.catcrafttrust;

import me.ryanhamshire.GriefPrevention.ClaimPermission;
import org.jetbrains.annotations.Nullable;

public record NativeTrustState(@Nullable ClaimPermission permission, boolean manager, boolean safeBuild)
{
    @SuppressWarnings("removal")
    public NativeTrustState
    {
        // Records saved before the upstream rename store Inventory; claims now load that trust as Container.
        if (permission == ClaimPermission.Inventory) permission = ClaimPermission.Container;
    }
}
