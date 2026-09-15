# CatCraft Trust Safety fork

This is a command-only CatCraft fork of GriefPrevention 18.0.0. It keeps GriefPrevention as the active protection and persistence system, and adds safe Build Trust plus optional temporary durations directly to the existing trust commands. It contains no trust GUI and must not be installed alongside the former GPTrust add-on or TrustHooks binary patch.

## Commands and permissions

All trust commands use the existing `griefprevention.claims` permission. Omitting the duration means forever. Duration units are case-insensitive and support minutes (`m`), hours (`h`), days (`d`), and weeks (`w`). For example, `1D` and `1d` both mean one day. The configured maximum is 30 days by default.

- `/buildtrust <player|public|permission> [duration]` (`/bt`) gives safe building and read-only container viewing. It always affects the claim where the sender is standing.
- `/accesstrust <target> [duration]` (`/at`) gives normal GriefPrevention Access Trust.
- `/containertrust <target> [duration]` (`/ct`) gives normal GriefPrevention Container Trust.
- `/trust <target> [duration]` (`/tr`) gives normal full Build permission.
- `/permissiontrust <target> [duration]` (`/pt`, `/managetrust`) lets the target manage trusted players while preserving any separate Build Trust marker.
- `/untrust <target>` (`/ut`) and `/untrust all` remove native trust and invalidate matching temporary metadata.
- `/trustlist` shows each trust level and labels safe Build Trust entries as temporary or forever.

The four original trust commands keep GriefPrevention's scope: inside a claim they affect that claim, and outside a claim they affect all claims owned by the sender. Build Trust requires the sender to stand in the claim so it can never silently change every claim.

Tab completion reads the server's current online-player collection, hides vanished players the sender cannot see, filters and sorts names in memory, and performs no offline-player or network lookup. The second argument suggests `1h`, `1d`, `1w`, `4w`, and `forever` when each fits the configured maximum.

## Safe Build Trust

Safe Build Trust is represented by native GriefPrevention Access permission plus a persisted CatCraft marker. GriefPrevention remains authoritative for active claim permissions, so existing claims, subdivisions, UUID targets, `public`, permission-group targets, events, and API checks continue to use the normal claim model.

A safe builder may place and break ordinary building blocks. Supported inventory blocks open as detached read-only copies, so clicks do not touch the real inventory. Click, shift-click, number-key, offhand, double-click, drag, drop, creative-inventory, cursor, close/reopen, and externally opened inventory paths are cancelled and revalidated.

Non-empty storage and ambiguous inventory-bearing blocks cannot be broken. Empty ordinary storage may be broken after its state is verified. Hoppers, droppers, dispensers, crafters, storage minecarts, and other item-moving mechanisms stay protected from placement, breaking, and mutation. Their contents can still be viewed in a detached read-only preview where supported. Decorated pots, chiseled bookshelves, shulker boxes, furnaces, brewing stands, campfires, jukeboxes, and lecterns have explicit handling. Unknown inventory holders fail closed.

While a claim has any live safe-builder marker, event-driven guards pause item-moving automation in that claim, including hopper moves, item pickup into inventories, dispensing, pistons, redstone state changes, relevant fluids, fire, and explosions. This conservative rule prevents a builder from using indirect mechanics to extract items, but it also pauses the owner's affected automation until safe Build Trust is removed or expires.

Repeated denial messages are rate-limited. Open sessions are indexed by viewer UUID and contain only claim ID, world UUID, block coordinates, owner UUID, and a random token. Sessions and pending tasks are removed on close, quit, replacement, and plugin disable.

## Temporary trust and persistence

Temporary grants apply to Access, Build, Container, Full, and Permission Trust. Each record stores only primitives, strings, UUIDs, trust state, expiry time, and a revision. No Player, World, Claim, Location, Inventory, Chunk, or Block object is persisted or retained in a temporary record.

State is stored at:

`plugins/GriefPreventionData/CatCraftTrust/temporary-trust.properties`

The primary file has an atomic backup. Changes use a write-ahead transition journal so a crash between metadata and native-claim writes can be reconciled. Revisions prevent an older timer from overwriting a newer trust decision. Expiry restores the state that existed before the temporary grant only when the claim owner and current native state still match that record. Newer external changes, claim deletion, ownership transfer, and stale records invalidate the old expiry.

Only the next expiry is scheduled. Due records are processed in a bounded batch, with excess work continued on the next tick. There is no per-tick scanner, repeating CatCraft task, world scan, chunk scan, online-player scan, or task per grant. Disk access occurs on trust changes, lifecycle reconciliation, expiry, and shutdown, never on inventory clicks.

## Configuration

The fork writes these values under `GriefPrevention.CatCraftTrust` in `plugins/GriefPreventionData/config.yml`:

```yaml
GriefPrevention:
  CatCraftTrust:
    Enabled: true
    MaximumTemporaryDurationDays: 30
    MaximumTemporaryRecords: 10000
    MaximumExpirationsPerTick: 100
    DenialMessageCooldownSeconds: 3
```

Bounds are 1-3650 days, 100-100000 records, 1-1000 expirations per tick, and a 1-30 second message cooldown. `/gpreload` requires `griefprevention.reload`. It updates whether new CatCraft grants are accepted and the command duration limit; restart the server after changing record, expiry-batch, or cooldown limits so the running service is rebuilt with them. When `Enabled` is false, the reconciler and read-only protection still run for existing records so temporary grants cannot become permanent accidentally.

## Migration and installation

Stop the server and back up the GriefPrevention data folder and the old `plugins/GPTrust` folder together. Remove the GPTrust add-on and any TrustHooks-patched GriefPrevention JAR, install only this fork, then start the server.

If integrated state does not already exist, the fork strictly validates and imports compatible records from `plugins/GPTrust/temporary-trust.properties`. It imports only records whose claim, owner, and current native state still match; it never overwrites a newer trust choice. After the integrated state is durable, the legacy file is renamed to `temporary-trust.properties.migrated`. Malformed legacy state stops the CatCraft trust service without disabling ordinary GriefPrevention claim protection.

Existing GriefPrevention claim files and database tables are not migrated or replaced. The fork adds only its sidecar metadata file.

## Downgrade and rollback

Replacing this fork with the previous GriefPrevention JAR does not corrupt or rewrite existing claim data, but the old JAR cannot understand or expire the sidecar records:

- A safe Build Trust target is stored natively as Access Trust. After downgrade, the target loses building permission but keeps ordinary Access Trust until explicitly untrusted.
- Temporary Access, Container, Full, or Permission Trust remains active indefinitely after downgrade because the old JAR has no expiry service.
- The CatCraft sidecar file is ignored by the old JAR. Leaving it in place is not destructive, but reinstalling the fork later may reconcile it.

For a controlled rollback, let temporary grants expire or remove affected trust entries with `/untrust`, check `/trustlist`, stop the server cleanly, back up both data locations, and then replace the JAR.

## Verification boundary

The source suite covers parsing and overflow, permanent-to-temporary restoration, stale timers, replacement grants, untrust invalidation, claim deletion and transfer, restart reconciliation, corrupt primary/backup handling, bounded expiry batches, permission dimensions, command scope and events, online-only completion, inventory mutation routes, storage breaking, special storage, entity storage, indirect automation, session revalidation, and retained-state types.

These automated checks do not replace a staged Paper startup with CatCraft's complete plugin set, Bedrock/Geyser interaction tests, a live TPS/Spark profile, or heap-retention observation. Complete those checks before promoting the JAR to the production network.
