# ADR-004: TacMap Chat v1

**Status**: Accepted
**Date**: 2026-08-27
**Applies to**: TacMap Chat v1 (introduced in release 2.0.0), Sync Protocol v3 rooms only

## Summary

TacMap Chat adds short live messages and reports to an active Unit Sync room.
It has two visibly different recipient scopes:

- **Entire room** is encrypted under a chat-specific subkey of the v3 room
  key. Every holder of the join code can decrypt it, whether or not the relay
  happened to route the frame to that device.
- **Selected unit** uses an authenticated X25519 session secret between the two
  selected v3 actors. The relay, other room members, and other join-code
  holders cannot decrypt it.

Chat v1 is deliberately live-only. The relay routes ciphertext to currently
connected, chat-capable sessions and never writes chat keys, messages, reports,
or drafts to Durable Object storage. There is no offline mailbox, attachment,
delivery/read receipt, or silent room fallback. Local history and replay state
are sealed under the mission `DataKey`.

## Context

Sync Protocol v3 authenticates room-scoped actors with Ed25519, binds each
WebSocket to a signed session domain, and encrypts map objects and presence with
one room key. Reusing that room key directly for a selected-unit message would
not provide direct-message confidentiality: every join-code holder has the
room key.

TacMap also cannot treat a callsign as an address. Callsigns are encrypted,
mutable display strings. The stable address within one v3 room is the
self-certifying `actorId`, together with the actor's currently authenticated
session domain and chat key ID.

The design therefore extends v3 rather than introducing a v4 room. Existing v3
map/presence behavior remains interoperable, while chat requires upgraded
clients and a relay that returns the chat capability acknowledgement below.

## Decision

### 1. Generate one ephemeral X25519 key per live v3 session

After an exact, verified `hello-ack`, an unlocked client generates a fresh
X25519 keypair in memory. It does not derive this key from the Ed25519 seed and
does not persist the private key. It clears/releases the private key on socket
replacement, disconnect, Leave, App Lock/DataKey lock, or manager disposal.

The public key is advertised in this exact JSON object; unknown keys are not
permitted:

```json
{
  "t": "chat-key", "cv": 1,
  "by": "<actorId>", "sd": "<base64url sessionDomain>",
  "kx": "<base64url raw 32-byte X25519 public key>",
  "kid": "<base64url key ID>",
  "sig": "<base64url Ed25519 signature>"
}
```

All base64url values are canonical, unpadded RFC 4648 encodings. `kx` must
decode to exactly 32 bytes and must not be all zero. A client also rejects a
direct-message shared secret that is all zero.

The key ID is room-, actor-, and session-scoped:

```text
kidInput =
  UTF8("tacmap-chat-kid-v1\0") ||
  roomIdRaw ||
  actorIdLengthUInt16LE || UTF8(actorId) ||
  sessionDomainRaw || x25519PublicKeyRaw

kid = base64url-no-pad(SHA-256(kidInput))
```

The Ed25519 key-advertisement signature uses the ADR-001 typed preimage with:

```text
domain       = 0x05
protocol     = 0x03
room         = roomIdRaw
actor        = actorId
session      = sessionDomainRaw
counterHex   = ASCII("0000000000000000")
objectId     = empty
kind         = UTF8("chat-key-v1")
payloadHash  = SHA-256(0x01 || x25519PublicKeyRaw || kidRaw)
```

The relay requires a verified current hello, exact `by`/`sd`, a correct `kid`,
and a valid Ed25519 signature. It stores the accepted advert only in the socket
attachment and broadcasts it after the actor's hello. It replies:

```json
{"t":"chat-key-ack","cv":1,"by":"<actorId>","sd":"<session>","kid":"<kid>"}
```

The sender must not enable Chat before this exact acknowledgement. A legacy
relay silently ignores the extension and Chat therefore remains unavailable.
An identical advert retry is acknowledged without resetting counters or
rebroadcasting it. A different second key on one socket is rejected.

Bounded failures use:

```json
{"t":"chat-key-nack","cv":1,"by":"<actorId>","sd":"<session>","code":"<code>"}
```

### 2. Use one exact header for routing, AEAD, signatures, and direct KDF

The chat message header `H` is binary and length-prefixed:

```text
[0]       chatVersion       u8 = 0x01
[1]       scope             u8 = 0x01 room, 0x02 direct
[2..33]   roomIdRaw         32 bytes
[+0..1]   fromActorLength   uint16 little-endian
[+...]    fromActor         UTF-8 actorId bytes
[+...]    fromSession       32 raw bytes
[+...]    counterHex        exactly 16 lowercase ASCII hex bytes
[+...]    messageId         16 raw random bytes
[+...]    fromKid           32 raw bytes
[+0..1]   toActorLength     uint16 little-endian; zero for room
[+...]    toActor           UTF-8 actorId bytes; empty for room
[+...]    toSession         32 raw bytes; all zero for room
[+...]    toKid             32 raw bytes; all zero for room
```

The same `H` is used everywhere below. There is no JSON reserialization in a
cryptographic input.

AEAD associated data is:

```text
UTF8("tacmap-chat-aad-v1\0") || H
```

The outer sender-authentication preimage is:

```text
0x06 || 0x03 || H || SHA-256(rawSealedBlob)
```

The sender signs that preimage with its established Ed25519 identity. The
relay and recipient verify the signature before routing or decrypting. This
binds the ciphertext to the room, sender, sender session, counter, message ID,
scope, and exact selected recipient context. In particular, neither a relay nor
a room member can relabel a direct message as room-wide or change its target.

### 3. Separate room and direct encryption keys

For **Entire room**:

```text
roomChatKey = HMAC-SHA256(roomKey, UTF8("tacmap-chat-room-v1"))
```

There is no NUL byte in that HMAC label. This is domain separation, not a new
access boundary: every join-code holder can derive `roomChatKey`.

For **Selected unit**, each endpoint computes:

```text
Z     = X25519(ownSessionPrivateKey, peerAdvertisedSessionPublicKey)
salt  = SHA-256(UTF8("tacmap-chat-direct-salt-v1\0") || roomIdRaw)
info  = SHA-256(UTF8("tacmap-chat-direct-info-v1\0") || H)
key   = HKDF-SHA256(IKM=Z, salt=salt, info=info, outputLength=32)
```

The selected-unit key is unavailable to the relay and to other room-key
holders. The signed key adverts and `fromKid`/`toKid` binding prevent an
unknown-key-share substitution. A fresh session key gives forward secrecy for
network ciphertext after both endpoints clear that session private key. This is
not a Signal-style double ratchet and does not provide post-compromise security
inside a still-live session.

Both scopes use AES-256-GCM with a fresh random 12-byte nonce. The wire value is
canonical padded standard Base64 of:

```text
nonce(12) || ciphertext || tag(16)
```

The encoded ciphertext is at most 16,384 characters and the decoded value is
at least 28 bytes.

### 4. Bind scope and recipient explicitly on the wire

An Entire room frame has these exact keys:

```json
{
  "t":"chat", "cv":1, "scope":"room",
  "by":"<actorId>", "sd":"<sender session>",
  "vs":"<counterHex16>:<actorId>",
  "mid":"<base64url raw 16-byte random ID>",
  "fromKid":"<sender key ID>",
  "ct":"<standard base64 sealed blob>",
  "sig":"<base64url Ed25519 signature>"
}
```

A Selected unit frame uses `scope: "direct"` and adds all three fields:

```json
{
  "to":"<recipient actorId>",
  "toSd":"<recipient session domain>",
  "toKid":"<recipient chat key ID>"
}
```

Room frames must omit `to`, `toSd`, and `toKid`; direct frames must include
them. `mid` is exactly 22 base64url characters encoding 16 canonical bytes.
There is no `epk` or `rkid` field in chat v1.

The relay routes a direct frame only to a currently live socket matching the
exact `(to, toSd, toKid)` tuple. Failure returns `recipient_offline`; the relay
never widens delivery to the room. Confidentiality must still hold if a
malicious relay broadcasts the direct ciphertext to everyone.

### 5. Keep chat counters independent and replay state durable at endpoints

`vs` uses the ADR-001 `VersionStamp` string form, but the counter is a separate
positive signed-63-bit chat counter for the current WebSocket. It neither
advances nor is advanced by object or presence counters.

The relay accepts only:

```text
previous < incoming <= previous + 10,000
```

It verifies the sender signature before advancing the counter. Reusing or
lowering a counter with changed content is rejected. An exact retry of the
immediately preceding signed frame is re-routed and re-acknowledged so a lost
transport acknowledgement can recover idempotently.

The relay's counter is abuse containment, not the hostile-relay replay
guarantee. Each recipient durably stores per-room `{actorId, sessionDomain,
counter}` high-water state and recent exact message fingerprints under
`SafeStore` before exposing plaintext. It accepts a new remote session only
after the corresponding verified hello and chat-key advert. Leave may clear the
UI and live secrets but must not erase that state. Forget Room may delete it
only with the existing rollback-protection warning.

### 6. Keep payload type and operational content encrypted

Plaintext is strict UTF-8 JSON, at most 8,192 bytes. Unknown fields are rejected.
Text and report payloads are:

```json
{
  "pv":1,
  "kind":"text",
  "body":"RV at checkpoint 4.",
  "createdAt":1720000001000,
  "replyTo":"<optional prior mid>"
}
```

`kind` is `text` or `report`; `body` is 1 through 4,096 UTF-8 bytes;
`createdAt` is an integer epoch-millisecond display value and is never used for
freshness or replay acceptance. `replyTo`, when present, is a canonical `mid`.
Chat v1 has no attachments, rich text, Markdown rendering, or executable links.

TacMap Chat v1 accepts only `text` and `report`. Endpoint delivery/read receipts
are reserved for a later protocol decision and are not accepted, emitted, or
displayed by Chat v1. In particular, strict v1 parsers reject
`"kind":"receipt"`; adding receipts requires a new payload or chat version plus
an explicit state and privacy design.

### 7. Distinguish transport acknowledgement from endpoint delivery

The relay replies after successful live routing:

```json
{
  "t":"chat-ack", "cv":1,
  "by":"<actorId>", "sd":"<sender session>",
  "vs":"<chat stamp>", "mid":"<message ID>",
  "scope":"room", "fromKid":"<sender key ID>"
}
```

A direct acknowledgement also echoes `to`, `toSd`, and `toKid`. The sender
requires all fields to match its pending frame. This acknowledgement means only
that the untrusted relay says it routed the frame. It must never be labelled
delivered or read. The only positive transport state in TacMap Chat v1 is
**Routed** for a selected unit or **Sent to room** for the entire room.

Failures are bounded:

```json
{
  "t":"chat-nack", "cv":1,
  "mid":"<message ID when parseable>",
  "by":"<current actor when authenticated>",
  "sd":"<current session when authenticated>",
  "code":"invalid", "retry":false
}
```

Defined v1 codes are `invalid`, `invalid_signature`, `session_unavailable`,
`recipient_offline`, and `counter_rejected`.

### 8. Seal all local chat state under the mission DataKey

Chat history, normalized plaintext, drafts, pending sends, replay
fingerprints, and unread-message identifiers are mission data. They use
`SafeStore` with a domain-separated label such as `sync/chat/<roomId>`, atomic
checked writes, and the same corrupt-versus-locked behavior as other mission
stores. Accepting an inbound message and marking a conversation read both
persist before the corresponding unread badge state is published.

No chat operation may reacquire the general mission key while the app is
locked. ADR-002's `recordingOnly` capability explicitly excludes Unit Sync and
therefore excludes Chat. Incoming plaintext is not displayed until the durable
write succeeds. Notification previews default to generic wording rather than
plaintext operational content.

Pending direct frames are valid only for their exact `toSd` and `toKid`. After
a reconnect or target key change they become failed drafts requiring explicit
user review; they are never silently re-encrypted and resent to a new session.

### 9. Make misdelivery difficult in the UI

The composer stores an immutable cryptographic target, not a callsign:

```text
(scope, actorId, pinned Ed25519 fingerprint, sessionDomain, chatKeyId)
```

The UI always shows a persistent, visually distinct recipient pill:

- **ENTIRE ROOM**
- **SELECTED UNIT · <current callsign> · <fingerprint suffix>**

Changing scope is explicit. Direct send never falls back to room. If the unit
leaves, does not advertise Chat, or changes session/key while the composer is
open, Send is disabled and the user must reselect the unit. The receiver labels
the sender from the verified actor binding; payload text cannot choose its own
sender identity.

## Relay storage and metadata

The relay never puts a chat advert, message, or report into Durable
Object storage and never includes chat frames in durable snapshot pages. It may
carry the current signed `chat-key` alongside current hello/presence frames for
a live peer after `snapshot-end`.

The relay can see:

- the room, sender actor/session/key ID, and direct recipient actor/session/key
  ID;
- whether scope is room or direct;
- connection IPs, timing, sizes, retries, transport acknowledgements, and error
  codes;
- ephemeral X25519 public keys and all outer Ed25519 signatures.

It cannot see body text, report type/content, reply references, or timestamps
because those remain inside ciphertext. The protocol does not claim
traffic-flow confidentiality or padding.

## Backward compatibility

- The room path and protocol remain `/v3/room/<roomId>` and v3.
- Old clients ignore unknown chat frames and retain normal map/presence sync.
- Old relays ignore `chat-key`; the missing exact `chat-key-ack` keeps Chat
  disabled rather than pretending it is available.
- Chat never operates in an explicitly legacy `2:` room.
- Chat fields are strict and carry their own `cv: 1`; a future incompatible
  chat format uses a new chat version or a new ADR, never ambiguous optional
  reinterpretation.

## Residual risks and honest limits

- A join-code holder can introduce a new actor and send room messages under
  that new identity. TOFU still cannot prove a brand-new actor belongs to the
  claimed human/unit.
- A malicious relay can suppress, delay, reorder, selectively route, or lie in
  transport acknowledgements. Chat v1 has no endpoint receipt, so **Routed**
  does not prove that a recipient decrypted, stored, displayed, or read a
  frame.
- Direct chat has session forward secrecy after key erasure but no double
  ratchet or post-compromise security. Compromise during a live session can
  expose that session's direct traffic.
- Room chat is readable by every current or later holder of the room join code
  who captures the ciphertext.
- Device compromise, screenshots, copied text, notification access, and a
  recipient deliberately redistributing plaintext remain outside E2E crypto.
- Traffic analysis remains visible. Self-hosting changes who operates the relay
  but does not hide metadata from that relay.

## Verification

The shared fixture `testdata/tacmap_chat_v1.json` pins both key adverts and all
byte-level inputs and outputs: key IDs, typed preimages, signatures, headers,
AAD, room subkey, X25519 shared secret, HKDF salt/info/key, deterministic GCM
seals, ciphertext hashes, and outer signatures.

Android, iOS, and relay tests must consume that fixture and additionally prove:

1. `cv`, canonical Base64, exact keys, `mid`, and ciphertext ceilings fail
   closed;
2. invalid key/message signatures do not create capability or counter state;
3. room frames reach only current chat-capable sockets and are never stored;
4. direct frames route only to exact `(to, toSd, toKid)` and never broaden;
5. offline, replaced, and stale-key targets produce a failure;
6. changed replay and far-future counters fail, while an exact immediate retry
   remains idempotent;
7. recipients persist replay state and message atomically before exposing it
   in the UI;
8. DataKey lock, recording-only mode, Leave, reconnect, and Forget Room obey the
   lifecycle above; and
9. packet capture confirms the relay-visible metadata list and no plaintext.
