package me.ryanhamshire.GriefPrevention.catcrafttrust;

import me.ryanhamshire.GriefPrevention.Claim;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Test-only bridge for exercising the real trust service through Claim. */
public final class SafeBuildIntegrationHarness
{
    private SafeBuildIntegrationHarness()
    {
    }

    public static CatCraftTrustService start(Path directory, Claim... claims) throws Exception
    {
        Map<Long, Claim> claimsById = new HashMap<>();
        for (Claim claim : claims)
        {
            claimsById.put(claim.getID(), claim);
        }
        ClaimAccess access = new ClaimAccess(claimsById);
        CatCraftTrustService service = new CatCraftTrustService(
                directory.resolve("safe-build-" + UUID.randomUUID() + ".properties"),
                100, access, new NoOpScheduler(), 10);
        service.start();
        return service;
    }

    private static final class ClaimAccess implements ClaimTrustAccess
    {
        private final Map<Long, Claim> claims;
        private final Set<String> safeBuild = new HashSet<>();

        private ClaimAccess(Map<Long, Claim> claims)
        {
            this.claims = claims;
        }

        @Override
        public ClaimSnapshot resolve(long claimId)
        {
            Claim claim = claims.get(claimId);
            if (claim == null) return null;
            return new ClaimSnapshot(claimId, claim.getOwnerID(),
                    claim.parent == null ? null : claim.parent.getID(),
                    claim.getSubclaimRestrictions());
        }

        @Override
        public NativeTrustState capture(long claimId, String target, TrustDimension dimension)
        {
            Claim claim = claims.get(claimId);
            if (claim == null) return new NativeTrustState(null, false, false);
            String canonical = target.toLowerCase(Locale.ROOT);
            return new NativeTrustState(claim.getPermission(canonical),
                    claim.managers.contains(canonical),
                    safeBuild.contains(key(claimId, canonical)));
        }

        @Override
        public void apply(long claimId, String target, NativeTrustState state, TrustDimension dimension)
        {
            Claim claim = claims.get(claimId);
            if (claim == null) throw new IllegalStateException("missing claim");
            String canonical = target.toLowerCase(Locale.ROOT);
            if (dimension == TrustDimension.PERMISSION)
            {
                if (state.permission() == null)
                {
                    claim.dropPermission(canonical);
                    if (state.manager()) claim.managers.add(canonical);
                }
                else
                {
                    claim.setPermission(canonical, state.permission());
                }
                if (state.safeBuild()) safeBuild.add(key(claimId, canonical));
                else safeBuild.remove(key(claimId, canonical));
            }
            else if (state.manager())
            {
                claim.managers.add(canonical);
            }
            else
            {
                claim.managers.remove(canonical);
            }
        }

        @Override
        public void save(long claimId)
        {
        }

        private static String key(long claimId, String target)
        {
            return claimId + "|" + target;
        }
    }

    private static final class NoOpScheduler implements TrustTaskScheduler
    {
        @Override
        public ScheduledHandle schedule(long delayTicks, Runnable task)
        {
            return () -> { };
        }

        @Override
        public void nextTick(Runnable task)
        {
        }
    }
}
