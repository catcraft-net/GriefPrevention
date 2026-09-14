# Task 2 report: bounded persistent state and next-expiration service

## RED evidence

Tests were written before the Task 2 implementation. The focused command was run with Java 21 and the requested Maven cache:

```text
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn -Dmaven.repo.local=/private/tmp/gptrust-main-m2 \
  -Dtest=CatCraftTrustStateStoreTest,CatCraftTrustServiceTest test
```

The expected RED run exited with status 1 during test compilation because `CatCraftTrustStateStore`, `CatCraftTrustService`, and the package-private adapter types did not yet exist.

## GREEN evidence

The focused Task 2 suite passed after implementation:

```text
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The complete project suite also passed with Java 21 and `/private/tmp/gptrust-main-m2`:

```text
Tests run: 91, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Files changed

- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustStateStore.java`
- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustService.java`
- `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustStateStoreTest.java`
- `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustServiceTest.java`

The implementation provides bounded direct and per-claim indexes, revision-aware expiration ordering, versioned UTF-8/Base64 properties persistence with temporary-file replacement and backup recovery, startup state reconciliation, safe-build inheritance lookup, batched expiry restoration, and narrow package-private integration ports. External mutation callbacks remove in-memory records before best-effort persistence; failed rewrites are logged.

## Commit

`ee010a6f211c5f79a0ceb9f4659a600d5aa48f60` (`Add persistent CatCraft temporary trust service`)

## Self-review

- The staged and committed diff contains only the four Task 2 trust-core files.
- The store restores its expiration queue when loading persisted records and rejects over-bound or invalid records.
- Native writes are preceded by persisted command-batch metadata, and internal mutation depth suppresses callback invalidation during those writes.
- Startup removes records whose claim, owner, or native expected state no longer matches, including recorded-but-unapplied records.
- Expiration work is bounded per callback and deduplicates the continuation next-tick callback.
- Safe-build checks match UUID/public targets, evaluate permission nodes only with a player, and stop inheritance at restricted subdivisions without caching Bukkit objects.

## Concerns

- Paper runtime wiring and live server behavior are not covered by this unit suite; later integration work must provide the `ClaimTrustAccess` and `TrustTaskScheduler` adapters and validate them against the target Paper build.

## Fix-round review evidence

The review regressions were added before the production hardening pass. The first focused run exited with status 1 during test compilation because the new queue-bound assertion referenced the not-yet-added `expirationQueueSize()` regression probe. After the production changes, the focused suite passed:

```text
Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The final complete suite passed with Java 21 and `/private/tmp/gptrust-main-m2`:

```text
Tests run: 102, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The fix round adds a durable transition journal that retains preceding and intended trust until native application is resolved; preserves permanent Build markers; keeps one physical expiration entry per active key; performs stale safe-build cleanup in memory only while continuing candidate lookup; uses one cancellable callback handle; validates the primary before creating a backup; bounds raw file size, property keys, and values before record decoding; preserves fallback subdivision restrictions; and defers overdue startup restoration to the bounded expiration scheduler with one disk rewrite per batch.

Fix-round commit:

`92ed10d170552cb3fb5c888dd793097e99a74a07` (`Harden CatCraft trust journal transitions`)

## Fix-round 2 review evidence

Five regression tests were added before the final fix pass. The focused RED run reproduced four defects: permanent Build replacement restored the pre-marker state, external mutation left a pending transition, a two-claim batch exceeded capacity without failing, and a malformed transition key loaded without backup recovery. The source-size regression is enforced by the same oversized-file fixture with a pre-read `Files.size` guard and post-read race check.

The focused fix-round suite passed:

```text
Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The final full suite passed with Java 21 and `/private/tmp/gptrust-main-m2`:

```text
Tests run: 106, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Fix-round 2 commit:

`e798e5f351cc57254ba41360e3d5d7eb12c1773e` (`Fix CatCraft trust replacement and journal bounds`)

## Fix-round 3 review evidence

A regression test was added first for a canonical target containing `|` and covering transition save/load plus startup validation. The focused RED run failed as expected:

```text
Tests run: 29, Failures: 0, Errors: 1, Skipped: 0
ERROR CatCraftTrustServiceTest.canonicalTargetContainingPipeSurvivesTransitionSaveLoadAndStartup
java.lang.IllegalArgumentException: invalid transition key shape
```

The production fix keeps the first two separators as claim id and dimension boundaries and treats the full remaining suffix as canonical target text. Existing nonblank, exact canonical, 256-character, claim-id, dimension, and record-key equality checks remain in place.

The focused fix-round suite passed with Java 21 and `/private/tmp/gptrust-main-m2`:

```text
Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The final complete suite passed with Java 21 and `/private/tmp/gptrust-main-m2`:

```text
Tests run: 107, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Fix-round 3 commit:

`b587a02` (`Allow pipe-containing trust targets in journal keys`)

## Hardening round: raw native adapters and trust precedence

### RED evidence

The four hardening regressions were written before the production changes and
run against clean head `40e3a62` with Java 21 and the requested Maven cache:

```text
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn -Dmaven.repo.local=/private/tmp/gptrust-main-m2 \
  -Dtest=CatCraftTrustServiceTest test
Tests run: 30, Failures: 4, Errors: 0, Skipped: 0
BUILD FAILURE
```

The failures covered the raw adapter losing the permanent marker, startup
discarding a marker transition after native permission application, and
normal/temporary MANAGE grants retaining permission records. A fifth
regression then covered capacity when MANAGE replaces the only permission
record:

```text
Tests run: 1, Failures: 0, Errors: 1, Skipped: 0
ERROR ...manageReplacementUsesCapacityOfSupersededPermissionRecord
java.lang.IllegalStateException: CatCraft temporary trust record limit reached
BUILD FAILURE
```

### GREEN evidence

The focused service suite passed after the hardening implementation:

```text
Tests run: 31, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The complete Java 21 project suite passed with the same Maven cache:

```text
Tests run: 172, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Files changed

- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustService.java`
- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustStateStore.java`
- `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustServiceTest.java`

The service now overlays safe-build exclusively from the sidecar record while
adapters capture and apply raw native permission/manager state. Expiration
recreates a zero-expiry BUILD marker when a temporary record restores its
permanent safe-build baseline. Startup transition matching compares native
state separately from the sidecar marker and resolves marker-only ambiguity to
the less-privileged state. MANAGE grants remove permission records and pending
permission transitions before journaling manager changes, preserve the native
permission fallback, and preflight capacity after the superseded key is
removed. The state store exposes that replacement-aware capacity check.

### Commit

`66f057a61b676b9590ec886b39c26109e3230750` (`Harden trust markers and manage precedence`)

### Self-review

- Only the service, state store, and service test are modified; no existing GP
  integration files are changed.
- Permanent BUILD records remain expiry-zero markers through replacement,
  expiration, persistence, and restart, including adapters that always report
  `safeBuild=false`.
- Permission and manager operations preserve the other native dimension, and
  MANAGE metadata removal prevents a stale permission expiry from rewriting a
  newer manager decision.
- Startup keeps journal intent fail-closed when native state cannot prove a
  marker-only transition, while it can finalize a marker transition when raw
  native permission matches the intended state.
- The existing inactive guards and single cancellable scheduled callback path
  remain unchanged.

### Concerns

The real Paper adapter and live restart behavior remain outside this unit
scope. The adapter must keep `safeBuild` out of Claim persistence and mutate
only the requested native dimension, as exercised by the raw-native test
double.

## Corrected trust-dimension and expiry-journal round

### RED

Before the production changes, the focused regression run was:

```text
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn -Dmaven.repo.local=/private/tmp/gptrust-main-m2 \
  -Dtest=CatCraftTrustServiceTest,SafeBuildPermissionIntegrationTest test
Tests run: 48, Failures: 8, Errors: 0, Skipped: 0
BUILD FAILURE
```

The failures demonstrated the accepted correction: MANAGE evicted the safe-build
marker, a later capture failure consumed a revision and left a transition, and
duplicate batches were accepted. The new expiry journal test also showed that
an apply-then-throw boundary had no persisted transition. The overflow assertion
was corrected in the test before the production GREEN run.

### GREEN

The focused service and Claim integration suite passed after implementation:

```text
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn -Dmaven.repo.local=/private/tmp/gptrust-main-m2 \
  -Dtest=CatCraftTrustServiceTest,SafeBuildPermissionIntegrationTest test
Tests run: 51, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The complete Java 21 suite passed with the requested Maven cache:

```text
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn -Dmaven.repo.local=/private/tmp/gptrust-main-m2 test
Tests run: 189, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Files

- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustService.java`
- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustStateStore.java`
- `src/main/java/me/ryanhamshire/GriefPrevention/Claim.java`
- `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustServiceTest.java`
- `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/SafeBuildPermissionIntegrationTest.java`
- `docs/superpowers/specs/2026-09-14-catcraft-trust-safety-design.md`

### Implementation

MANAGE now uses the manager dimension independently. It no longer evicts a
permission-dimension Build marker; dimension-specific comparisons preserve the
marker when manager state changes, across expiry and startup. Claim.setPermission
for Manage invalidates only manager metadata, while dropPermission continues to
remove both dimensions.

Grant preparation captures every claim before reserving revisions or committing
transitions. The state store validates duplicate keys, capacity, reservation
staleness, revision ordering, and overflow before one atomic transition commit.
The expiration worker prepares a bounded batch, writes TrustTransition journal
state before any native restore, then applies native state and completes the
transition. Apply-then-throw and final-save-failure tests verify startup recovery.

### Commit

`d3fc844` (`Restore independent trust dimensions and expiry journaling`). The
commit contains only this correction round's scoped source, test, and design
changes; the ignored SDD report and ledger are updated in the shared checkout.

### Self-review

- Permanent Build markers and permanent MANAGE remain independent through grant,
  restart, external manager mutation, and `/untrust`.
- Temporary manager expiry restores only the manager dimension and preserves a
  permanent permission marker.
- Native adapters receive only raw permission/manager fields; the sidecar
  safe-build bit is matched and persisted by the service.
- Expiration queue selection excludes already prepared keys without retaining
  Bukkit objects or adding repeating scans; one scheduled callback remains in
  use.
- Pre-journal failure aborts in-memory transitions before native mutation;
  post-journal native failures leave the transition for startup reconciliation.

### Concerns

The test suite includes a filesystem-sabotage test for final-save failure and
logs the expected persistence error. Live Paper restart and the concrete runtime
adapter remain outside this unit scope. Maven may emit a cached offline metadata
warning for the Spigot repository, but compilation and all 189 tests completed
successfully from `/private/tmp/gptrust-main-m2`.

## Core hardening round on e95d6ab

### RED evidence

The new regression tests were run before adding their production APIs. The Java 21 focused compile failed as expected because the test suite referenced the not-yet-implemented boolean external hook, completion hook, and bounded due-batch selector:

```text
void type not allowed here
cannot find symbol: completeExternalPermissionMutation
cannot find symbol: dueExpiring
BUILD FAILURE
```

After the first implementation, fix-round regressions also failed before their final corrections:

```text
Tests run: 2, Failures: 2, Errors: 0
expected: <false> but was: <true>
```

Those failures covered native grant/revoke failure availability assertions. A separate external WAL-save regression failed with the same expected service-state mismatch before the policy was narrowed to expiry-only terminal failure.

### GREEN evidence

Focused Java 21 core and Claim integration tests passed with the requested Maven cache:

```text
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn -Dmaven.repo.local=/private/tmp/gptrust-main-m2 \
  -Dtest=CatCraftTrustServiceTest,CatCraftTrustStateStoreTest,SafeBuildPermissionIntegrationTest test
Tests run: 73, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The complete Java 21 project suite passed:

```text
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn -Dmaven.repo.local=/private/tmp/gptrust-main-m2 test
Tests run: 208, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Files changed

- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustService.java`
- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustStateStore.java`
- `src/main/java/me/ryanhamshire/GriefPrevention/Claim.java`
- `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustServiceTest.java`
- `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustStateStoreTest.java`
- `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/SafeBuildPermissionIntegrationTest.java`

The Claim integration now journals external permission, target-removal, and recursive clear mutations before native changes and completes the same transition afterward; a failed WAL preparation prevents the native mutation. The service keeps permission and manager dimensions independent, preserves permanent BuildTrust markers, and leaves a durable transition when the completion rewrite fails. Expiration selects one ordered bounded due batch, writes its journal before native restoration, disables the service and cancels its single scheduling handle on any unrecoverable expiry failure, and rejects new grants while unavailable. Grant and revoke preparation remains atomic before the WAL save, with duplicate claims rejected, no-op dimensions omitted, and replacement-aware capacity over active plus pending keys. State loading also enforces the union record bound after decoding.

### Commit

Production and test commit: `0e4069d0ed89f0dcb9880f3bff8d0be03c0bb003` (`Harden trust invalidation and expiry recovery`). Documentation is committed separately as required.

### Self-review

- External Claim callbacks use a before/after protocol, so Safe Build metadata is removed from active indexes before native state changes while the durable transition retains the preceding record for restart reconciliation.
- A failed external WAL save returns false to Claim and leaves the native permission untouched; a final-save failure leaves the durable transition and makes the service unavailable.
- Expiration has one due-batch queue traversal and one cancellable handle; prepare, journal, native, and final persistence failures do not reschedule a retry loop.
- Multi-claim revoke captures all dimensions before committing once, rejects duplicate claim IDs, skips unchanged dimensions, and rolls back in-memory transitions when the pre-WAL save fails.
- No Task 6 command files or Task 7 lifecycle files were changed.

### Concerns

The concrete Paper adapter, live native mutation ordering, and restart behavior on a deployed server remain outside this unit run. The sabotage regressions intentionally emit severe persistence log lines. Grant/revoke native apply failures retain their durable journal for recovery and leave the service available so a later external mutation can invalidate the pending transition; expiration failures use the stricter terminal unavailable policy.
