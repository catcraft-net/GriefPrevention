# SDD ledger — plan: docs/superpowers/plans/2026-09-14-catcraft-trust-safety.md

Branch: feature/catcraft-trust-safety
Starting commit: 7b9806c
Spec: docs/superpowers/specs/2026-09-14-catcraft-trust-safety-design.md
Baseline: Java 21 Maven test, 67 tests, 0 failures, 0 errors, 0 skipped.

Ruling: Use the task-specific cloned repository as the isolated workspace and create a feature branch inside it — the clone was created only for this work and is separate from the user's live source checkout — a nested linked worktree would add management overhead without protecting additional user state.

Ruling: Use exactly three Luna xhigh workers, as requested. Dispatch implementation sequentially to prevent shared-file conflicts. Rotate those workers for independent reviews and keep existing GriefPrevention integration files with the primary integrator — this preserves the requested worker count while retaining review separation.

## Pre-flight dependency and consistency scan

| Tasks | Producer / consumer boundary | Finding and ruling |
|---|---|---|
| 1 -> 2 | Value records and duration parser feed state store/service | Consistent. Task 1 includes the checked exception and reason enum required by parser tests. |
| 1 -> 3 | Trust kind and parser feed command request parsing | Consistent. Task 3 normalizes omitted duration and `forever` to a permanent request. |
| 2 -> 5 | Service lookup and mutation guard feed Claim integration | Consistent. External mutation callbacks do not throw checked exceptions; command grants retain durable checked failure handling. |
| 2 + 3 -> 6 | Batched service grants and parsed requests feed native commands | Consistent. One collection grant permits one metadata persistence operation for all-claim legacy commands. |
| 3 -> 6 | Online completion helpers feed Bukkit command completers | Consistent. Only current online players are supplied by the integrator. |
| 4 -> 7 | Provider/listener feed lifecycle registration | Consistent. Listener depends on an interface and the service is passed as a method reference or adapter. |
| 2 + 4 -> 7 | Service and listener cleanup feed disable/delete/transfer lifecycle | Consistent. Cleanup removes in-memory authority first and stale disk entries fail startup revalidation. |
| 5 + 6 + 7 -> 8 | Integrated permissions, commands, and lifecycle feed final regression tests | Consistent. Task 8 changes production only behind a failing regression test. |
| 1 | Tests and code declared within task | Consistent. Every referenced public type is listed in the file map. |
| 2 | Tests and code declared within task | Consistent. `ClaimSnapshot`, scheduler handle, and access ports are defined in the task text. |
| 3 | Tests and code declared within task | Consistent. `TrustCommandRequest` fields and helper signatures are explicit. |
| 4 | Tests and code declared within task | Consistent. Human/merchant exclusion and external inventory-open interception are included. |
| 5 | Tests and code declared within task | Consistent. Safe BuildTrust grants only Build and Access and does not alter the enum hierarchy. |
| 6 | Tests and code declared within task | Consistent. BuildTrust is current-claim only; existing commands retain their outside-claim scope. |
| 7 | Tests and code declared within task | Consistent. Simultaneous primary/backup corruption is reported honestly as unrecoverable metadata, while normal GP remains enabled to preserve claim protection. |
| 8 | Tests and code declared within task | Consistent. Automated and live-server evidence remain explicitly separated. |

Ruling: The plan originally used a new empty Maven cache path for offline runs. Execute with `/private/tmp/gptrust-main-m2`, which contains the verified baseline dependencies — changing the cache path has no product effect and avoids a false offline-resolution failure.

Task 1: complete — commit 418cb1a956172c71c795e7400d20c6fcf36efa0c; 11 focused and 78 full-suite tests passed; independent spec and quality review by command_luna passed with no findings.

Task 2: fix round 1/5 started from ee010a6f211c5f79a0ceb9f4659a600d5aa48f60 — container_luna review found 8 P1 and 3 P2 issues covering crash ordering, permanent BuildTrust persistence, queue bounds, hot-path I/O, callback overlap, backup preservation, loaded input bounds, restricted inheritance, stale candidate lookup, startup batching, and missing regression tests. All findings accepted; none conflict with the specification.

Ruling: Replacement mutations need a durable transition that preserves both the preceding record and intended record until native application is resolved — a single overwritten row cannot distinguish crash-before-apply from a superseded timer — the extra transition state increases persistence code but is required to prevent orphaned native permission and stale restoration.

Ruling: Use one deduplicated ordered expiration entry per active record key and one cancellable scheduled handle for both next-tick continuation and future expiration — this enforces the record bound and the single-callback requirement.

Task 2: fix round 2/5 started from 92ed10d170552cb3fb5c888dd793097e99a74a07 — scoped re-review confirmed 11/11 original findings addressed and found 4 new P1 plus 1 new P2 issue: permanent BuildTrust baseline loss, transitions surviving external mutation, unbounded transition batches, malformed transition keys escaping backup recovery, and post-allocation raw-size checks. All findings accepted.

Ruling: When a new temporary grant replaces a permanent BuildTrust marker, its restoration baseline is the permanent record's expected state, including `safeBuild=true`; when replacing an older temporary record, retain the older record's original previous state so chained temporary changes do not resurrect an expired temporary permission.

Task 2: fix round 3/5 started from e798e5f351cc57254ba41360e3d5d7eb12c1773e — scoped re-review confirmed 5/5 round-2 findings addressed and found one P2 target-compatibility regression for `|` in target text.

Ruling: Preserve the existing 256-character target contract and parse record keys at the first two separators, treating the complete suffix as target text — rejecting an already-valid permission target would violate compatibility and is unnecessary because stored target values are separately encoded and validated.

Task 2: complete — commits ee010a6f211c5f79a0ceb9f4659a600d5aa48f60, 92ed10d170552cb3fb5c888dd793097e99a74a07, e798e5f351cc57254ba41360e3d5d7eb12c1773e, and b587a023e8999db1c6b2a13495b898cc82199912; 29 focused and 107 full-suite tests passed; three review rounds closed 17 findings with no open or new findings.

Task 3: complete — commit 714efc9; 6 task-focused and 17 combined command/value tests passed; independent spec and quality review by trust_core_luna passed with no findings.

Task 4: fix round 1/5 started from 81ef85e3ed21511b125343fb88dc0a8683fa0d19 — command_luna review found two P1 and two P2 issues: hopper-minecart placement, projectile destruction of storage entities, viewer-location session revalidation, and fail-open null shulker metadata. All findings accepted.

Ruling: Treat hopper and chest minecart items as denied inventory-bearing entity placement and inspect the interaction's held item before returning for an ordinary clicked rail — entity placement does not produce BlockPlaceEvent.

Ruling: Attribute direct-player and player-shot projectile damage to the responsible player for both entity damage and vehicle destruction — other indirect damage without reliable player attribution remains denied by GriefPrevention's normal protection.

Task 4: complete — commits 81ef85e3ed21511b125343fb88dc0a8683fa0d19 and c97837aa977ad16e153f78a37931d1a2f4219409; 34 focused and 147 full-suite tests passed; one review round closed four security findings with no open or new findings.

Task 5: implementation complete pending independent review — commit c922388; 7 focused and 154 full-suite tests passed. Safe BuildTrust is overlaid only for Build and Access checks, native hierarchy remains unchanged, and live external permission mutations invalidate CatCraft metadata before native mutation.

Task 5: fix round 1/5 started from c922388 — command_luna found one P1 and three P2 issues covering inactive-service mutations, manager replacement semantics, repeated recursive persistence, and incomplete public/group/subdivision matrix proof. All findings accepted after confirming the approved design explicitly requires permanent PermissionTrust to replace a safe-build marker. Fix commit 78900e5; 42 focused and 160 full-suite tests passed; re-review pending.

Cross-task audit: trust_core_luna found a P1 permanent marker restoration defect and a P1 real-adapter contract gap in Task 2. A temporary grant replacing permanent BuildTrust restores native Access on expiry but currently removes the durable safe-build record, and the adapter contract incorrectly assumes native Claim state can supply the sidecar marker bit. Schedule a separate TDD hardening pass before lifecycle wiring.

Task 5: complete — commits c922388, 78900e5, d42964f, and 40e3a62; final 49 focused and 167 full-suite tests passed. Three independent review rounds closed all lifecycle, persistence, replacement-semantics, and permission-matrix findings. command_luna final verdict PASS with no findings.

Task 2 hardening review: commit 66f057a passed 31 focused and 172 full-suite tests, but container_luna found one P1 and two P2 issues. The P1 confirms the original requirement: PermissionTrust is an independent manager dimension and must coexist with Safe BuildTrust. The committed hardening incorrectly removed the permission marker. The P2 findings cover non-journaled permanent-marker restoration during expiry and partial in-memory state if multi-claim preparation throws before the first save. All findings accepted; correction waits for Task 6's in-flight tests to compile cleanly in the shared tree.

Task 2 correction: strict TDD RED exposed the independent-dimension, expiry write-ahead, duplicate-batch, revision-overflow, and atomic-preparation regressions. GREEN passed 51 focused and 189 full-suite tests with Java 21 and `/private/tmp/gptrust-main-m2`. MANAGE now coexists with permission-dimension safe-build records; Claim.setPermission(Manage) invalidates only manager metadata while /untrust removes both. Grant preparation reserves and commits revisions atomically after all captures, and bounded expiry batches persist TrustTransition state before native restoration. The design sentence at line 54 was corrected to state that `/permissiontrust` preserves the safe-build marker. Commit d3fc844.

Task 6 review fixes: strict TDD RED recorded 4 expected failures across non-trust fallback completion, PermissionTrust event compatibility, BuildTrust errors, and prefixed grant success output. GREEN passed 17 focused and 196 full-suite tests with Java 21 and `/private/tmp/gptrust-main-m2`. All-claims legacy grants now have explicit one-batch/kind coverage, runtime completer registration and aliases are covered, and trust success/error output is prefixed and duration-aware. Code commit 30787437cbfc93de8dfbd0651cfc682d56488992.

Task 2 core hardening: strict TDD RED covered missing two-phase external hooks and bounded due-batch APIs, then regression RED covered terminal native failure and external save policy corrections. GREEN passed 73 focused and 208 full-suite Java 21 tests with `/private/tmp/gptrust-main-m2`. External Claim mutations now use durable before/after transitions and fail closed on WAL preparation failure; expiry processing is one bounded due batch with terminal unavailable behavior on prepare/journal/native/final-save failure; revoke preparation is duplicate-safe, no-op aware, capacity checked over active plus pending keys, and atomic before save. Production/test commit `0e4069d0ed89f0dcb9880f3bff8d0be03c0bb003`; report and ledger documentation commit follows separately.

Task 6 review correction: strict TDD RED covered the baseline PermissionTrust event payload, ampersand-bearing target display, and child-friendly duration wording; 19 focused tests failed as expected before production changes. GREEN passed 19 focused and 210 full-suite Java 21 tests with `/private/tmp/gptrust-main-m2`. PermissionTrust now emits `given=true` with `ClaimPermission.Manage` while the service retains the independent `MANAGE` kind; target display is literal without changing the canonical service target; success durations use lowercase `forever` and human minute/hour/day/week words with scope retained. Code commit `fe1a4a3`; report and ledger documentation commit follows separately.
