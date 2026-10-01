# ADR-001: Sync Protocol v3

**Status**: Accepted  
**Date**: 2026-07-11  
**Addresses**: SEC-002, SEC-003, SEC-004, SEC-008, SEC-016, SEC-022

## Summary

Replace the v2 sync wire protocol with v3: room-scoped cryptographic identities, a deterministic binary signed preimage, string-typed version stamps, durable client-side replay state, and a relay-verified auth binding. The relay remains E2E-blind (never sees plaintext).

## Motivation

v2 weaknesses this ADR closes:

| Problem | v2 behavior | v3 fix |
|---|---|---|
| Cross-room device correlation | UUID reused across rooms | Room-scoped actorId derived from pubkey + room |
| Object correlation across rotated rooms | Local UUID visible to relay | Wire object ID = HMAC(metadataKey, localUUID) |
| Impersonation via replay | In-memory TOFU cleared on leave | Durable per-room actor pins |
| Replay after local deletion | Versions cleared on disconnect | Durable per-object stamp + tombstone state |
| Split-brain on equal version | Lower `by` wins (arbitrary UUID) | VersionStamp: hex counter + actorId, lexicographic tiebreak |
| Presence replay | Wall-clock timestamp freshness | Persisted accepted-hello high-water + per-session counter/domain |
| Max-version pinning (SEC-022) | Unbounded Lamport clock | Counter advance window relative to room high-water |
| Delimiter ambiguity in preimage | U+001F-joined strings | Typed, length-prefixed binary preimage |
| Relay can't verify auth/room relationship | Blind TOFU token pin | roomId = SHA-256(prefix \|\| authToken) — verifiable |

## Decision

### 1. Key derivation

All values derived from a single join code. The join code for v3 rooms is prefixed with `3:` (e.g., `3:ABCDEFGHJKMNPQRS`); the prefix is stripped before derivation.

```
master      = PBKDF2-HMAC-SHA256(joinCode, "tacmap-sync-salt-v3", 210_000, 32)
authToken   = HMAC-SHA256(master, "tacmap-auth-v3")              # 32 bytes
roomIdRaw   = SHA-256("tacmap-room-id-v3\0" || authToken)        # 32 bytes
roomId      = base64url-no-pad(roomIdRaw)                        # 43 chars, URL path
roomKey     = HMAC-SHA256(master, "tacmap-roomkey-v3")            # 32 bytes, AES-256-GCM
metadataKey = HMAC-SHA256(master, "tacmap-metadata-v3")           # 32 bytes, wire obj IDs
```

The relay receives `base64url-no-pad(authToken)` in the `Authorization: Bearer` header. On TOFU pin, the relay verifies:

```
SHA-256("tacmap-room-id-v3\0" || decode(bearerToken)) == roomIdRaw
```

If verification fails, the connection is rejected with 403. This prevents a stolen token from being used to create a different room.

### 2. Room-scoped actor ID

```
actorId = base64url-no-pad(
  SHA-256("tacmap-actor-v3\0" || roomIdRaw || ed25519PublicKeyRaw)
)
```

Properties:
- Self-certifying: anyone with roomIdRaw + pubkey can recompute the actorId.
- Room-scoped: same device key in different rooms produces different actorIds.
- 43-character base64url string (256-bit hash).
- The relay recomputes this value for every v3 actor announcement from the room path and raw public key. It durably pins `actorId -> pubkey` only after a valid signed `hello` proof. A socket cannot send v3 mutations or presence until that proof succeeds.

### 3. Wire object IDs

```
wireObjectId = base64url-no-pad(
  HMAC-SHA256(metadataKey, "tacmap-wire-obj-v3\0" || localObjectUUID_bytes)
)
```

- 43-character base64url string.
- The relay sees only derived wire IDs, never local UUIDs.
- Different metadataKey (different room) -> different wire ID for the same object.
- `localObjectUUID_bytes` = the 16 raw bytes of the UUID (not the string form).

### 4. VersionStamp

```
format: counterHex16 ":" actorId
example: "0000000000000042:dGFjbWFwLWFjdG9yLXYzAC4uLg"
```

- `counterHex16`: exactly 16 lowercase hex digits representing a non-negative 63-bit integer (0 to 2^63 - 1 = 9,223,372,036,854,775,807 = `7fffffffffffffff`).
- Comparison: parse counter as unsigned 64-bit from hex; higher counter wins. On equal counter, lexicographically greater actorId wins.
- Wire format: transmitted as a JSON string in field `"vs"`. Never a JSON number.
- Signed as the raw 16 ASCII bytes of counterHex16 (included in preimage).

### 5. Signed preimage (binary, typed, length-prefixed)

All signatures use Ed25519 over this deterministic binary preimage:

```
Byte layout:
  [0]      domain          (1 byte: 0x01=put, 0x02=delete, 0x03=presence, 0x04=hello)
  [1]      protocol        (1 byte: 0x03 for v3)
  [2..33]  roomIdRaw       (32 bytes)
  [34..35] actorIdLen      (2 bytes, little-endian uint16)
  [36..36+N-1] actorIdBytes (N bytes, UTF-8 of the base64url actorId string)
  [+0..+31] sessionDomain  (32 bytes, SHA-256 of random 32 bytes generated per connect)
  [+0..+15] counterHex     (16 bytes, ASCII of counterHex16)
  [+0..+1] objectIdLen     (2 bytes LE; 0 for presence)
  [+0..+M-1] objectIdBytes (M bytes, UTF-8 of wireObjectId or empty for presence)
  [+0]     kindLen         (1 byte, uint8)
  [+0..+K-1] kindBytes    (K bytes, UTF-8; "put"/"del" for objects, "loc" for presence)
  [+0..+31] payloadHash   (32 bytes, SHA-256 of plaintext before AEAD seal)
```

For **object put**: domain=0x01, kind=UTF-8("put") or the object's kind string (e.g. "waypoint"), payloadHash=SHA-256(GeoJSON plaintext).

For **object delete**: domain=0x02, kind=UTF-8("del"), payloadHash=SHA-256("") = `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.

For **presence**: domain=0x03, objectIdLen=0, objectIdBytes=empty,
kind=UTF-8("loc"), and payloadHash=SHA-256 of the exact payload bytes carried
by `p` in the encrypted presence envelope defined below. Receivers hash those
decoded bytes directly; parsing and reserializing them before verification is
forbidden.

The optional backwards-compatible **presence retention advertisement** also
uses domain=0x03 and the same actor/session/counter, but kind is
UTF-8("loc-retention") and payloadHash is the SHA-256 of the exact `pr` bytes
described below. The distinct kind prevents either signature from being reused
as the other.

For **hello**: domain=0x04, `counterHex` is the actor's positive monotonic
session epoch, objectIdLen=0, objectIdBytes=empty, kind=UTF-8("hello"), and
payloadHash=SHA-256(raw 32-byte Ed25519 public key). The hello epoch is a
separate unsigned 64-bit value encoded as exactly 16 lowercase hexadecimal
digits (`0000000000000001` through `ffffffffffffffff`). It is not an object
`VersionStamp` counter and therefore is not limited to signed-63-bit range.

The `sessionDomain` is generated once per WebSocket connection:
`SHA-256(random_32_bytes)` and authenticated by the signed `hello`. Clients
persist the accepted remote epoch, session domain, and presence counter. A
higher valid hello changes session and resets that counter; an equal hello may
only reactivate the identical persisted session, without resetting it. This
does not prove that an unseen, higher signed epoch is the actor's newest epoch:
a malicious relay can present an obsolete but genuinely signed higher session
unless the client has an external transparency log or out-of-band trust anchor.
Durable put/delete records also carry their `sd` outside the ciphertext because
a late joiner must have the exact session domain used by the inner signature;
omitting it makes an otherwise valid snapshot unverifiable. The inner Ed25519
signature authenticates the outer `sd`.

### 6. Wire format changes (v3 rooms)

Messages from client to relay:

```json
{"t":"put", "id":"<wireObjectId>", "vs":"<VersionStamp>", "by":"<actorId>",
 "kind":"<string>", "ct":"<base64>", "pub":"<base64url pubkey>",
 "sd":"<base64url sessionDomain>"}

{"t":"del", "id":"<wireObjectId>", "vs":"<VersionStamp>", "by":"<actorId>",
 "kind":"del", "ct":"<base64>", "pub":"<base64url pubkey>",
 "sd":"<base64url sessionDomain>"}

{"t":"loc", "by":"<actorId>", "ct":"<base64>", "pub":"<base64url pubkey>",
 "sd":"<base64url sessionDomain>", "vs":"<presenceStamp>"}

{"t":"hello", "by":"<actorId>", "pub":"<base64url pubkey>",
 "sd":"<base64url sessionDomain>", "vs":"<positiveHelloEpochHex>:<actorId>",
 "sig":"<base64url Ed25519 signature>"}

{"t":"ping"}
```

For `loc`, opening `ct` with the presence AAD produces this inner envelope:

```json
{
  "pv": 1,
  "p": "<standard-base64 exact UTF-8 presence-payload JSON bytes>",
  "lat": 0.0, "lon": 0.0, "heading": 0.0, "speed": 0.0,
  "callsign": "", "affiliation": "UNKNOWN", "echelon": "TEAM",
  "function": "INFANTRY", "isHQ": false,
  "pub": "<base64url pubkey>", "sig": "<base64url Ed25519 signature>",
  "prv": 1, "pr": "<standard-base64 exact {\"ttl\":seconds} bytes>",
  "prsig": "<base64url Ed25519 signature>"
}
```

`pv` MUST be `1`. `p` MUST use canonical standard Base64 with padding. Its
decoded bytes are the authoritative payload and MUST be hashed without
transformation for the presence signed preimage. After signature verification,
the receiver parses those same bytes for the displayed presence. The flat
presence fields are duplicates retained for legacy-v3 compatibility; senders
include them during migration. `pub` and `sig` remain encrypted. The inner
`pub` MUST equal the outer `pub`, which is independently bound to `by`.

The `prv` / `pr` / `prsig` trio is optional for compatibility. When any field
is present, all MUST validate: `prv` is exactly `1`; `pr` is canonical standard
Base64 containing a JSON object with only integer `ttl` in the inclusive range
45...3900 seconds; and `prsig` verifies the `loc-retention` preimage above.
Absent fields mean a 45-second lifetime. Updated receivers may retain the
last-known marker for the signed lifetime only while the exact `by` + `sd`
session remains active, capped at 3900 seconds. Partial, malformed, or invalidly
signed retention metadata rejects the entire location frame.

The `"hello"` message is sent immediately after validating `snapshot-end`. The
client increments and persists its per-actor hello epoch before signing or
sending; a crash can waste an epoch. The relay recomputes `actorId`, verifies
the Ed25519 signature, and transactionally requires the epoch to be greater
than the actor record's stored epoch. It then persists the latest epoch and
complete signed hello frame before binding the WebSocket. Equal or older epochs
close with 4014 and never bind. A newly accepted epoch supersedes older live
sockets for that actor.

After both durable registration and socket binding complete, the relay replies
to the announcing socket with
`{"t":"hello-ack","by":"<actorId>","sd":"<sessionDomain>","vs":"<helloEpoch>:<actorId>"}`.
The client remains snapshot-gated and sends no mutation or presence until this
acknowledgement exactly matches its current actor and per-connection session
domain. This prevents the first queued mutation from racing the relay's
asynchronous proof verification and actor-pin write.

The `"pub"` field also rides outside the AEAD ciphertext in v3. This allows the
relay to pin actorId->pubkey without opening the seal. Peers still recompute
actorId and require the encrypted inner `pub` to match the outer value.

Messages from relay to client (additions to Phase 2 format):

```json
{"t":"hello", "by":"<actorId>", "pub":"<base64url pubkey>",
 "sd":"<base64url sessionDomain>", "vs":"<positiveHelloEpochHex>:<actorId>",
 "sig":"<base64url Ed25519 signature>"}
```

Broadcast to all other sockets in the room only after relay verification. On late join, durable records are sent inside the fenced snapshot. After `snapshot-end`, the relay sends each currently active signed `hello` and current presence frame individually; ephemeral actor data is never mixed into durable snapshot pages.

### 7. AEAD changes

- Key: `roomKey` (same derivation pattern, different label).
- AAD for objects: `wireObjectId || ":" || vs || ":" || kind` (UTF-8 bytes).
- AAD for presence: `"loc:" || actorId || ":" || vs` (UTF-8 bytes).
- AAD for deletes: `wireObjectId || ":" || vs || ":del"` (UTF-8 bytes).
- Wire encoding of sealed blob: base64 standard (same as v2): `iv(12) || ct || tag(16)`.

The AAD binds the ciphertext to its routing metadata. Any relay manipulation of id/vs/kind breaks decryption.

### 8. Durable replay state

Each client persists per-room state, sealed at rest via SafeStore (label `"sync/room/<roomId>"`):

```json
{
  "schemaVersion": 3,
  "localCounter": "0000000000000001",
  "lastSnapshotSeq": 42,
  "stamps": {
    "<wireObjId>": "<VersionStamp>"
  },
  "tombstones": {
    "<wireObjId>": "<VersionStamp>"
  },
  "contentHashes": {
    "<wireObjId>": "<sha256hex>"
  },
  "actors": {
    "<actorId>": {
      "pubkey": "<base64url ed25519 pubkey>",
      "confirmed": false,
      "firstSeen": 1720000000
    }
  },
  "helloEpochs": {
    "<actorId>": "<16-char lowercase u64 hex>"
  },
  "pendingModelApplications": {
    "<wireObjId>": {
      "vs": "<VersionStamp>", "pub": "<base64url ed25519 pubkey>",
      "deleted": false, "hash": "<sha256hex>",
      "priorHash": "<sha256hex-or-null>", "localId": "<uuid-or-null>",
      "generation": "<16-char local-model generation>"
    }
  },
  "presenceSeq": {
    "<remoteActorId>": {
      "sd": "<base64url sessionDomain>",
      "counter": "0000000000000001"
    }
  }
}
```

**Invariants:**
- `leave()` closes transport and clears UI presence. Does NOT erase replay state.
- An explicit "forget room" action warns the user that rollback protection is lost, then deletes this file.
- A tombstoned wireObjId rejects any put with a lower-or-equal stamp, even after reconnect/restart.
- The complete local outbound mutation reservation (counter, stamp, actor pin,
  and exact content hash or tombstone) is persisted BEFORE sending. A crash
  before send can waste one counter value (acceptable).
- Before applying an accepted remote mutation to the app model, the replay
  transaction also persists a per-object pending marker containing the exact
  mutation, local UUID, prior canonical model hash (or absence), and the accepted
  app-global local-model generation. The generation journal is sealed separately
  from room state and updated for every waypoint/drawing edit, delete, or recreate
  even while disconnected or after Leave. Android stores emit every mutation to
  a lossless non-conflated channel (including inverse edit/revert and
  create/delete operations); initial load emits nothing. iOS's lifetime store
  observer establishes its first snapshot as a baseline. Remote sync operations
  are tagged/suppressed individually rather than using a time window, so a
  concurrent local event is never dropped.
  After model
  apply is verified, the marker is durably cleared. On restart, an exact snapshot
  record repairs the model only when its matching pending marker remains: if the
  model matches the incoming state, clear the marker; otherwise, if the global
  generation changed, preserve the local state regardless of hash equality; only
  with an unchanged generation may a recorded prior-state match be applied. Any
  other state preserves the offline
  edit/recreate/delete, clear the marker, and publish that local state at a new
  higher stamp after hello acknowledgement. Exact records without pending work
  establish an echo baseline but never overwrite the current model. Persistence
  failure at acceptance or marker clearing fails closed.
- Once a durable mutation is authenticated, set `localCounter = max(localCounter, acceptedCounter)` and persist it. After a completed snapshot, the first local mutation therefore reserves a counter strictly above the authenticated snapshot high-water.
- Local outbound presence uses a separate, in-memory per-WebSocket counter
  starting at 1. It never advances the durable object counter or relay room
  high-water.
- The local actor's hello epoch is incremented and durably reserved before each
  new WebSocket hello.
- For a remote actor, a valid hello with a higher epoch atomically persists the
  new epoch and
  `presenceSeq[actorId] = { sd, counter: "0000000000000000" }`. A valid hello
  whose epoch equals the persisted epoch may reactivate that session after a
  local reconnect only when its `pub` and `sd` exactly match the persisted actor
  and session; it MUST NOT reset the counter. Lower epochs and equal epochs
  with different session context are rejected.
- Remote presence is accepted only after that hello, when its `by`, `pub`, and
  `sd` match the active persisted session and its counter is strictly greater
  than the persisted presence counter (and within the advance window). Persist
  the new counter before exposing the peer in UI. `leave()` and process restart
  may clear the UI but MUST NOT clear this replay state. Consequently, the
  relay's cached equal-counter `loc` after reconnect is ignored; the sender's
  next higher periodic `loc` safely repopulates the UI.

### 9. Counter advance window

To prevent permanent max-version pinning (SEC-022):

```
ADVANCE_WINDOW = 10_000
roomHighWater = max(all authenticated stamps.values + tombstones.values counter components)
```

On receiving a live stamp with counter > roomHighWater + ADVANCE_WINDOW, reject the mutation. Snapshot records are first strictly decoded, actor-bound, AEAD-opened and signature-verified as a set; their authenticated maximum establishes the reconnect baseline before the live advance window is enabled. This permits a legitimate late join to a mature room without trusting the relay's unsigned `highWater` hint.

The relay applies the same window to durable writes against its own
`meta:highWater`. Idle expiry and tombstone compaction keep that value (§16),
so a client whose durable counter is far above the live records in a quiet
room is still inside the window when it returns. The only reset is the idle
purge after `ROOM_PURGE_TTL_MS` (§16), which deletes the whole room: a device
returning after it is more than `ADVANCE_WINDOW` ahead of the fresh room and is
nacked `counter-window`, and has to move to a new join code.

That only covers the relay side. Expiry and compaction remove the records that
carried the high-water, so a snapshot no longer always contains a record at the
relay's `highWater`. A brand-new joiner after expiry can therefore have an
authenticated baseline more than `ADVANCE_WINDOW` below a returning mature
actor, and its own window then drops that actor's live put/del until it
reconnects and gets the stored records in a snapshot. This is not a regression
(before SP1 the relay nacked that writer outright with `counter-window`,
S1-01), but shipped clients drop silently. SP2 requirement: on a live window
rejection the client resyncs (reconnects for a fresh snapshot) instead of
dropping, and it still never trusts the relay's `highWater` hint.

### 10. Snapshot gap detection

On receiving `snapshot-begin { seq, highWater }`:
- If `seq < lastSnapshotSeq` from replay state: the relay is serving older state
  than previously seen. Log and surface a rollback diagnostic. Apply only
  individually authenticated records that beat durable local mutations, plus
  complete exact matches carrying a matching pending-model marker needed to
  repair a persist-before-model crash; never apply a merely equal stamp, or an
  exact record whose pending work was already resolved.
- After a fully valid matching `snapshot-end`, persist `lastSnapshotSeq = max(previousLastSnapshotSeq, snapshotEnd.seq)`. A stale fence can never lower durable state.
- `highWater` is an untrusted relay hint for diagnostics only. The client computes its enforcement baseline from authenticated snapshot records.
- An honest relay never lowers `seq`, with one documented exception: after
  `ROOM_PURGE_TTL_MS` (90 days) without activity it deletes the whole room
  (§16), and a device returning after that sees a fresh room at `seq` 0. That
  is indistinguishable from a malicious rollback, so the client raises the
  diagnostic above; the guidance is to move to a new join code (SP2 clients
  will surface this as its own state). Idle expiry and tombstone compaction
  (§16) remove records without a mutation frame, so each pass that removes
  anything advances `seq` and records it in `meta:horizonSeq`. A later
  resume-from-seq extension must send a full snapshot to any client whose last
  seq is below that horizon, or above the room's current `seq`.

No mutations from the client are permitted before `snapshot-end` is received and applied.

Clients process snapshot pages as a stream and enforce all of these independent
ceilings before committing the snapshot:

- at most 10,000 records across all pages;
- at most 52 MiB (54,525,952 bytes) of cumulative UTF-8 snapshot-frame text;
- at most the per-frame and per-record ciphertext ceilings already specified.

The same wire object ID appearing twice anywhere in one fenced snapshot is a
protocol error, even when both records are byte-identical. The client rejects
the entire snapshot, retains its previous durable replay state, and reconnects;
it must not apply a relay-chosen duplicate ordering. `snapshot-end.seq` must
exactly match `snapshot-begin.seq`, and no record or byte counters reset between
pages.

### 11. Relay actor registration

Storage key: `actor:<actorId>` -> `{ pubkey, firstSeen, lastSeen, helloEpoch, hello }`, where
`hello` is the complete latest verified signed hello frame and `lastSeen` is the
UTC hour of the latest accepted hello (used only by tombstone compaction, §16).
Actor pins count toward both `MAX_RECORDS` and `MAX_STORED_BYTES`, as do live
objects and retained tombstones. Idle expiry deletes every actor pin (§16).

On receiving `hello`, the relay first strictly decodes `by/pub/sd/vs/sig`, recomputes actorId from roomId+pubkey, and verifies the hello signature. Only then:
- If no stored actor and quota permits: atomically store the pubkey, first-seen
  time, positive epoch and signed frame, updating record/byte accounting.
- If stored and `pubkey != stored.pubkey`: reject with close code 4010 "actor key mismatch".
- If stored and incoming epoch is not strictly greater: reject with close code
  4014 "stale hello epoch" without changing the actor record.
- If proof is invalid: reject with close code 4011. No pin is written.

The relay verifies only the public signed hello proof. It cannot verify encrypted put/delete/presence payload signatures; peers do that after AEAD decryption.

### 12. Relay protocol version per room

Storage key: `meta:protocol` -> `2 | 3`

- V2 Durable Object name: the raw room ID. V3 Durable Object name:
  `"v3:" + roomId`. URL paths remain `/room/<roomId>` and `/v3/room/<roomId>`.
  The namespace prefix prevents a v2 room from preclaiming the same visible v3
  room ID and token/protocol metadata.
- Set on first connection inside that protocol-scoped object.
- Subsequent connections must match. A v3 client connecting to a v2 room (or vice versa) receives 426 "Protocol mismatch".
- A room's protocol version never changes.

### 13. Migration

- v3 rooms use URL path `/v3/room/<roomId>`.
- v2 rooms remain on `/room/<roomId>`.
- Join codes are explicitly versioned: `3:ABCDEFGHJKMNPQRS` for v3 and
  `2:ABCDEFGHJKMNPQRS` for intentional legacy v2. The prefix is stripped before
  derivation. Unprefixed codes are rejected; there is no silent v2 downgrade.
- There is no in-place room upgrade. To move to v3, create a new room (new join code with `3:` prefix). The old v2 room keeps working; once idle it is expired like any other room (§16).
- Pre-release dormant v3 objects created before protocol-scoped DO names used
  the raw room ID. They are intentionally not migrated: the new relay sees a
  clean `v3:<roomId>` object, and the old dormant object goes through idle
  expiry like any other room (§16): its live objects and pins are deleted,
  its tombstones are compacted later, and the rest is wiped by the 90-day idle
  purge (or at once if it never accepted a write). Test/staging users must
  recreate those rooms. These objects predate activation and contain no
  released v3 room data.
- v3 generation is active and generated codes use `3:`. Clients display an
  explicit warning and require confirmation for legacy `2:` rooms.

### 14. Trust boundary

A join-code holder can:
- Introduce new actorIds with their own signing keys (TOFU can't distinguish genuine new peers from fake ones).
- Create, modify, and delete any object they can derive the wire ID for.
- See all decrypted content in the room.

A join-code holder CANNOT:
- Impersonate an established actorId (pubkey pinned at relay + peers verify).
- Roll back a version stamp that a peer has already persisted in replay state.
- Forge a signature under another device's Ed25519 key.
- Activate an older hello session or substitute different context at the
  accepted epoch. Replaying the exact current hello is idempotent; its presence
  still requires a counter strictly above the client's durable high-water.

The relay CANNOT:
- Read plaintext (no room key).
- Forge AEAD-sealed blobs (no room key).
- Swap actor keys (durable pin; peers recompute actorId from pubkey).
- Roll back state a client has previously seen (durable stamps + gap detection).
- Suppress the gap warning or lower actor epoch on a client that has retained
  its sealed replay state.

The relay CAN (residual risks):
- Omit updates (detected only if another peer communicates the gap out of band).
- Serve a stale snapshot to a brand-new/reinstalled client (no prior state to compare against).
- Replay an obsolete but genuinely signed hello/session whose epoch is higher
  than a client's retained high-water but which that client has not seen. This
  includes brand-new/reinstalled clients with no high-water. Without an external
  transparency log or out-of-band trust anchor, the client cannot distinguish
  that session from the actor's newest session. The honest relay's durable epoch
  check stops this at ingress, but it is not a cryptographic guarantee against a
  malicious relay controlling its own stored state.
- Observe co-membership, traffic timing, and connection metadata.

### 15. Relay delivery contract

Every limit below is published in `testdata/sync_protocol_v3.json`
`relayLimits.values`, which the relay suite pins to `sync/src/limits.ts`.
Clients must pace below them (recommended budget: `relayLimits.clientPacing`).

- **Frames.** At most `MAX_FRAME_BYTES` UTF-8 bytes per frame (close 4009).
  Object `ct` is at most `CT_MAX` characters of padded standard base64,
  presence `PRESENCE_CT_MAX`, chat `CHAT_CT_MAX`; a larger object is nacked
  `invalid`, `retry:false`.
- **Rate window.** Per socket, a *fixed* `RATE_WINDOW_MS` window that opens at
  the first frame and resets on the first frame after it expires, so up to
  twice `RATE_MAX_MSGS` frames can pass across one boundary. It allows
  `RATE_MAX_MSGS` frames and `RATE_MAX_BYTES` bytes and counts every frame,
  including malformed, unknown, ping, presence and retries. Exceeding it closes
  the socket 4008 and drops its queued frames without acknowledgement. The same
  numbers bound the per-socket processing backlog.
- **Room backlog.** At most `ROOM_PENDING_MAX_MSGS` frames and
  `ROOM_PENDING_MAX_BYTES` bytes may wait for processing across the room. When
  a frame would exceed that, the socket with the largest backlog (counting the
  arriving frame on its sender) is closed 4008; the arriving frame is admitted
  unless its own socket was the largest. Residual risk: largest-backlog is a
  heuristic, not an identity check. A member holding several bound actor
  identities can keep each of its backlogs just under an honest peer's natural
  burst (for example the pending-op and own-tombstone resend right after
  `hello-ack`), so the honest peer is the one closed, on every reconnect. Each
  identity costs a quota record and is a visible member session. A socket
  with no bound actor only holds a backlog while its own `hello` is being
  verified and pinned (everything else it sends is nacked or dropped without
  waiting), after which it is either bound, as one identity, or closed. SP2
  client pacing (`clientPacing`, including the post-`hello-ack` resend) shrinks
  the honest burst that can be targeted.
- **Admission.** At most `MAX_CONNECTIONS` open sockets. Sockets the relay has
  already closed do not count toward that, but every accepted socket, open or
  closed-without-echo, counts toward a hard cap of `MAX_ACCEPTED_SOCKETS`
  (503 `Room full`), since those still cost memory until their transport dies.
  When the room is full, v3 sockets that have not sent an accepted `hello` by
  their deadline are closed 1013, most overdue first, to make room. The
  deadline is `HELLO_DEADLINE_MS` after the upgrade plus the time to drain the
  snapshot the socket was sent at `HELLO_DEADLINE_BYTES_PER_SEC` (a 50 MB
  snapshot adds about 500 s), because shipped clients only say `hello` after
  `snapshot-end`. A joiner on a link slower than that rate can still be
  displaced from a full room and has to reconnect. Upgrade failures use the
  HTTP statuses in `relayLimits.upgradeStatus`; 503 and 429 are transient.
- **Acknowledgements.** A `put`/`del` carrying `rid` gets exactly one
  `op-ack {t, av:1, rid, by, sd, id, vs, kind, cth}` once it is durable (or was
  already stored byte-identically), or one
  `op-nack {t, av:1, rid, by?, sd?, code, retry}`. Codes and retry flags are
  listed in `relayLimits.opNackCodes`: `invalid`, `hello-required` (retry),
  `session-mismatch`, `session-replaced`, `stale`, `counter-window`, `quota`,
  `storage` (retry). `not-found` is reserved: it only arises for a delete
  without `rid`, which is never nacked.
- **Departures.** `leave {by, sd}` carries exactly one of `transient: true`
  (connection lost or closed by the relay), `explicit: true` (verified signed
  leave) or `replaced: true` (a newer hello from the same actor). The relay
  announces the transient departure itself before any relay-initiated close,
  because a close callback is not guaranteed when the client never echoes it.
  A session fenced by an in-flight replacement is announced by the replacement
  path instead: `replaced` when the replacement binds, or `transient` if the
  replacement fails after the relay closed the fenced socket (it is never
  re-advertised). A `hello` whose socket the relay closes while it is still
  being verified or pinned binds nothing and retires no other session. Live
  fan-out (`put`/`del`/`loc`/`hello`/chat) skips sockets the relay has closed.
- **Close codes.** Listed in `relayLimits.closeCodes`; the relay suite fails if
  the source can send a code the fixture does not document.

- **Snapshot memory.** Snapshot builds run one at a time (inside
  `blockConcurrencyWhile`, which now never throws: a failed build fails only
  the joining request with 503). Storage reads behind a build adapt to what
  they have seen: the first read takes `STORAGE_FIRST_PAGE_SIZE` records and
  each later read is sized so the previous page's *average* record would fill
  about `STORAGE_READ_BUDGET_BYTES` (at most `STORAGE_PAGE_SIZE` records).
  That is best effort, not a cap: the relay cannot see a record's size before
  reading it. With uniformly large records the largest read measured 4.0 MB
  (24 x 500 KB), but a room whose small records sort before large ones still
  pulls up to `STORAGE_PAGE_SIZE` large records in one read (measured 40 MB for
  4 small then 60 x 667 KB), bounded only by the 50 MB byte quota, the same
  worst case as the fixed 100-record read before SP1. Wire object IDs are
  hashes, so size order is effectively random in real rooms. The finished
  pages, up to about 50.4 MB for a full room, are still queued on the joining
  socket before the 101 and drain at the client's pace. The relay cannot
  observe drain progress, so several slow joiners of a full room can each hold
  a full copy outbound (and `MAX_ACCEPTED_SOCKETS` bounds how many). Bounding
  that needs streaming after the 101 behind a per-socket fence or
  resume-from-seq, both deferred to SP4.

These rules are wire-compatible with every shipped v3 client: no frame gained
a field, no new frame type or nack code is sent, and existing close codes keep
their meaning. 1013 is new but only reaches a v3 socket with no accepted
`hello` (a hello still being verified or pinned binds nothing, so it counts as
none). The one new upgrade response is 503 `Room reset during join` (§16),
which clients treat like any other transient 503.

### 16. Relay retention, idle expiry, idle purge and tombstone compaction

What the relay stores per room: `meta:auth` (token hash), `meta:protocol`,
`meta:seq`, `meta:highWater`, record/byte counters (plus
`meta:accountingSchema` in v3), `meta:lastActivity`, each object's latest
sealed record, tombstones, actor pins (§11), and relay-only bookkeeping:
`tomb:<wireObjId>` (the UTC hour the delete landed), `meta:tombIndexAt`,
`meta:expiredAt` (time of the last idle expiry), `meta:horizonSeq` (`seq` of
the last expiry or compaction), `meta:droppedPinsSeen` (one hour value, below)
and, only while an expiry pass is unfinished, `meta:expiring`. Stored and
snapshotted records keep exactly the §6 shape; bookkeeping is never sent to
clients.

`meta:tombIndexAt` marks that the room's tombstones have `tomb:` rows. Rooms
created by an SP1 relay store 0 there, so nothing that survives idle expiry
records when the room was created (pin `firstSeen` times go with the pins). A room created before SP1 gets the hour its first SP1 maintenance pass
indexed it (about the deploy time, not its creation); that hour is the
conservative last-seen floor for its legacy pins and authors, which have no
recorded hello time, and it goes with the room at the idle purge.

`meta:lastActivity` is written at most once per `ACTIVITY_PERSIST_MS` for
joins, accepted writes and hellos, whenever the relay sees the last open socket go
(its own close, the client's close, or a transport error), and by the
maintenance alarm, which runs at least every `MAINTENANCE_INTERVAL_MS` while
any socket is open. Presence, chat and rejected frames never write it.

**Idle expiry.** About `IDLE_TTL_MS` (7 days) after the last persisted
activity, with no socket open, the relay deletes every live object record and
every actor pin. It keeps `meta:auth`, `meta:protocol`, `meta:seq` (advanced),
`meta:highWater`, the counters (rewritten exactly), `meta:lastActivity` and
all tombstones. A returning client therefore sees a snapshot fence no lower
than before, stays inside the relay's counter window (§9 covers fresh joiners),
and its peers' deletes still win over its stale copies. Shipped clients keep
local objects that are absent from a snapshot and republish what they hold the
next time they rebuild their local diff (after an app restart or unlock, or on
the next edit); until one of them does, a brand-new joiner sees only the
tombstones.

A room that never accepted a write (`seq` 0, no tombstone, zero high-water)
has nothing a returning device could be rolled back on, so expiry deletes it
entirely, as before SP1. v3 room IDs are bound to the token (§1), so the fresh
trust-on-first-use pin on its next use cannot be squatted; v2 behaves as it
always did.

Every other room keeps its meta rows (about a dozen, no mission content) and
its remaining tombstones until the idle purge. While they exist the retained
token hash, like the routing ID itself, lets whoever holds relay storage
confirm a join-code guess at one PBKDF2 derivation per guess.

Crash safety: each pass writes the advanced `seq`, `meta:horizonSeq`,
`meta:droppedPinsSeen` and the `meta:expiring` marker in one transaction
before deleting anything, so `seq` is already past whatever a pass removes even
if it dies half way. A failed pass makes the alarm come back after
`MAINTENANCE_RETRY_MS` (each retry that still removes something advances `seq`
again), and a join while the marker is set recounts the counters exactly
before admitting the socket.

**Idle purge.** About `ROOM_PURGE_TTL_MS` (90 days) after the last persisted
activity (after the last idle expiry if no activity was ever recorded), with
no socket open, the relay deletes everything it holds for the room with one
`deleteAll`: meta rows, bookkeeping, every remaining tombstone, and then the
alarm. Nothing is left, so room storage is bounded by rooms used in the last
90 days, whatever `ROOM_LIMITER` lets through. Any activity in between that
moves `meta:lastActivity` (as listed above) restarts both the 7-day and the
90-day clock. Every alarm pass that leaves an idle room still holding
anything re-arms the next idle deadline (expiry or purge), so no room is
stranded without one; a room whose very first join fails right after its
trust-on-first-use pin also gets an alarm at pin time and is wiped like a
drive-by room.

A device returning after the purge gets a fresh room: `seq` 0 and zero
`highWater`, no tombstones. Its `snapshot-begin.seq` is below its
`lastSnapshotSeq`, so shipped clients raise the §10 rollback diagnostic,
which a client cannot tell apart from a malicious rollback; its own deletes
and its peers' are gone from the relay; and if its counters had passed
`ADVANCE_WINDOW` its writes are nacked `counter-window` (§9). The guidance is
to move to a new join code. SP2 clients are to recognise a fresh room under
an old join code and say so plainly instead of only warning. In v2 whoever
connects first afterwards pins the fresh room, as at idle expiry before SP1;
v3 room IDs are bound to the token (§1), so only holders of the join code can.

Crash safety: `deleteAll` is atomic on the SQLite storage backend this relay
is configured for (`new_sqlite_classes` in `wrangler.jsonc`), so a failed or
interrupted purge leaves the room exactly as it was. The deadline is
recomputed from durable state on the next pass, which comes after
`MAINTENANCE_RETRY_MS` (or the runtime's own alarm retry if the isolate died),
and a join in between simply restarts the clock. The compat date predates
`deleteAll` removing alarms, so the relay deletes the alarm itself; an alarm
that survives anyway finds an empty room and writes nothing. The purge and
the drive-by wipe run under `blockConcurrencyWhile`, and a join re-checks the
pinned token hash and protocol under the same lock just before it is
admitted, so a join that passed its auth check before a wipe gets 503
`Room reset during join` instead of a socket in an unpinned room; its
reconnect pins a fresh room the normal way.

**Tombstone compaction.** A tombstone is removed once it is older than
`TOMBSTONE_TTL_MS` (30 days) **and** its author has not sent an accepted hello
for `TOMBSTONE_TTL_MS` and has no open session right now. The author condition
is required for compatibility: shipped clients resend every own tombstone that
a snapshot does not confirm, so compacting a live author's tombstone would only
make it come back on the next reconnect, in a burst that can trip the rate
window. Compaction decrements the counters exactly and advances `seq`.

How the author's last hello is known:

- v3 author with a pin: the pin's `lastSeen` hour.
- v3 author without a pin: pins only go at idle expiry and any hello since
  would have created a new one, so the author was last seen no later than the
  newest `lastSeen` among all pins any expiry dropped (`meta:droppedPinsSeen`,
  a running maximum written before the first delete of each pass, so a pass
  that dies half way still recorded it). The relay uses the earlier of that and
  the room's last activity. Precision is traded for
  keeping less: no per-device time survives expiry, so a departed author's
  tombstone can stay until 30 days after the latest hello of any device whose
  pin was dropped, even while other devices keep using the room.
- v2: there are no pins, so every author counts as last seen at the room's last
  activity. v2 tombstones are only compacted once the whole room has been
  unused for 30 days; a v2 room in steady use keeps them (bounded by
  `MAX_RECORDS`), as before SP1.

**Trade-off.** A device that was offline longer than `TOMBSTONE_TTL_MS`, still
holding an object that another (by then departed) device deleted, can publish
it again and the relay accepts it. Peers that kept the tombstone in their
replay state reject it, so those peers and new joiners disagree about that
object until it is edited or deleted again. Tombstones of active authors are
not compacted in SP1; that needs a client change (SP2) so clients stop
resending own tombstones older than the TTL.

The reverse case: a departed author that comes back after its tombstones were
compacted resends every own tombstone the snapshot does not confirm, all at
once right after `hello-ack` (both shipped clients do). With more than about
`RATE_MAX_MSGS` of them that trips the rate window (4008) roughly once per 200
before it converges, and the relay stores them all again with fresh `tomb:`
rows, undoing the compaction for that author. Before SP1 the 7-day wipe caused
the same burst for every author. SP2 removes it: the post-`hello-ack` resend
goes through the client pacer, and own tombstones older than the TTL are not
resent once a relay capability says compaction is in force.

**Relay-version dependence.** Nothing in-band tells a client which retention
semantics a relay implements, and pre-SP1 relays (including self-hosted ones
on older code) still wipe the whole room at idle expiry. Client behaviour that
relies on this section (not resending own tombstones older than the TTL,
treating `seq` as non-rolling across an expiry, relying on retained
tombstones, or reading a fresh room as an idle purge rather than a rollback)
must be gated on a negotiated relay capability, introduced with the SP2/SP4
work, and fall back to pre-SP1 assumptions without it.

## Consequences

- v2 and v3 rooms are completely separate. No cross-protocol communication.
- Rooms (v2 and v3) idle for 7 days lose their live objects and actor pins but keep sequence, high-water and tombstone state (§16); rooms that never stored anything are deleted outright. Tombstones are compacted after 30 days once their author is gone (v2: once the room has been unused for 30 days). After 90 idle days the whole room is deleted, and a device returning after that needs a new join code.
- Client storage grows: sealed replay state file per room (~1-10 KB typically).
- Join codes are longer (2 chars for `3:` prefix).
- The relay stores the latest signed hello and epoch with each durable actor
  registration; this larger record is included in room byte accounting.
- PBKDF2 iteration cost is unchanged (210k). Argon2id upgrade is a separate future ADR.
