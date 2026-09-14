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
        loaded = false;
        store.load();
        try
        {
            reconcileStartup();
            loaded = true;
            scheduleNextExpiration();
        }
        catch (IOException | RuntimeException | Error failure)
        {
            loaded = false;
            cancelScheduledExpiration();
            throw failure;
        }
    }

    public void stop() throws IOException
    {
        cancelScheduledExpiration();
        boolean wasLoaded = loaded;
        loaded = false;
        if (wasLoaded) store.save();
    }

    public boolean isSafeBuilder(Claim claim, UUID playerId, @Nullable Player player)
    {
        if (!loaded) return false;
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
        requireStarted();
        Objects.requireNonNull(claims, "claims");
        Objects.requireNonNull(kind, "kind");
        String canonicalTarget = canonicalTarget(target);
        processDue(nowMillis.getAsLong());
        List<Claim> eligibleClaims = new ArrayList<>();
        Set<String> batchKeys = new HashSet<>();
        Set<String> supersededKeys = new HashSet<>();
        TrustDimension batchDimension = dimensionFor(kind);
        for (Claim claim : claims)
        {
            if (claim != null && claim.getID() != null)
            {
                eligibleClaims.add(claim);
                batchKeys.add(TemporaryTrustRecordKey.key(
                        claim.getID(), canonicalTarget, batchDimension));
                if (kind == CatCraftTrustKind.MANAGE)
                {
                    supersededKeys.add(TemporaryTrustRecordKey.key(
                            claim.getID(), canonicalTarget, TrustDimension.PERMISSION));
                }
            }
        }
        store.ensureCapacity(batchKeys, supersededKeys);
        List<GrantMutation> mutations = new ArrayList<>();
        for (Claim claim : eligibleClaims)
        {
            if (claim == null || claim.getID() == null) continue;
            long claimId = claim.getID();
            ClaimSnapshot snapshot = snapshotFor(claim);
            TrustDimension dimension = dimensionFor(kind);
            Optional<TemporaryTrustRecord> previousRecord = store.get(claimId, canonicalTarget, dimension);
            if (kind == CatCraftTrustKind.MANAGE)
            {
                store.removeTarget(claimId, canonicalTarget, TrustDimension.PERMISSION);
            }
            NativeTrustState current = captureForRecord(claimId, canonicalTarget, dimension,
                    previousRecord.orElse(null));
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
        requireStarted();
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
            Optional<TemporaryTrustRecord> previousPermission =
                    store.get(claimId, canonicalTarget, TrustDimension.PERMISSION);
            Optional<TemporaryTrustRecord> previousManager =
                    store.get(claimId, canonicalTarget, TrustDimension.MANAGER);
            NativeTrustState permission = captureForRecord(claimId, canonicalTarget,
                    TrustDimension.PERMISSION, previousPermission.orElse(null));
            NativeTrustState manager = captureForRecord(claimId, canonicalTarget,
                    TrustDimension.MANAGER, previousManager.orElse(null));
            NativeTrustState intendedPermission = new NativeTrustState(null, permission.manager(), false);
            NativeTrustState intendedManager = new NativeTrustState(manager.permission(), false, manager.safeBuild());
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
        requireStarted();
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
        if (!loaded || store.removeClaim(claimId) == 0) return;
        persistBestEffort();
        scheduleNextExpiration();
    }

    public void onClaimOwnerChanging(Claim claim)
    {
        if (!loaded || claim == null || claim.getID() == null
                || store.removeClaim(claim.getID()) == 0) return;
        persistBestEffort();
        scheduleNextExpiration();
    }

    public void onExternalPermissionMutation(Claim claim, String target, TrustDimension dimension)
    {
        if (!loaded || internalMutationDepth > 0 || claim == null
                || claim.getID() == null || dimension == null) return;
        try
        {
            if (store.removeTarget(claim.getID(), canonicalTarget(target), dimension) == 0) return;
        }
        catch (RuntimeException ex)
        {
            logger.severe("Could not invalidate CatCraft trust metadata: " + ex.getMessage());
            return;
        }
        persistBestEffort();
        scheduleNextExpiration();
    }

    public void onExternalTargetMutation(Claim claim, String target)
    {
        if (!loaded || internalMutationDepth > 0 || claim == null || claim.getID() == null) return;
        try
        {
            if (store.removeTargetAll(claim.getID(), canonicalTarget(target)) == 0) return;
        }
        catch (RuntimeException ex)
        {
            logger.severe("Could not invalidate CatCraft trust metadata: " + ex.getMessage());
            return;
        }
        persistBestEffort();
        scheduleNextExpiration();
    }

    public void onExternalTargetRemoved(Claim claim, String target)
    {
        if (!loaded || internalMutationDepth > 0 || claim == null || claim.getID() == null) return;
        final String canonicalTarget;
        try
        {
            canonicalTarget = canonicalTarget(target);
        }
        catch (RuntimeException ex)
        {
            logger.severe("Could not invalidate CatCraft trust metadata: " + ex.getMessage());
            return;
        }
        if (removeTargetTree(claim, canonicalTarget, new HashSet<>()) == 0) return;
        persistBestEffort();
        scheduleNextExpiration();
    }

    public void onExternalPermissionsCleared(Claim claim)
    {
        if (!loaded || internalMutationDepth > 0 || claim == null || claim.getID() == null) return;
        if (removeClaimTree(claim, new HashSet<>()) == 0) return;
        persistBestEffort();
        scheduleNextExpiration();
    }

    public List<TemporaryTrustRecord> recordsForClaim(long claimId)
    {
        return store.forClaim(claimId);
    }

    public boolean isStarted()
    {
        return loaded;
    }

    public boolean isInternalMutation()
    {
        return internalMutationDepth > 0;
    }

    private int removeClaimTree(Claim claim, Set<Long> visited)
    {
        Long claimId = claim.getID();
        if (claimId == null || !visited.add(claimId)) return 0;
        int removed = store.removeClaim(claimId);
        for (Claim child : claim.children)
        {
            if (child != null) removed += removeClaimTree(child, visited);
        }
        return removed;
    }

    private int removeTargetTree(Claim claim, String target, Set<Long> visited)
    {
        Long claimId = claim.getID();
        if (claimId == null || !visited.add(claimId)) return 0;
        int removed = store.removeTargetAll(claimId, target);
        for (Claim child : claim.children)
        {
            if (child != null) removed += removeTargetTree(child, target, visited);
        }
        return removed;
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
        NativeTrustState current = captureForRecord(record.claimId(), record.target(),
                record.dimension(), record);
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
            if (restoresPermanentBuildMarker(record))
            {
                NativeTrustState markerState = record.previousState();
                store.put(new TemporaryTrustRecord(record.claimId(), record.target(),
                        CatCraftTrustKind.BUILD, TrustDimension.PERMISSION,
                        markerState, markerState, 0L, store.nextRevision(),
                        record.ownerIdAtGrant()));
            }
            return true;
        }
        catch (RuntimeException failure)
        {
            logger.severe("Could not restore expired CatCraft trust for claim "
                    + record.claimId() + ": " + failure.getMessage());
            return false;
        }
    }

    private NativeTrustState captureForRecord(long claimId,
                                              String target,
                                              TrustDimension dimension,
                                              @Nullable TemporaryTrustRecord activeRecord)
    {
        NativeTrustState raw = claimAccess.capture(claimId, target, dimension);
        TemporaryTrustRecord permissionRecord = activeRecord != null
                && activeRecord.dimension() == TrustDimension.PERMISSION
                ? activeRecord
                : store.get(claimId, target, TrustDimension.PERMISSION).orElse(null);
        return withSafeBuild(raw, isSafeBuildRecord(permissionRecord));
    }

    private NativeTrustState captureTransitionState(TrustTransition transition)
    {
        long claimId = claimIdFromKey(transition.key());
        String target = targetFromKey(transition.key());
        TrustDimension dimension = dimensionFromKey(transition.key());
        NativeTrustState raw = claimAccess.capture(claimId, target, dimension);
        boolean matchesPreceding = sameNativeState(raw, transition.precedingState());
        boolean matchesIntended = sameNativeState(raw, transition.intendedState());
        boolean safeBuild = ifSafeBuildState(raw, transition, matchesPreceding, matchesIntended);
        return withSafeBuild(raw, safeBuild);
    }

    private static boolean ifSafeBuildState(NativeTrustState raw,
                                            TrustTransition transition,
                                            boolean matchesPreceding,
                                            boolean matchesIntended)
    {
        if (matchesPreceding && !matchesIntended) return transition.precedingState().safeBuild();
        if (matchesIntended && !matchesPreceding) return transition.intendedState().safeBuild();
        if (!matchesPreceding) return raw.safeBuild();
        if (transition.precedingState().safeBuild() == transition.intendedState().safeBuild())
        {
            return transition.intendedState().safeBuild();
        }
        // A marker-only transition has no native evidence of which side was applied.
        // Keep the less privileged side so a crash cannot resurrect safe-build access.
        return false;
    }

    private static boolean sameNativeState(NativeTrustState left, NativeTrustState right)
    {
        return left.permission() == right.permission() && left.manager() == right.manager();
    }

    private static NativeTrustState withSafeBuild(NativeTrustState raw, boolean safeBuild)
    {
        return new NativeTrustState(raw.permission(), raw.manager(), safeBuild);
    }

    private static boolean isSafeBuildRecord(@Nullable TemporaryTrustRecord record)
    {
        return record != null
                && record.dimension() == TrustDimension.PERMISSION
                && record.appliedKind() == CatCraftTrustKind.BUILD
                && record.expectedState().safeBuild();
    }

    private static boolean restoresPermanentBuildMarker(TemporaryTrustRecord record)
    {
        NativeTrustState previous = record.previousState();
        return record.dimension() == TrustDimension.PERMISSION
                && previous.permission() == ClaimPermission.Access
                && previous.safeBuild();
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
            NativeTrustState current = captureTransitionState(transition);
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
            NativeTrustState current = captureForRecord(record.claimId(), record.target(),
                    record.dimension(), record);
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
            NativeTrustState current = captureForRecord(claimId, record.target(),
                    record.dimension(), record);
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

    private void requireStarted()
    {
        if (!loaded) throw new IllegalStateException("CatCraft trust service is not started");
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

/**
 * Adapter for raw GriefPrevention claim state. The sidecar state store is the
 * authority for safe-build markers; adapters must not require a safe-build
 * field in Claim or persist that marker in native claim data.
 */
interface ClaimTrustAccess
{
    @Nullable ClaimSnapshot resolve(long claimId);

    /** Captures native permission and manager state; safeBuild is ignored by the service. */
    NativeTrustState capture(long claimId, String target, TrustDimension dimension);

    /** Applies only the requested native dimension; safeBuild is sidecar metadata. */
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
