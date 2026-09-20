package me.ryanhamshire.GriefPrevention.catcrafttrust;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void replacementPreservesCurrentOrthogonalDimensionAndHistoricalBaseline() throws Exception
    {
        RawNativeAccess access = rawAccess(42L, OWNER, NONE);
        AtomicLong now = new AtomicLong(1000L);
        CatCraftTrustService service = new CatCraftTrustService(new CatCraftTrustStateStore(
                directory.resolve("orthogonal.properties"), 10), access, new FakeScheduler(), now::get, 10);
        Claim claim = claim(42L, OWNER);
        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, Duration.ofSeconds(1));
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.MANAGE, null);
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.CONTAINER, Duration.ofSeconds(1));
        now.set(2000L);
        service.processDue(now.get());
        assertEquals(new NativeTrustState(null, true, false), access.state(42L, TARGET, TrustDimension.PERMISSION));
        service.revoke(List.of(claim), TARGET);
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.MANAGE, Duration.ofSeconds(1));
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.CONTAINER, null);
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.MANAGE, Duration.ofSeconds(1));
        now.set(3000L);
        service.processDue(now.get());
        assertEquals(new NativeTrustState(ClaimPermission.Inventory, false, false),
                access.state(42L, TARGET, TrustDimension.PERMISSION));
    }

    @Test
    void backupRetainsPreparedGrantWhenPrimaryIsLostAfterNativeWrite() throws Exception
    {
        Path file = directory.resolve("prepared-backup.properties");
        FakeAccess access = access(42L, OWNER, NONE);
        CatCraftTrustService service = new CatCraftTrustService(new CatCraftTrustStateStore(file, 10),
                access, new FakeScheduler(), () -> 1000L, 10);
        service.start();
        access.failAfterSave = true;
        assertThrows(RuntimeException.class, () -> service.grant(List.of(claim(42L, OWNER)), TARGET,
                CatCraftTrustKind.CONTAINER, Duration.ofSeconds(1)));
        access.failAfterSave = false;
        Files.delete(file);
        CatCraftTrustService restarted = new CatCraftTrustService(new CatCraftTrustStateStore(file, 10),
                access, new FakeScheduler(), () -> 2000L, 10);
        restarted.start();
        restarted.processDue(2000L);
        assertEquals(NONE, access.state(42L, TARGET, TrustDimension.PERMISSION));
    }

    @Test
    void backupRecordRecoveryFlushesUnsavedNativeRestorationBeforeDiscardingMetadata() throws Exception
    {
        Path file = directory.resolve("backup-reload.properties");
        FakeAccess access = access(42L, OWNER, NONE);
        CatCraftTrustService service = new CatCraftTrustService(new CatCraftTrustStateStore(file, 10),
                access, new FakeScheduler(), () -> 1000L, 10);
        service.start();
        service.grant(List.of(claim(42L, OWNER)), TARGET, CatCraftTrustKind.CONTAINER, Duration.ofSeconds(1));
        // Model fallback to the valid pre-expiry record after a failed native restore save.
        Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        access.apply(42L, TARGET, NONE, TrustDimension.PERMISSION);
        Files.delete(file);
        CatCraftTrustService reloaded = new CatCraftTrustService(new CatCraftTrustStateStore(file, 10),
                access, new FakeScheduler(), () -> 2000L, 10);
        reloaded.start();
        access.states.clear();
        access.states.putAll(access.persistedStates);
        assertEquals(NONE, access.state(42L, TARGET, TrustDimension.PERMISSION));
    }

    @Test
    void failedExternalSaveCannotTurnTemporaryContainerTrustPermanentOnRestart() throws Exception
    {
        verifyFailedExternalSaveRecovery(false);
    }

    @Test
    void failedExternalSaveCannotForgetUnsavedRevocationOnReload() throws Exception
    {
        verifyFailedExternalSaveRecovery(true);
    }

    private void verifyFailedExternalSaveRecovery(boolean reload) throws Exception
    {
        Path file = directory.resolve("external-restart.properties");
        FakeAccess access = access(42L, OWNER, NONE);
        AtomicLong now = new AtomicLong(1000L);
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, new FakeScheduler(), now::get, 10);
        Claim claim = claim(42L, OWNER);
        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.CONTAINER, Duration.ofSeconds(1));
        assertTrue(service.onExternalPermissionMutation(claim, TARGET, TrustDimension.PERMISSION));
        access.apply(42L, TARGET, NONE, TrustDimension.PERMISSION);
        access.failSave = true;
        assertThrows(IllegalStateException.class,
                () -> service.completeExternalPermissionMutation(claim, TARGET, TrustDimension.PERMISSION));
        if (!reload)
        {
            access.states.clear();
            access.states.putAll(access.persistedStates);
        }
        access.failSave = false;
        now.set(2000L);
        CatCraftTrustService restarted = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, new FakeScheduler(), now::get, 10);
        restarted.start();
        restarted.processDue(now.get());
        access.states.clear();
        access.states.putAll(access.persistedStates);
        assertEquals(NONE, access.state(42L, TARGET, TrustDimension.PERMISSION));
    }

    @Test
    void partialNativeGrantFailureMakesServiceUnavailable() throws Exception
    {
        FakeAccess access = access(42L, OWNER, NONE);
        CatCraftTrustService service = service(access, new FakeScheduler(), new AtomicLong(1000L), 10);
        service.start();
        access.failAfterApply = true;
        assertThrows(RuntimeException.class, () -> service.grant(List.of(claim(42L, OWNER)), TARGET,
                CatCraftTrustKind.CONTAINER, Duration.ofDays(1)));
        assertFalse(service.isStarted());
    }

    @Test
    void reloadAfterFailedNativeExpirySaveCannotForgetDurableTemporaryGrant() throws Exception
    {
        Path file = directory.resolve("native-save-recovery.properties");
        FakeAccess access = access(42L, OWNER, NONE);
        FakeScheduler scheduler = new FakeScheduler();
        AtomicLong now = new AtomicLong(1000L);
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, scheduler, now::get, 10);
        service.start();
        service.grant(List.of(claim(42L, OWNER)), TARGET, CatCraftTrustKind.CONTAINER, Duration.ofSeconds(1));
        access.failSave = true;
        now.set(2000L);
        scheduler.runFuture();
        assertFalse(service.isStarted());
        access.failSave = false;
        // A plugin reload still sees the in-memory restoration; a server restart sees disk.
        CatCraftTrustService reloaded = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, new FakeScheduler(), now::get, 10);
        reloaded.start();
        access.states.clear();
        access.states.putAll(access.persistedStates);
        assertEquals(NONE, access.state(42L, TARGET, TrustDimension.PERMISSION));
        assertTrue(reloaded.recordsSnapshot().isEmpty());
    }

    @Test
    void externalInvalidationRetainsJournalUntilNativeSaveSucceeds() throws Exception
    {
        FakeAccess access = access(42L, OWNER, NONE);
        CatCraftTrustService service = service(access, new FakeScheduler(), new AtomicLong(1000L), 10);
        Claim claim = claim(42L, OWNER);
        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.CONTAINER, Duration.ofDays(1));
        assertTrue(service.onExternalPermissionMutation(claim, TARGET, TrustDimension.PERMISSION));
        access.apply(42L, TARGET, NONE, TrustDimension.PERMISSION);
        access.failSave = true;
        assertThrows(IllegalStateException.class,
                () -> service.completeExternalPermissionMutation(claim, TARGET, TrustDimension.PERMISSION));
        assertFalse(service.isStarted());
        CatCraftTrustStateStore durable = new CatCraftTrustStateStore(directory.resolve("state-1000"), 10);
        durable.load();
        assertEquals(1, durable.transitionValues().size());
        assertEquals(CatCraftTrustKind.CONTAINER,
                durable.transitionValues().getFirst().precedingRecord().appliedKind());
    }

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
    void successfulExpirationEmitsOneNotificationAfterPersistence() throws Exception
    {
        FakeAccess access = access(42L, OWNER, NONE);
        FakeScheduler scheduler = new FakeScheduler();
        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        CatCraftTrustService service = service(access, scheduler, now, 10);
        List<TemporaryTrustRecord> notifications = new ArrayList<>();
        service.setExpirationListener(notifications::add);
        service.start();
        service.grant(List.of(claim(42L, OWNER)), TARGET,
                CatCraftTrustKind.BUILD, Duration.ofMinutes(1));

        now.addAndGet(Duration.ofMinutes(1).toMillis());
        scheduler.runFuture();

        assertEquals(1, notifications.size());
        assertEquals(TARGET, notifications.getFirst().target());
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

    @Test
    void externalMutationJournalsTombstoneBeforeNativeChangeAndCompletesAfterward()
            throws Exception
    {
        Path file = directory.resolve("external-write-ahead.properties");
        FakeAccess access = access(42L, OWNER, NONE);
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, new FakeScheduler(),
                () -> 1_700_000_000_000L, 10);
        Claim claim = claim(42L, OWNER);
        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, Duration.ofDays(1));

        assertTrue(service.onExternalPermissionMutation(claim, TARGET, TrustDimension.PERMISSION));
        assertTrue(service.recordsForClaim(42L).isEmpty());
        CatCraftTrustStateStore journal = new CatCraftTrustStateStore(file, 10);
        journal.load();
        assertEquals(1, journal.transitionValues().size());
        assertTrue(journal.transitionValues().get(0).precedingRecord() != null);

        service.completeExternalPermissionMutation(claim, TARGET, TrustDimension.PERMISSION);

        assertTrue(service.recordsForClaim(42L).isEmpty());
        CatCraftTrustStateStore completed = new CatCraftTrustStateStore(file, 10);
        completed.load();
        assertTrue(completed.transitionValues().isEmpty());
        assertTrue(completed.values().isEmpty());
    }

    @Test
    void externalMutationSaveFailureLeavesRecordAndBlocksNativeCompletion()
            throws Exception
    {
        Path file = directory.resolve("external-save-failure.properties");
        FakeAccess access = access(42L, OWNER, NONE);
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(file, 10);
        CatCraftTrustService service = new CatCraftTrustService(store, access, new FakeScheduler(),
                () -> 1_700_000_000_000L, 10);
        Claim claim = claim(42L, OWNER);
        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, Duration.ofDays(1));
        Files.createDirectory(file.resolveSibling(file.getFileName() + ".tmp"));

        assertFalse(service.onExternalPermissionMutation(claim, TARGET, TrustDimension.PERMISSION));

        assertEquals(1, service.recordsForClaim(42L).size());
        assertTrue(store.transitionValues().isEmpty());
    }

    @Test
    void externalMutationFinalSaveFailureLeavesDurableTransitionForRestart()
            throws Exception
    {
        Path file = directory.resolve("external-final-save-failure.properties");
        FakeAccess access = access(42L, OWNER, NONE);
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, new FakeScheduler(),
                () -> 1_700_000_000_000L, 10);
        Claim claim = claim(42L, OWNER);
        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, Duration.ofDays(1));

        assertTrue(service.onExternalPermissionMutation(claim, TARGET, TrustDimension.PERMISSION));
        Files.createDirectory(file.resolveSibling(file.getFileName() + ".tmp"));
        assertThrows(IllegalStateException.class,
                () -> service.completeExternalPermissionMutation(claim, TARGET, TrustDimension.PERMISSION));

        assertFalse(service.isStarted());
        CatCraftTrustStateStore journal = new CatCraftTrustStateStore(file, 10);
        journal.load();
        assertEquals(1, journal.transitionValues().size());
        assertTrue(journal.values().isEmpty());
    }

    @Test
    void inactiveServiceDoesNotMutateOrPersistLoadedMetadata() throws Exception
    {
        Path file = directory.resolve("inactive-state.properties");
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(file, 10);
        store.put(new TemporaryTrustRecord(42L, TARGET, CatCraftTrustKind.BUILD,
                TrustDimension.PERMISSION, NONE,
                new NativeTrustState(ClaimPermission.Access, false, true),
                0L, 1L, OWNER));
        CatCraftTrustService service = new CatCraftTrustService(
                store, access(42L, OWNER, NONE), new FakeScheduler(),
                () -> 1_700_000_000_000L, 10);

        service.onExternalPermissionMutation(claim(42L, OWNER), TARGET, TrustDimension.PERMISSION);

        assertEquals(1, service.recordsForClaim(42L).size());
        assertFalse(Files.exists(file));
    }

    @Test
    void commandMutationApisRejectInactiveService() throws Exception
    {
        Path file = directory.resolve("inactive-commands.properties");
        FakeAccess access = access(42L, OWNER, NONE);
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, new FakeScheduler(),
                () -> 1_700_000_000_000L, 10);
        Claim claim = claim(42L, OWNER);

        assertThrows(IllegalStateException.class, () -> service.grant(
                List.of(claim), TARGET, CatCraftTrustKind.BUILD, null));
        assertThrows(IllegalStateException.class, () -> service.revoke(List.of(claim), TARGET));
        assertThrows(IllegalStateException.class, () -> service.clearClaims(List.of(claim)));
        assertTrue(access.applied.isEmpty());
        assertFalse(Files.exists(file));
    }

    @Test
    void failedStartupDoesNotLeaveServiceActive() throws Exception
    {
        Path file = directory.resolve("failed-start.properties");
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(file, 10);
        store.put(new TemporaryTrustRecord(42L, TARGET, CatCraftTrustKind.BUILD,
                TrustDimension.PERMISSION, NONE,
                new NativeTrustState(ClaimPermission.Access, false, true),
                0L, 1L, OWNER));
        store.save();
        FakeAccess access = access(42L, OWNER,
                new NativeTrustState(ClaimPermission.Access, false, true));
        access.failResolve = true;
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, new FakeScheduler(),
                () -> 1_700_000_000_000L, 10);

        assertThrows(RuntimeException.class, service::start);
        assertFalse(service.isStarted());
    }

    @Test
    void failedStopStillMakesServiceInactive() throws Exception
    {
        Path file = directory.resolve("failed-stop.properties");
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access(42L, OWNER, NONE),
                new FakeScheduler(), () -> 1_700_000_000_000L, 10);
        service.start();
        Files.createDirectory(file);
        Files.writeString(file.resolve("keep"), "force replacement failure");

        assertThrows(Exception.class, service::stop);
        assertFalse(service.isStarted());
    }

    @Test
    void noOpExternalMutationDoesNotCreateOrRewriteStateFile() throws Exception
    {
        Path file = directory.resolve("no-op-external-state.properties");
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access(42L, OWNER, NONE),
                new FakeScheduler(), () -> 1_700_000_000_000L, 10);
        service.start();

        service.onExternalPermissionMutation(claim(42L, OWNER), TARGET, TrustDimension.PERMISSION);
        service.onExternalTargetMutation(claim(42L, OWNER), TARGET);
        service.onExternalTargetRemoved(claim(42L, OWNER), TARGET);
        service.onExternalPermissionsCleared(claim(42L, OWNER));

        assertFalse(Files.exists(file));
    }

    @Test
    void clearingParentRemovesParentAndSubdivisionMetadataInOneCallback() throws Exception
    {
        Path file = directory.resolve("clear-subtree.properties");
        FakeAccess access = new FakeAccess();
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, new FakeScheduler(),
                () -> 1_700_000_000_000L, 10);
        Claim parent = claim(42L, OWNER);
        Claim child = claim(43L, OWNER);
        parent.children.add(child);
        child.parent = parent;
        access.snapshots.put(42L, new ClaimSnapshot(42L, OWNER, null, false));
        access.snapshots.put(43L, new ClaimSnapshot(43L, OWNER, 42L, false));
        access.states.put(access.key(42L, TARGET, TrustDimension.PERMISSION), NONE);
        access.states.put(access.key(43L, TARGET, TrustDimension.PERMISSION), NONE);
        service.start();
        service.grant(List.of(parent, child), TARGET, CatCraftTrustKind.BUILD, Duration.ofDays(1));

        service.onExternalPermissionsCleared(parent);

        assertTrue(service.recordsForClaim(42L).isEmpty());
        assertTrue(service.recordsForClaim(43L).isEmpty());
    }

    @Test
    void removingTargetFromParentRemovesSubdivisionMetadataInOneCallback() throws Exception
    {
        FakeAccess access = new FakeAccess();
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(directory.resolve("remove-subtree.properties"), 10),
                access, new FakeScheduler(), () -> 1_700_000_000_000L, 10);
        Claim parent = claim(42L, OWNER);
        Claim child = claim(43L, OWNER);
        parent.children.add(child);
        child.parent = parent;
        access.snapshots.put(42L, new ClaimSnapshot(42L, OWNER, null, false));
        access.snapshots.put(43L, new ClaimSnapshot(43L, OWNER, 42L, false));
        access.states.put(access.key(42L, TARGET, TrustDimension.PERMISSION), NONE);
        access.states.put(access.key(43L, TARGET, TrustDimension.PERMISSION), NONE);
        service.start();
        service.grant(List.of(parent, child), TARGET, CatCraftTrustKind.BUILD, Duration.ofDays(1));

        service.onExternalTargetRemoved(parent, TARGET);

        assertTrue(service.recordsForClaim(42L).isEmpty());
        assertTrue(service.recordsForClaim(43L).isEmpty());
    }

    @Test
    void writeAheadReplacementPreservesPrecedingTimerWhenNativeApplyFails() throws Exception
    {
        Path file = directory.resolve("write-ahead.properties");
        FakeAccess access = access(42L, OWNER, NONE);
        FakeScheduler scheduler = new FakeScheduler();
        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        Claim claim = claim(42L, OWNER);
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, scheduler, now::get, 10);

        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, Duration.ofDays(1));
        access.failApply = true;
        assertThrows(RuntimeException.class, () -> service.grant(
                List.of(claim), TARGET, CatCraftTrustKind.CONTAINER, Duration.ofDays(2)));

        FakeAccess restartedAccess = access(42L, OWNER,
                new NativeTrustState(ClaimPermission.Access, false, true));
        CatCraftTrustService restarted = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), restartedAccess,
                new FakeScheduler(), now::get, 10);
        restarted.start();

        TemporaryTrustRecord restored = restarted.recordsForClaim(42L).get(0);
        assertEquals(CatCraftTrustKind.BUILD, restored.appliedKind());
        assertEquals(now.get() + Duration.ofDays(1).toMillis(), restored.expiresAtMillis());
    }

    @Test
    void writeAheadRevokePreservesPrecedingTimerWhenNativeApplyFails() throws Exception
    {
        Path file = directory.resolve("write-ahead-revoke.properties");
        FakeAccess access = access(42L, OWNER, NONE);
        FakeScheduler scheduler = new FakeScheduler();
        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        Claim claim = claim(42L, OWNER);
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, scheduler, now::get, 10);

        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, Duration.ofDays(1));
        access.failApply = true;
        assertThrows(RuntimeException.class, () -> service.revoke(List.of(claim), TARGET));

        FakeAccess restartedAccess = access(42L, OWNER,
                new NativeTrustState(ClaimPermission.Access, false, true));
        CatCraftTrustService restarted = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), restartedAccess,
                new FakeScheduler(), now::get, 10);
        restarted.start();

        assertEquals(1, restarted.recordsForClaim(42L).size());
    }

    @Test
    void expiredInspectionFailureDisablesServiceWithoutSchedulingRetry() throws Exception
    {
        Path file = directory.resolve("expiry-inspection-failure.properties");
        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        FakeAccess access = access(42L, OWNER, NONE);
        FakeScheduler scheduler = new FakeScheduler();
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, scheduler, now::get, 10);
        Claim claim = claim(42L, OWNER);
        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, Duration.ofDays(1));
        access.failResolve = true;
        now.addAndGet(Duration.ofDays(1).toMillis());

        scheduler.runFuture();

        assertFalse(service.isStarted());
        assertTrue(scheduler.future.isEmpty());
        assertTrue(scheduler.nextTick.isEmpty());
        assertFalse(service.isSafeBuilder(claim, UUID.fromString(TARGET), null));
        assertThrows(IllegalStateException.class, () -> service.grant(
                List.of(claim), TARGET, CatCraftTrustKind.BUILD, Duration.ofDays(1)));
    }

    @Test
    void expiredJournalFailureDisablesServiceWithoutSchedulingRetry() throws Exception
    {
        Path file = directory.resolve("expiry-journal-failure.properties");
        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        FakeAccess access = access(42L, OWNER, NONE);
        FakeScheduler scheduler = new FakeScheduler();
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, scheduler, now::get, 10);
        Claim claim = claim(42L, OWNER);
        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, Duration.ofDays(1));
        Files.createDirectory(file.resolveSibling(file.getFileName() + ".tmp"));
        now.addAndGet(Duration.ofDays(1).toMillis());

        scheduler.runFuture();

        assertFalse(service.isStarted());
        assertTrue(scheduler.future.isEmpty());
        assertTrue(scheduler.nextTick.isEmpty());
    }

    @Test
    void permanentBuildIsPersistedAsSafeBuildMarker()
            throws Exception
    {
        Path file = directory.resolve("permanent-build.properties");
        FakeAccess access = access(42L, OWNER, NONE);
        FakeScheduler scheduler = new FakeScheduler();
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, scheduler,
                () -> 1_700_000_000_000L, 10);
        Claim claim = claim(42L, OWNER);

        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, null);

        assertEquals(1, service.recordsForClaim(42L).size());
        assertEquals(0L, service.recordsForClaim(42L).get(0).expiresAtMillis());
        assertTrue(service.isSafeBuilder(claim, UUID.fromString(TARGET), null));
    }

    @Test
    void staleLocalSafeBuildCandidateDoesNotHideLaterValidCandidate()
            throws Exception
    {
        Path file = directory.resolve("safe-build-candidates.properties");
        FakeAccess access = access(42L, OWNER, NONE);
        access.states.put(access.key(42L, "public", TrustDimension.PERMISSION), NONE);
        access.states.put(access.key(42L, TARGET, TrustDimension.PERMISSION),
                new NativeTrustState(ClaimPermission.Access, false, true));
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(file, 10);
        store.put(new TemporaryTrustRecord(42L, "public", CatCraftTrustKind.BUILD,
                TrustDimension.PERMISSION, NONE,
                new NativeTrustState(ClaimPermission.Access, false, true), 0L, 1L, OWNER));
        store.put(new TemporaryTrustRecord(42L, TARGET, CatCraftTrustKind.BUILD,
                TrustDimension.PERMISSION, NONE,
                new NativeTrustState(ClaimPermission.Access, false, true), 0L, 2L, OWNER));
        store.save();
        byte[] before = Files.readAllBytes(file);
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, new FakeScheduler(),
                () -> 1_700_000_000_000L, 10);
        Claim claim = claim(42L, OWNER);
        service.start();
        byte[] afterStart = Files.readAllBytes(file);

        assertTrue(service.isSafeBuilder(claim, UUID.fromString(TARGET), null));
        assertEquals(List.of(
                new TemporaryTrustRecord(42L, TARGET, CatCraftTrustKind.BUILD,
                        TrustDimension.PERMISSION, NONE,
                        new NativeTrustState(ClaimPermission.Access, false, true), 0L, 2L, OWNER)),
                service.recordsForClaim(42L));
        assertArrayEquals(afterStart, Files.readAllBytes(file));
    }

    @Test
    void restrictedFallbackSnapshotStopsParentInheritance()
            throws Exception
    {
        FakeAccess access = new FakeAccess();
        access.snapshots.put(41L, new ClaimSnapshot(41L, OWNER, null, false));
        access.states.put(access.key(41L, TARGET, TrustDimension.PERMISSION),
                new NativeTrustState(ClaimPermission.Access, false, true));
        Path file = directory.resolve("restricted-fallback.properties");
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(file, 10);
        store.put(new TemporaryTrustRecord(41L, TARGET, CatCraftTrustKind.BUILD,
                TrustDimension.PERMISSION, NONE,
                new NativeTrustState(ClaimPermission.Access, false, true), 0L, 1L, OWNER));
        store.save();
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, new FakeScheduler(),
                () -> 1_700_000_000_000L, 10);
        Claim child = claim(42L, OWNER);
        when(child.getSubclaimRestrictions()).thenReturn(true);
        Claim parent = claim(41L, OWNER);
        child.parent = parent;

        service.start();

        assertFalse(service.isSafeBuilder(child, UUID.fromString(TARGET), null));
    }

    @Test
    void startupQueuesOverdueRecordsForBoundedProcessing()
            throws Exception
    {
        Path file = directory.resolve("startup-batch.properties");
        long now = 1_700_000_000_000L;
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(file, 10);
        FakeAccess access = new FakeAccess();
        for (long claimId = 1L; claimId <= 3L; claimId++)
        {
            access.snapshots.put(claimId, new ClaimSnapshot(claimId, OWNER, null, false));
            access.states.put(access.key(claimId, TARGET, TrustDimension.PERMISSION),
                    new NativeTrustState(ClaimPermission.Access, false, true));
            store.put(new TemporaryTrustRecord(claimId, TARGET, CatCraftTrustKind.BUILD,
                    TrustDimension.PERMISSION, NONE,
                    new NativeTrustState(ClaimPermission.Access, false, true),
                    now - 1L, claimId, OWNER));
        }
        store.save();
        FakeScheduler scheduler = new FakeScheduler();
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, scheduler, () -> now, 1);

        service.start();

        assertEquals(3, service.recordsForClaim(1L).size()
                + service.recordsForClaim(2L).size()
                + service.recordsForClaim(3L).size());
        assertEquals(1, scheduler.nextTick.size());
        assertTrue(access.applied.isEmpty());
    }

    @Test
    void canceledNextTickCannotRunAlongsideReplacementFutureCallback()
            throws Exception
    {
        Path file = directory.resolve("single-callback.properties");
        long initial = 1_700_000_000_000L;
        AtomicLong now = new AtomicLong(initial);
        FakeAccess access = new FakeAccess();
        for (long claimId = 1L; claimId <= 2L; claimId++)
        {
            access.snapshots.put(claimId, new ClaimSnapshot(claimId, OWNER, null, false));
            access.states.put(access.key(claimId, TARGET, TrustDimension.PERMISSION),
                    new NativeTrustState(ClaimPermission.Access, false, true));
        }
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(file, 10);
        for (long claimId = 1L; claimId <= 2L; claimId++)
        {
            store.put(new TemporaryTrustRecord(claimId, TARGET, CatCraftTrustKind.BUILD,
                    TrustDimension.PERMISSION, NONE,
                    new NativeTrustState(ClaimPermission.Access, false, true),
                    initial + 1L, claimId, OWNER));
        }
        store.save();
        FakeScheduler scheduler = new FakeScheduler();
        CatCraftTrustService service = new CatCraftTrustService(store, access, scheduler,
                now::get, 1);

        service.start();
        now.incrementAndGet();
        scheduler.runFuture();
        store.put(new TemporaryTrustRecord(2L, TARGET, CatCraftTrustKind.BUILD,
                TrustDimension.PERMISSION, NONE,
                new NativeTrustState(ClaimPermission.Access, false, true),
                initial + Duration.ofDays(1).toMillis(), 3L, OWNER));
        service.grant(List.of(), TARGET, CatCraftTrustKind.BUILD, null);

        scheduler.runNextTick();
        assertEquals(1, scheduler.future.size());
        assertEquals(1, service.recordsForClaim(2L).size());
    }

    @Test
    void expirationOfMissingClaimDiscardsRecordWithoutNativeWrite()
            throws Exception
    {
        Path file = directory.resolve("missing-claim-expiry.properties");
        long initial = 1_700_000_000_000L;
        AtomicLong now = new AtomicLong(initial);
        FakeAccess access = access(42L, OWNER,
                new NativeTrustState(ClaimPermission.Access, false, true));
        FakeScheduler scheduler = new FakeScheduler();
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(file, 10);
        store.put(new TemporaryTrustRecord(42L, TARGET, CatCraftTrustKind.BUILD,
                TrustDimension.PERMISSION, NONE,
                new NativeTrustState(ClaimPermission.Access, false, true), initial + 1L, 1L, OWNER));
        store.save();
        CatCraftTrustService service = new CatCraftTrustService(store, access, scheduler,
                now::get, 10);

        service.start();
        access.snapshots.remove(42L);
        now.incrementAndGet();
        scheduler.runFuture();

        assertTrue(service.recordsForClaim(42L).isEmpty());
        assertTrue(access.applied.isEmpty());
    }

    @Test
    void temporaryReplacementRestoresPermanentBuildStateAndMarkerBaseline()
            throws Exception
    {
        FakeAccess access = access(42L, OWNER, NONE);
        FakeScheduler scheduler = new FakeScheduler();
        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        CatCraftTrustService service = service(access, scheduler, now, 10);
        Claim claim = claim(42L, OWNER);

        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, null);
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.CONTAINER, Duration.ofDays(1));

        now.addAndGet(Duration.ofDays(1).toMillis());
        scheduler.runFuture();

        assertEquals(new NativeTrustState(ClaimPermission.Access, false, true),
                access.state(42L, TARGET, TrustDimension.PERMISSION));
    }

    @Test
    void rawNativeAdapterKeepsPermanentBuildMarkerThroughReplacementExpiryAndRestart()
            throws Exception
    {
        Path file = directory.resolve("raw-native-marker.properties");
        long initial = 1_700_000_000_000L;
        AtomicLong now = new AtomicLong(initial);
        RawNativeAccess access = rawAccess(42L, OWNER, NONE);
        FakeScheduler scheduler = new FakeScheduler();
        Claim claim = claim(42L, OWNER);
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, scheduler, now::get, 10);

        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, null);
        assertEquals(new NativeTrustState(ClaimPermission.Access, false, false),
                access.state(42L, TARGET, TrustDimension.PERMISSION));
        assertEquals(1, service.recordsForClaim(42L).size());
        assertEquals(CatCraftTrustKind.BUILD, service.recordsForClaim(42L).get(0).appliedKind());
        assertEquals(0L, service.recordsForClaim(42L).get(0).expiresAtMillis());
        assertTrue(service.isSafeBuilder(claim, UUID.fromString(TARGET), null));

        service.grant(List.of(claim), TARGET, CatCraftTrustKind.CONTAINER, Duration.ofDays(1));
        assertEquals(new NativeTrustState(ClaimPermission.Inventory, false, false),
                access.state(42L, TARGET, TrustDimension.PERMISSION));

        now.addAndGet(Duration.ofDays(1).toMillis());
        scheduler.runFuture();

        assertEquals(new NativeTrustState(ClaimPermission.Access, false, false),
                access.state(42L, TARGET, TrustDimension.PERMISSION));
        assertEquals(1, service.recordsForClaim(42L).size());
        assertEquals(CatCraftTrustKind.BUILD, service.recordsForClaim(42L).get(0).appliedKind());
        assertEquals(0L, service.recordsForClaim(42L).get(0).expiresAtMillis());
        assertTrue(service.isSafeBuilder(claim, UUID.fromString(TARGET), null));

        CatCraftTrustService restarted = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, new FakeScheduler(), now::get, 10);
        restarted.start();
        assertEquals(1, restarted.recordsForClaim(42L).size());
        assertEquals(CatCraftTrustKind.BUILD, restarted.recordsForClaim(42L).get(0).appliedKind());
        assertTrue(restarted.isSafeBuilder(claim, UUID.fromString(TARGET), null));
    }

    @Test
    void startupCompletesMarkerTransitionWhenRawNativePermissionMatchesIntendedState()
            throws Exception
    {
        Path file = directory.resolve("marker-only-transition.properties");
        NativeTrustState markerState = new NativeTrustState(ClaimPermission.Access, false, true);
        TemporaryTrustRecord intended = new TemporaryTrustRecord(42L, TARGET,
                CatCraftTrustKind.BUILD, TrustDimension.PERMISSION, NONE, markerState,
                0L, 1L, OWNER);
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(file, 10);
        store.beginTransition(new TrustTransition(intended.key(), null, NONE,
                intended, markerState));
        store.save();

        RawNativeAccess access = rawAccess(42L, OWNER,
                new NativeTrustState(ClaimPermission.Access, false, false));
        Claim claim = claim(42L, OWNER);
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, new FakeScheduler(),
                () -> 1_700_000_000_000L, 10);

        service.start();

        assertEquals(List.of(intended), service.recordsForClaim(42L));
        assertTrue(service.isSafeBuilder(claim, UUID.fromString(TARGET), null));
    }

    @Test
    void manageGrantCoexistsWithSafeBuildMarkerAndPreservesBothDimensions()
            throws Exception
    {
        Path file = directory.resolve("manage-supersedes-marker.properties");
        RawNativeAccess access = rawAccess(42L, OWNER, NONE);
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, new FakeScheduler(),
                () -> 1_700_000_000_000L, 10);
        Claim claim = claim(42L, OWNER);

        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, null);
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.MANAGE, null);

        assertEquals(new NativeTrustState(ClaimPermission.Access, true, false),
                access.state(42L, TARGET, TrustDimension.MANAGER));
        assertEquals(1, service.recordsForClaim(42L).size());
        assertTrue(service.recordsForClaim(42L).stream()
                .anyMatch(record -> record.dimension() == TrustDimension.PERMISSION));
        assertTrue(service.isSafeBuilder(claim, UUID.fromString(TARGET), null));

        CatCraftTrustService restarted = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, new FakeScheduler(),
                () -> 1_700_000_000_000L, 10);
        restarted.start();
        assertEquals(1, restarted.recordsForClaim(42L).size());
        assertTrue(restarted.isSafeBuilder(claim, UUID.fromString(TARGET), null));
    }

    @Test
    void manageGrantDoesNotEvictSafeBuildMarkerToBypassCapacity()
            throws Exception
    {
        Path file = directory.resolve("manage-capacity-replacement.properties");
        RawNativeAccess access = rawAccess(42L, OWNER, NONE);
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 1), access, new FakeScheduler(),
                () -> 1_700_000_000_000L, 10);
        Claim claim = claim(42L, OWNER);

        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, null);
        assertThrows(IllegalStateException.class, () -> service.grant(
                List.of(claim), TARGET, CatCraftTrustKind.MANAGE, null));

        assertEquals(new NativeTrustState(ClaimPermission.Access, false, false),
                access.state(42L, TARGET, TrustDimension.MANAGER));
        assertEquals(1, service.recordsForClaim(42L).size());
        assertTrue(service.isSafeBuilder(claim, UUID.fromString(TARGET), null));
    }

    @Test
    void temporaryManageCoexistsWithPermissionExpiryAndPreservesSafeBuildFallback()
            throws Exception
    {
        Path file = directory.resolve("manage-supersedes-expiry.properties");
        long initial = 1_700_000_000_000L;
        AtomicLong now = new AtomicLong(initial);
        RawNativeAccess access = rawAccess(42L, OWNER, NONE);
        FakeScheduler scheduler = new FakeScheduler();
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, scheduler, now::get, 10);
        Claim claim = claim(42L, OWNER);

        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, null);
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.MANAGE, Duration.ofDays(2));

        assertEquals(2, service.recordsForClaim(42L).size());
        assertEquals(new NativeTrustState(ClaimPermission.Access, true, false),
                access.state(42L, TARGET, TrustDimension.MANAGER));
        assertTrue(service.isSafeBuilder(claim, UUID.fromString(TARGET), null));

        now.addAndGet(Duration.ofDays(1).toMillis());
        scheduler.runFuture();
        assertEquals(new NativeTrustState(ClaimPermission.Access, true, false),
                access.state(42L, TARGET, TrustDimension.MANAGER));
        assertTrue(service.isSafeBuilder(claim, UUID.fromString(TARGET), null));

        now.addAndGet(Duration.ofDays(1).toMillis());
        scheduler.runFuture();
        assertEquals(new NativeTrustState(ClaimPermission.Access, false, false),
                access.state(42L, TARGET, TrustDimension.MANAGER));
        assertTrue(service.isSafeBuilder(claim, UUID.fromString(TARGET), null));
    }

    @Test
    void revokeRemovesBothCoexistingTrustDimensions() throws Exception
    {
        Path file = directory.resolve("revoke-both-dimensions.properties");
        RawNativeAccess access = rawAccess(42L, OWNER, NONE);
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, new FakeScheduler(),
                () -> 1_700_000_000_000L, 10);
        Claim claim = claim(42L, OWNER);

        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, null);
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.MANAGE, null);
        service.revoke(List.of(claim), TARGET);

        assertTrue(service.recordsForClaim(42L).isEmpty());
        assertEquals(NONE, access.state(42L, TARGET, TrustDimension.PERMISSION));
    }

    @Test
    void failedLaterClaimCaptureLeavesGrantPreparationAtomic() throws Exception
    {
        Path file = directory.resolve("grant-preparation-atomic.properties");
        FakeAccess access = new FakeAccess();
        access.snapshots.put(1L, new ClaimSnapshot(1L, OWNER, null, false));
        access.snapshots.put(2L, new ClaimSnapshot(2L, OWNER, null, false));
        access.failCaptureClaimId = 2L;
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(file, 10);
        CatCraftTrustService service = new CatCraftTrustService(store, access, new FakeScheduler(),
                () -> 1_700_000_000_000L, 10);

        service.start();

        assertThrows(RuntimeException.class, () -> service.grant(
                List.of(claim(1L, OWNER), claim(2L, OWNER)),
                TARGET, CatCraftTrustKind.BUILD, Duration.ofDays(1)));

        assertTrue(service.recordsForClaim(1L).isEmpty());
        assertEquals(1L, store.nextRevision());
        assertTrue(store.transitionValues().isEmpty());
        assertFalse(Files.exists(file));
    }

    @Test
    void duplicateClaimEntriesAreRejectedBeforeGrantMutation() throws Exception
    {
        Path file = directory.resolve("grant-duplicate.properties");
        FakeAccess access = access(42L, OWNER, NONE);
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, new FakeScheduler(),
                () -> 1_700_000_000_000L, 10);
        Claim claim = claim(42L, OWNER);

        service.start();

        assertThrows(IllegalArgumentException.class, () -> service.grant(
                List.of(claim, claim), TARGET, CatCraftTrustKind.BUILD, Duration.ofDays(1)));
        assertEquals(NONE, access.state(42L, TARGET, TrustDimension.PERMISSION));
        assertTrue(service.recordsForClaim(42L).isEmpty());
        assertFalse(Files.exists(file));
    }

    @Test
    void revisionOverflowIsRejectedWithoutGrantMutation() throws Exception
    {
        Path file = directory.resolve("grant-revision-overflow.properties");
        FakeAccess access = access(42L, OWNER,
                new NativeTrustState(ClaimPermission.Access, false, true));
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(file, 10);
        store.put(new TemporaryTrustRecord(41L, TARGET, CatCraftTrustKind.BUILD,
                TrustDimension.PERMISSION, NONE,
                new NativeTrustState(ClaimPermission.Access, false, true),
                0L, Long.MAX_VALUE - 1L, OWNER));
        store.save();
        CatCraftTrustService service = new CatCraftTrustService(store, access,
                new FakeScheduler(), () -> 1_700_000_000_000L, 10);

        service.start();

        assertThrows(IllegalStateException.class, () -> service.grant(
                List.of(claim(42L, OWNER)), TARGET, CatCraftTrustKind.BUILD,
                Duration.ofDays(1)));
        assertEquals(new NativeTrustState(ClaimPermission.Access, false, true),
                access.state(42L, TARGET, TrustDimension.PERMISSION));
        assertTrue(service.recordsForClaim(42L).isEmpty());
    }

    @Test
    void expiryWritesTransitionBeforeApplyThenThrowAndStartupRecovers() throws Exception
    {
        Path file = directory.resolve("expiry-write-ahead.properties");
        long initial = 1_700_000_000_000L;
        AtomicLong now = new AtomicLong(initial);
        FakeAccess access = access(42L, OWNER, NONE);
        FakeScheduler scheduler = new FakeScheduler();
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, scheduler, now::get, 10);
        Claim claim = claim(42L, OWNER);

        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, Duration.ofDays(1));
        access.failAfterApply = true;
        now.addAndGet(Duration.ofDays(1).toMillis());
        scheduler.runFuture();

        CatCraftTrustStateStore journal = new CatCraftTrustStateStore(file, 10);
        journal.load();
        assertEquals(1, journal.transitionValues().size());
        assertEquals(1, journal.values().size());

        access.failAfterApply = false;
        CatCraftTrustService restarted = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, new FakeScheduler(), now::get, 10);
        restarted.start();
        assertTrue(restarted.recordsForClaim(42L).isEmpty());
        assertFalse(service.isStarted());
        assertTrue(scheduler.future.isEmpty());
        assertTrue(scheduler.nextTick.isEmpty());
    }

    @Test
    void expiryKeepsWriteAheadJournalWhenFinalSaveFails() throws Exception
    {
        Path file = directory.resolve("expiry-final-save.properties");
        long initial = 1_700_000_000_000L;
        AtomicLong now = new AtomicLong(initial);
        FakeAccess access = access(42L, OWNER, NONE);
        FakeScheduler scheduler = new FakeScheduler();
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, scheduler, now::get, 10);
        Claim claim = claim(42L, OWNER);

        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, Duration.ofDays(1));
        access.sabotageSaveTemporary = file.resolveSibling(file.getFileName() + ".tmp");
        now.addAndGet(Duration.ofDays(1).toMillis());
        scheduler.runFuture();

        assertEquals(NONE, access.state(42L, TARGET, TrustDimension.PERMISSION));
        CatCraftTrustStateStore journal = new CatCraftTrustStateStore(file, 10);
        journal.load();
        assertEquals(1, journal.transitionValues().size());

        CatCraftTrustService restarted = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, new FakeScheduler(), now::get, 10);
        restarted.start();
        assertTrue(restarted.recordsForClaim(42L).isEmpty());
        assertFalse(service.isStarted());
        assertTrue(scheduler.future.isEmpty());
        assertTrue(scheduler.nextTick.isEmpty());
    }

    @Test
    void unavailableServiceRejectsExternalMutationAndPreservesRecoveryJournal()
            throws Exception
    {
        Path file = directory.resolve("external-transition.properties");
        FakeAccess access = access(42L, OWNER, NONE);
        FakeScheduler scheduler = new FakeScheduler();
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, scheduler,
                () -> 1_700_000_000_000L, 10);
        Claim claim = claim(42L, OWNER);
        service.start();
        access.failApply = true;
        assertThrows(RuntimeException.class, () -> service.grant(
                List.of(claim), TARGET, CatCraftTrustKind.BUILD, Duration.ofDays(1)));

        assertFalse(service.onExternalPermissionMutation(claim, TARGET, TrustDimension.PERMISSION));
        FakeAccess restartedAccess = access(42L, OWNER, NONE);
        CatCraftTrustService restarted = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), restartedAccess,
                new FakeScheduler(), () -> 1_700_000_000_000L, 10);

        restarted.start();

        assertTrue(restarted.recordsForClaim(42L).isEmpty());
    }

    @Test
    void multiClaimBatchFailsCapacityPreflightBeforeWritingTransitions()
            throws Exception
    {
        Path file = directory.resolve("capacity-preflight.properties");
        FakeAccess access = new FakeAccess();
        for (long claimId = 1L; claimId <= 2L; claimId++)
        {
            access.snapshots.put(claimId, new ClaimSnapshot(claimId, OWNER, null, false));
            access.states.put(access.key(claimId, TARGET, TrustDimension.PERMISSION), NONE);
        }
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 1), access, new FakeScheduler(),
                () -> 1_700_000_000_000L, 10);

        service.start();

        assertThrows(IllegalStateException.class, () -> service.grant(
                List.of(claim(1L, OWNER), claim(2L, OWNER)),
                TARGET, CatCraftTrustKind.BUILD, Duration.ofDays(1)));
        assertFalse(Files.exists(file));
        assertTrue(service.recordsForClaim(1L).isEmpty());
        assertTrue(service.recordsForClaim(2L).isEmpty());
    }

    @Test
    void revokeRejectsDuplicateClaimsBeforeAnyPreparation() throws Exception
    {
        Path file = directory.resolve("revoke-duplicate.properties");
        FakeAccess access = access(42L, OWNER, NONE);
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(file, 10);
        CatCraftTrustService service = new CatCraftTrustService(store, access, new FakeScheduler(),
                () -> 1_700_000_000_000L, 10);
        Claim claim = claim(42L, OWNER);
        service.start();

        assertThrows(IllegalArgumentException.class, () -> service.revoke(
                List.of(claim, claim), TARGET));

        assertTrue(store.transitionValues().isEmpty());
        assertTrue(service.recordsForClaim(42L).isEmpty());
        assertTrue(access.applied.isEmpty());
        assertFalse(Files.exists(file));
    }

    @Test
    void laterRevokeCaptureFailureLeavesPreparationAtomic() throws Exception
    {
        Path file = directory.resolve("revoke-preparation-atomic.properties");
        FakeAccess access = new FakeAccess();
        access.snapshots.put(1L, new ClaimSnapshot(1L, OWNER, null, false));
        access.snapshots.put(2L, new ClaimSnapshot(2L, OWNER, null, false));
        access.failCaptureClaimId = 2L;
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(file, 10);
        CatCraftTrustService service = new CatCraftTrustService(store, access, new FakeScheduler(),
                () -> 1_700_000_000_000L, 10);
        service.start();

        assertThrows(RuntimeException.class, () -> service.revoke(
                List.of(claim(1L, OWNER), claim(2L, OWNER)), TARGET));

        assertTrue(store.transitionValues().isEmpty());
        assertTrue(service.recordsForClaim(1L).isEmpty());
        assertTrue(service.recordsForClaim(2L).isEmpty());
        assertTrue(access.applied.isEmpty());
        assertFalse(Files.exists(file));
    }

    @Test
    void revokeSkipsNoOpDimensionAtRecordCapacity() throws Exception
    {
        Path file = directory.resolve("revoke-no-op-capacity.properties");
        FakeAccess access = access(42L, OWNER, new NativeTrustState(ClaimPermission.Access, false, true));
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(file, 1);
        CatCraftTrustService service = new CatCraftTrustService(store, access, new FakeScheduler(),
                () -> 1_700_000_000_000L, 10);
        Claim claim = claim(42L, OWNER);
        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, null);

        service.revoke(List.of(claim), TARGET);

        assertTrue(service.recordsForClaim(42L).isEmpty());
        assertEquals(NONE, access.state(42L, TARGET, TrustDimension.PERMISSION));
        assertTrue(store.transitionValues().isEmpty());
    }

    @Test
    void grantSaveFailureRollsBackInMemoryTransitionBeforeNativeApply() throws Exception
    {
        Path file = directory.resolve("grant-pre-wal-failure.properties");
        FakeAccess access = access(42L, OWNER, NONE);
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(file, 10);
        CatCraftTrustService service = new CatCraftTrustService(store, access, new FakeScheduler(),
                () -> 1_700_000_000_000L, 10);
        Claim claim = claim(42L, OWNER);
        service.start();
        Files.createDirectory(file.resolveSibling(file.getFileName() + ".tmp"));

        assertThrows(Exception.class, () -> service.grant(
                List.of(claim), TARGET, CatCraftTrustKind.BUILD, Duration.ofDays(1)));

        assertTrue(store.transitionValues().isEmpty());
        assertTrue(service.recordsForClaim(42L).isEmpty());
        assertEquals(NONE, access.state(42L, TARGET, TrustDimension.PERMISSION));
    }

    @Test
    void revokeSaveFailureRollsBackInMemoryTransitionBeforeNativeApply() throws Exception
    {
        Path file = directory.resolve("revoke-pre-wal-failure.properties");
        FakeAccess access = access(42L, OWNER, NONE);
        CatCraftTrustStateStore store = new CatCraftTrustStateStore(file, 10);
        CatCraftTrustService service = new CatCraftTrustService(store, access, new FakeScheduler(),
                () -> 1_700_000_000_000L, 10);
        Claim claim = claim(42L, OWNER);
        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, Duration.ofDays(1));
        Files.createDirectory(file.resolveSibling(file.getFileName() + ".tmp"));

        assertThrows(Exception.class, () -> service.revoke(List.of(claim), TARGET));

        assertTrue(store.transitionValues().isEmpty());
        assertEquals(1, service.recordsForClaim(42L).size());
        assertEquals(new NativeTrustState(ClaimPermission.Access, false, true),
                access.state(42L, TARGET, TrustDimension.PERMISSION));
    }

    @Test
    void canonicalTargetContainingPipeSurvivesTransitionSaveLoadAndStartup()
            throws Exception
    {
        String pipeTarget = "[catcraft|builders]";
        Path file = directory.resolve("pipe-target.properties");
        FakeAccess access = access(42L, OWNER, NONE);
        FakeScheduler scheduler = new FakeScheduler();
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, scheduler,
                () -> 1_700_000_000_000L, 10);
        Claim claim = claim(42L, OWNER);
        service.start();

        service.grant(List.of(claim), pipeTarget, CatCraftTrustKind.BUILD, Duration.ofDays(1));
        CatCraftTrustService restarted = new CatCraftTrustService(
                new CatCraftTrustStateStore(file, 10), access, new FakeScheduler(),
                () -> 1_700_000_000_000L, 10);
        restarted.start();

        assertEquals(pipeTarget.toLowerCase(), restarted.recordsForClaim(42L).get(0).target());
    }

    @Test
    void claimTransferRestoresActiveTrustBeforeOwnerChanges() throws Exception
    {
        FakeAccess access = access(42L, OWNER, NONE);
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(directory.resolve("transfer.properties"), 10),
                access, new FakeScheduler(), () -> 1_700_000_000_000L, 10);
        Claim claim = claim(42L, OWNER);
        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, Duration.ofDays(1));

        service.prepareClaimTransfer(claim);

        assertEquals(NONE, access.state(42L, TARGET, TrustDimension.PERMISSION));
        assertTrue(service.recordsForClaim(42L).isEmpty());
    }

    @Test
    void claimTransferDoesNotOverwriteASeparateNewerTrustDecision() throws Exception
    {
        FakeAccess access = access(42L, OWNER, NONE);
        CatCraftTrustService service = new CatCraftTrustService(
                new CatCraftTrustStateStore(directory.resolve("transfer-stale.properties"), 10),
                access, new FakeScheduler(), () -> 1_700_000_000_000L, 10);
        Claim claim = claim(42L, OWNER);
        service.start();
        service.grant(List.of(claim), TARGET, CatCraftTrustKind.BUILD, Duration.ofDays(1));
        NativeTrustState newer = new NativeTrustState(ClaimPermission.Inventory, false, false);
        access.states.put(access.key(42L, TARGET, TrustDimension.PERMISSION), newer);

        service.prepareClaimTransfer(claim);

        assertEquals(newer, access.state(42L, TARGET, TrustDimension.PERMISSION));
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

    private static RawNativeAccess rawAccess(long claimId, UUID owner, NativeTrustState state)
    {
        RawNativeAccess access = new RawNativeAccess();
        access.snapshots.put(claimId, new ClaimSnapshot(claimId, owner, null, false));
        access.states.put(access.key(claimId, TARGET),
                new NativeTrustState(state.permission(), state.manager(), false));
        return access;
    }

    private static Claim claim(long claimId, UUID owner)
    {
        Claim claim = mock(Claim.class);
        claim.children = new ArrayList<>();
        when(claim.getID()).thenReturn(claimId);
        when(claim.getOwnerID()).thenReturn(owner);
        return claim;
    }

    private static final class FakeAccess implements ClaimTrustAccess
    {
        private final Map<Long, ClaimSnapshot> snapshots = new HashMap<>();
        private final Map<String, NativeTrustState> states = new HashMap<>();
        private final List<String> applied = new ArrayList<>();
        private boolean failApply;
        private boolean failSave;
        private boolean failAfterSave;
        private final Map<String, NativeTrustState> persistedStates = new HashMap<>();
        private boolean failResolve;
        private long failCaptureClaimId = Long.MIN_VALUE;
        private boolean failAfterApply;
        private Path sabotageSaveTemporary;

        @Override
        public ClaimSnapshot resolve(long claimId)
        {
            if (failResolve) throw new IllegalStateException("resolve failure");
            return snapshots.get(claimId);
        }

        @Override
        public NativeTrustState capture(long claimId, String target, TrustDimension dimension)
        {
            if (claimId == failCaptureClaimId) throw new IllegalStateException("capture failure");
            return states.getOrDefault(key(claimId, target, dimension), new NativeTrustState(null, false, false));
        }

        @Override
        public void apply(long claimId, String target, NativeTrustState desired, TrustDimension dimension)
        {
            if (failApply) throw new RuntimeException("native apply failure");
            NativeTrustState current = capture(claimId, target, dimension);
            NativeTrustState merged = dimension == TrustDimension.PERMISSION
                    ? new NativeTrustState(desired.permission(), current.manager(), desired.safeBuild())
                    : new NativeTrustState(current.permission(), desired.manager(), current.safeBuild());
            states.put(key(claimId, target, dimension), merged);
            applied.add(key(claimId, target, dimension));
            if (failAfterApply) throw new RuntimeException("native apply failure after mutation");
            if (sabotageSaveTemporary != null)
            {
                try
                {
                    Files.createDirectory(sabotageSaveTemporary);
                }
                catch (Exception failure)
                {
                    throw new RuntimeException("could not sabotage final save", failure);
                }
                sabotageSaveTemporary = null;
            }
        }

        @Override
        public void save(long claimId)
        {
            if (failSave) throw new IllegalStateException("native save failed");
            persistedStates.putAll(states);
            if (failAfterSave) throw new IllegalStateException("interrupted after native save");
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

    /** Represents the native GP claim state and never stores the sidecar marker. */
    private static final class RawNativeAccess implements ClaimTrustAccess
    {
        private final Map<Long, ClaimSnapshot> snapshots = new HashMap<>();
        private final Map<String, NativeTrustState> states = new HashMap<>();

        @Override
        public ClaimSnapshot resolve(long claimId)
        {
            return snapshots.get(claimId);
        }

        @Override
        public NativeTrustState capture(long claimId, String target, TrustDimension dimension)
        {
            NativeTrustState state = states.getOrDefault(key(claimId, target),
                    new NativeTrustState(null, false, false));
            return new NativeTrustState(state.permission(), state.manager(), false);
        }

        @Override
        public void apply(long claimId, String target, NativeTrustState desired,
                          TrustDimension dimension)
        {
            NativeTrustState current = capture(claimId, target, dimension);
            NativeTrustState merged = dimension == TrustDimension.PERMISSION
                    ? new NativeTrustState(desired.permission(), current.manager(), false)
                    : new NativeTrustState(current.permission(), desired.manager(), false);
            states.put(key(claimId, target), merged);
        }

        @Override
        public void save(long claimId)
        {
        }

        private String key(long claimId, String target)
        {
            return claimId + "|" + target.toLowerCase();
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
