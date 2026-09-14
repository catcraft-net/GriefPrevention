package me.ryanhamshire.GriefPrevention.catcrafttrust;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Main-thread state machine for temporary trust decisions. GriefPrevention's
 * native claim state remains authoritative; this service journals enough state
 * to safely restore a temporary decision when it expires.
 */
public final class CatCraftTrustService
{
    private static final long MILLIS_PER_TICK = 50L;
    private static final int DEFAULT_EXPIRATIONS_PER_TICK = 64;

    private final CatCraftTrustStateStore store;
    private final ClaimTrustAccess claimAccess;
    private final TrustTaskScheduler scheduler;
    private final LongSupplier nowMillis;
    private final int maximumExpirationsPerTick;
    private final Logger logger;
    private ScheduledHandle expirationHandle;
    private boolean nextTickScheduled;
    private boolean loaded;
    private int internalMutationDepth;

    public CatCraftTrustService(CatCraftTrustStateStore store,
                                ClaimTrustAccess claimAccess,
                                TrustTaskScheduler scheduler)
    {
        this(store, claimAccess, scheduler, System::currentTimeMillis, DEFAULT_EXPIRATIONS_PER_TICK);
    }

    public CatCraftTrustService(CatCraftTrustStateStore store,
                                ClaimTrustAccess claimAccess,
                                TrustTaskScheduler scheduler,
                                LongSupplier nowMillis,
                                int maximumExpirationsPerTick)
    {
        this.store = Objects.requireNonNull(store, "store");
        this.claimAccess = Objects.requireNonNull(claimAccess, "claimAccess");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis");
        if (maximumExpirationsPerTick <= 0)
        {
            throw new IllegalArgumentException("maximumExpirationsPerTick must be positive");
        }
        this.maximumExpirationsPerTick = maximumExpirationsPerTick;
        this.logger = Logger.getLogger(CatCraftTrustService.class.getName());
    }

    public CatCraftTrustService(CatCraftTrustStateStore store,
                                ClaimTrustAccess claimAccess,
                                TrustTaskScheduler scheduler,
                                Clock clock,
                                int maximumExpirationsPerTick)
    {
        this(store, claimAccess, scheduler, Objects.requireNonNull(clock, "clock")::millis,
                maximumExpirationsPerTick);
    }

    public CatCraftTrustService(Path file,
                                int maximumRecords,
                                ClaimTrustAccess claimAccess,
                                TrustTaskScheduler scheduler,
                                int maximumExpirationsPerTick)
    {
        this(new CatCraftTrustStateStore(file, maximumRecords), claimAccess, scheduler,
                System::currentTimeMillis, maximumExpirationsPerTick);
    }

    public void start() throws IOException
    {
        if (expirationHandle != null)
        {
            expirationHandle.cancel();
            expirationHandle = null;
        }
        nextTickScheduled = false;
        store.load();
        loaded = true;
        reconcileStartup();
        scheduleNextExpiration();
    }

    public void stop() throws IOException
    {
        if (expirationHandle != null) expirationHandle.cancel();
        expirationHandle = null;
        nextTickScheduled = false;
        if (loaded) store.save();
        loaded = false;
    }

    public boolean isSafeBuilder(Claim claim, UUID playerId, @Nullable Player player)
    {
        if (claim == null || claim.getID() == null) return false;
        Set<Long> visited = new HashSet<>();
        Long claimId = claim.getID();
        ClaimSnapshot fallback = new ClaimSnapshot(claimId, claim.getOwnerID(),
                claim.parent == null ? null : claim.parent.getID(), false);
        ClaimSnapshot resolved = claimAccess.resolve(claimId);
        return isSafeBuilder(claimId, playerId, player,
                resolved == null ? fallback : resolved, visited);
    }

    public void grant(Collection<Claim> claims,
                      String target,
                      CatCraftTrustKind kind,
                      @Nullable Duration duration) throws IOException
    {
        Objects.requireNonNull(claims, "claims");
        Objects.requireNonNull(kind, "kind");
        String canonicalTarget = canonicalTarget(target);
        processDue(nowMillis.getAsLong());
        List<GrantMutation> mutations = new ArrayList<>();
        for (Claim claim : claims)
        {
            if (claim == null || claim.getID() == null) continue;
            long claimId = claim.getID();
            ClaimSnapshot snapshot = snapshotFor(claim);
            TrustDimension dimension = dimensionFor(kind);
            NativeTrustState current = claimAccess.capture(claimId, canonicalTarget, dimension);
            Optional<TemporaryTrustRecord> previousRecord = store.get(claimId, canonicalTarget, dimension);
            NativeTrustState baseline = previousRecord.isPresent()
                    && current.equals(previousRecord.get().expectedState())
                    ? previousRecord.get().previousState() : current;
            NativeTrustState expected = desiredState(kind, current);
            if (duration == null)
            {
                store.remove(claimId + "|" + dimension + "|" + canonicalTarget);
            }
            else
            {
                long expiresAt = safeExpiry(nowMillis.getAsLong(), duration);
                TemporaryTrustRecord record = new TemporaryTrustRecord(
                        claimId, canonicalTarget, kind, dimension, baseline, expected,
                        expiresAt, store.nextRevision(), snapshot.ownerId());
                store.put(record);
            }
            mutations.add(new GrantMutation(claimId, canonicalTarget, dimension, expected));
        }

        store.save();
        try
        {
            for (GrantMutation mutation : mutations)
            {
                withInternalMutation(() -> claimAccess.apply(mutation.claimId(), mutation.target(),
                        mutation.expectedState(), mutation.dimension()));
                claimAccess.save(mutation.claimId());
            }
        }
        catch (RuntimeException failure)
        {
            logger.severe("Could not apply CatCraft trust change: " + failure.getMessage());
            throw failure;
        }
        scheduleNextExpiration();
    }

    public void revoke(Collection<Claim> claims, String target) throws IOException
    {
        Objects.requireNonNull(claims, "claims");
        String canonicalTarget = canonicalTarget(target);
        processDue(nowMillis.getAsLong());
        List<RevokeMutation> mutations = new ArrayList<>();
        for (Claim claim : claims)
        {
            if (claim == null || claim.getID() == null) continue;
            long claimId = claim.getID();
            store.removeTargetAll(claimId, canonicalTarget);
            NativeTrustState permission = claimAccess.capture(claimId, canonicalTarget, TrustDimension.PERMISSION);
            NativeTrustState manager = claimAccess.capture(claimId, canonicalTarget, TrustDimension.MANAGER);
            mutations.add(new RevokeMutation(claimId, canonicalTarget,
                    new NativeTrustState(null, permission.manager(), false),
                    new NativeTrustState(manager.permission(), false, manager.safeBuild())));
        }
        store.save();
        for (RevokeMutation mutation : mutations)
        {
            withInternalMutation(() -> {
                claimAccess.apply(mutation.claimId(), mutation.target(), mutation.permission(), TrustDimension.PERMISSION);
                claimAccess.apply(mutation.claimId(), mutation.target(), mutation.manager(), TrustDimension.MANAGER);
            });
            claimAccess.save(mutation.claimId());
        }
        scheduleNextExpiration();
    }

    public void clearClaims(Collection<Claim> claims) throws IOException
    {
        Objects.requireNonNull(claims, "claims");
        boolean changed = false;
        for (Claim claim : claims)
        {
            if (claim != null && claim.getID() != null) changed |= store.removeClaim(claim.getID()) > 0;
        }
        if (changed) store.save();
        scheduleNextExpiration();
    }

    public void onClaimDeleted(long claimId)
    {
        store.removeClaim(claimId);
        persistBestEffort();
        scheduleNextExpiration();
    }

    public void onClaimOwnerChanging(Claim claim)
    {
        if (claim == null || claim.getID() == null) return;
        store.removeClaim(claim.getID());
        persistBestEffort();
        scheduleNextExpiration();
    }

    public void onExternalPermissionMutation(Claim claim, String target, TrustDimension dimension)
    {
        if (internalMutationDepth > 0 || claim == null || claim.getID() == null || dimension == null) return;
        try
        {
            store.remove(claim.getID() + "|" + dimension + "|" + canonicalTarget(target));
        }
        catch (RuntimeException ex)
        {
            logger.severe("Could not invalidate CatCraft trust metadata: " + ex.getMessage());
            return;
        }
        persistBestEffort();
        scheduleNextExpiration();
    }

    public List<TemporaryTrustRecord> recordsForClaim(long claimId)
    {
        return store.forClaim(claimId);
    }

    public boolean isInternalMutation()
    {
        return internalMutationDepth > 0;
    }

    void processDue(long now)
    {
        if (!loaded) return;
        if (expirationHandle != null)
        {
            expirationHandle.cancel();
            expirationHandle = null;
        }
        int processed = 0;
        while (processed < maximumExpirationsPerTick)
        {
            Optional<TemporaryTrustRecord> next = store.nextExpiring();
            if (next.isEmpty() || next.get().expiresAtMillis() > now) break;
            TemporaryTrustRecord record = next.get();
            if (expire(record)) processed++;
            else break;
        }
        Optional<TemporaryTrustRecord> next = store.nextExpiring();
        if (next.isPresent() && next.get().expiresAtMillis() <= now)
        {
            scheduleNextTick();
        }
        else
        {
            scheduleNextExpiration();
        }
    }

    private boolean expire(TemporaryTrustRecord record)
    {
        ClaimSnapshot snapshot = claimAccess.resolve(record.claimId());
        if (snapshot == null || !Objects.equals(snapshot.ownerId(), record.ownerIdAtGrant()))
        {
            store.removeIfRevision(record.key(), record.revision());
            persistBestEffort();
            return true;
        }
        NativeTrustState current = claimAccess.capture(record.claimId(), record.target(), record.dimension());
        if (current.equals(record.previousState()))
        {
            store.removeIfRevision(record.key(), record.revision());
            persistBestEffort();
            return true;
        }
        if (!current.equals(record.expectedState()))
        {
            store.removeIfRevision(record.key(), record.revision());
            persistBestEffort();
            return true;
        }
        try
        {
            withInternalMutation(() -> claimAccess.apply(record.claimId(), record.target(),
                    record.previousState(), record.dimension()));
            claimAccess.save(record.claimId());
            store.removeIfRevision(record.key(), record.revision());
            persistBestEffort();
            return true;
        }
        catch (RuntimeException failure)
        {
            logger.severe("Could not restore expired CatCraft trust for claim "
                    + record.claimId() + ": " + failure.getMessage());
            return false;
        }
    }

    private void reconcileStartup() throws IOException
    {
        boolean changed = false;
        long now = nowMillis.getAsLong();
        for (TemporaryTrustRecord record : store.values())
        {
            ClaimSnapshot snapshot = claimAccess.resolve(record.claimId());
            if (snapshot == null || !Objects.equals(snapshot.ownerId(), record.ownerIdAtGrant()))
            {
                store.removeIfRevision(record.key(), record.revision());
                changed = true;
                continue;
            }
            NativeTrustState current = claimAccess.capture(record.claimId(), record.target(), record.dimension());
            if (!current.equals(record.expectedState()))
            {
                store.removeIfRevision(record.key(), record.revision());
                changed = true;
                continue;
            }
            if (record.expiresAtMillis() > 0 && record.expiresAtMillis() <= now && expire(record))
            {
                changed = true;
            }
        }
        if (changed) store.save();
    }

    private boolean isSafeBuilder(long claimId,
                                  UUID playerId,
                                  @Nullable Player player,
                                  ClaimSnapshot snapshot,
                                  Set<Long> visited)
    {
        if (!visited.add(claimId)) return false;
        long now = nowMillis.getAsLong();
        Optional<TemporaryTrustRecord> record = store.findSafeBuild(
                claimId, playerId, player, now, snapshot.ownerId());
        if (record.isPresent())
        {
            NativeTrustState current = claimAccess.capture(claimId, record.get().target(), record.get().dimension());
            if (current.equals(record.get().expectedState())) return true;
            store.removeIfRevision(record.get().key(), record.get().revision());
            persistBestEffort();
        }
        if (snapshot.restricted() || snapshot.parentId() == null) return false;
        ClaimSnapshot parent = claimAccess.resolve(snapshot.parentId());
        return parent != null && isSafeBuilder(parent.claimId(), playerId, player, parent, visited);
    }

    private ClaimSnapshot snapshotFor(Claim claim)
    {
        ClaimSnapshot resolved = claimAccess.resolve(claim.getID());
        if (resolved != null) return resolved;
        return new ClaimSnapshot(claim.getID(), claim.getOwnerID(),
                claim.parent == null ? null : claim.parent.getID(), false);
    }

    private void scheduleNextExpiration()
    {
        if (!loaded) return;
        if (expirationHandle != null)
        {
            expirationHandle.cancel();
            expirationHandle = null;
        }
        Optional<TemporaryTrustRecord> next = store.nextExpiring();
        if (next.isEmpty()) return;
        long delayMillis = next.get().expiresAtMillis() - nowMillis.getAsLong();
        if (delayMillis <= 0)
        {
            scheduleNextTick();
            return;
        }
        long delayTicks = Math.max(1L, ((delayMillis - 1L) / MILLIS_PER_TICK) + 1L);
        expirationHandle = scheduler.schedule(delayTicks, () -> processDue(nowMillis.getAsLong()));
    }

    private void scheduleNextTick()
    {
        if (nextTickScheduled) return;
        nextTickScheduled = true;
        scheduler.nextTick(() -> {
            nextTickScheduled = false;
            processDue(nowMillis.getAsLong());
        });
    }

    private void persistBestEffort()
    {
        try
        {
            store.save();
        }
        catch (IOException ex)
        {
            logger.severe("Could not persist CatCraft trust state: " + ex.getMessage());
        }
    }

    private static String canonicalTarget(String target)
    {
        if (target == null) throw new IllegalArgumentException("target must not be null");
        String canonical = target.trim().toLowerCase(Locale.ROOT);
        if (canonical.isEmpty() || canonical.length() > TemporaryTrustRecord.MAX_TARGET_LENGTH)
        {
            throw new IllegalArgumentException("target must be nonblank and bounded");
        }
        return canonical;
    }

    private static TrustDimension dimensionFor(CatCraftTrustKind kind)
    {
        return kind == CatCraftTrustKind.MANAGE ? TrustDimension.MANAGER : TrustDimension.PERMISSION;
    }

    private static NativeTrustState desiredState(CatCraftTrustKind kind, NativeTrustState current)
    {
        return switch (kind)
        {
            case BUILD -> new NativeTrustState(ClaimPermission.Access, current.manager(), true);
            case ACCESS -> new NativeTrustState(ClaimPermission.Access, current.manager(), false);
            case CONTAINER -> new NativeTrustState(ClaimPermission.Inventory, current.manager(), false);
            case FULL -> new NativeTrustState(ClaimPermission.Build, current.manager(), false);
            case MANAGE -> new NativeTrustState(current.permission(), true, current.safeBuild());
        };
    }

    private static long safeExpiry(long now, Duration duration)
    {
        Objects.requireNonNull(duration, "duration");
        final long millis;
        try
        {
            millis = duration.toMillis();
        }
        catch (ArithmeticException ex)
        {
            throw new IllegalArgumentException("duration is too large", ex);
        }
        if (millis <= 0 || now > Long.MAX_VALUE - millis)
        {
            throw new IllegalArgumentException("duration must be positive and fit the clock");
        }
        return now + millis;
    }

    private void withInternalMutation(Runnable action)
    {
        internalMutationDepth++;
        try
        {
            action.run();
        }
        finally
        {
            internalMutationDepth--;
        }
    }

    private record GrantMutation(long claimId, String target, TrustDimension dimension,
                                 NativeTrustState expectedState)
    {
    }

    private record RevokeMutation(long claimId, String target, NativeTrustState permission,
                                  NativeTrustState manager)
    {
    }
}

record ClaimSnapshot(long claimId, @Nullable UUID ownerId, @Nullable Long parentId, boolean restricted)
{
}

interface ClaimTrustAccess
{
    @Nullable ClaimSnapshot resolve(long claimId);

    NativeTrustState capture(long claimId, String target, TrustDimension dimension);

    void apply(long claimId, String target, NativeTrustState state, TrustDimension dimension);

    void save(long claimId);
}

interface TrustTaskScheduler
{
    ScheduledHandle schedule(long delayTicks, Runnable task);

    void nextTick(Runnable task);
}

interface ScheduledHandle
{
    void cancel();
}
