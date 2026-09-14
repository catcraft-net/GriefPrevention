# CatCraft Trust Safety Design

## Purpose

CatCraft will ship one customized GriefPrevention JAR that adds a safe BuildTrust permission and optional temporary durations to GriefPrevention's existing trust commands. The player interface is command-only. The existing claim system, claim IDs, ownership rules, subdivisions, permanent permissions, permission targets, events, and public API remain authoritative.

The final server must not load the former GPTrust add-on or the binary TrustHooks patch. The plugin name remains `GriefPrevention`, and the produced artifact will be named `GriefPrevention-18.0.0-CatCraft-TrustSafety.jar`.

## Source baseline

Development starts from `catcraft-net/GriefPrevention` commit `d4eefbf3e3b09a245d9ecc37112b23e7c0380397`. This commit includes CatCraft's long-offline `/untrust` resolver. A clean Java 21 baseline build runs 67 tests successfully.

The supplied production JAR reports `18.0.0-3-g5b7d6c4-CatCraft2` in `plugin.yml`, but it also contains `OfflineUntrustResolver`, which was committed later in the source repository. The source repository is therefore the development baseline; no decompiled production classes will be copied into it.

## Scope

The fork adds:

- `/buildtrust <target> [duration]`.
- Optional durations for `/trust`, `/accesstrust`, `/containertrust`, and `/permissiontrust`.
- Online-player tab completion for the trust commands.
- A text `/trustlist` representation of BuildTrust and temporary expiration.
- Read-only viewing of supported block containers for BuildTrusted players.
- Protection against breaking or indirectly draining stored items.
- Persistent, restart-safe temporary trust expiration.
- Direct cleanup when native GriefPrevention trust or claim state changes.

The fork does not add trust GUIs, an anvil input flow, a second claim system, a database dependency, a repeating scanner, or a second plugin.

## Trust model

### Existing permissions remain unchanged

`ClaimPermission` stays in its existing order: Edit, Manage, Build, Inventory, Access. The order currently implements a linear permission hierarchy in `ClaimPermission.isGrantedBy`. Safe BuildTrust is non-linear because it grants Build and Access while denying Inventory and Manage, so it will not be added to that enum.

Normal GriefPrevention trust remains unchanged:

- Access permits basic interactions.
- Inventory permits containers and also Access.
- Build permits building, Inventory, and Access.
- Manage permits trust management and the permissions below it.

### Safe BuildTrust overlay

A BuildTrust target receives native Access permission in the claim plus a CatCraft safe-build marker. The marker is consulted by GriefPrevention's permission checks:

- Build is allowed.
- Access is allowed.
- Inventory is denied unless a separate, stronger current native permission grants it.
- Manage and Edit are not granted.

The Access fallback is intentional. If the server later runs an older GriefPrevention JAR, unknown CatCraft metadata is ignored and the target falls back to AccessTrust rather than retaining building or container access.

An ordinary permanent `/trust`, `/containertrust`, `/accesstrust`, or `/permissiontrust` command replaces the safe-build marker. `/untrust` removes it. Direct `Claim` permission mutations by another plugin invalidate temporary metadata when the claim is live, preventing an old expiry from undoing the external decision.

Permission inheritance for subdivisions follows GriefPrevention's existing rules. A restricted subdivision uses only its own marker and permissions. An unrestricted subdivision may inherit a safe-build marker from its parent in the same way it inherits normal permissions.

Player UUIDs, `public`, and bracketed permission targets remain valid wherever the existing command accepts them. Names are resolved through GriefPrevention's existing UUID logic. Stored records use the canonical claim permission identifier rather than a display name.

## Commands

The supported syntax is:

```text
/buildtrust <target> [duration]
/accesstrust <target> [duration]
/containertrust <target> [duration]
/trust <target> [duration]
/permissiontrust <target> [duration]
```

No duration means permanent trust. `forever` is accepted as an optional synonym but is not required or emphasized in usage messages.

Duration suffixes are case-insensitive:

- `m`: minutes
- `h`: hours
- `d`: days
- `w`: weeks

Examples include `30m`, `1H`, `1d`, and `2W`. Parsing uses exact integer arithmetic and rejects zero, negative values, decimals, missing units, extra characters, and arithmetic overflow. A configurable maximum duration defaults to 30 days. A duration above the maximum fails without changing trust.

Commands operate on the claim the player is standing in. Existing GriefPrevention behaviour outside a claim remains unchanged for its existing commands. `/buildtrust` requires a specific current claim so a safe-build grant cannot silently affect every claim.

Messages use the `&b[CatCraft]` prefix and simple wording. Repeated inventory denial messages are rate-limited per player. Trust changes generate one success message, and an expiration generates at most one message when the owner is online.

### Tab completion

For the first argument, completion reads Bukkit's current online-player collection, filters by the typed prefix and `sender.canSee(player)`, and sorts names case-insensitively. It performs no offline-player lookup, file access, claim scan, cache population, or asynchronous work.

For the second argument, completion offers short valid examples no greater than the configured maximum. Default suggestions are `1h`, `1d`, `1w`, and `4w`. `forever` may also be offered. No permanent collection of player names is retained.

### Trust list

`/trustlist` remains text-based. It preserves normal player, public, and permission-group entries. Safe BuildTrust entries are labelled `Build Trust`. Temporary entries include a compact remaining duration; permanent entries are labelled `Forever`.

## Temporary trust records

### Record identity and content

The CatCraft state file belongs to GriefPrevention and stores only primitives and strings. Each record includes:

- Claim ID.
- Canonical trust target.
- Permission dimension: normal permission or manager.
- Applied trust kind.
- Previous native permission and previous safe-build state.
- Expected active native permission and safe-build state.
- Owner UUID at grant, or the administrative-claim marker.
- Expiration timestamp in epoch milliseconds, or zero for a durable safe-build marker.
- Monotonic revision.

Records are indexed by claim ID and canonical target. A priority queue orders expiring records by timestamp and revision. No `Player`, `Claim`, `World`, `Location`, `Inventory`, entity, block, or chunk object is stored.

### Mutation order

Temporary changes use a write-ahead sequence:

1. Resolve and authorize the current claim and target.
2. Capture the previous state.
3. Persist the new revision and expected state atomically.
4. Apply and save the native GriefPrevention permission.
5. Publish the new in-memory indexes and reschedule the single expiration task.

If the server stops between steps 3 and 4, startup reconciliation sees that native state does not match the expected state and discards the unapplied record. The inverse failure, an active temporary permission with no expiration record, is avoided by persisting the record first.

A permanent change first persists invalidation of the old temporary revision and then applies the permanent native permission. An old scheduled callback checks both record identity and revision before acting.

The state file is written to a sibling temporary file and atomically moved into place when the filesystem supports it. A validated backup is retained for recovery from a truncated primary file. Disk access occurs only during startup, shutdown, and trust or claim mutations, never during permission, block, inventory, or tab-completion events.

### Expiration

Only the nearest relevant expiration is scheduled. When it fires, the service processes due records up to a configurable per-tick work limit and schedules remaining overdue work for the next tick. It then schedules the next future expiration.

Before restoring a previous state, expiration re-resolves the claim ID and verifies:

- The claim still exists.
- The owner matches the owner at grant.
- The record is still the newest revision.
- The current native permission and safe-build marker match the state applied by that record.

If any check fails, the record is removed without changing the claim. This ensures a stale timer cannot overwrite a newer trust command, external permission change, claim transfer, or claim deletion.

On startup, the service loads its bounded record set once, validates every record, processes already-expired entries in bounded batches, and schedules the nearest future expiration. There is no periodic claim reconciliation.

### Native lifecycle integration

CatCraft cleanup is called directly from GriefPrevention's mutation paths:

- `Claim.setPermission`, `Claim.dropPermission`, and `Claim.clearPermissions` for live claims.
- `/trust`, `/buildtrust`, `/containertrust`, `/accesstrust`, `/permissiontrust`, and `/untrust` command paths.
- Claim deletion and abandonment.
- Claim owner transfer.
- Subdivision deletion.
- Plugin disable.

Internal CatCraft restoration uses a guarded low-level mutation path so it does not invalidate its own current revision. Construction and data loading do not trigger mutation hooks before the CatCraft service is ready.

## BuildTrust container safety

### Opening containers

When a safe builder interacts with a supported block inventory, the original inventory opening is cancelled. GriefPrevention creates a detached snapshot inventory with cloned item stacks and opens that snapshot for the viewer. The live block inventory is never used as the top inventory in the read-only session.

Supported blocks include chests, trapped chests, barrels, shulker boxes, furnaces, blast furnaces, smokers, hoppers, droppers, dispensers, brewing stands, crafters, decorated pots, chiseled bookshelves, and other block-state inventory holders that can be copied safely. Unsupported or ambiguous inventory holders are denied.

Human and merchant inventories are excluded. Inventory-bearing entities are denied unless a dedicated detached snapshot path is explicitly supported and tested.

### Mutation denial

While a read-only session is active, every inventory mutation involving that view is cancelled. The listener does not maintain a blacklist of selected click types. It cancels the full click and drag events after checking that the session and top inventory still match.

This covers normal clicks, shift-clicking, number keys, hotbar swaps, offhand swaps, double-click collection, drag operations, drop actions, creative inventory actions, cursor-item placement, and movement between the player's inventory and the snapshot. The cursor and player inventory are not rewritten during cancellation, preventing item duplication or deletion.

The session stores only viewer UUID, claim ID, world UUID, block coordinates, expected owner, and an opaque inventory identity token. It is removed on close, disconnect, plugin disable, trust change, claim deletion, or failed revalidation. Session collections are bounded by online viewers.

### Storage breaking and indirect extraction

Before allowing a safe builder to break a block, GriefPrevention determines whether it can contain or eject stored items. A non-empty block is denied. Shulker boxes with BlockEntityTag or component-held contents, decorated pots, chiseled bookshelves, and other special storage receive dedicated checks.

Ambiguous storage state fails closed. Empty ordinary storage may be broken where the material-specific check proves that it cannot retain hidden contents.

Safe builders may not place, break, configure, or use hoppers or equivalent automatic transfer blocks where that could extract from protected storage. They may not break inventory-bearing entities such as chest or hopper minecarts. Container automation already created by the owner continues operating normally; the protection adds no global hopper scan or ownership tracker.

Every consequential block or inventory event re-resolves the claim at the event location. An open snapshot does not remain authorized after trust removal, claim deletion, transfer, subdivision restriction changes, or movement to a different claim.

## Configuration

New settings are placed under a CatCraft-specific section in GriefPrevention's existing configuration:

```yaml
GriefPrevention:
  CatCraftTrust:
    Enabled: true
    MaximumTemporaryDurationDays: 30
    MaximumTemporaryRecords: 10000
    MaximumExpirationsPerTick: 100
    DenialMessageCooldownSeconds: 3
```

Bounds are enforced when loading configuration. Unsafe or malformed values fall back to defaults and produce one console warning.

## Migration and rollback

### Existing GriefPrevention installations

Existing claims need no migration. Normal builders, container users, accessors, managers, permission targets, public trust, and subdivisions remain in GriefPrevention's existing storage.

### GPTrust add-on migration

If `plugins/GPTrust/temporary-trust.properties` exists and no completed-import marker exists, the fork validates and imports compatible records once. Import never overwrites a newer native permission. After a successful atomic CatCraft state save, the old file is renamed with a `.migrated` suffix. GPTrust must not be loaded at the same time as the integrated fork.

If no GPTrust state exists, startup performs no migration work.

### Reverting to an older GriefPrevention JAR

The older JAR ignores the CatCraft state file. Permanent or active BuildTrust entries retain only their native Access permission, so they lose building and read-only viewing rather than gaining container access. Normal GriefPrevention trust remains readable in its original format.

Temporary grants of normal Build, Inventory, or Manage should be allowed to expire or be removed before rollback. If the server is reverted while one is active, the older JAR cannot process the CatCraft expiration record and the currently stored native permission remains until an administrator runs `/untrust` or changes it. A shutdown report will list active temporary non-BuildTrust grants so operators can resolve them before replacing the JAR.

## Resource and abuse controls

- No world, chunk, claim, or online-player polling.
- No per-tick scanner.
- At most one future expiration task plus one bounded next-tick continuation for an overdue batch.
- Record count and input lengths are bounded.
- Hot permission and inventory checks use in-memory maps keyed by claim ID and target.
- No file or database access in block, interaction, inventory, permission, or tab-completion events.
- Read-only view sessions are bounded by online viewers and removed deterministically.
- Trust commands batch multi-record persistence and native claim saves rather than rewriting the state file per individual record.
- Malformed state fails closed without applying or restoring trust.

## Verification strategy

### Unit and state-machine tests

- Case-insensitive, overflow-safe duration parsing and configurable limits.
- Permanent to temporary to expiry restores the previous state.
- Temporary to newer permanent ignores the old expiry.
- Temporary to different temporary ignores the first expiry.
- `/untrust` and `/untrust all` invalidate records.
- Deletion, abandonment, transfer, and subdivision removal clean metadata.
- Restart before and after expiration.
- Crash-boundary reconciliation for recorded-but-unapplied state.
- Permission-group and public targets.
- Record-limit, corrupt-file, backup recovery, and revision-overflow behaviour.
- Online-only, visibility-aware tab completion with no offline lookup.

### Inventory and block harness

- Ordinary block placement and breaking.
- Detached container viewing.
- Normal click, shift-click, number-key, double-click, drag, drop, cursor, offhand, and creative mutation denial.
- Close, disconnect, reopen, external trust change, deletion, and transfer revalidation.
- Full chest, barrel, shulker, decorated pot, and chiseled bookshelf breaking.
- Hopper placement and interaction bypass attempts.
- Container minecart and unsupported inventory denial.
- Human and merchant inventory exclusion.
- No item duplication or deletion across cancelled actions.

### Build and package checks

- Full Maven test suite on Java 21.
- Clean package from the exact source commit plus CatCraft changes.
- JAR inspection for plugin name, commands, version, and expected classes.
- Source ZIP/JAR provenance and SHA-256 checksums.
- Static check for retained Bukkit object types in long-lived state.
- Scheduler check confirming there is no repeating CatCraft trust task.

Live Paper, Geyser/Bedrock, TPS, and heap behaviour remain deployment checks unless a representative server runtime is available during development. The artifact will distinguish automated verification from live-server proof.
