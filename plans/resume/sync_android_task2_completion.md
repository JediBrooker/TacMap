# Task 2 Android completion evidence

Workspace: `/Users/cbrooker/Code/TacticalMaps/.claude/worktrees/review-sync`.
Scratch evidence: `/Users/cbrooker/.claude/jobs/10094e99/tmp/codex-sync/android`.
Status: implementation and required enabled native interop accepted. Final independent Android security/parity source review is accepted; actual local SP1 lifecycle/full-pair and shipped TLS full-pair verification passed on both platforms. See `TASK2_INTEROP.md` for exact commands, run IDs, results and proof limits. No push, deployment or version bump was performed. The coordinator authorized this agent to create the scoped local Task 2 commit after all writers froze.

## Implementation and dispositions

- Chat history applies exact encoded-byte hysteresis (2 MiB trigger, 1.5 MiB retained) before the 500-message cap. Candidate and retained sizes include unread metadata; unread IDs follow retained messages.
- Signed leave consumes remaining control-pacer budget after queued application work is discarded. If a leave cannot be sent immediately within budget, transport closes without a pacing bypass.
- Snapshot staging excludes stale/conflicting records, resolved exact records, and locally diverged pending records that will not apply. A record skipped because a local opposite-kind object appeared during validation has no durable replay effects. Remaining records are reparsed/restaged on the validation worker after current kind/layer inputs change, then current store inputs are rechecked.
- A repeated live mutation wire ID ends its subgroup before the later record supersedes the earlier pending marker. This preserves adopted layer metadata needed by later records.
- Production WebSocket completion accounts for dequeued, in-progress application frames through output flush. Application and automatically generated PONG/CLOSE frames share allocation-time 16 MiB / 8,192-frame bounds. Overflow clears ledger state and terminates directly without recursively allocating a close frame. TLS delegate parameters and hostname verification remain intact.
- Replay writes, hello reservations, presence clean points, local mutation reservations, model-generation journal writes, and journal startup/rebind reads run on ordered persistence workers. Durable authority, network frames, members, peers, and models are published only after successful durability and current lifecycle/socket checks. Departed replay instances cannot overwrite counters belonging to a fresh join.
- Async replay capture and adoption use per-key patches and sparse read-only overlay views over authority held stable by the persistence mutex. Owner capture/adoption does not copy whole replay maps. Worker encoding scans the complete document as required for sealed storage; worker-derived presence metadata returns only changed actor entries. Generation-journal mutations also capture/adopt sparse changes.
- Every dispatching boundary flush rechecks socket/generation and foreground eligibility after suspension. Old admitted frames cannot dispatch after background entry or into a replaced manager state.
- Foreground journal startup/rebind is awaited before v3 activation. A second Activity pause invalidates the attach generation and detaches exact store identities; late attach completion cannot clear background gates or reconnect. Revision observers recheck captured store identities after awaiting startup load. Explicit legacy v2 joins retain their previous independence from the v3 generation journal.
- Background reconnect remains disabled (`BackgroundPresencePolicy.RECONNECT_ENABLED = false`); owner decision D1 remains unmade.
- Shared contract/fixture changes were authored by the iOS owner: chat count boundary, Android transport queue bound, and repeated-wire-ID subgroup semantics. Android assertions consume these shared values. No format, relay-frame, or authentication-header change was introduced.

## Regression and counterfactual evidence

`SyncAsyncPersistenceTest` exercises actual background executors and the real SafeStore naming/sealing path with held/failing writers. It covers responsive owner work; private pending authority; no premature hello, members, peer/model publication; background/leave/new-join fences; departed writer ordering; sparse owner work in a 2,000-record room; held journal startup reads; held foreground reload followed by another Activity pause; and explicit legacy join with a corrupt v3 journal.

The large-room test measures two captured key patches and two adopted key patches for one changed record, with zero runtime full-map copies. The complete sealed replay reload verifies the resulting stamp and content hash.

Counterfactuals were run by temporarily restoring the prior code paths, then restoring exact production bytes in `finally`:

| Removed fix | Failing evidence |
| --- | --- |
| Async sparse replay candidate; prior full runtime copy path restored | `replayCaptureAndAdoptionTouchOnlyEditedKeysInALargeRoom`: owner patch count expected 2, observed 0 (prior full-copy path); full-copy counter is instrumented in the runtime copy helper. |
| Worker startup load; prior synchronous startup helper restored | `managerStartupJournalReadDoesNotBlockTheOwnerOrExposeAnUnloadedJournal`: actual worker read never enters. |
| Post-flush current foreground/socket fence | `admittedBoundaryFrameCannotDispatchAfterBackgroundEntry`: old admitted frame closes background transport. |
| Attach invalidation and exact store-identity checks | `heldForegroundJournalReloadCannotReactivateAfterASecondPause`: late completion reactivates after the second Activity pause. |

Logs and XML: `residual-counterfactual.log`, `residual-counterfactual.xml`, `boundary-attach-counterfactual.log`, `boundary-attach-counterfactual.xml` in the scratch evidence directory. The leave/rejoin boundary regression also remains as coverage; its particular counterfactual schedule is already protected by the interim v2 protocol reset, so the directly failing boundary proof uses background entry.

Earlier source counterfactual proof for initial chat/pacer/snapshot changes: `regressions-before-fixes.log` (six tests: five expected failures; spare-budget leave already passed).

## Validation

Commands run from the workspace, with `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` for Gradle:

```sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
# Focused actual-worker, snapshot, transport, shared-fixture and reconnect runs:
./gradlew :app:testDebugUnitTest --tests 'com.tacmap.sync.SyncAsyncPersistenceTest' --tests 'com.tacmap.sync.SyncManagerSp3Test' --tests 'com.tacmap.sync.SyncTransportWriteCompletionTest' --tests 'com.tacmap.sync.SyncClientBehaviourFixtureTest' --tests 'com.tacmap.sync.SyncBackgroundReconnectTest'
```

- Full suite after sparse persistence and initial startup guards: **921 tests, 142 classes, zero failures/errors, one preexisting skip**. Debug and instrumentation APK builds passed.
- Latest full suite after background-boundary, second-pause and explicit-v2 regressions: **924 tests, 142 classes, zero failures/errors, one preexisting skip**; all 15 actual-worker regressions included. Debug and instrumentation APK builds passed.
- Earlier final sync-dialog device regression: API 36 `emulator-5562`, **PASS**, 8.604 seconds. No device execution occurred during the residual review/fix pass.
- `python3 scripts/generate_localizations.py --check`: PASS, native outputs current.
- `python3 scripts/check_localizations.py`: PASS; 368 source components, 56 reviewed literal occurrences, 1,967 messages and 21 plural families validated.
- `python3 testdata/tools/gen_sync_client_behaviour.py`: regenerated 41 scenario groups, fixture byte-identical. SHA-256 `46e1437fae8b9c871486436aa3da58feaf4ed456667923feaa76fba37fd4a2c2`.
- `git diff --check`: PASS.

Latest summary and copied test XML: `android-result.json` and `TEST-com.tacmap.sync.*.xml` in the scratch directory. APKs: `android/app/build/outputs/apk/debug/app-debug.apk` and `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.

## Native interop preparation and independent harness review

Android runner: `com.tacmap.test/androidx.test.runner.AndroidJUnitRunner`, class `com.tacmap.sync.TacMapChatLiveInteropTest`; root supplied fresh local relay, v3 join code, run ID and lifecycle phase. Only emulator-5562 was assigned. Enabled live execution completed after independent source review: local resume, independent local full pair and shipped TLS full pair each passed one test with zero failures and no skips on each native platform. The seed phase intentionally ended with actual host process termination after both clients persisted their exact fixture sets.

The Android/iOS harnesses perform signed presence, bidirectional waypoint/drawing create/edit/delete, authenticated room/direct chat, actual host process kill/relaunch, actual relay restart with a changed peer session, and Android background/foreground lifecycle flow. The later lifecycle extension was authored separately and independently reviewed here:

- Resume checks exact seeded waypoint/drawing names and counts **before join**, so relay refill cannot satisfy the persistence proof.
- Lifecycle chat barriers bind both body and authenticated sender actor.
- Seed prints a host kill-ready marker, waits at most 180 seconds, then fails if the host did not kill the process; resume reopens the same app-private sandbox and run-derived sealed-storage key.
- Relay restart waits for connected, chat-ready, changed signed peer session domain, then completes both-peer barrier.
- Background entry verifies chat pause and detached mission state; after 20 seconds it rejects the peer room-chat/model challenge. Reopened sealed stores must still lack the challenge before foreground reattachment. A fresh foreground snapshot must then converge the challenged model while background chat remains absent.
- Timeout paths fail with state diagnostics; the helper path exercises actual manager lifecycle policy, while OS screen-off itself remains a separate device-verification concern.

Native execution also confirmed reopened exact records before join, changed authenticated sessions after actual relay restart, retained signed Android background presence, rejected background direct chat, absent background room-chat/model effects in memory and reopened disk, foreground snapshot recovery, and final routed room/direct chat in both directions. Evidence is in `/Users/cbrooker/.claude/jobs/10094e99/tmp/codex-sync/interop`, with phase logs and `events.json` linked in `TASK2_INTEROP.md`. Own relay processes/listener and emulator reverse mapping were removed afterward.

The native harness exercises real production managers and transports on a simulator/emulator with isolated sealed test keys and synthetic past location observations. OS Activity/service callbacks, authentication key-lock policy, physical GPS and rendering remain Task 4 boundaries. The final threat-model pass, integration merge and version bump remain later sequential tasks.
