# Task 3 independent merge review and relay verification

Status: **accepted** for integration source, localization/fixture integrity, and the required relay checks. The merged native unit suites are green: iOS **991 tests, zero failures, two opt-in skips**; Android **1,128 tests across 170 classes, zero failures/errors, one skip**. Physical-device acceptance and the broader threat-model pass remain Tasks 4 and 5. No source/test edit, commit, push, deployment or device operation was performed by this reviewer.

Reviewed the stable working/index merge of sync `92e49dd` into WP4/WP2 integration `664f0b0` (WP4 `8b08cf0`, original integration `82bf970`; branch common ancestor `e3369bc`). This reviewer did not author the new integration presence/calibration gate or its regression. Earlier Task 2 iOS authorship remains disclosed in [TASK2_FINAL_REVIEW.md](TASK2_FINAL_REVIEW.md), with independent supplemental approval there. Source was frozen by the integration owner before this review; the subsequent test-only grid API adaptation was explicitly reread.

## Merge integrity and source intersections

- Independently computed the recursive three-way expected JSON, rather than trusting text-conflict resolution. Catalog (2,091 entries), reviews (all three top-level sections and their nested fingerprints), and source inventory (443 paths, compared by path) exactly match that expected merge; zero semantic conflicting leaves. `scripts/check_localizations.py` validates 2,091 messages, 23 plural families, translation fingerprints and all native outputs. Inventory review labels remain intact; registration is not a claim that every component received a fresh language/device audit.
- Checked 276 WP4-only and 120 sync-only changed paths against immutable reviewed branch blobs. Every such production/source/fixture path retains its expected bytes. The sole final single-branch difference is the intentional `PDFTileSourceTests.swift` test-only API adaptation: MainActor annotation, real empty presence model/subscription, and removal of the retired `peers:` argument. Existing grid/camera/LOD assertions are preserved. The additional overlay regression is the intended integration-only test. Three additional audit paths are inherited from the original integration branch, not new implementation.
- `ContentView.swift` retains WP4 library/calibration/import lifecycle and sync's direct presence model, transient-inactive policy, key/App Lock re-evaluation, chat teardown and foreground/background gates. It does not restore the former embedded import copier or obsolete PDF overlay/tiler path.
- `TileMapContainer.swift` retains WP4 calibration source/camera ordering, imported-map visibility, rotation policy, provisional grid/user-location masking, latest-wins grid cancellation, and PDF tile/runtime failure behavior. Sync presence moves markers through its direct subscription, without making location frames rebuild drawing overlays/root view. Calibration visibility is checked by the publisher callback as well as the immediate visibility transition; exit restores the latest peer value. Nil observation unsubscribes and clears markers. No obsolete `syncPDF`/image-overlay call is resurrected.
- Android keeps both visual and hit-test peers hidden while calibrating (`CustomMapScreen` overlay mask and empty touch-overlay peer map), matching the merged iOS behavior. The new actual-overlay regression hides a visible peer, publishes a moved peer through the asynchronous stream while hidden, proves markers/hit tests stay absent, then verifies the latest coordinate on exit and nil clear. Focused execution passes **1/0**; the full suite includes it.
- Recovery guards remain intact on both platforms: Corrupt/Locked/migration-pending reads do not reconcile/sweep/prune; rebuilt indexes retain the sealed orphan-preservation flag, and cleanup APIs check it. The merged threat model retains these recovery limits together with sync retention, authenticated replay/session rules, worker persistence scope and chat teardown ordering. Existing sync source and shared fixtures are unchanged from their reviewed branch. Snapshot regression remains SECURITY with a maximum sequence fence; relay nack/close availability hints do not downgrade authentication or durable replay authority. Background reconnect stays disabled on both platforms; no wire or fixture expectation was relaxed.
- Independently verified all **133** preexisting untracked audit-file hashes still match the owner's pre-merge manifest. Preserved original PDF fixture bytes, including valid xref trailing spaces; staged/working merge-relative whitespace checks and combined non-PDF source whitespace checks pass. No unresolved index entries remain.

The exact JSON hashes, path inventory, final intentional test exception and preexisting-audit verification are saved in `/Users/cbrooker/.claude/jobs/10094e99/tmp/codex-task3-review/merge-proof.json`.

## Executed relay and static checks

All commands ran in the merged worktree's `sync/` directory, with logs under `/Users/cbrooker/.claude/jobs/10094e99/tmp/codex-task3-review/relay/`:

| Check | Result / log |
|---|---|
| `npm ci` | Pass, `npm-ci.log` |
| `npx vitest run` | **117 tests across four files pass**, `vitest.log` |
| `npm run typecheck` | Worker types current and TypeScript pass, `typecheck.log` |
| `npm run check` | Pass, explicitly `wrangler deploy --dry-run`; `dry-run.log`. Nothing deployed. |
| `npm run soak -- --spawn --port 8797 --inspector-port 19297 --clients 8 --seconds 90 --restart-at 30,60` | Pass, `soak-8x90-restarts30-60.log` |

Both ports were free before spawning. The soak used its own persisted scratch directory, actual local worker restarts at seconds 30 and 60, and cleaned up afterward; neither port remains listening. All eight clients and the late joiner converge, no divergent clients or pending operations remain, and record/presence verification failures are zero. It records 4,370 acknowledgements, 1,329 presence sends and 9,301 verified presence receipts. The 63 socket closes are attributed to 31 intentional workload drops and 32 restart-window closes, with zero relay-attributed closes. Local fanout latency is workload evidence, not a physical-device performance guarantee.

Client fixture generator `python3 testdata/tools/gen_sync_client_behaviour.py --check` also passes (`client-fixture-check.log`); localization output is in `localization-check.log` one directory above the relay logs.

## Native unit evidence and remaining scope

Directly read the merged iOS successful full unit-only log: `/Users/cbrooker/.claude/jobs/10094e99/tmp/codex-integration/ios-merged-units.log`; result `iosDD/Logs/Test/Test-TacticalMaps-2026.10.02_10-39-35-+1000.xcresult`. Focused new presence/calibration regression is in `ios-merged-presence-focused-final.log`; see [TASK3_IOS_VERIFICATION.md](TASK3_IOS_VERIFICATION.md). Android direct result totals are in `android-result.json`, with successful units/debug/instrumentation artifact builds in `android-full.log` under the same integration scratch directory. These counts exceed the pre-merge 770/924 suites because the WP4 tests are now included.

No concrete integration blocker remains in the reviewed intersections. Earlier enabled native sync/local/shipped-TLS acceptance is recorded in [TASK2_INTEROP.md](TASK2_INTEROP.md); this review adds the merged relay soak and native unit suites, not a new physical-device or OS auth-bound key-policy claim. Tasks 4 and 5 must establish their own device/measurement/threat-model evidence. The large existing managers remain a maintenance constraint requiring boundary-specific reasoning; this merge introduces no broad refactor or shortcut around their durability/lifecycle checks.
