# Task 2 native interoperability verification

Status: ACCEPTED. Enabled real native interoperability passes on both local SP1 and the current shipped production TLS relay. Actual app process kill/relaunch, relay restart and Android manager background/foreground challenges pass. Native-discovered FR-13 is repaired and independently reviewed; final iOS unit suite 770/0/2 opt-in skips and Android 924/0/1 opt-in skip are separate from the enabled live results below.

Devices exclusively assigned: iOS simulator `6BADDA88-5E21-4E0B-983D-E69D22C5B6D8` and Android `emulator-5562`. No other emulator is touched.

Scratch evidence and orchestration: `/Users/cbrooker/.claude/jobs/10094e99/tmp/codex-sync/interop`.

## Executed coverage

Both existing opt-in native harnesses preserve normal full-mode signed presence, waypoint/drawing create/edit/delete, room/direct chat in both directions through the actual production managers, sealed stores, signing/encryption, bounded WebSocket transports and relay. The iOS manager receives its actual relay URL provider; Android sets and asserts the real self-host relay preference. Fresh random v3 room codes and run IDs isolate every run.

Additional test-only lifecycle phases use stable isolated sandbox paths/preferences keyed by the random run ID. The seed phase writes and authenticates two waypoints and two drawings on each native client then holds. The host physically kills each app process, including the hosted iOS test process. The resume phase asserts the same sealed record counts before joining, edits both ways, requires new authenticated peer sessions following a physical local relay restart, then checks Android background detach/chat pause and signed retained presence at iOS. A direct send to the background Android peer is rejected. Foreground attachment uses freshly reopened native stores and requires a fresh snapshot/chat session. No production debug hooks were added.

The Android background stage calls the actual production manager transition used by UnitSyncRuntime, with opted-in test GPS fixes. It proves native transport/presence/chat behavior, but does not claim an end-to-end physical GPS, Android foreground location service, or OS Activity callback test. Physical app process termination and relaunch are separate host actions.

## Commands and evidence

- Local relay prepared with `cd sync && npm ci && npx wrangler dev --port 8796 --local`; both native endpoints `ws://127.0.0.1:8796`, with only `emulator-5562` using `adb reverse tcp:8796 tcp:8796`. The shipped policy correctly rejects emulator alias cleartext. Original own relay PIDs: 93419, 93446, 93471.
- iOS `xcodebuild build-for-testing`: PASS, log `interop/ios-build.log`.
- Android `:app:assembleDebugAndroidTest`: PASS, log `interop/android-build-final.log`.
- Orchestrator: `python3 .../interop/native_run.py`, exact commands/events persisted in `interop/events.json`.
- Both native full-mode tests were explicitly enabled against SP1 local and the shipped production TLS relay `wss://tacmap-sync.christianbrooker.workers.dev`; all live phases completed without skips. The shipped full pair also proves the production TLS handshake; an additional redundant handshake-only smoke was not required.

No push, deployment, or version change.


## Diagnostic attempts before lifecycle acceptance

All attempts use fresh isolated random rooms and preserve the production policies/assertions. Evidence directories under `interop` retain each failure.

1. `attempt1-rejected-emulator-alias`: shipped cleartext URL policy rejects `ws://10.0.2.2`; route corrected through own emulator adb reverse and actual loopback preference.
2. `attempt2-missing-ios-location-permission`: actual simulator location permission absent; granted only own simulator app permission, then harness asserts the production authorization gate.
3. `attempt3-needs-component-diagnostics`: added per-component diagnostics without weakening expected signed presence or CRUD.
4. `attempt4-future-synthetic-android-fix`: test provider constructed a monotonic timestamp after the manager captured its freshness clock. Production correctly rejected future fixes. Both test providers now report genuine prior observations (100 ms old).
5. `attempt5-instrumentation-thread-chat-race`: Android test operations moved to Main ownership, matching real UI and pacer/session invariants; async waits yield Main. This correction did not resolve missing reverse chat.
6. `attempt6-main-owned-reverse-chat-routed`: Android room barrier is ROUTED with no failure, iOS sends routed barrier and Android receives it; both signed presence and both model kinds pass. iOS never stores the Android barrier.
7. `attempt7-raw-reverse-chat-observed`: test-only wrapper confirms actual Android chat arrives through real URLSession. Initial independent crypto observer incorrectly included `3:` prefix in room derivation; corrected to match production parser.
8. `attempt8-valid-reverse-chat-opened`: actual raw Android frame arrives; captured peer key verifies, session/key tuples match, actual production signature/AEAD/payload open succeeds. iOS manager still does not store it and shows no chat issue.
9. Preserved proof run `059578f1ed8696bfe22b42b4`: test-only Mirror confirms actual manager `chatPeerKeys=0` while published `chatRecipients=1` when connected. Raw production crypto open succeeds. Source diagnosis is early peer hello/chat-key preceding local hello-ack, followed by stale staged recipients being republished after local chat startup clears peer keys. Routed to iOS owner for deterministic red regression and repair; native execution was held until the deterministic regression, repair and independent approval. The final native run below passes.

The transparent transport observer changes neither messages nor delivery. It logs only test-room identifiers, boolean endpoint checks and error class; no keys. No production hooks or wire changes. The persisted synthetic chat body diagnostics only contain the verifier's random-room fixtures.


## Final enabled live acceptance (2026-10-02, Sydney)

Rebuilt final iOS production/harness and Android app/instrumentation artifacts after the independently accepted FR-13 repair. Build logs: `interop/ios-build-fr13-native-final.log` and `interop/android-build-native-final.log`. Orchestrator `interop/native_run.py` exited 0. Exact arguments, environment fixture values, PIDs and UTC events: `interop/events.json`, with the readable execution stream in `interop/orchestration.log`.

| Phase | Random run ID | Native result | Executed acceptance |
| --- | --- | --- | --- |
| Local seed | `5c25e19e36a97aaa4520168f` | Both native hold markers reached; intentionally killed before test teardown | Signed presence and waypoint/drawing CRUD both directions, zero records after CRUD deletes, then sealed exact two waypoints/two drawings from both platforms |
| Local resume | Same run | iOS 1 test/0 failures, Android 1 test/0 failures; exit codes `[0,0]`, no skips | Exact named records reopened before join; edits after real app kill; new authenticated peer sessions after actual relay restart; background/foreground challenges; both-way room/direct chat |
| Independent local full pair | `8b2024e4af5b0e8a51b74fc1` | iOS 1/0, Android 1/0; `[0,0]`, no skips | Signed presence, waypoint/drawing CRUD each direction and authenticated barriers; final records zero; both-way room/report and direct/text messages with routed acknowledgments |
| Current shipped TLS full pair | `77444f3f37f734ccd425a049` | iOS 1/0, Android 1/0; `[0,0]`, no skips | Same native full coverage using production WSS relay and a separate isolated random test room |

Logs and result bundles: `interop/local-seed-{ios,android}.log`, `interop/local-resume-{ios,android}.log`, `interop/local-full-{ios,android}.log`, `interop/production-full-{ios,android}.log`; iOS bundles use the matching phase name plus `.xcresult`. Android native markers and instrumentation process IDs are preserved in `interop/android-logcat.log`. The seed screenshot is `interop/local-seed-ios.png`; it is a host screenshot, not a claim about physical device UI acceptance.

At 00:25:45 UTC the host successfully executes Android `am force-stop com.tacmap` and iOS `simctl terminate ... com.tacticalmaps.app`, then launches new native test processes. Resume logs on both sides assert the exact `LIFE_IOS_WP`, `LIFE_ANDROID_WP`, `LIFE_IOS_DRAW`, `LIFE_ANDROID_DRAW` sets (two of each kind) before any network join. At 00:25:48 the host stops its original three relay PIDs, then launches a fresh local Wrangler process at 00:25:50 against the same local relay state. Both clients require a different authenticated peer session domain and receive real native barriers afterward.

Android background acceptance explicitly proves signed retained presence, direct-chat rejection at iOS, detached stores, no background room-chat receipt or model apply in either memory/reopened disk, and fresh foreground snapshot convergence of the missed model challenge. The ephemeral room-chat challenge remains absent after foreground. Fresh room/direct messages subsequently pass in both directions. A relay-restart barrier reached the peer but its local ack was interrupted by immediate background detach; its outgoing entry becomes `FAILED/disconnected`, consistent with live-only transport. Final room/direct message acknowledgments are independently required to be routed and pass.

FR-13 diagnosis and repair are retained in `TASK2_IOS_FOLLOWUP_REVIEW.md` and `TASK2_FINAL_REVIEW.md`: early valid peer chat authority survives initial local key establishment; full teardown clears actual and staged/published recipients. Final real native logs show actual peer-key count and published recipient count agree, and actual production chat open plus manager persistence both succeed.

Cleanup verified after the successful run: all owned original/restarted relay processes stopped, no listener at TCP8796, and no reverse mapping on the owned emulator. `interop/cleanup-proof.json` records the checks. No other emulator, user room, push, deployment, or version change was used. Devices and source are released/frozen for the next sequential task.

The native proof uses simulator/emulator production managers and transports, fixed isolated at-rest test keys, and synthetic past observations. It establishes native durability/protocol/lifecycle flow, not production OS-authentication key-lock policy, physical GPS, OS Activity/service delivery or physical-device rendering. Those remain Task 4 acceptance boundaries.
