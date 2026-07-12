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

For **presence**: domain=0x03, objectIdLen=0, objectIdBytes=empty, kind=UTF-8("loc"), payloadHash=SHA-256(canonical presence JSON bytes).

For **hello**: domain=0x04, `counterHex` is the actor's positive monotonic
session epoch, objectIdLen=0, objectIdBytes=empty, kind=UTF-8("hello"), and
payloadHash=SHA-256(raw 32-byte Ed25519 public key). The hello epoch is a
separate unsigned 64-bit value encoded as exactly 16 lowercase hexadecimal
digits (`0000000000000001` through `ffffffffffffffff`). It is not an object
`VersionStamp` counter and therefore is not limited to signed-63-bit range.

The `sessionDomain` is generated once per WebSocket connection: `SHA-256(random_32_bytes)`. It is authenticated by the signed `hello`. Together with a client's persisted accepted-hello epoch high-water, it rejects presence from a session at or below that high-water. It does not prove that an unseen, higher signed epoch is the actor's newest epoch: a malicious relay can present an obsolete but genuinely signed higher session unless the client has an external transparency log or out-of-band trust anchor. Durable put/delete records also carry their `sd` outside the ciphertext because a late joiner must have the exact session domain used by the inner signature; omitting it makes an otherwise valid snapshot unverifiable. The inner Ed25519 signature authenticates the outer `sd`.

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

The `"pub"` field rides OUTSIDE the AEAD ciphertext in v3 (unlike v2 where it was inside). This allows the relay to pin actorId->pubkey without opening the seal. The pubkey is still verified by peers via actorId recomputation.

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
  "presenceSeq": {}
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
- Presence uses a separate, in-memory per-WebSocket counter starting at 1. It never advances the durable object counter or relay room high-water. A new signed session domain resets presence high-water.
- The local actor's hello epoch is incremented and durably reserved before each
  new WebSocket hello. For remote actors, accept and persist a signed hello only
  when its epoch is greater than the stored value. Reject its presence before
  this check and never lower a stored epoch.

### 9. Counter advance window

To prevent permanent max-version pinning (SEC-022):

```
ADVANCE_WINDOW = 10_000
roomHighWater = max(all authenticated stamps.values + tombstones.values counter components)
```

On receiving a live stamp with counter > roomHighWater + ADVANCE_WINDOW, reject the mutation. Snapshot records are first strictly decoded, actor-bound, AEAD-opened and signature-verified as a set; their authenticated maximum establishes the reconnect baseline before the live advance window is enabled. This permits a legitimate late join to a mature room without trusting the relay's unsigned `highWater` hint.

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

Storage key: `actor:<actorId>` -> `{ pubkey, firstSeen, helloEpoch, hello }`, where
`hello` is the complete latest verified signed hello frame. Actor pins count
toward both `MAX_RECORDS` and `MAX_STORED_BYTES`, as do live objects and retained
tombstones.

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
- There is no in-place room upgrade. To move to v3, create a new room (new join code with `3:` prefix). The old v2 room continues working until idle-expired.
- Pre-release dormant v3 objects created before protocol-scoped DO names used
  the raw room ID. They are intentionally not migrated: the new relay sees a
  clean `v3:<roomId>` object and the old dormant object expires under normal
  idle TTL. Test/staging users must recreate those rooms. These objects predate
  activation and contain no released v3 room data.
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
- Replay a hello or presence session whose epoch is at or below the returning
  client's durably retained accepted-hello high-water.

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

## Consequences

- v2 and v3 rooms are completely separate. No cross-protocol communication.
- Existing v2 rooms work indefinitely until idle-expired (7 days, per Phase 2).
- Client storage grows: sealed replay state file per room (~1-10 KB typically).
- Join codes are longer (2 chars for `3:` prefix).
- The relay stores the latest signed hello and epoch with each durable actor
  registration; this larger record is included in room byte accounting.
- PBKDF2 iteration cost is unchanged (210k). Argon2id upgrade is a separate future ADR.
