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
import java.util.function.Consumer;
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
    private Consumer<TemporaryTrustRecord> expirationListener = ignored -> { };
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

    public void setExpirationListener(Consumer<TemporaryTrustRecord> listener)
    {
        if (loaded) throw new IllegalStateException("expiration listener must be set before start");
        this.expirationListener = Objects.requireNonNull(listener, "listener");
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

    /** Constant-time global hint used before hot automation-event claim lookups. */
    public boolean hasAnySafeBuilders()
    {
        return loaded && store.hasAnySafeBuildRecords();
    }

    /**
     * Checks the claim and inherited parent chain without retaining Claim
     * objects. Expired or owner-mismatched records are excluded.
     */
    public boolean hasAnySafeBuilder(Claim claim)
    {
        if (!loaded || claim == null || claim.getID() == null) return false;
        ClaimSnapshot fallback = new ClaimSnapshot(claim.getID(), claim.getOwnerID(),
                claim.parent == null ? null : claim.parent.getID(), claim.getSubclaimRestrictions());
        ClaimSnapshot resolved = claimAccess.resolve(claim.getID());
        return hasAnySafeBuilder(resolved == null ? fallback : resolved, new HashSet<>());
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
        requireStarted();
        List<Claim> eligibleClaims = new ArrayList<>();
        Set<String> batchKeys = new HashSet<>();
        TrustDimension batchDimension = dimensionFor(kind);
        for (Claim claim : claims)
        {
            if (claim != null && claim.getID() != null)
            {
                eligibleClaims.add(claim);
                String key = TemporaryTrustRecordKey.key(
                        claim.getID(), canonicalTarget, batchDimension);
                if (!batchKeys.add(key))
                {
                    throw new IllegalArgumentException("duplicate claim in trust batch");
                }
            }
        }
        store.ensureCapacity(batchKeys);
        boolean persistRecord = duration != null || kind == CatCraftTrustKind.BUILD;
        long expiresAt = duration == null ? 0L : safeExpiry(nowMillis.getAsLong(), duration);
        List<GrantPreparation> preparations = new ArrayList<>();
        for (Claim claim : eligibleClaims)
        {
            long claimId = claim.getID();
            ClaimSnapshot snapshot = snapshotFor(claim);
            TrustDimension dimension = dimensionFor(kind);
            Optional<TemporaryTrustRecord> previousRecord = store.get(claimId, canonicalTarget, dimension);
            NativeTrustState current = captureForRecord(claimId, canonicalTarget, dimension,
                    previousRecord.orElse(null));
            NativeTrustState baseline = previousRecord.isPresent()
                    && matchesRecordState(current, previousRecord.get().expectedState(), dimension)
                    ? replacementBaseline(previousRecord.get()) : current;
            NativeTrustState expected = desiredState(kind, current);
            TemporaryTrustRecord precedingRecord = previousRecord.isPresent()
                    && matchesRecordState(current, previousRecord.get().expectedState(), dimension)
                    ? previousRecord.get() : null;
            preparations.add(new GrantPreparation(claimId, canonicalTarget, dimension, snapshot,
                    precedingRecord, current, baseline, expected));
        }
        RevisionReservation reservation = store.reserveRevisions(persistRecord
                ? preparations.size() : 0);
        List<TrustTransition> transitions = new ArrayList<>(preparations.size());
        List<GrantMutation> mutations = new ArrayList<>();
        int recordIndex = 0;
        for (GrantPreparation preparation : preparations)
        {
            TemporaryTrustRecord intendedRecord = null;
            if (persistRecord)
            {
                intendedRecord = new TemporaryTrustRecord(
                        preparation.claimId(), preparation.target(), kind, preparation.dimension(),
                        preparation.baseline(), preparation.expected(), expiresAt,
                        reservation.revisionAt(recordIndex++), preparation.snapshot().ownerId());
            }
            String key = TemporaryTrustRecordKey.key(preparation.claimId(), preparation.target(),
                    preparation.dimension());
            transitions.add(new TrustTransition(key, preparation.precedingRecord(), preparation.current(),
                    intendedRecord, preparation.expected()));
            mutations.add(new GrantMutation(preparation.claimId(), preparation.target(),
                    preparation.dimension(), preparation.expected(), key));
        }

        try
        {
            store.commitPreparedTransitions(transitions, reservation);
            store.save();
        }
        catch (IOException | RuntimeException failure)
        {
            for (TrustTransition transition : transitions) store.abortTransition(transition.key());
            throw failure;
        }
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
        try
        {
            store.save();
        }
        catch (IOException | RuntimeException failure)
        {
            markUnavailable();
            throw failure;
        }
        scheduleNextExpiration();
    }

    public void revoke(Collection<Claim> claims, String target) throws IOException
    {
        requireStarted();
        Objects.requireNonNull(claims, "claims");
        String canonicalTarget = canonicalTarget(target);
        List<Claim> eligibleClaims = new ArrayList<>();
        Set<Long> claimIds = new HashSet<>();
        for (Claim claim : claims)
        {
            if (claim != null && claim.getID() != null)
            {
                if (!claimIds.add(claim.getID()))
                {
                    throw new IllegalArgumentException("duplicate claim in trust batch");
                }
                eligibleClaims.add(claim);
            }
        }
        processDue(nowMillis.getAsLong());
        requireStarted();
        List<RevokeMutation> mutations = new ArrayList<>();
        List<TrustTransition> transitions = new ArrayList<>();
        for (Claim claim : eligibleClaims)
        {
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
            boolean permissionNeedsMutation = previousPermission.isPresent()
                    || !matchesRecordState(permission, intendedPermission, TrustDimension.PERMISSION)
                    || store.hasTransition(permissionKey);
            boolean managerNeedsMutation = previousManager.isPresent()
                    || !matchesRecordState(manager, intendedManager, TrustDimension.MANAGER)
                    || store.hasTransition(managerKey);
            if (permissionNeedsMutation)
            {
                TrustTransition transition = new TrustTransition(permissionKey,
                        previousPermission.isPresent()
                                && matchesRecordState(permission, previousPermission.get().expectedState(),
                                TrustDimension.PERMISSION)
                                ? previousPermission.get() : null,
                        permission, null, intendedPermission);
                transitions.add(transition);
                mutations.add(new RevokeMutation(claimId, canonicalTarget,
                        permissionKey, TrustDimension.PERMISSION, intendedPermission));
            }
            if (managerNeedsMutation)
            {
                TrustTransition transition = new TrustTransition(managerKey,
                        previousManager.isPresent()
                                && matchesRecordState(manager, previousManager.get().expectedState(),
                                TrustDimension.MANAGER)
                                ? previousManager.get() : null,
                        manager, null, intendedManager);
                transitions.add(transition);
                mutations.add(new RevokeMutation(claimId, canonicalTarget,
                        managerKey, TrustDimension.MANAGER, intendedManager));
            }
        }
        store.ensureCapacity(transitions.stream().map(TrustTransition::key).toList());
        if (transitions.isEmpty())
        {
            scheduleNextExpiration();
            return;
        }
        try
        {
            store.commitPreparedTransitions(transitions, store.reserveRevisions(0));
            store.save();
        }
        catch (IOException | RuntimeException failure)
        {
            for (TrustTransition transition : transitions) store.abortTransition(transition.key());
            throw failure;
        }
        try
        {
            for (RevokeMutation mutation : mutations)
            {
                withInternalMutation(() -> {
                    claimAccess.apply(mutation.claimId(), mutation.target(), mutation.intended(), mutation.dimension());
                });
                claimAccess.save(mutation.claimId());
                store.completeTransition(mutation.key());
            }
        }
        catch (RuntimeException failure)
        {
            logger.severe("Could not apply CatCraft trust revocation: " + failure.getMessage());
            throw failure;
        }
        try
        {
            store.save();
        }
        catch (IOException | RuntimeException failure)
        {
            markUnavailable();
            throw failure;
        }
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

    /**
     * Restores active CatCraft decisions before a claim tree changes owner.
     * The transition journal is flushed before native permissions change, so
     * a crash cannot leave a grant attached to the new owner indefinitely.
     */
    public void prepareClaimTransfer(Claim claim) throws IOException
    {
        requireStarted();
        Objects.requireNonNull(claim, "claim");
        Set<Long> ids = claimIds(claim, new HashSet<>());
        List<TemporaryTrustRecord> candidates = store.values().stream()
                .filter(record -> ids.contains(record.claimId()))
                .toList();
        if (candidates.isEmpty()) return;

        List<ExpirationMutation> mutations = new ArrayList<>();
        List<TemporaryTrustRecord> metadataOnly = new ArrayList<>();
        for (TemporaryTrustRecord record : candidates)
        {
            ClaimSnapshot snapshot = claimAccess.resolve(record.claimId());
            if (snapshot == null || !Objects.equals(snapshot.ownerId(), record.ownerIdAtGrant()))
            {
                metadataOnly.add(record);
                continue;
            }
            NativeTrustState current = captureForRecord(record.claimId(), record.target(),
                    record.dimension(), record);
            if (!matchesRecordState(current, record.expectedState(), record.dimension()))
            {
                metadataOnly.add(record);
                continue;
            }
            mutations.add(new ExpirationMutation(record, current,
                    new TrustTransition(record.key(), record, record.expectedState(),
                            null, record.previousState())));
        }

        List<TrustTransition> transitions = mutations.stream()
                .map(ExpirationMutation::transition).toList();
        try
        {
            store.commitPreparedTransitions(transitions, store.reserveRevisions(0));
            store.save();
        }
        catch (IOException | RuntimeException failure)
        {
            for (TrustTransition transition : transitions) store.abortTransition(transition.key());
            throw failure;
        }

        for (TemporaryTrustRecord record : metadataOnly)
        {
            store.removeIfRevision(record.key(), record.revision());
        }
        try
        {
            for (ExpirationMutation mutation : mutations)
            {
                TemporaryTrustRecord record = mutation.record();
                if (!matchesRecordState(mutation.current(), record.previousState(), record.dimension()))
                {
                    withInternalMutation(() -> claimAccess.apply(record.claimId(), record.target(),
                            record.previousState(), record.dimension()));
                    claimAccess.save(record.claimId());
                }
                store.completeTransition(record.key());
            }
            store.save();
        }
        catch (IOException | RuntimeException failure)
        {
            markUnavailable();
            if (failure instanceof IOException io) throw io;
            throw failure;
        }
        scheduleNextExpiration();
    }

    /**
     * Prepares and durably journals an external mutation before Claim changes
     * native state.  A false result means the native mutation must be skipped.
     */
    public boolean onExternalPermissionMutation(Claim claim, String target, TrustDimension dimension)
    {
        if (!loaded || internalMutationDepth > 0 || claim == null
                || claim.getID() == null || dimension == null) return true;
        try
        {
            return journalExternalMutations(Set.of(claim.getID()), canonicalTarget(target),
                    Set.of(dimension));
        }
        catch (RuntimeException ex)
        {
            logger.severe("Could not prepare CatCraft trust invalidation: " + ex.getMessage());
            return false;
        }
    }

    public void completeExternalPermissionMutation(Claim claim, String target, TrustDimension dimension)
    {
        if (!loaded || internalMutationDepth > 0 || claim == null
                || claim.getID() == null || dimension == null) return;
        try
        {
            finishExternalMutations(Set.of(TemporaryTrustRecordKey.key(
                    claim.getID(), canonicalTarget(target), dimension)));
        }
        catch (RuntimeException ex)
        {
            logger.severe("Could not complete CatCraft trust invalidation: " + ex.getMessage());
            markUnavailable();
        }
    }

    public boolean onExternalTargetMutation(Claim claim, String target)
    {
        if (!loaded || internalMutationDepth > 0 || claim == null || claim.getID() == null) return true;
        try
        {
            return journalExternalMutations(Set.of(claim.getID()), canonicalTarget(target),
                    Set.of(TrustDimension.PERMISSION, TrustDimension.MANAGER));
        }
        catch (RuntimeException ex)
        {
            logger.severe("Could not prepare CatCraft trust invalidation: " + ex.getMessage());
            return false;
        }
    }

    public void completeExternalTargetMutation(Claim claim, String target)
    {
        if (!loaded || internalMutationDepth > 0 || claim == null || claim.getID() == null) return;
        try
        {
            String canonical = canonicalTarget(target);
            Set<String> keys = new HashSet<>();
            for (TrustDimension dimension : TrustDimension.values())
            {
                keys.add(TemporaryTrustRecordKey.key(claim.getID(), canonical, dimension));
            }
            finishExternalMutations(keys);
        }
        catch (RuntimeException ex)
        {
            logger.severe("Could not complete CatCraft trust invalidation: " + ex.getMessage());
            markUnavailable();
        }
    }

    public boolean onExternalTargetRemoved(Claim claim, String target)
    {
        if (!loaded || internalMutationDepth > 0 || claim == null || claim.getID() == null) return true;
        try
        {
            return journalExternalMutations(claimIds(claim, new HashSet<>()), canonicalTarget(target),
                    Set.of(TrustDimension.PERMISSION, TrustDimension.MANAGER));
        }
        catch (RuntimeException ex)
        {
            logger.severe("Could not prepare CatCraft trust invalidation: " + ex.getMessage());
            return false;
        }
    }

    public void completeExternalTargetRemoved(Claim claim, String target)
    {
        if (!loaded || internalMutationDepth > 0 || claim == null || claim.getID() == null) return;
        try
        {
            String canonical = canonicalTarget(target);
            finishExternalMutations(keysForClaims(claimIds(claim, new HashSet<>()), canonical));
        }
        catch (RuntimeException ex)
        {
            logger.severe("Could not complete CatCraft trust invalidation: " + ex.getMessage());
            markUnavailable();
        }
    }

    public boolean onExternalPermissionsCleared(Claim claim)
    {
        if (!loaded || internalMutationDepth > 0 || claim == null || claim.getID() == null) return true;
        try
        {
            return journalExternalMutations(claimIds(claim, new HashSet<>()), null,
                    Set.of(TrustDimension.PERMISSION, TrustDimension.MANAGER));
        }
        catch (RuntimeException ex)
        {
            logger.severe("Could not prepare CatCraft trust invalidation: " + ex.getMessage());
            return false;
        }
    }

    public void completeExternalPermissionsCleared(Claim claim)
    {
        if (!loaded || internalMutationDepth > 0 || claim == null || claim.getID() == null) return;
        try
        {
            finishExternalMutations(keysForClaims(claimIds(claim, new HashSet<>()), null));
        }
        catch (RuntimeException ex)
        {
            logger.severe("Could not complete CatCraft trust invalidation: " + ex.getMessage());
            markUnavailable();
        }
    }

    public List<TemporaryTrustRecord> recordsForClaim(long claimId)
    {
        return store.forClaim(claimId);
    }

    public List<TemporaryTrustRecord> recordsSnapshot()
    {
        return store.values();
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

    private boolean journalExternalMutations(Set<Long> claimIds,
                                             @Nullable String target,
                                             Set<TrustDimension> dimensions)
    {
        List<String> candidateKeys = new ArrayList<>();
        if (target == null)
        {
            for (String key : store.keysForClaims(claimIds))
            {
                if (dimensions.contains(dimensionFromKey(key))) candidateKeys.add(key);
            }
        }
        else
        {
            for (Long claimId : claimIds)
            {
                for (TrustDimension dimension : dimensions)
                {
                    candidateKeys.add(TemporaryTrustRecordKey.key(claimId, target, dimension));
                }
            }
        }

        List<TrustTransition> prepared = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try
        {
            for (String key : candidateKeys)
            {
                if (!seen.add(key)) continue;
                long claimId = claimIdFromKey(key);
                String keyTarget = targetFromKey(key);
                TrustDimension dimension = dimensionFromKey(key);
                Optional<TemporaryTrustRecord> previous = store.get(key);
                NativeTrustState current = captureForRecord(claimId, keyTarget, dimension,
                        previous.orElse(null));
                NativeTrustState intended = dimension == TrustDimension.PERMISSION
                        ? withSafeBuild(current, false)
                        : new NativeTrustState(current.permission(), false, current.safeBuild());
                if (previous.isEmpty() && !store.hasTransition(key)
                        && matchesRecordState(current, intended, dimension)) continue;
                prepared.add(new TrustTransition(key, previous.orElse(null), current,
                        null, intended));
            }
        }
        catch (RuntimeException failure)
        {
            logger.severe("Could not prepare CatCraft trust invalidation: " + failure.getMessage());
            return false;
        }
        if (prepared.isEmpty()) return true;

        try
        {
            store.commitExternalTransitions(prepared);
            store.save();
            return true;
        }
        catch (IOException | RuntimeException failure)
        {
            for (TrustTransition transition : prepared) store.abortTransition(transition.key());
            logger.severe("Could not journal CatCraft trust invalidation: " + failure.getMessage());
            return false;
        }
    }

    private void finishExternalMutations(Set<String> keys)
    {
        boolean changed = false;
        for (String key : keys)
        {
            if (!store.hasTransition(key)) continue;
            claimAccess.save(claimIdFromKey(key));
            store.completeTransition(key);
            changed = true;
        }
        if (!changed) return;
        if (!persistBestEffort())
        {
            markUnavailable();
            return;
        }
        scheduleNextExpiration();
    }

    private Set<Long> claimIds(Claim claim, Set<Long> visited)
    {
        Long claimId = claim.getID();
        if (claimId == null || !visited.add(claimId)) return visited;
        for (Claim child : claim.children)
        {
            if (child != null) claimIds(child, visited);
        }
        return visited;
    }

    private Set<String> keysForClaims(Set<Long> claimIds, @Nullable String target)
    {
        Set<String> keys = new HashSet<>();
        for (String key : store.keysForClaims(claimIds))
        {
            if (target == null || target.equals(targetFromKey(key))) keys.add(key);
        }
        return keys;
    }

    void processDue(long now)
    {
        if (!loaded) return;
        cancelScheduledExpiration();
        List<TemporaryTrustRecord> due;
        try
        {
            due = store.dueExpiring(now, maximumExpirationsPerTick);
        }
        catch (RuntimeException failure)
        {
            failExpiration("Could not select expired CatCraft trust", failure);
            return;
        }
        if (due.isEmpty())
        {
            scheduleDueOrNext(now);
            return;
        }

        List<ExpirationPreparation> preparations = new ArrayList<>(due.size());
        for (TemporaryTrustRecord record : due)
        {
            try
            {
                preparations.add(prepareExpiration(record));
            }
            catch (RuntimeException failure)
            {
                failExpiration("Could not inspect expired CatCraft trust", failure);
                return;
            }
        }
        List<ExpirationMutation> mutations = new ArrayList<>();
        int markerCount = (int) preparations.stream().filter(ExpirationPreparation::restoreMarker).count();
        RevisionReservation reservation;
        boolean nativeApplyStarted = false;
        try
        {
            reservation = store.reserveRevisions(markerCount);
            int markerIndex = 0;
            for (ExpirationPreparation preparation : preparations)
            {
                if (!preparation.transition()) continue;
                TemporaryTrustRecord record = preparation.record();
                TemporaryTrustRecord marker = null;
                if (preparation.restoreMarker())
                {
                    NativeTrustState markerState = record.previousState();
                    marker = new TemporaryTrustRecord(record.claimId(), record.target(),
                            CatCraftTrustKind.BUILD, TrustDimension.PERMISSION, markerState, markerState,
                            0L, reservation.revisionAt(markerIndex++), record.ownerIdAtGrant());
                }
                mutations.add(new ExpirationMutation(record, preparation.current(),
                        new TrustTransition(record.key(), record, record.expectedState(),
                                marker, record.previousState())));
            }
            store.commitPreparedTransitions(mutations.stream().map(ExpirationMutation::transition).toList(),
                    reservation);
        }
        catch (RuntimeException failure)
        {
            for (ExpirationMutation mutation : mutations) store.abortTransition(mutation.record().key());
            failExpiration("Could not prepare expired CatCraft trust", failure);
            return;
        }
        try
        {
            if (!mutations.isEmpty())
            {
                store.save();
            }
            for (ExpirationPreparation preparation : preparations)
            {
                if (!preparation.removeMetadata()) continue;
                TemporaryTrustRecord record = preparation.record();
                store.removeIfRevision(record.key(), record.revision());
            }
            for (ExpirationMutation mutation : mutations)
            {
                try
                {
                    if (!matchesRecordState(mutation.current(), mutation.record().previousState(),
                            mutation.record().dimension()))
                    {
                        TemporaryTrustRecord record = mutation.record();
                        nativeApplyStarted = true;
                        withInternalMutation(() -> claimAccess.apply(record.claimId(), record.target(),
                                record.previousState(), record.dimension()));
                        claimAccess.save(record.claimId());
                    }
                    store.completeTransition(mutation.record().key());
                }
                catch (RuntimeException failure)
                {
                    throw new ExpirationFailure("Could not restore expired CatCraft trust for claim "
                            + mutation.record().claimId(), failure);
                }
            }
        }
        catch (IOException | RuntimeException failure)
        {
            if (failure instanceof ExpirationFailure expirationFailure)
            {
                failExpiration(expirationFailure.getMessage(), expirationFailure.getCause());
            }
            else
            {
                for (ExpirationMutation mutation : mutations)
                {
                    // A journal failure occurs before native apply, so the
                    // active record can safely be restored in memory.
                    if (!nativeApplyStarted) store.abortTransition(mutation.record().key());
                }
                failExpiration("Could not process expired CatCraft trust", failure);
            }
            return;
        }
        if (!persistBestEffort())
        {
            failExpiration("Could not persist expired CatCraft trust", null);
            return;
        }
        for (ExpirationMutation mutation : mutations)
        {
            try
            {
                expirationListener.accept(mutation.record());
            }
            catch (RuntimeException notificationFailure)
            {
                logger.warning("Could not notify a CatCraft trust expiry: "
                        + notificationFailure.getMessage());
            }
        }
        scheduleDueOrNext(now);
    }

    private void failExpiration(String message, @Nullable Throwable failure)
    {
        logger.severe(message + (failure == null || failure.getMessage() == null
                ? "" : ": " + failure.getMessage()));
        markUnavailable();
    }

    private void scheduleDueOrNext(long now)
    {
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

    private ExpirationPreparation prepareExpiration(TemporaryTrustRecord record)
    {
        ClaimSnapshot snapshot = claimAccess.resolve(record.claimId());
        if (snapshot == null || !Objects.equals(snapshot.ownerId(), record.ownerIdAtGrant()))
        {
            return new ExpirationPreparation(record, record.expectedState(), true, false, false);
        }
        NativeTrustState current = captureForRecord(record.claimId(), record.target(),
                record.dimension(), record);
        boolean expected = matchesRecordState(current, record.expectedState(), record.dimension());
        boolean previous = matchesRecordState(current, record.previousState(), record.dimension());
        if (!expected && !previous)
        {
            return new ExpirationPreparation(record, current, true, false, false);
        }
        boolean restoreMarker = restoresPermanentBuildMarker(record);
        if (previous && !restoreMarker)
        {
            return new ExpirationPreparation(record, current, true, false, false);
        }
        return new ExpirationPreparation(record, current, false, restoreMarker, true);
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
        boolean matchesPreceding = matchesNativeState(raw, transition.precedingState(), dimension);
        boolean matchesIntended = matchesNativeState(raw, transition.intendedState(), dimension);
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

    private static boolean matchesNativeState(NativeTrustState left, NativeTrustState right,
                                              TrustDimension dimension)
    {
        return dimension == TrustDimension.PERMISSION
                ? left.permission() == right.permission()
                : left.manager() == right.manager();
    }

    private static boolean matchesRecordState(NativeTrustState actual,
                                              NativeTrustState expected,
                                              TrustDimension dimension)
    {
        if (dimension == TrustDimension.PERMISSION)
        {
            return actual.permission() == expected.permission()
                    && actual.safeBuild() == expected.safeBuild();
        }
        return actual.manager() == expected.manager();
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
            TrustDimension dimension = dimensionFromKey(transition.key());
            if (matchesRecordState(current, transition.intendedState(), dimension))
            {
                // Reload may see an in-memory restoration whose earlier native save failed.
                claimAccess.save(snapshot.claimId());
                store.completeTransition(transition.key());
            }
            else if (matchesRecordState(current, transition.precedingState(), dimension))
            {
                claimAccess.save(snapshot.claimId());
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
            if (!matchesRecordState(current, record.expectedState(), record.dimension()))
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
            if (matchesRecordState(current, record.expectedState(), record.dimension())) return true;
            store.removeIfRevision(record.key(), record.revision());
        }
        if (snapshot.restricted() || snapshot.parentId() == null) return false;
        ClaimSnapshot parent = claimAccess.resolve(snapshot.parentId());
        return parent != null && isSafeBuilder(parent.claimId(), playerId, player, parent, visited);
    }

    private boolean hasAnySafeBuilder(ClaimSnapshot snapshot, Set<Long> visited)
    {
        if (snapshot == null || !visited.add(snapshot.claimId())) return false;
        if (store.findAnySafeBuild(snapshot.claimId(), nowMillis.getAsLong(), snapshot.ownerId()).isPresent())
        {
            return true;
        }
        if (snapshot.restricted() || snapshot.parentId() == null) return false;
        return hasAnySafeBuilder(claimAccess.resolve(snapshot.parentId()), visited);
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

    private void markUnavailable()
    {
        cancelScheduledExpiration();
        loaded = false;
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

    private boolean persistBestEffort()
    {
        try
        {
            store.save();
            return true;
        }
        catch (IOException | RuntimeException ex)
        {
            logger.severe("Could not persist CatCraft trust state: " + ex.getMessage());
            return false;
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

    private record GrantPreparation(long claimId, String target, TrustDimension dimension,
                                    ClaimSnapshot snapshot,
                                    @Nullable TemporaryTrustRecord precedingRecord,
                                    NativeTrustState current, NativeTrustState baseline,
                                    NativeTrustState expected)
    {
    }

    private record ExpirationPreparation(TemporaryTrustRecord record, NativeTrustState current,
                                         boolean removeMetadata, boolean restoreMarker,
                                         boolean transition)
    {
    }

    private record ExpirationMutation(TemporaryTrustRecord record, NativeTrustState current,
                                      TrustTransition transition)
    {
    }

    private record RevokeMutation(long claimId, String target, String key,
                                  TrustDimension dimension, NativeTrustState intended)
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

    private static final class ExpirationFailure extends RuntimeException
    {
        private ExpirationFailure(String message, Throwable cause)
        {
            super(message, cause);
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
