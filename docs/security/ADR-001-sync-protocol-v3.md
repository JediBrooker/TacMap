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
| Presence replay | Wall-clock timestamp freshness | Persisted monotonic counter + session domain |
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
- The relay pins `actorId -> pubkey` durably. The pubkey is transmitted in the first message's sealed payload (inside AEAD ciphertext, invisible to relay). Peers recompute actorId from the received pubkey to verify the binding.

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
  [0]      domain          (1 byte: 0x01=put, 0x02=delete, 0x03=presence)
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

The `sessionDomain` is generated once per WebSocket connection: `SHA-256(random_32_bytes)`. It is exchanged as a field in the first message after snapshot. It prevents cross-session replay even if an attacker observes a valid signed frame — the receiver tracks the current session domain per peer.

### 6. Wire format changes (v3 rooms)

Messages from client to relay:

```json
{"t":"put", "id":"<wireObjectId>", "vs":"<VersionStamp>", "by":"<actorId>",
 "kind":"<string>", "ct":"<base64>", "pub":"<base64url pubkey>"}

{"t":"del", "id":"<wireObjectId>", "vs":"<VersionStamp>", "by":"<actorId>",
 "kind":"del", "ct":"<base64>", "pub":"<base64url pubkey>"}

{"t":"loc", "by":"<actorId>", "ct":"<base64>", "pub":"<base64url pubkey>",
 "vs":"<presenceStamp>"}

{"t":"hello", "by":"<actorId>", "pub":"<base64url pubkey>",
 "sd":"<base64url sessionDomain>"}

{"t":"ping"}
```

The `"hello"` message is sent immediately after receiving `snapshot-end`. It registers the actorId/pubkey binding and announces the session domain to peers.

The `"pub"` field rides OUTSIDE the AEAD ciphertext in v3 (unlike v2 where it was inside). This allows the relay to pin actorId->pubkey without opening the seal. The pubkey is still verified by peers via actorId recomputation.

Messages from relay to client (additions to Phase 2 format):

```json
{"t":"hello", "by":"<actorId>", "pub":"<base64url pubkey>",
 "sd":"<base64url sessionDomain>"}
```

Broadcast to all other sockets in the room. The relay stores the pubkey binding durably.

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
  "presenceSeq": {
    "<actorId>": "<counterHex16>"
  },
  "sessionDomains": {
    "<actorId>": "<base64url sessionDomain>"
  }
}
```

**Invariants:**
- `leave()` closes transport and clears UI presence. Does NOT erase replay state.
- An explicit "forget room" action warns the user that rollback protection is lost, then deletes this file.
- A tombstoned wireObjId rejects any put with a lower-or-equal stamp, even after reconnect/restart.
- `localCounter` is incremented and persisted BEFORE sending. A crash between persist and send wastes one counter value (acceptable).

### 9. Counter advance window

To prevent permanent max-version pinning (SEC-022):

```
ADVANCE_WINDOW = 10_000
roomHighWater = max(all stamps.values + tombstones.values counter components)
```

On receiving a stamp with counter > roomHighWater + ADVANCE_WINDOW, reject the mutation. Log a warning — this likely means a hostile relay is injecting far-future stamps to freeze the room.

### 10. Snapshot gap detection

On receiving `snapshot-begin { seq }`:
- If `seq < lastSnapshotSeq` from replay state: the relay is serving older state than we've previously seen. Log warning. Still apply the snapshot (we can't prove omission without append-only logs), but surface in diagnostics.
- If `seq >= lastSnapshotSeq`: normal. Update `lastSnapshotSeq` to `snapshot-end.seq` after successful application.

No mutations from the client are permitted before `snapshot-end` is received and applied.

### 11. Relay actor registration

Storage key: `actor:<actorId>` -> `{ pubkey: string, firstSeen: number }`

On receiving a `hello` or first put/del/loc from an actorId:
- If no stored actor: store `{ pubkey, firstSeen: Date.now() }`.
- If stored and `pubkey != stored.pubkey`: reject with close code 4010 "actor key mismatch". This prevents key swap attacks at the relay level.

The relay does NOT verify Ed25519 signatures (it can't see the signed plaintext). Peers verify signatures after AEAD decryption.

### 12. Relay protocol version per room

Storage key: `meta:protocol` -> `2 | 3`

- Set on first connection to a room (v2 via `/room/`, v3 via `/v3/room/`).
- Subsequent connections must match. A v3 client connecting to a v2 room (or vice versa) receives 426 "Protocol mismatch".
- A room's protocol version never changes.

### 13. Migration

- v3 rooms use URL path `/v3/room/<roomId>`.
- v2 rooms remain on `/room/<roomId>`.
- Join codes are versioned: `3:ABCDEFGHJKMNPQRS`. The `3:` prefix is stripped before derivation. Codes without a prefix are v2.
- There is no in-place room upgrade. To move to v3, create a new room (new join code with `3:` prefix). The old v2 room continues working until idle-expired.
- Clients display legacy v2 rooms with a "Legacy room" indicator. Creating new rooms always generates v3 codes.

### 14. Trust boundary

A join-code holder can:
- Introduce new actorIds with their own signing keys (TOFU can't distinguish genuine new peers from fake ones).
- Create, modify, and delete any object they can derive the wire ID for.
- See all decrypted content in the room.

A join-code holder CANNOT:
- Impersonate an established actorId (pubkey pinned at relay + peers verify).
- Roll back a version stamp that a peer has already persisted in replay state.
- Forge a signature under another device's Ed25519 key.
- Replay a presence message from a previous session (session domain + counter).

The relay CANNOT:
- Read plaintext (no room key).
- Forge AEAD-sealed blobs (no room key).
- Swap actor keys (durable pin; peers recompute actorId from pubkey).
- Roll back state a client has previously seen (durable stamps + gap detection).
- Suppress the gap warning on stale snapshots (client-side check against persisted seq).

The relay CAN (residual risks):
- Omit updates (detected only if another peer communicates the gap out of band).
- Serve a stale snapshot to a brand-new/reinstalled client (no prior state to compare against).
- Observe co-membership, traffic timing, and connection metadata.

## Consequences

- v2 and v3 rooms are completely separate. No cross-protocol communication.
- Existing v2 rooms work indefinitely until idle-expired (7 days, per Phase 2).
- Client storage grows: sealed replay state file per room (~1-10 KB typically).
- Join codes are longer (2 chars for `3:` prefix).
- The relay stores actor registrations durably (one 100-byte record per device per room).
- PBKDF2 iteration cost is unchanged (210k). Argon2id upgrade is a separate future ADR.
