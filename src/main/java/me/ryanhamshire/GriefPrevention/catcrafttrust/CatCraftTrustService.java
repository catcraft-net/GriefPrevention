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
        cancelScheduledExpiration();
        store.load();
        loaded = true;
        reconcileStartup();
        scheduleNextExpiration();
    }

    public void stop() throws IOException
    {
        cancelScheduledExpiration();
        if (loaded) store.save();
        loaded = false;
    }

    public boolean isSafeBuilder(Claim claim, UUID playerId, @Nullable Player player)
    {
        if (claim == null || claim.getID() == null) return false;
        Set<Long> visited = new HashSet<>();
        Long claimId = claim.getID();
        ClaimSnapshot fallback = new ClaimSnapshot(claimId, claim.getOwnerID(),
                claim.parent == null ? null : claim.parent.getID(), claim.getSubclaimRestrictions());
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
        List<Claim> eligibleClaims = new ArrayList<>();
        Set<String> batchKeys = new HashSet<>();
        TrustDimension batchDimension = dimensionFor(kind);
        for (Claim claim : claims)
        {
            if (claim != null && claim.getID() != null)
            {
                eligibleClaims.add(claim);
                batchKeys.add(TemporaryTrustRecordKey.key(
                        claim.getID(), canonicalTarget, batchDimension));
            }
        }
        store.ensureCapacity(batchKeys);
        List<GrantMutation> mutations = new ArrayList<>();
        for (Claim claim : eligibleClaims)
        {
            if (claim == null || claim.getID() == null) continue;
            long claimId = claim.getID();
            ClaimSnapshot snapshot = snapshotFor(claim);
            TrustDimension dimension = dimensionFor(kind);
            NativeTrustState current = claimAccess.capture(claimId, canonicalTarget, dimension);
            Optional<TemporaryTrustRecord> previousRecord = store.get(claimId, canonicalTarget, dimension);
            NativeTrustState baseline = previousRecord.isPresent()
                    && current.equals(previousRecord.get().expectedState())
                    ? replacementBaseline(previousRecord.get()) : current;
            NativeTrustState expected = desiredState(kind, current);
            TemporaryTrustRecord precedingRecord = previousRecord.isPresent()
                    && current.equals(previousRecord.get().expectedState())
                    ? previousRecord.get() : null;
            TemporaryTrustRecord intendedRecord = null;
            if (duration != null || kind == CatCraftTrustKind.BUILD)
            {
                long expiresAt = duration == null ? 0L : safeExpiry(nowMillis.getAsLong(), duration);
                intendedRecord = new TemporaryTrustRecord(
                        claimId, canonicalTarget, kind, dimension, baseline, expected,
                        expiresAt, store.nextRevision(), snapshot.ownerId());
            }
            String key = TemporaryTrustRecordKey.key(claimId, canonicalTarget, dimension);
            store.beginTransition(new TrustTransition(key, precedingRecord, current,
                    intendedRecord, expected));
            mutations.add(new GrantMutation(claimId, canonicalTarget, dimension, expected, key));
        }

        store.save();
        try
        {
            for (GrantMutation mutation : mutations)
            {
                withInternalMutation(() -> claimAccess.apply(mutation.claimId(), mutation.target(),
                        mutation.expectedState(), mutation.dimension()));
                claimAccess.save(mutation.claimId());
                store.completeTransition(mutation.key());
            }
        }
        catch (RuntimeException failure)
        {
            logger.severe("Could not apply CatCraft trust change: " + failure.getMessage());
            throw failure;
        }
        store.save();
        scheduleNextExpiration();
    }

    public void revoke(Collection<Claim> claims, String target) throws IOException
    {
        Objects.requireNonNull(claims, "claims");
        String canonicalTarget = canonicalTarget(target);
        processDue(nowMillis.getAsLong());
        List<Claim> eligibleClaims = new ArrayList<>();
        Set<String> batchKeys = new HashSet<>();
        for (Claim claim : claims)
        {
            if (claim != null && claim.getID() != null)
            {
                eligibleClaims.add(claim);
                batchKeys.add(TemporaryTrustRecordKey.key(
                        claim.getID(), canonicalTarget, TrustDimension.PERMISSION));
                batchKeys.add(TemporaryTrustRecordKey.key(
                        claim.getID(), canonicalTarget, TrustDimension.MANAGER));
            }
        }
        store.ensureCapacity(batchKeys);
        List<RevokeMutation> mutations = new ArrayList<>();
        for (Claim claim : eligibleClaims)
        {
            if (claim == null || claim.getID() == null) continue;
            long claimId = claim.getID();
            NativeTrustState permission = claimAccess.capture(claimId, canonicalTarget, TrustDimension.PERMISSION);
            NativeTrustState manager = claimAccess.capture(claimId, canonicalTarget, TrustDimension.MANAGER);
            NativeTrustState intendedPermission = new NativeTrustState(null, permission.manager(), false);
            NativeTrustState intendedManager = new NativeTrustState(manager.permission(), false, manager.safeBuild());
            Optional<TemporaryTrustRecord> previousPermission =
                    store.get(claimId, canonicalTarget, TrustDimension.PERMISSION);
            Optional<TemporaryTrustRecord> previousManager =
                    store.get(claimId, canonicalTarget, TrustDimension.MANAGER);
            String permissionKey = TemporaryTrustRecordKey.key(
                    claimId, canonicalTarget, TrustDimension.PERMISSION);
            String managerKey = TemporaryTrustRecordKey.key(
                    claimId, canonicalTarget, TrustDimension.MANAGER);
            store.beginTransition(new TrustTransition(permissionKey,
                    previousPermission.isPresent() && permission.equals(previousPermission.get().expectedState())
                            ? previousPermission.get() : null,
                    permission, null, intendedPermission));
            store.beginTransition(new TrustTransition(managerKey,
                    previousManager.isPresent() && manager.equals(previousManager.get().expectedState())
                            ? previousManager.get() : null,
                    manager, null, intendedManager));
            mutations.add(new RevokeMutation(claimId, canonicalTarget,
                    permissionKey, managerKey, intendedPermission, intendedManager));
        }
        store.save();
        for (RevokeMutation mutation : mutations)
        {
            withInternalMutation(() -> {
                claimAccess.apply(mutation.claimId(), mutation.target(), mutation.permission(), TrustDimension.PERMISSION);
                claimAccess.apply(mutation.claimId(), mutation.target(), mutation.manager(), TrustDimension.MANAGER);
            });
            claimAccess.save(mutation.claimId());
            store.completeTransition(mutation.permissionKey());
            store.completeTransition(mutation.managerKey());
        }
        store.save();
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
            String key = TemporaryTrustRecordKey.key(
                    claim.getID(), canonicalTarget(target), dimension);
            store.remove(key);
            store.removeTransition(key);
        }
        catch (RuntimeException ex)
        {
            logger.severe("Could not invalidate CatCraft trust metadata: " + ex.getMessage());
            return;
        }
        persistBestEffort();
        scheduleNextExpiration();
    }

    public void onExternalPermissionsCleared(Claim claim)
    {
        if (internalMutationDepth > 0 || claim == null || claim.getID() == null) return;
        store.removeClaim(claim.getID());
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
        if (processed > 0) persistBestEffort();
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
            return true;
        }
        NativeTrustState current = claimAccess.capture(record.claimId(), record.target(), record.dimension());
        if (current.equals(record.previousState()))
        {
            store.removeIfRevision(record.key(), record.revision());
            return true;
        }
        if (!current.equals(record.expectedState()))
        {
            store.removeIfRevision(record.key(), record.revision());
            return true;
        }
        try
        {
            withInternalMutation(() -> claimAccess.apply(record.claimId(), record.target(),
                    record.previousState(), record.dimension()));
            claimAccess.save(record.claimId());
            store.removeIfRevision(record.key(), record.revision());
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
        for (TrustTransition transition : store.transitionValues())
        {
            ClaimSnapshot snapshot = claimAccess.resolve(claimIdFromKey(transition.key()));
            UUID owner = transition.intendedRecord() != null
                    ? transition.intendedRecord().ownerIdAtGrant()
                    : transition.precedingRecord() == null
                    ? null : transition.precedingRecord().ownerIdAtGrant();
            if (snapshot == null || (owner != null && !Objects.equals(snapshot.ownerId(), owner)))
            {
                store.discardTransition(transition.key());
                changed = true;
                continue;
            }
            NativeTrustState current = claimAccess.capture(claimIdFromKey(transition.key()),
                    targetFromKey(transition.key()), dimensionFromKey(transition.key()));
            if (current.equals(transition.intendedState()))
            {
                store.completeTransition(transition.key());
            }
            else if (current.equals(transition.precedingState()))
            {
                store.abortTransition(transition.key());
            }
            else
            {
                store.discardTransition(transition.key());
            }
            changed = true;
        }
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
        for (TemporaryTrustRecord record : store.safeBuildCandidates(
                claimId, playerId, player, now, snapshot.ownerId()))
        {
            NativeTrustState current = claimAccess.capture(claimId, record.target(), record.dimension());
            if (current.equals(record.expectedState())) return true;
            store.removeIfRevision(record.key(), record.revision());
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
                claim.parent == null ? null : claim.parent.getID(), claim.getSubclaimRestrictions());
    }

    private void scheduleNextExpiration()
    {
        if (!loaded) return;
        cancelScheduledExpiration();
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
        cancelScheduledExpiration();
        CancellableCallback callback = new CancellableCallback();
        expirationHandle = callback;
        scheduler.nextTick(() -> {
            if (callback.cancelled || expirationHandle != callback) return;
            expirationHandle = null;
            processDue(nowMillis.getAsLong());
        });
    }

    private void cancelScheduledExpiration()
    {
        if (expirationHandle != null) expirationHandle.cancel();
        expirationHandle = null;
    }

    private static long claimIdFromKey(String key)
    {
        return Long.parseLong(key.substring(0, key.indexOf('|')));
    }

    private static String targetFromKey(String key)
    {
        int first = key.indexOf('|');
        int second = key.indexOf('|', first + 1);
        return key.substring(second + 1);
    }

    private static TrustDimension dimensionFromKey(String key)
    {
        int first = key.indexOf('|');
        int second = key.indexOf('|', first + 1);
        return TrustDimension.valueOf(key.substring(first + 1, second));
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

    private static NativeTrustState replacementBaseline(TemporaryTrustRecord previous)
    {
        return previous.expiresAtMillis() == 0L
                && previous.appliedKind() == CatCraftTrustKind.BUILD
                ? previous.expectedState() : previous.previousState();
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
                                 NativeTrustState expectedState, String key)
    {
    }

    private record RevokeMutation(long claimId, String target, String permissionKey,
                                  String managerKey, NativeTrustState permission,
                                  NativeTrustState manager)
    {
    }

    private static final class CancellableCallback implements ScheduledHandle
    {
        private boolean cancelled;

        @Override
        public void cancel()
        {
            cancelled = true;
        }
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

final class TemporaryTrustRecordKey
{
    private TemporaryTrustRecordKey()
    {
    }

    static String key(long claimId, String target, TrustDimension dimension)
    {
        return claimId + "|" + dimension + "|" + target.toLowerCase(Locale.ROOT);
    }
}
