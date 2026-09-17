# ADR-002: Mission-Key Lifetime, Rotation, and Recording-Only Access

**Status**: Accepted; physical-device validation and rotation-cleanup follow-up pending

**Date**: 2026-08-16

**Addresses**: release 1.2.3 recording, location, App Lock, mission-key lifetime, and access-control rotation contracts

## Summary

TacMap normally makes the mission data-encryption key (DEK) available only while
mission data is unlocked. A track recording explicitly started by the user is
the one exception: it may retain a private in-memory key value long enough to
append encrypted fixes after the general mission key has been locked.

That exception is deliberately narrow. It authorizes only the already-started
track log and location session. It does not authorize waypoints, drawings,
imported maps, Unit Sync, exports, or a new recording. The recording key is
released on every known terminal path, and only the `recording` state may render
`REC`.

The current implementation scopes the retained key by ownership and lifetime,
not by cryptographic derivation: both platforms retain the same 256-bit mission
DEK value used by other sealed stores. Android keeps a copied `ByteArray` and
explicitly fills it with zero before release. iOS keeps a private `Data` value
and releases it, but Swift does not guarantee that every backing copy is
overwritten. A derived or separately wrapped track-only key is follow-up work.

## Context and threat boundary

TacMap encrypts mission stores with one installation-specific DEK. The DEK is
device-bound at rest:

- Android stores only a wrapped DEK in app-private preferences. Its wrapping
  KEK is non-exportable in Android Keystore.
- iOS stores the DEK as a `ThisDeviceOnly` Keychain item protected by the
  platform class-key hierarchy. It does not sync or restore onto another device;
  an encrypted backup can restore it to the same device.

The default device-bound mode prevents a plain application-file copy or ordinary
cross-device restore from yielding a plaintext mission store. Its strength for a
powered-down or seized device depends on the platform and device implementation;
Android hardware backing is not asserted without `KeyInfo` verification. It does
not claim protection from code already executing as TacMap on a live rooted or
jailbroken device. The optional auth-bound mode additionally asks the platform
Keystore/Keychain to require device-owner presence before key use.

Background recording creates a tension: locking all key material on Home or
screen lock would make durable location appends impossible, while retaining the
general mission-key cache would keep unrelated data available. This ADR defines
the smaller `recordingOnly` exception and its honest limitations.

## Decision

### 1. Use five logical mission-key states

These are security-contract states, not a requirement for one shared source-code
enum. Platform UI and recording reducers may use more detailed transient states.

| State | Meaning | Permitted work | Exit conditions |
|---|---|---|---|
| `locked` | General mission data is unavailable to the UI. In auth-bound mode the platform also refuses a DEK read without authentication. | Show an opaque lock/unlock surface; preserve encrypted files untouched. | User begins unlock, or an unrecoverable key condition is detected. |
| `unlocking` | Device credential, biometric, PIN gate, or key recovery/read is in progress. | Read and verify existing key records; no optimistic store publication and no replacement DEK. | Verified key -> `unlocked`; cancellation -> `locked`; permanent failure -> `unrecoverable`. |
| `unlocked` | The current mission DEK is available and mission stores may be read or written. | Normal map, store, import/export, and Unit Sync operations, subject to their own gates. | Background/App Lock policy -> `locked` or `recordingOnly`; permanent key failure -> `unrecoverable`. |
| `recordingOnly` | The general mission-key boundary is locked, but a user-authorized active recorder still owns its private in-memory key value. | Append and fsync encrypted fixes to the already-prepared track log; keep only the platform location/background mechanism needed for that session. | Stop, discard, append failure, permission/GPS loss, service teardown, activation timeout, or process termination. |
| `unrecoverable` | Prior-install/key evidence exists but the platform key, wrapping key, metadata, or sentinel can no longer be authenticated. | Preserve ciphertext and explain that it cannot currently be opened. | No automatic exit. Recovery or destructive reset requires a separately designed user flow. |

`locked` is strongest in auth-bound mode. In device-bound mode Android can
locally unwrap the key again without a prompt, and iOS can read its
after-first-unlock Keychain item. UI teardown and call-site discipline still
enforce the lifecycle boundary, but they are not a hardware authorization
boundary. App Lock and auth-bound mission-data protection are separate controls.

An existing initialized install must never silently mint a new DEK when its old
key record is missing. Doing so would make preserved ciphertext look merely
corrupt and invite overwrite. Both platforms distinguish a fresh install from
this `unrecoverable` condition.

### 2. Rotate access control without re-encrypting mission files

Changing the “require unlock to decrypt mission data” setting re-protects the
same DEK; it does not rewrite every mission store.

Android writes a wrapped copy into the inactive slot, synchronously commits it,
reads the exact record back, and unwraps and validates its versioned payload and
authenticated DEK sentinel. It then creates and validates a dedicated
user-auth-required Android Keystore alias as a non-preference AUTH-mode floor.
While that alias exists, record selection refuses every DEVICE slot regardless
of the mutable active/backup preference pointers. Only after the anchor exists
does Android commit and read back both AUTH pointers, synchronously remove and
read back every inactive/legacy wrapped slot, and delete and read back deletion
of the weaker DEVICE KEK.

A failure before the Keystore anchor leaves DEVICE authoritative. A failure or
process death after the anchor can deny access until recovery, but cannot select
a DEVICE wrapper. On the next authenticated AUTH unwrap, cleanup is retried.
Disabling follows the reverse safe order: Android persists, reads back, unwraps,
and validates a DEVICE record, commits and reads back its pointers while the
AUTH anchor still blocks that prepared downgrade, and deletes the anchor last.
Thus restoring an older SharedPreferences file can cause denial of service but
cannot silently undo an already anchored AUTH transition.

iOS writes the same DEK into the inactive Keychain account with the target
access control and reads the exact 32 bytes back. It then stores and reads back
pending Keychain metadata naming both the replacement and prior account. The
final order is deliberately directional:

- DEVICE → AUTH deletes and verifies absence of the prior DEVICE-readable slot
  before final AUTH metadata can be committed or displayed.
- AUTH → DEVICE commits and reads back honest DEVICE metadata first; deletion
  of the redundant stronger AUTH slot is best-effort and cannot weaken the
  published DEVICE posture.

An interrupted transition remains pending, blocks mission-store reads, and is
externally reported as conservative DEVICE until startup can finalize or safely
roll it back. Rollback never deletes the replacement until prior metadata has
committed and read back. Metadata-less upgrades preserve both legacy slots;
only a successful user-initiated 32-byte DEK read may select a slot, and a
corrupt saved candidate can fall back to the still-preserved alternate. Thus no
cleanup step can delete the only recoverable legacy DEK merely from mutable
defaults or Keychain item presence.

### 3. Treat recording scope as a capability, not a general unlock

The recording-owned value is available only inside `TrackRecorder`. Background
append APIs receive it explicitly and must not call the global `DataKey` or a
general `SafeStore` key provider. Locking the general key therefore cannot cause
a background callback to reopen mission stores.

The present value is still the raw mission DEK. Class ownership, private API
surface, session generation, and prompt zero/release reduce exposure time, but
they do not cryptographically prevent a memory disclosure from opening another
mission store. A future format should use a random track data key wrapped by the
mission DEK, or a domain-separated derived key bound to versioned log metadata.
That change needs an explicit log-format and recovery migration; substituting a
new key silently would make existing tracks unreadable.

### 4. Android recording authorization and durability ordering

Android uses this order:

1. A visible `MainActivity` receives the user action. No ViewModel or background
   caller can directly start the recording service.
2. The Activity resolves `Precise`, `ApproximateOnly`, or `Denied`, confirms GPS
   is enabled, and checks the foreground-location service prerequisites. On API
   34+ this includes the manifest foreground-service permissions and the
   service's `location` type.
3. The recorder enters `Starting`. It marks the log as sealed-only, fsyncs an
   empty temporary log, and atomically replaces the target.
4. Only after that durable preparation does it obtain a copy of the mission DEK,
   copy it into recorder-owned memory, zero the supplied temporary array, assign
   a session generation, and arm a generation-bound ten-second activation
   watchdog.
5. `TrackRecordingService` enters the foreground, verifies precise permission
   and GPS again, and successfully registers its `GPS_PROVIDER` listener.
6. The service acknowledges the same generation. Only then does the reducer
   enter `Recording` and allow the UI to display `REC`.

Repeated Activity requests while `Starting` or `Recording` are no-ops. Repeated
service commands for the active authorized generation are ignored rather than
stopping the service. A stale watchdog cannot stop an activated or newer
generation.

Each accepted fix is sealed and appended with the recorder-owned key, flushed,
and `fsync`ed before it is published to observers. Stop, discard, append error,
permission loss, GPS loss, activation timeout, or an unexpected service destroy
clears the session generation and fills the retained key array with zero.
Expected stop clears that authority before stopping the service, so the later
`onDestroy` callback is ignored and cannot recursively stop or re-interrupt it.

The foreground service is `START_NOT_STICKY`. It is continuity within the live
process/session, not a promise that Android will recreate a killed recording.

### 5. iOS recording coordinator and scoped-key behavior

iOS uses a `RecordingCoordinator` with `idle`, `awaitingPermission`, `starting`,
`recording`, and `interrupted` states:

- `notDetermined` enters `awaitingPermission` and requests When-In-Use access;
- denied/restricted stays idle and offers Settings guidance;
- authorized When-In-Use or Always enters `starting` exactly once;
- a permission loss while starting/recording stops and preserves the log;
- an append failure or other unexpected recorder stop becomes `interrupted`.

During durable start, `TrackRecorder` reads the current key, refuses to replace a
non-empty saved log, marks the log sealed-only, atomically creates an empty file
with complete-until-first-authentication protection, and synchronizes its file
handle. It then retains the private key value. Only after that succeeds does the
coordinator enable `allowsBackgroundLocationUpdates` and publish `recording`,
from which the UI derives `REC`.

Background appends use only `TrackRecorder.recordingKey`; they do not call the
global key provider. Stop, discard, start failure, append failure, or permission
revocation assigns `nil` to that value. This releases the scoped reference but,
unlike Android's mutable array, is not a guarantee that Swift has overwritten
every copy of the bytes.

`ContentView` remains mounted beneath the App Lock overlay so an authorized
recording and its location manager are not destroyed merely because the UI
locks. When auth-bound mission protection is enabled, leaving the active scene
clears the global `DataKey` cache; the recorder's private value continues. In
device-bound mode iOS does not clear that cache on every scene transition.

### 6. Bound location, foreground, and notification permissions

Android recording requires precise foreground location. Approximate-only and
denied states never start recording and provide explanation, retry, and app
Settings paths. GPS-disabled state provides a location-services Settings path.
The separate “Enable My Location” action owns normal live-map permission
onboarding; it does not enter the recording reducer.

Android intentionally does not request `ACCESS_BACKGROUND_LOCATION`. The
user-visible location foreground service, ongoing notification, and explicit
start action are the background-continuity mechanism. It uses the platform
`GPS_PROVIDER`, not fused or network location.

On Android 13+, `POST_NOTIFICATIONS` is requested contextually with recording.
Denial is explained once but is not reported as a recording failure: Android may
hide the drawer notification while still exposing the foreground service in
system Active apps. Foreground-service creation, precise permission, and GPS
must still succeed independently.

iOS uses When-In-Use location plus the declared background-location mode,
enabling background updates and the system background-location indicator only
for an active recording. iOS has no equivalent notification permission for this
flow.

Neither platform promises recording through force-stop, user force-quit,
process termination, reboot, revoked permission, or OS policy termination. The
fsynced encrypted log is recovered on a later unlocked launch; the active
session and memory key are not.

### 7. Store the iOS App Lock credential as one checked record

App Lock's PIN credential is independent of the mission DEK. Its current record
is one versioned `WhenUnlockedThisDeviceOnly` Keychain item containing the salt
and PIN hash. Failure-count and lock-until throttle values remain separate
Keychain items; “one record” refers to the credential pair, not all App Lock
state.

New PIN/update ordering is:

1. generate the salt and encoded versioned record;
2. read the previous combined record;
3. update or add the replacement;
4. read back and compare the exact encoded bytes;
5. on failed verification, attempt to restore the previous bytes or delete an
   unverified newly added item, and report failure.

Legacy migration reads the split Keychain salt/hash or the older UserDefaults
pair, writes the combined record, verifies exact bytes, and only then deletes
legacy artifacts. If migration or cleanup fails, verification falls back to the
still-working legacy credential. A verified combined record anchors cleanup
retries and is not rewritten unnecessarily.

Disabling App Lock first verifies the PIN and establishes a verified combined
anchor when only legacy state exists. Fallible legacy/throttle cleanup runs
before deleting the active combined credential. A cleanup error therefore keeps
App Lock enabled. An inconclusive Keychain read also reports App Lock as enabled,
which is the fail-closed choice.

## Platform parity contract

| Contract | Android | iOS |
|---|---|---|
| Truthful recording states | Awaiting permission, starting, recording, interrupted, idle | Awaiting permission, starting, recording, interrupted, idle |
| `REC` publication | After foreground service and GPS listener acknowledge the prepared generation | After durable recorder start and background updates are enabled |
| Background append key source | Explicit recorder-owned `ByteArray` copy | Explicit recorder-owned `Data` value |
| General-key behavior at lock | Activity pause clears cache in both modes; device mode can unwrap locally again | Cache clears on inactive/App Lock only in auth-bound mode |
| Retained-key termination | Explicit zero-fill and release | Reference release; byte overwrite is not guaranteed |
| Location authorization | Precise only; approximate/denied guidance | When-In-Use or Always; denied/restricted guidance |
| Background mechanism | Location foreground service; no background-location grant | `allowsBackgroundLocationUpdates` and system indicator |
| Forced termination | Session ends; recover durable log later | Session ends; recover durable log later |

Native APIs and permission copy differ, but both platforms must preserve the
same security outcomes: no `REC` before durable/background activation, no
global-key reacquisition by a background append, truthful interruption, and no
claim of force-quit survival.

## Verification

### Automated checks

Android tests must cover:

- cold-restart preference rollback after AUTH enable, including an old DEVICE
  active/backup pointer with and without the persisted AUTH candidate;
- every commit, read-back, verification, anchor, wrapper-removal, and KEK-
  deletion failure boundary, proving post-anchor failures never select DEVICE;
- DEVICE wrapper and KEK deletion only after verified AUTH activation, and AUTH
  anchor deletion only after a verified DEVICE record is durably active;
- log preparation before retained-key capture and no key capture when recovery
  or durable preparation is rejected;
- explicit-key append while the global provider throws;
- `REC` only after service acknowledgement;
- duplicate Activity/service starts, generation supersession, stale watchdogs,
  activation timeout, unexpected teardown, and non-recursive expected stop;
- retained-key removal on stop, failure, discard, permission loss, and timeout;
- precise, approximate-only, denied, revoked, GPS-disabled, notification-neutral,
  and API 34 preflight policy states.

iOS tests must cover:

- no `REC` while authorization is pending, one durable start after grant, and no
  duplicate start on repeated authorization callbacks;
- denied/restricted Settings guidance and clean mid-session revocation;
- durable-log failure and append failure never leave recording active;
- background append after the global key provider is unavailable, plus scoped
  key release on normal and error paths;
- checked App Lock update/add/read-back, legacy migration ordering, partial
  cleanup retry, disable ordering, and preservation/fail-closed behavior under
  injected Keychain failures.

Boolean test seams prove that a recorder no longer owns a key reference. They do
not inspect residual allocator memory. Android's explicit `fill(0)` is also
subject to normal managed-runtime limitations and is not a hardware erasure
claim.

### Physical-device matrix

Before release, exercise:

1. Android API 26, 29, 33, 34, and 36; iOS 16.3 and the current iOS release;
2. precise/approximate/denied, permission upgrade, permission revocation, GPS or
   Location Services disabled, and Settings return;
3. Android notification allowed and denied, foreground-service activation,
   duplicate start delivery, activation timeout, and unexpected service teardown;
4. Home, app switcher, screen lock, App Lock, auth-bound unlock cancellation,
   and successful reauthentication during an active and inactive recording;
5. write failure, full disk, protected/unavailable file, process kill, crash,
   force-stop/force-quit, reboot, and later encrypted-log recovery;
6. VoiceOver/TalkBack, large text, landscape, actionable Settings/retry copy, and
   confirmation that only true recording state shows `REC`;
7. access-control rotation in both directions, interrupted writes/metadata
   selection, cold-start recovery, and confirmation that obsolete key slots are
   not usable as an authorization downgrade.

## Known implementation differences and mismatches

1. The Phase 2 plan calls the recording value “recording-scoped.” The current
   scope is lifetime/API scope around a raw copy of the general mission DEK, not
   a derived track-only cryptographic key. This ADR records the limitation; a
   versioned wrapped/derived track key remains follow-up work.
2. Android explicitly zero-fills its retained mutable key. iOS releases a
   `Data` reference and cannot currently prove byte-for-byte zeroization. Tests
   on both platforms primarily prove loss of ownership, not allocator erasure.
3. Android makes AUTH a user-auth-required Keystore-anchored floor, verifies
   cold-start selection, and checks deletion of the obsolete DEVICE wrapper and
   KEK after activation. iOS uses read-back-verified pending metadata and checks
   Keychain deletion before an AUTH upgrade is published. A redundant stronger
   AUTH item may remain after entering DEVICE mode, but cannot weaken that mode;
   an unresolved weaker item forces conservative DEVICE reporting and blocks
   mission-store reads until recovery.
4. Both apps request foreground live-map permission on the first map
   presentation. Recording remains a separate explicit action. Background
   location/service activation occurs only for an active recording or for a
   joined v3 room when the user separately enables both location sharing and
   Background Unit Sync location.
5. The plan's physical-device checklist remains open. Host/unit tests and builds
   do not prove OEM foreground-service behavior, OS key prompts, background
   delivery, real Keychain/Keystore failure, or post-kill recovery.
6. The plan describes the five mission-key states, but current source uses
   platform-specific key flags and recording reducers rather than one literal
   shared state type. The table in this ADR is the normative mapping.

## Relationship to other decisions

- [ADR-001: Sync Protocol v3](ADR-001-sync-protocol-v3.md) remains authoritative
  for Unit Sync keys, replay state, and wire behavior. `recordingOnly` never
  grants permission to start or resume Unit Sync or to read its sealed state.
- [ADR-003: Store Egress and Durable Entitlement](ADR-003-dark-egress-and-entitlement.md)
  remains authoritative for store lifecycle contact and offline entitlement.
  Billing/StoreKit state is separate from the mission DEK and never receives the
  recording key or track/location payload.

## Consequences and follow-up

- An explicitly started recording can continue across Home/App Lock while
  unrelated mission stores are lifecycle-locked.
- Durable-before-visible ordering makes `REC` a statement about an active
  persistence/background path rather than user intent alone.
- Failure paths preserve the already-fsynced encrypted track and stop claiming
  that new fixes are being saved.
- The raw-DEK recording copy and rotation-cleanup gaps bound the current
  assurance. Design a versioned track-only key format and verified obsolete-slot
  cleanup before claiming cryptographic least privilege or rollback-resistant
  access-control rotation.
- Record the physical-device matrix as release evidence. Do not convert a host
  unit-test result into a claim of survival after force-stop or force-quit.
