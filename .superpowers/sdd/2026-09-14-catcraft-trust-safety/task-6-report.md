# Task 6 report — trust commands, trust list, and tab completion

## RED

Added the focused command and tab-completion tests before the production
integration. The first clean RED run was:

```text
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn -Dmaven.repo.local=/private/tmp/gptrust-main-m2 \
  -Dtest=TrustCommandIntegrationTest,TrustTabCompletionIntegrationTest test
```

The test sources compiled far enough to expose the missing production
`config_catCraftTrustMaximumDuration` field, with compilation errors at
`TrustCommandIntegrationTest.java:72` and
`TrustTabCompletionIntegrationTest.java:39`. This was the expected RED
failure before adding the command integration.

## GREEN

Focused suite after implementation:

```text
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Full Java 21 suite after implementation:

```text
Tests run: 182, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Both runs used `/private/tmp/gptrust-main-m2` and
`/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`.

## Files

- `src/main/java/me/ryanhamshire/GriefPrevention/GriefPrevention.java`
  - Added the default 30-day maximum duration field for Task 7.
  - Registered the five trust command completers and routed completion through
    the online-only support helper.
  - Added `/buildtrust` and optional duration parsing to all five grant
    commands.
  - Validated duration and target input before claim lookup, target resolution,
    event dispatch, or mutation.
  - Kept BuildTrust on the current manageable claim and retained all-owned-
    claims behavior for the other commands outside a claim.
  - Fires one `TrustChangedEvent`, uses its final claim collection, and makes
    one batched `CatCraftTrustService.grant` call when the service is active.
    The native event level for BuildTrust is Access and PermissionTrust remains
    Manage, preserving the independent manager dimension in the command layer.
  - Kept `/untrust` and `/untrust all` on their existing resolver and Claim
    invalidation hooks without a parallel service revoke call.
  - Added text trust-list sidecar output for Build Trust, temporary remaining
    time, and Forever labels while suppressing safe-build duplicates from the
    Access line.
- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftMessages.java`
  - Added prefixed invalid-target and service-unavailable messages.
- `src/main/resources/plugin.yml`
  - Registered `/buildtrust` with alias `bt` and updated all five grant usages
    to include `[time]`.
- `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/TrustCommandIntegrationTest.java`
  - Covers all command kinds, permanent and mixed-case temporary durations,
    BuildTrust claim scope and Manage denial, invalid input no-mutation,
    offline UUID/public/group targets, event cancellation/final claims,
    untrust hooks, and trust-list labels.
- `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/TrustTabCompletionIntegrationTest.java`
  - Covers visible online-player completion for all five commands including
    the `bt` label and maximum-filtered duration completion without offline
    lookups.

## Commit

```text
47e780fbc58ea800516f6b369a2f73d24564513b Integrate CatCraft trust commands
```

## Self-review

- `ClaimPermission` declaration order was not changed.
- No lifecycle, `DataStore`, `Claim`, container, or trust-core source was
  changed in this task.
- Tab completion only reads the direct online-player collection for the first
  argument; duration completion performs no player or claim lookup.
- The command support helper is stateless and all command-side state is local
  to the invocation; no Bukkit objects are retained in CatCraft state.
- The implementation uses `Locale.ROOT` through the existing support helper
  for case-insensitive command and duration handling.
- `git diff --check` passed and the working tree was clean after the commit.

## Concerns

- Task 7 still needs to load and clamp `config_catCraftTrustMaximumDuration`
  from its configured maximum-days key. The command layer currently supplies
  the required 30-day default.
- Live Paper command dispatch and in-game tab completion were not available in
  this focused source test run.
- The command layer passes PermissionTrust as the independent `MANAGE` kind and
  event level. Any remaining marker-precedence behavior belongs to the
  trust-core follow-up and is outside this scoped commit.

## Review fix round

The Task 6 review fixes were implemented in commit
`30787437cbfc93de8dfbd0651cfc682d56488992`.

### RED

The new focused regressions were run before the production edits with Java 21
and `/private/tmp/gptrust-main-m2`:

```text
Tests run: 17, Failures: 4, Errors: 0, Skipped: 0
```

The expected failures were the non-trust completion returning an empty list
instead of `null`, PermissionTrust exposing `Manage` instead of its legacy
null event level, BuildTrust error messages lacking the CatCraft prefix, and
trust grant success messages lacking the CatCraft prefix, kind, and duration.
The command registration and alias regressions passed against the existing
implementation and now protect those already-correct paths.

### GREEN

Focused command and tab-completion suite after the fixes:

```text
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Full Java 21 suite after the fixes:

```text
Tests run: 196, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Both runs used `/private/tmp/gptrust-main-m2` and
`/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`.

### Files changed

- `src/main/java/me/ryanhamshire/GriefPrevention/GriefPrevention.java`
  - Returns `null` for non-trust tab completion so Bukkit fallback completion
    remains available.
  - Preserves the legacy PermissionTrust event payload (`given=true` and a
    null `ClaimPermission`) while continuing to send `MANAGE` to the trust
    service.
  - Uses prefixed BuildTrust errors and a prefixed success message containing
    the trust kind, target, scope, and `Forever` or a normalized duration.
- `src/main/java/me/ryanhamshire/GriefPrevention/catcrafttrust/CatCraftMessages.java`
  - Added simple prefixed BuildTrust and grant-success messages.
- `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/TrustCommandIntegrationTest.java`
  - Covers all-claims behavior and one batched service grant for each legacy
    trust command, event payloads for every kind, player-facing messages, and
    runtime completer registration.
- `src/test/java/me/ryanhamshire/GriefPrevention/catcrafttrust/TrustTabCompletionIntegrationTest.java`
  - Covers Bukkit fallback (`null`) and canonical completion through trust
    aliases.

### Self-review

- No trust-core service/store source, lifecycle source, Claim source, or
  plugin configuration was modified.
- All-claims tests assert the exact kind and one service invocation per
  command; event tests assert the final claims, identifier, given flag, and
  native payload, including the PermissionTrust compatibility case.
- Success formatting derives from the already parsed `Duration`, so it does
  not perform a lookup or retain command state.
- The existing trust-list regression continues to cover temporary expiry
  labels, permanent `Forever`, and duplicate suppression for safe-build
  access entries.
- `git diff --check` passed before the code commit.

### Concerns

- Live Paper dispatch and Bukkit fallback completion were not available in
  this source test run; the fallback contract is covered through the plugin
  API test seam.
- Existing GriefPrevention deprecation warnings remain unchanged.
