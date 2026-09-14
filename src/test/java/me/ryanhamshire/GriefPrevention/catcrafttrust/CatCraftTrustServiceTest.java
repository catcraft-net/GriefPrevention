package me.ryanhamshire.GriefPrevention.catcrafttrust;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatCraftTrustServiceTest
{
    private static final UUID OWNER = UUID.fromString("16f54277-9f89-4d94-9f78-c876845219d7");
    private static final String TARGET = "85801356-29c3-4f1f-b363-3114641675b1";
    private static final NativeTrustState NONE = new NativeTrustState(null, false, false);

    @TempDir
    Path directory;

    @Test
    void temporaryGrantExpiresBackToPreviousNativeState() throws Exception
    {
        FakeAccess access = access(42L, OWNER, NONE);
        FakeScheduler scheduler = new FakeScheduler();
        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        CatCraftTrustService service = service(access, scheduler, now, 10);
        Claim claim = claim(42L, OWNER);

        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, Duration.ofDays(1));

        assertEquals(new NativeTrustState(ClaimPermission.Access, false, true),
                access.state(42L, TARGET, TrustDimension.PERMISSION));
        assertEquals(1, service.recordsForClaim(42L).size());
        assertEquals(1, scheduler.future.size());

        now.addAndGet(Duration.ofDays(1).toMillis());
        scheduler.runFuture();

        assertEquals(NONE, access.state(42L, TARGET, TrustDimension.PERMISSION));
        assertTrue(service.recordsForClaim(42L).isEmpty());
    }

    @Test
    void permanentReplacementInvalidatesTemporaryGeneration()
            throws Exception
    {
        FakeAccess access = access(42L, OWNER, NONE);
        FakeScheduler scheduler = new FakeScheduler();
        CatCraftTrustService service = service(access, scheduler, new AtomicLong(1_700_000_000_000L), 10);
        Claim claim = claim(42L, OWNER);

        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, Duration.ofDays(1));
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.CONTAINER, null);

        assertEquals(new NativeTrustState(ClaimPermission.Inventory, false, false),
                access.state(42L, TARGET, TrustDimension.PERMISSION));
        assertTrue(service.recordsForClaim(42L).isEmpty());
        scheduler.runFuture();
        assertEquals(new NativeTrustState(ClaimPermission.Inventory, false, false),
                access.state(42L, TARGET, TrustDimension.PERMISSION));
    }

    @Test
    void replacementTemporaryGrantRestoresOriginalBaseline()
            throws Exception
    {
        NativeTrustState baseline = new NativeTrustState(ClaimPermission.Access, false, false);
        FakeAccess access = access(42L, OWNER, baseline);
        FakeScheduler scheduler = new FakeScheduler();
        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        CatCraftTrustService service = service(access, scheduler, now, 10);
        Claim claim = claim(42L, OWNER);

        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, Duration.ofDays(1));
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.CONTAINER, Duration.ofDays(2));
        assertEquals(new NativeTrustState(ClaimPermission.Inventory, false, false),
                access.state(42L, TARGET, TrustDimension.PERMISSION));

        now.addAndGet(Duration.ofDays(2).toMillis());
        scheduler.runFuture();
        assertEquals(baseline, access.state(42L, TARGET, TrustDimension.PERMISSION));
    }

    @Test
    void startupDiscardsUnappliedAndOwnerMismatchedRecords()
            throws Exception
    {
        Path file = directory.resolve("temporary-trust.properties");
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(file, 10);
        store.put(new TemporaryTrustRecord(42L, TARGET, CatCraftTrustKind.BUILD,
                TrustDimension.PERMISSION, NONE,
                new NativeTrustState(ClaimPermission.Access, false, true),
                1_700_000_100_000L, 1L, OWNER));
        store.put(new TemporaryTrustRecord(43L, TARGET, CatCraftTrustKind.BUILD,
                TrustDimension.PERMISSION, NONE,
                new NativeTrustState(ClaimPermission.Access, false, true),
                1_700_000_100_000L, 2L, OWNER));
        store.save();

        FakeAccess access = new FakeAccess();
        access.snapshots.put(42L, new ClaimSnapshot(42L, OWNER, null, false));
        access.snapshots.put(43L, new ClaimSnapshot(43L, UUID.randomUUID(), null, false));
        access.states.put(access.key(42L, TARGET, TrustDimension.PERMISSION), NONE);
        access.states.put(access.key(43L, TARGET, TrustDimension.PERMISSION),
                new NativeTrustState(ClaimPermission.Access, false, true));
        CatCraftTrustService service = new CatCraftTrustService(
                store, access, new FakeScheduler(), () -> 1_700_000_000_000L, 10);

        service.start();

        assertTrue(service.recordsForClaim(42L).isEmpty());
        assertTrue(service.recordsForClaim(43L).isEmpty());
        assertTrue(access.applied.isEmpty());
    }

    @Test
    void processesOnlyConfiguredExpirationBatchAndContinuesNextTick()
            throws Exception
    {
        FakeAccess access = new FakeAccess();
        FakeScheduler scheduler = new FakeScheduler();
        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        for (long claimId = 1L; claimId <= 3L; claimId++)
        {
            access.snapshots.put(claimId, new ClaimSnapshot(claimId, OWNER, null, false));
            access.states.put(access.key(claimId, TARGET, TrustDimension.PERMISSION),
                    new NativeTrustState(ClaimPermission.Access, false, true));
        }
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(directory.resolve("state"), 10);
        for (long claimId = 1L; claimId <= 3L; claimId++)
        {
            store.put(new TemporaryTrustRecord(claimId, TARGET, CatCraftTrustKind.BUILD,
                    TrustDimension.PERMISSION, NONE,
                    new NativeTrustState(ClaimPermission.Access, false, true),
                    now.get() + 1L, claimId, OWNER));
        }
        store.save();
        CatCraftTrustService service = new CatCraftTrustService(store, access, scheduler, now::get, 2);

        service.start();
        now.incrementAndGet();
        scheduler.runFuture();
        assertEquals(1, service.recordsForClaim(3L).size());
        assertEquals(1, scheduler.nextTick.size());

        scheduler.runNextTick();
        assertTrue(service.recordsForClaim(3L).isEmpty());
    }

    @Test
    void safeBuilderLookupSupportsLocalAndUnrestrictedParentClaims()
            throws Exception
    {
        FakeAccess access = access(42L, OWNER, new NativeTrustState(ClaimPermission.Access, false, true));
        access.snapshots.put(41L, new ClaimSnapshot(41L, OWNER, null, false));
        access.states.put(access.key(41L, TARGET, TrustDimension.PERMISSION),
                new NativeTrustState(ClaimPermission.Access, false, true));
        FakeScheduler scheduler = new FakeScheduler();
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(directory.resolve("inherit-state"), 10);
        store.put(new TemporaryTrustRecord(41L, TARGET, CatCraftTrustKind.BUILD,
                TrustDimension.PERMISSION, NONE,
                new NativeTrustState(ClaimPermission.Access, false, true),
                0L, 1L, OWNER));
        store.save();
        CatCraftTrustService service = new CatCraftTrustService(
                store, access, scheduler, () -> 1_700_000_000_000L, 10);
        Claim child = claim(42L, OWNER);

        service.start();
        access.snapshots.put(42L, new ClaimSnapshot(42L, OWNER, 41L, false));
        assertTrue(service.isSafeBuilder(child, UUID.fromString(TARGET), null));

        access.snapshots.put(42L, new ClaimSnapshot(42L, OWNER, 41L, true));
        assertFalse(service.isSafeBuilder(child, UUID.fromString(TARGET), null));
    }

    @Test
    void externalMutationInvalidatesInMemoryBeforeBestEffortPersistence()
            throws Exception
    {
        FakeAccess access = access(42L, OWNER, NONE);
        FakeScheduler scheduler = new FakeScheduler();
        CatCraftTrustService service = service(access, scheduler, new AtomicLong(1_700_000_000_000L), 10);
        Claim claim = claim(42L, OWNER);
        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, Duration.ofDays(1));

        service.onExternalPermissionMutation(claim, TARGET, TrustDimension.PERMISSION);

        assertTrue(service.recordsForClaim(42L).isEmpty());
    }

    private CatCraftTrustService service(FakeAccess access, FakeScheduler scheduler,
                                         AtomicLong now, int maxExpirations)
    {
        return new CatCraftTrustService(
                new CatCraftTrustStateStore(directory.resolve("state-" + now.get()), 10),
                access, scheduler, now::get, maxExpirations);
    }

    private static FakeAccess access(long claimId, UUID owner, NativeTrustState state)
    {
        FakeAccess access = new FakeAccess();
        access.snapshots.put(claimId, new ClaimSnapshot(claimId, owner, null, false));
        access.states.put(access.key(claimId, TARGET, TrustDimension.PERMISSION), state);
        return access;
    }

    private static Claim claim(long claimId, UUID owner)
    {
        Claim claim = mock(Claim.class);
        when(claim.getID()).thenReturn(claimId);
        when(claim.getOwnerID()).thenReturn(owner);
        return claim;
    }

    private static final class FakeAccess implements ClaimTrustAccess
    {
        private final Map<Long, ClaimSnapshot> snapshots = new HashMap<>();
        private final Map<String, NativeTrustState> states = new HashMap<>();
        private final List<String> applied = new ArrayList<>();

        @Override
        public ClaimSnapshot resolve(long claimId)
        {
            return snapshots.get(claimId);
        }

        @Override
        public NativeTrustState capture(long claimId, String target, TrustDimension dimension)
        {
            return states.getOrDefault(key(claimId, target, dimension), new NativeTrustState(null, false, false));
        }

        @Override
        public void apply(long claimId, String target, NativeTrustState desired, TrustDimension dimension)
        {
            NativeTrustState current = capture(claimId, target, dimension);
            NativeTrustState merged = dimension == TrustDimension.PERMISSION
                    ? new NativeTrustState(desired.permission(), current.manager(), desired.safeBuild())
                    : new NativeTrustState(current.permission(), desired.manager(), current.safeBuild());
            states.put(key(claimId, target, dimension), merged);
            applied.add(key(claimId, target, dimension));
        }

        @Override
        public void save(long claimId)
        {
        }

        private String key(long claimId, String target, TrustDimension dimension)
        {
            return claimId + "|" + target.toLowerCase() + "|" + dimension;
        }

        private NativeTrustState state(long claimId, String target, TrustDimension dimension)
        {
            return capture(claimId, target, dimension);
        }
    }

    private static final class FakeScheduler implements TrustTaskScheduler
    {
        private final List<Runnable> future = new ArrayList<>();
        private final ArrayDeque<Runnable> nextTick = new ArrayDeque<>();

        @Override
        public ScheduledHandle schedule(long delayTicks, Runnable task)
        {
            future.add(task);
            return () -> future.remove(task);
        }

        @Override
        public void nextTick(Runnable task)
        {
            nextTick.add(task);
        }

        private void runFuture()
        {
            if (!future.isEmpty()) future.remove(0).run();
        }

        private void runNextTick()
        {
            if (!nextTick.isEmpty()) nextTick.remove().run();
        }
    }
}
