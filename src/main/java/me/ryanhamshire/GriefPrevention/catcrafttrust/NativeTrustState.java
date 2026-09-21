package me.ryanhamshire.GriefPrevention.catcrafttrust;

import me.ryanhamshire.GriefPrevention.ClaimPermission;
import org.jetbrains.annotations.Nullable;

public record NativeTrustState(@Nullable ClaimPermission permission, boolean manager, boolean safeBuild)
{
}
