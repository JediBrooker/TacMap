# Task 2 iOS checkpoint — 2026-10-02

Implementation is ready for the final independent review and live interoperability run. No commit, push or deployment was made by the iOS agent.

## Verification

- Initial full suite on freshly erased simulator `6BADDA88-5E21-4E0B-983D-E69D22C5B6D8`: 750 tests, 0 failures, 1 opt-in production-relay test skipped.
- Latest full suite after FR-7/FR-9/FR-12/FR-13: **770 tests, 0 failures, 2 opt-in live tests skipped**. The intended unit-only command completed successfully. Log: `/Users/cbrooker/.claude/jobs/10094e99/tmp/codex-sync/ios-early-peer-key-units.log`; result: `/Users/cbrooker/.claude/jobs/10094e99/tmp/codex-sync/iosDD/Logs/Test/Test-TacticalMaps-2026.10.02_10-23-37-+1000.xcresult`. Prior 768-test pass remains in `ios-startup-ready-full.log`. Earlier 767-test repair pass remains in `ios-fr7-fr9-full.log` and 764-test checkpoint in `ios-suitepool-green.log`.
- `python3 scripts/check_localizations.py`: 1,967 messages, 21 plural families, all native output/fingerprints valid.
- `python3 testdata/tools/gen_sync_client_behaviour.py --check` and `git diff --check`: pass.
- Twelve asynchronous-persistence tests use the production serial executor and actual sealed files. They additionally prove a chat write during a held failed replacement hello cannot erase the old durable replay fence; journal startup migration retains two local edit events over an existing generation; a superseded join stays fenced; and startup completion in background defers queued journal seals until foreground. The empty-event startup/foreground readiness regression additionally holds replay load through two pause/return cycles. Focused twelve-test pass: `ios-early-peer-key-focused.log` in the same scratch directory.

The reused simulator first exhibited CFPreferences “Path not accessible” and unavailable device identity after hundreds of random real preference domains; another fresh run reproduced this with the larger suite. The test harness now reuses at most 64 real durable domains, exclusively assigned to live harnesses. Teardown leaves/cancels the manager before clearing a domain, and tests with actual asynchronous writers drain the held flush and its clean point before returning the domain or data key. This is test-resource isolation; real preference and sealing APIs remain exercised. The final full pass used that repair. Earlier assertion-only failures in the new tests were corrected: chat restart deliberately resolves Sending to Failed/session-ended, and a second snapshot requires a new connection.

## Changes

- Map replay commits/reservations and batched local-generation journal writes run on a serial background persistence executor. Publication and outbound frames follow successful durability, with socket/join/lifecycle checks after awaits. Replay load and legacy actor-pin repairs also run on that worker. Clean points serialize behind outstanding flushes before mutating their exact-floor flag.
- The final reviewer was explicitly reassigned to author the narrow FR-7/FR-9/FR-12/FR-13 repairs. Replay state now retains a durable-only session-domain view, updated on successful persistence/load, for chat fence pruning, including hello-only sessions. Journal configure loads a private instance on the persistence worker; lifetime model observers queue edit events until startup and their ordered seals complete. Joins wait behind that barrier, and queued edit seals wait for foreground eligibility. A v3-only connection readiness gate requires the loaded replay authority and key room to match, plus completed journal startup; explicit v2 behavior is preserved. The interop owner independently approved these new iOS repairs in `TASK2_IOS_FOLLOWUP_REVIEW.md`; the reassigned author does not claim independent approval of their own changes.
- Snapshot validation captures the current object kinds, model epoch, hashes and generations, and revalidates after local changes. Only records that will apply may stage new layers. An exact resolved old record cannot hide a missing layer from a fresh record. Batched model application stays on the main actor, once per store.
- Live logical batches split before a repeated mutation wire ID and before model-reading response frames; each batch remains bounded by the 64-frame drain.
- Signed outer `t`/`deleted` inconsistencies are rejected consistently with Android. Unsupported records do not advance replay state. Presence counter boundary arithmetic cannot overflow.
- Superseded pending delivery IDs release their pacer window. Signed leave uses the existing budget and closes immediately when no allowance remains. Paused mutations keep their notice until leave; Retry uses the existing parked-session action.
- Chat history applies byte hysteresis before the count cap, including the 501-record boundary that count-first pruning would hide. Shared fixture expected values are independently generated.
- Presence-only redraw, stationary suppression, PBKDF2 off-main, reverse wire lookup and background reconnect-disabled behavior remain covered by the efficiency suite.

The separate chat-history store retains synchronous main-actor sealing, with durable-before-send/receive guarantees, outside the parent's SP3 map replay/journal worker scope. Production journal startup loading and its SafeStore migration/sealed-only barrier now run on the persistence worker. Normal manager map replay commits have no intentional synchronous sealing path; direct synchronous state APIs remain for focused state tests. Threat-model text distinguishes this scope. A locked/corrupt startup journal still fails closed; this repair does not add automatic retries that could reinterpret quarantined history as empty.

Measured simulator checks: a 1,000-record snapshot uses 3 replay writes rather than 1,002 and one waypoint-store write rather than 1,000; 50 live puts use 2 replay writes and one waypoint-store write; a 500-object import uses one journal write; 60 presence-only updates publish no root-manager change; a 2,000-object/tombstone case uses 2,000 HMACs rather than 4,000,000. These are simulator measurements, not device latency guarantees.

## Live run (parent GO renewed after FR-13; evidence pending)

`SyncNativeInteropTests/testNativeAndroidRoundTripWhenLocalRelayIsProvided` uses production iOS networking/crypto/manager and actual sealed stores. Supply test process environment:

- `TACMAP_LIVE_RELAY=ws://127.0.0.1:8796`
- `TACMAP_LIVE_JOIN_CODE=3:<fresh-room>`
- `TACMAP_CHAT_INTEROP_RUN_ID=<fresh-run>`
- `TACMAP_SYNC_FULL_INTEROP=true`

It matches Android's full-mode harness: signed presence; waypoint/drawing creation, edits and deletions with chat barriers `T2_IOS_CREATED_SEEN`, `T2_IOS_EDITED_SEEN`, `T2_IOS_DELETED_SEEN` and their Android counterparts; then room/direct chat both ways. The physical app-kill/relaunch, relay restart and background checks require the root's coordinated lifecycle run. The default skipped live tests are not compatibility evidence; production-relay smoke must also be enabled before declaring Task 2 accepted.

## Supplemental readiness regression evidence

`testEmptyStartupAndForegroundReconciliationCannotConnectBeforeReplayLoad` fails against the prior source: foreground reconciliation opens one socket before replay load and a second on the next foreground return (`ios-startup-ready-oldsource.log`). The v3 readiness gate fixes that exact path; the unchanged regression then passes in the focused and full suites. The prior failed counterfactual xcodebuild lingered after the test completed and overlapped the next launch; it was terminated, and successful focused/full reruns completed sequentially. The first focused attempt also exposed an existing test timing race: the outbound-background test used a journal writer-entry count as durability. It now awaits an actual serial journal-drain boundary before firing its one virtual diff timer; its held outbound seal, background send suppression and membership assertions are unchanged.

## Native-discovered FR-13 repair and verification

Enabled native execution found a production startup ordering defect: an early authenticated peer key was discarded when the local hello-ack started chat, and a stale staged recipient map was republished. The observer proved raw Android chat arrived and passed production crypto, while Mirror showed actual peer keys=0/published recipients=1. The reassigned author repaired local-only startup clearing and full teardown publication consistency; the interop reviewer independently approved. Both real-manager regressions fail prior source (`ios-early-peer-key-red.log`). Positive coverage receives and durably reloads actual signed/encrypted chat after early key advertisement, verifying the actual peer key. Negative coverage actually tears down during an inbound drain, proves peer authority and recipient publication are removed, and rejects a subsequent valid encrypted frame. Existing hello/session/pin/crypto/replay/background rules remain unchanged; threat-model text records the ordering.

The first full command accidentally included unrelated UI/capture tests: units completed 770/0/2, a stale Privacy UI navigation-title assertion failed, and the broader run was stopped (Xcode exit75). That run is not a passing whole-run claim. The parent authorized one expected-title correction to the existing Settings screen; both privacy switches/reachability assertions remain. Focused UI test passes (1/0, `ios-privacy-title-focused.log`). The subsequent intended unit-only command passes 770/0/2 (`ios-early-peer-key-units.log`). No production UI change, commit, push or deployment. Live acceptance still requires rerunning the repaired native pair.
