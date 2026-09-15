package me.ryanhamshire.GriefPrevention.catcrafttrust;

import org.jetbrains.annotations.Nullable;

import java.time.Duration;

public record TrustCommandRequest(String target, CatCraftTrustKind kind, @Nullable Duration duration)
{
}
