# CatCraft Trust Safety Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build one CatCraft GriefPrevention JAR that provides command-only safe BuildTrust, restart-safe temporary durations for every trust command, online-player tab completion, and read-only container inspection.

**Architecture:** Keep GriefPrevention's existing `ClaimPermission` enum and claim persistence authoritative. Represent BuildTrust as native Access permission plus an indexed CatCraft marker stored beside GriefPrevention data; a single service owns revisions, persistence, and the next-expiration task. Integrate the marker into native claim checks and command mutation paths, while a dedicated listener opens detached container snapshots and denies storage-extraction bypasses.

**Tech Stack:** Java 21, Maven, Spigot API 1.21, JUnit 5.12.1, Mockito 5.16.0, Bukkit scheduler and event API, GriefPrevention flat-file/database abstractions.

**Spec:** `docs/superpowers/specs/2026-09-14-catcraft-trust-safety-design.md`

## Global Constraints

- Build from CatCraft commit `d4eefbf3e3b09a245d9ecc37112b23e7c0380397` plus committed design and implementation changes.
- Keep `plugin.yml` name and main class as `GriefPrevention` and produce `GriefPrevention-18.0.0-CatCraft-TrustSafety.jar`.
- Do not change `ClaimPermission` declaration order or GriefPrevention's normal trust semantics.
- Do not add a GUI, anvil input, database dependency, second plugin, repeating trust scanner, world scan, chunk scan, claim scan, or online-player polling task.
- Do not retain `Player`, `Claim`, `World`, `Location`, `Inventory`, block, entity, or chunk objects in long-lived CatCraft state.
- Do not perform disk or database access in permission, block, inventory, or tab-completion events.
- Use the `&b[CatCraft]` prefix and simple player-facing language for all new messages.
- No duration means permanent; accept case-insensitive `m`, `h`, `d`, and `w`, with a default maximum of 30 days.
- Apply test-driven development: add a failing focused test, run it, implement the smallest correct change, rerun the focused test, then commit.
- Preserve the existing long-offline `/untrust` resolution and all existing player, UUID, `public`, and permission-group trust targets.

---

## File map and ownership

The three delegated workstreams must not edit the same production files.

**Trust core worker owns:**

- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustKind.java`
- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/TrustDimension.java`
- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/NativeTrustState.java`
- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/TemporaryTrustRecord.java`
- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/DurationParser.java`
- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/InvalidDurationReason.java`
- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/InvalidDurationException.java`
- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustStateStore.java`
- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustService.java`
- Corresponding core tests under `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/`

**Command worker owns:**

- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftMessages.java`
- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/TrustCommandRequest.java`
- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustCommandSupport.java`
- Corresponding command tests under `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/`

**Container-safety worker owns:**

- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/SafeBuildTrustProvider.java`
- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/StorageProtectionPolicy.java`
- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/ReadOnlyContainerListener.java`
- Corresponding safety tests under `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/`

**Primary integrator owns:**

- `src/main/java/me/ryanhamshire/GriefPrevention/GriefPrevention.java`
- `src/main/java/me/ryanhamshire/GriefPrevention/Claim.java`
- `src/main/java/me/ryanhamshire/GriefPrevention/DataStore.java`
- `src/main/resources/plugin.yml`
- Integration and packaging tests.

---

### Task 1: Trust value types and overflow-safe duration parser

**Files:**

- Create the seven trust value files listed under the trust core ownership block.
- Test: `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/DurationParserTest.java`
- Test: `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/TemporaryTrustRecordTest.java`

**Interfaces:**

- Produces: `DurationParser.parse(String, Duration): Duration` throwing `InvalidDurationException`.
- Produces: immutable record types used by every later task.

- [ ] **Step 1: Write duration parser tests**

Cover `30m`, `1H`, `1d`, `2W`, `forever`, zero, negative input, decimals, missing units, trailing data, values over 30 days, and multiplication overflow. Use fixed `Duration.ofDays(30)` limits and assert exact exception reasons `INVALID_FORMAT`, `MUST_BE_POSITIVE`, `TOO_LONG`, and `OVERFLOW`.

```java
assertEquals(Duration.ofDays(1), DurationParser.parse("1D", Duration.ofDays(30)));
assertEquals(Duration.ZERO, DurationParser.parse("forever", Duration.ofDays(30)));
assertEquals(InvalidDurationReason.OVERFLOW,
        assertThrows(InvalidDurationException.class,
                () -> DurationParser.parse("999999999999999999w", Duration.ofDays(30))).reason());
```

- [ ] **Step 2: Run the focused tests and confirm failure**

Run:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -Dmaven.repo.local=/private/tmp/catcraft-gp-m2 -Dtest=DurationParserTest,TemporaryTrustRecordTest test
```

Expected: compilation fails because the new types do not exist.

- [ ] **Step 3: Implement immutable value types**

Use these exact declarations:

```java
public enum CatCraftTrustKind { BUILD, ACCESS, CONTAINER, FULL, MANAGE }
public enum TrustDimension { PERMISSION, MANAGER }
public record NativeTrustState(@Nullable ClaimPermission permission, boolean manager, boolean safeBuild) {}
public record TemporaryTrustRecord(
        long claimId,
        String target,
        CatCraftTrustKind appliedKind,
        TrustDimension dimension,
        NativeTrustState previousState,
        NativeTrustState expectedState,
        long expiresAtMillis,
        long revision,
        @Nullable UUID ownerIdAtGrant) {}
```

Validate non-negative claim IDs, nonblank bounded targets, positive revisions, non-negative expiration timestamps, and dimension/state consistency in the record constructor. Add `key()` returning `claimId + "|" + dimension + "|" + target.toLowerCase(Locale.ROOT)`.

- [ ] **Step 4: Implement checked duration parsing**

Parse the numeric component with `Long.parseLong`, normalize only the final unit character, map the unit to an exact millisecond multiplier, and use `Math.multiplyExact`. Treat `forever` as `Duration.ZERO`. Reject all values whose converted duration is zero or greater than the configured maximum.

```java
long millis = Math.multiplyExact(amount, multiplier);
Duration result = Duration.ofMillis(millis);
if (result.compareTo(maximum) > 0) throw new InvalidDurationException(TOO_LONG);
```

- [ ] **Step 5: Run focused tests and commit**

Expected: all Task 1 tests pass.

```bash
git add src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust
git commit -m "Add CatCraft trust value types and duration parser"
```

---

### Task 2: Bounded persistent state and next-expiration service

**Files:**

- Create: `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustStateStore.java`
- Create: `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustService.java`
- Test: `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustStateStoreTest.java`
- Test: `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustServiceTest.java`

**Interfaces:**

- Consumes: Task 1 record and enum types.
- Produces: indexed safe-build lookup, atomic record persistence, revision checks, startup reconciliation, and a single next-expiration callback.
- Produces these service methods for integration:

```java
void start() throws IOException;
void stop() throws IOException;
boolean isSafeBuilder(Claim claim, UUID playerId, @Nullable Player player);
void grant(Collection<Claim> claims, String target, CatCraftTrustKind kind, @Nullable Duration duration) throws IOException;
void revoke(Collection<Claim> claims, String target) throws IOException;
void clearClaims(Collection<Claim> claims) throws IOException;
void onClaimDeleted(long claimId);
void onClaimOwnerChanging(Claim claim);
void onExternalPermissionMutation(Claim claim, String target, TrustDimension dimension);
List<TemporaryTrustRecord> recordsForClaim(long claimId);
boolean isInternalMutation();
```

- [ ] **Step 1: Write state-store failure and index tests**

Verify primary-file round trips, case-insensitive keys, maximum record count, per-claim lookup, safe-build lookup by UUID/public/permission node, queue ordering, stale revision removal, corrupt-primary backup recovery, rejection when both files are corrupt, and atomic replacement leaving no orphan temporary file.

```java
store.put(firstRevision);
store.put(secondRevision);
assertEquals(secondRevision, store.get(secondRevision.key()).orElseThrow());
assertEquals(List.of(secondRevision), store.forClaim(42L));
```

- [ ] **Step 2: Run the store test and confirm failure**

Run the focused Maven test and confirm the missing class failure.

- [ ] **Step 3: Implement the bounded store**

Use a `HashMap<String, TemporaryTrustRecord>` for direct record lookup, a `HashMap<Long, Map<String, TemporaryTrustRecord>>` for claim lookup, and a `PriorityQueue<ExpiryKey>` containing only expiring revisions. Reject new entries after `maximumRecords`; replace existing keys without growing the count. Limit target strings to 256 characters and loaded property count to the configured bound.

Encode records as a versioned UTF-8 properties file with Base64 URL encoding for target text. Write to `<name>.tmp`, flush and close, then move with `ATOMIC_MOVE` and `REPLACE_EXISTING`, falling back to `REPLACE_EXISTING` only when atomic moves are unsupported. Copy the last valid primary to `<name>.bak` before replacement.

- [ ] **Step 4: Write service state-machine tests**

Use Mockito claims and a fake clock/scheduler adapter. Verify permanent to temporary to expiry restoration, temporary superseded by permanent, temporary superseded by another temporary, revoke invalidation, owner mismatch, missing claim, recorded-but-unapplied startup state, expiration batch limits, and exactly one scheduled future callback.

```java
service.grant(List.of(claim), target, CatCraftTrustKind.BUILD, Duration.ofDays(1));
service.grant(List.of(claim), target, CatCraftTrustKind.CONTAINER, null);
clock.advance(Duration.ofDays(1));
scheduler.runDue();
verify(nativeAccess).apply(claimId, target,
        new NativeTrustState(ClaimPermission.Inventory, false, false), TrustDimension.PERMISSION);
```

- [ ] **Step 5: Implement the service around narrow adapters**

Define package-private ports so core tests do not initialize a Bukkit server:

```java
record ClaimSnapshot(long claimId, @Nullable UUID ownerId, @Nullable Long parentId, boolean restricted) {}

interface ClaimTrustAccess {
    @Nullable ClaimSnapshot resolve(long claimId);
    NativeTrustState capture(long claimId, String target, TrustDimension dimension);
    void apply(long claimId, String target, NativeTrustState state, TrustDimension dimension);
    void save(long claimId);
}

interface TrustTaskScheduler {
    ScheduledHandle schedule(long delayTicks, Runnable task);
    void nextTick(Runnable task);
}

interface ScheduledHandle { void cancel(); }
```

Persist a complete command batch before applying any native state, then save each affected claim through GriefPrevention. On startup, discard a record if current state matches neither its expected nor previous state. Restore only when claim, owner, newest revision, and expected state all still match. Limit one due callback to `maximumExpirationsPerTick` and continue remaining overdue records next tick. External mutation callbacks remove records from memory before attempting a best-effort state-file rewrite; if that rewrite fails, the persisted stale record is harmless because startup rejects it when native state no longer matches its expected state.

- [ ] **Step 6: Add safe-build inheritance lookup**

Check the current claim index first. Match UUID and `public` directly; test bracketed permission nodes only when a `Player` is available. If no local match exists and the claim is an unrestricted subdivision, walk to its parent. Stop at a restricted subdivision. Do not cache `Player` or claim references.

- [ ] **Step 7: Run Task 1 and Task 2 tests and commit**

```bash
git add src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust
git commit -m "Add persistent CatCraft temporary trust service"
```

---

### Task 3: Command parsing, messaging, and online-player completion

**Files:**

- Create: `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftMessages.java`
- Create: `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/TrustCommandRequest.java`
- Create: `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustCommandSupport.java`
- Test: `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustCommandSupportTest.java`

**Interfaces:**

- Consumes: `CatCraftTrustKind` and `DurationParser` from Task 1.
- Produces: request parsing and completion helpers used by `GriefPrevention.onCommand` and command registration.

```java
public record TrustCommandRequest(String target, CatCraftTrustKind kind, @Nullable Duration duration) {}
Optional<TrustCommandRequest> parse(String commandName, String[] args, Duration maximum, Consumer<String> error);
List<String> completePlayers(CommandSender sender, String prefix, Collection<? extends Player> onlinePlayers);
List<String> completeDurations(String prefix, Duration maximum);
```

- [ ] **Step 1: Write command-support tests**

Verify each command maps to the correct trust kind; one argument produces permanent trust; two arguments parse case-insensitive duration; three arguments fail; invalid duration leaves no request; completion filters prefixes case-insensitively, excludes invisible players, sorts names, and never invokes an offline lookup. Verify duration suggestions do not exceed a shortened configured maximum.

```java
assertEquals(CatCraftTrustKind.BUILD,
        support.parse("buildtrust", new String[]{"Steve", "1D"}, max, errors::add)
                .orElseThrow().kind());
verify(sender).canSee(visiblePlayer);
verifyNoInteractions(offlinePlayerResolver);
```

- [ ] **Step 2: Run the focused test and confirm failure**

Run `CatCraftTrustCommandSupportTest` alone and confirm missing production types.

- [ ] **Step 3: Implement command request parsing**

Map `buildtrust` to BUILD, `accesstrust` to ACCESS, `containertrust` to CONTAINER, `trust` to FULL, and `permissiontrust`/`managetrust` to MANAGE. Preserve the target exactly for GriefPrevention's resolver. Return an empty optional after sending exactly one prefixed usage or duration error.

Use the simple usage form:

```text
&b[CatCraft] &eUse /<command> <player> [time].
```

- [ ] **Step 4: Implement lag-free completion**

Iterate the supplied online-player collection once, require `sender instanceof Player` visibility checks where applicable, compare lowercase prefixes with `Locale.ROOT`, collect only names, sort once, and return an immutable list. Duration suggestions are generated from the constant list `1h`, `1d`, `1w`, `4w`, and `forever`, then filtered by prefix and configured maximum.

- [ ] **Step 5: Run focused tests and commit**

```bash
git add src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftMessages.java src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/TrustCommandRequest.java src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustCommandSupport.java src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustCommandSupportTest.java
git commit -m "Add CatCraft trust command support"
```

---

### Task 4: Storage policy and detached read-only container listener

**Files:**

- Create: `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/SafeBuildTrustProvider.java`
- Create: `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/StorageProtectionPolicy.java`
- Create: `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/ReadOnlyContainerListener.java`
- Test: `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/StorageProtectionPolicyTest.java`
- Test: `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/ReadOnlyContainerListenerTest.java`

**Interfaces:**

- Produces: listener that depends only on this provider, allowing the primary integrator to bind `CatCraftTrustService` without a circular dependency.

```java
public interface SafeBuildTrustProvider {
    boolean isSafeBuilder(Claim claim, UUID playerId, @Nullable Player player);
}
```

- [ ] **Step 1: Write material and block-state policy tests**

Cover chests, trapped chests, barrels, all furnace variants, shulker boxes, hoppers, droppers, dispensers, brewing stands, crafters, decorated pots, chiseled bookshelves, chest/hopper minecarts, ordinary blocks, empty versus non-empty inventories, shulker item metadata/components, and unknown inventory holders. Unknown or unreadable storage must return a deny decision. Include inventory-open events initiated by another plugin rather than a normal player interaction.

- [ ] **Step 2: Run policy tests and confirm failure**

Run only `StorageProtectionPolicyTest` and confirm missing production classes.

- [ ] **Step 3: Implement fail-closed storage classification**

Return explicit decisions rather than booleans:

```java
enum StorageDecision { ORDINARY, EMPTY_BREAKABLE, PROTECTED_NONEMPTY, AUTOMATION_DENIED, AMBIGUOUS_DENIED }
StorageDecision classifyBreak(Block block);
boolean denyPlacement(Material material);
boolean supportsDetachedView(BlockState state);
```

Deny placement/use of hoppers and equivalent extraction-capable automation for safe builders. Detect `InventoryHolder` contents without retaining the holder. Add material-specific checks for decorated pots, chiseled bookshelves, and shulker items. Exclude `HumanEntity` and `Merchant` from preview support.

- [ ] **Step 4: Write event-level mutation tests**

Create tests for normal click, shift-click, number key, offhand swap, double click, drag, drop, creative click, cursor item, close, disconnect, stale claim owner, removed trust, changed block, and session/top-inventory mismatch. Assert the live inventory and player inventory remain byte-for-byte unchanged after denied events.

- [ ] **Step 5: Implement detached snapshot sessions**

On an eligible player interaction or cancellable `InventoryOpenEvent`, cancel the original open, clone every `ItemStack` into a newly created Bukkit inventory, and open the snapshot on the next tick only after revalidation. Track sessions in `Map<UUID, ViewSession>` where `ViewSession` contains UUIDs, claim ID, block coordinates, owner UUID, and an inventory identity token only.

Cancel every `InventoryClickEvent` and `InventoryDragEvent` whose top inventory and token match the session. Do not rewrite cursor or slots after cancellation. Remove sessions on close, quit, kick, trust invalidation, claim invalidation, and plugin disable. Rate-limit denial messages with `Map<UUID, Long>` and remove entries alongside sessions.

- [ ] **Step 6: Implement break/place/entity bypass protection**

At event priority before normal GriefPrevention allowance, deny safe builders breaking protected/ambiguous storage, placing automation materials, manipulating protected automation, or damaging inventory-bearing entities. Re-resolve the claim at the exact event location each time. Do not inspect unrelated chunks or entities.

- [ ] **Step 7: Run safety tests and commit**

```bash
git add src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/SafeBuildTrustProvider.java src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/StorageProtectionPolicy.java src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/ReadOnlyContainerListener.java src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/StorageProtectionPolicyTest.java src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/ReadOnlyContainerListenerTest.java
git commit -m "Add read-only BuildTrust container protection"
```

---

### Task 5: Integrate safe-build permission and mutation invalidation into Claim

**Files:**

- Modify: `src/main/java/me/ryanhamshire/GriefPrevention/Claim.java:360-515`
- Modify: `src/main/java/me/ryanhamshire/GriefPrevention/Claim.java:596-632`
- Test: `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/SafeBuildPermissionIntegrationTest.java`

**Interfaces:**

- Consumes: `CatCraftTrustService.isSafeBuilder` and mutation invalidation methods.
- Produces: native `Claim.checkPermission` behaviour used by GriefPrevention and other plugins.

- [ ] **Step 1: Write permission matrix tests**

For UUID, online player, public marker, permission-node marker, unrestricted child, and restricted child, assert that safe builders receive Build and Access while Inventory, Manage, and Edit remain denied. Also assert normal Build and Container trust keep their original hierarchy.

- [ ] **Step 2: Run integration test and confirm the new cases fail**

Existing permission tests must continue passing while safe-build cases fail before integration.

- [ ] **Step 3: Add the safe-build overlay to default permission evaluation**

After owner/admin bypass handling and before explicit normal permission lookup, allow only Build or Access when the active service reports a safe builder:

```java
if ((permission == ClaimPermission.Build || permission == ClaimPermission.Access)
        && GriefPrevention.instance.catCraftTrustService != null
        && GriefPrevention.instance.catCraftTrustService.isSafeBuilder(this, uuid, player)) {
    return null;
}
```

Do not add a special Inventory denial; allow normal stronger native permissions to continue through the existing logic.

- [ ] **Step 4: Hook live claim mutations**

Before external `setPermission`, `dropPermission`, and `clearPermissions` mutations, notify the service when `inDataStore`, claim ID, plugin instance, and started service are valid. Skip notification during guarded internal restoration. Propagate cleanup to subdivisions using their actual IDs.

- [ ] **Step 5: Run permission and existing claim tests and commit**

```bash
git add src/main/java/me/ryanhamshire/GriefPrevention/Claim.java src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/SafeBuildPermissionIntegrationTest.java
git commit -m "Integrate CatCraft BuildTrust into claim permissions"
```

---

### Task 6: Integrate trust commands, text trust list, and tab completion

**Files:**

- Modify: `src/main/java/me/ryanhamshire/GriefPrevention/GriefPrevention.java:975-1570`
- Modify: `src/main/java/me/ryanhamshire/GriefPrevention/GriefPrevention.java:2398-2495`
- Modify: `src/main/resources/plugin.yml:19-49`
- Test: `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/TrustCommandIntegrationTest.java`
- Test: `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/TrustTabCompletionIntegrationTest.java`

**Interfaces:**

- Consumes: command support and trust service from Tasks 2 and 3.
- Produces: complete player-facing command workflow.

- [ ] **Step 1: Write command integration tests**

Verify permanent and temporary forms for all five grant commands, BuildTrust requiring the current manageable claim, existing all-claims behaviour for legacy commands outside claims, invalid durations causing no mutation, offline UUID resolution, groups/public preservation, `/untrust` invalidation, and text `/trustlist` expiry labels.

- [ ] **Step 2: Register `/buildtrust` and extend existing command arity**

Add this `plugin.yml` entry while leaving plugin name unchanged:

```yaml
buildtrust:
  description: Lets a player build and look inside containers without moving items.
  usage: /<command> <player> [time]
  aliases: bt
  permission: griefprevention.claims
```

Change existing trust usage text to the same `[time]` form.

- [ ] **Step 3: Route grant commands through one helper**

Replace the fixed one-argument checks with command-support parsing. Resolve and authorize targets using existing GriefPrevention logic, fire `TrustChangedEvent`, and call `CatCraftTrustService.grant` once per final claim. Batch metadata persistence and claim saves across all-claim operations. Keep `/buildtrust` limited to the current claim.

- [ ] **Step 4: Integrate untrust and trust-list output**

Let `Claim.dropPermission` and `clearPermissions` invalidate records through Task 5 hooks. Preserve `OfflineUntrustResolver`. Extend `/trustlist` with safe-build entries and format temporary remaining durations without converting group targets into players.

- [ ] **Step 5: Register tab completers**

Assign a `TabCompleter` to `buildtrust`, `trust`, `accesstrust`, `containertrust`, and `permissiontrust`. First-argument completion receives `getServer().getOnlinePlayers()` directly. Second-argument completion receives only the configured maximum duration.

- [ ] **Step 6: Run command tests and commit**

```bash
git add src/main/java/me/ryanhamshire/GriefPrevention/GriefPrevention.java src/main/resources/plugin.yml src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/TrustCommandIntegrationTest.java src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/TrustTabCompletionIntegrationTest.java
git commit -m "Integrate CatCraft trust commands"
```

---

### Task 7: Lifecycle, claim cleanup, configuration, and GPTrust migration

**Files:**

- Modify: `src/main/java/me/ryanhamshire/GriefPrevention/GriefPrevention.java:273-395`
- Modify: `src/main/java/me/ryanhamshire/GriefPrevention/GriefPrevention.java:520-780`
- Modify: `src/main/java/me/ryanhamshire/GriefPrevention/GriefPrevention.java:2717-2738`
- Modify: `src/main/java/me/ryanhamshire/GriefPrevention/DataStore.java:636-690`
- Test: `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustLifecycleTest.java`
- Test: `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/GPTrustMigrationTest.java`

**Interfaces:**

- Consumes: completed trust service and read-only listener.
- Produces: safe startup, disable, deletion, transfer, configuration, and one-time migration behaviour.

- [ ] **Step 1: Write lifecycle and migration tests**

Verify service startup occurs after claims load and before events are served; startup failure leaves safe-build overlays unavailable and performs no automated restoration; listener registration occurs once; disable cancels the task and clears view sessions; delete/transfer removes records; compatible GPTrust records import once; malformed records do not mutate claims; successful import renames the source only after the integrated state is durable.

- [ ] **Step 2: Add bounded configuration**

Read and write these exact keys with clamped values:

```text
GriefPrevention.CatCraftTrust.Enabled
GriefPrevention.CatCraftTrust.MaximumTemporaryDurationDays
GriefPrevention.CatCraftTrust.MaximumTemporaryRecords
GriefPrevention.CatCraftTrust.MaximumExpirationsPerTick
GriefPrevention.CatCraftTrust.DenialMessageCooldownSeconds
```

Defaults are `true`, `30`, `10000`, `100`, and `3`. Maximum records is clamped to 100 through 100000; expirations per tick to 1 through 1000; cooldown to 1 through 30 seconds.

- [ ] **Step 3: Wire startup and disable**

Construct the service after `DataStore` finishes loading. Import old data, reconcile, and then register `ReadOnlyContainerListener`. If state cannot be validated from primary or backup, log a severe error, leave safe-build overlays unavailable, perform no automated restoration, and report that stored native permissions may include unresolved temporary grants. Keep normal GriefPrevention enabled so existing claims remain protected; do not describe simultaneous primary-and-backup corruption as fully recoverable.

On plugin disable, cancel the scheduled task, clear view sessions and denial timestamps, flush dirty state, then close the normal data store. Log active temporary non-BuildTrust records for rollback awareness.

- [ ] **Step 4: Hook claim deletion and transfer**

Call `onClaimDeleted` for each subdivision and parent before storage deletion. Call `onClaimOwnerChanging` before `changeClaimOwner` commits the new owner. Cleanup removes records from active memory first. A persistence failure is logged but does not block normal claim deletion or transfer; on restart, missing claims and owner mismatches make any stale disk record fail closed and get discarded.

- [ ] **Step 5: Implement the one-time importer**

Read only `plugins/GPTrust/temporary-trust.properties`, enforce the same record bound and Base64 validation as the new store, translate compatible v2 records to new records, compare expected native state, persist the integrated state, then atomically rename the old file to `temporary-trust.properties.migrated`. Never load GPTrust classes.

- [ ] **Step 6: Run lifecycle tests and commit**

```bash
git add src/main/java/me/ryanhamshire/GriefPrevention/GriefPrevention.java src/main/java/me/ryanhamshire/GriefPrevention/DataStore.java src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust
git commit -m "Wire CatCraft trust lifecycle and migration"
```

---

### Task 8: Security regression suite and full verification

**Files:**

- Create: `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustSecurityRegressionTest.java`
- Create: `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftTrustPerformanceContractTest.java`
- Modify production files only when a new regression test proves a defect.

**Interfaces:**

- Consumes the complete integrated feature.
- Produces the verified release artifacts and evidence report.

- [ ] **Step 1: Add cross-component regression tests**

Cover every trust state sequence in the specification, every inventory mutation route, full storage blocks, special storage, hopper and minecart bypasses, movement between claims, external trust changes, claim deletion/transfer, restart reconciliation, corrupt persistence, target bounds, and large due-expiration batches.

- [ ] **Step 2: Add resource-contract tests**

Reflect over `TemporaryTrustRecord`, state-store entries, view sessions, and scheduled service fields to ensure no forbidden Bukkit object type is retained. Use a fake scheduler to prove no repeating task is registered and no more than one future expiration task remains active. Use mocked online/offline lookup providers to prove tab completion performs zero offline calls.

- [ ] **Step 3: Run the focused CatCraft suite**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -Dmaven.repo.local=/private/tmp/catcraft-gp-m2 test
```

Expected: all CatCraft trust tests pass with zero failures, errors, or skips.

- [ ] **Step 4: Run the full clean build**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -Dmaven.repo.local=/private/tmp/catcraft-gp-m2 clean verify
```

Expected: all original 67 tests plus the new CatCraft tests pass.

- [ ] **Step 5: Inspect packaged metadata and classes**

Verify `plugin.yml` keeps `name: GriefPrevention`, includes `/buildtrust`, contains no GPTrust main class, and reports the CatCraft git description. List scheduled task creation sites and confirm CatCraft uses no repeating scheduler method.

- [ ] **Step 6: Produce named JAR and source ZIP**

Copy the verified Maven JAR to `../../outputs/GriefPrevention-18.0.0-CatCraft-TrustSafety.jar`. Create `../../outputs/GriefPrevention-18.0.0-CatCraft-TrustSafety-source.zip` with `git archive` from the verified commit so `.git`, `target`, IDE files, and temporary state are excluded.

- [ ] **Step 7: Record checksums and source provenance**

Generate SHA-256 checksums for the input production JAR, final JAR, and source ZIP. Compare every `.class` source path expected from the CatCraft package with JAR entries and record the exact source commit.

- [ ] **Step 8: Perform final code review and commit any test-proven fixes**

Review native trust compatibility, revision checks, startup failure paths, storage classification, session cleanup, listener priority, task cancellation, bounds, and synchronous I/O call sites. Any change must start with a failing regression test and end with the focused and full suites passing.

- [ ] **Step 9: Commit the verification harness**

```bash
git add src/test
git commit -m "Add CatCraft trust security verification"
```

Document separately that automated Java tests and package inspection do not replace a live Paper/Geyser deployment test, TPS profile, or heap-retention observation.
