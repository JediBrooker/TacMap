# TacMap sync backend

Real-time shared-tactical-picture sync for TacMap — a Cloudflare **Worker + Durable
Object** that relays **end-to-end-encrypted** overlay changes between devices in a
unit. The server is **E2E-blind**: it stores and forwards opaque ciphertext and
never holds the keys.

Newly generated join codes use protocol v3 (the `3:` code prefix) and connect
through `/v3/room/<roomId>`. The `/room/<roomId>` v2 route remains available
only for compatibility with existing `2:` rooms. The v3 design and
cross-platform requirements are specified in
[`ADR-001`](../docs/security/ADR-001-sync-protocol-v3.md).

## Architecture

- **Worker** (`src/index.ts`, default export) routes `wss://…/room/<roomId>`
  (legacy v2) and `wss://…/v3/room/<roomId>` (current v3) WebSocket upgrades to
  protocol-separated Durable Objects. V2 uses the raw room ID; v3 uses the
  internal name `v3:<roomId>` so a v2 object cannot preclaim v3 metadata.
- **`SyncRoom`** Durable Object: one per unit room. Holds the connected sockets
  (hibernatable WebSockets) and the latest blob per object id in DO storage.
  - **Store-and-forward**: on connect a client receives a sequence-fenced,
    byte-bounded snapshot of every current record, so a device that was offline
    catches up without any frame exceeding the client ceiling.
  - **Last-write-wins merge**: an incoming change is kept only if it's newer than
    the stored one — `v` first, then client-id (`by`) as a deterministic
    tie-break. Accepted changes are broadcast to the other sockets.
  - **Tombstones**: deletes are retained (`deleted: true`) for room lifetime and
    count toward record and byte quotas, so they reach late joiners without
    enabling unbounded storage.

Human-friendly room names are client-local display metadata. They are not part
of the join code or derived room ID, are not sent in relay URLs, headers, or wire
frames, and are not stored or synchronized by the relay. Devices sharing a join
code may therefore use different local names for the same cryptographic room.

## Legacy v2 wire protocol (JSON over WebSocket)

Client → server:

| msg | fields | meaning |
|-----|--------|---------|
| `put` | `id`, `v`, `by`, `kind`, `ct` | upsert object `id` with base64 ciphertext `ct` at version `v` |
| `del` | `id`, `v`, `by` | tombstone object `id` at version `v` |
| `ping` | — | keepalive; server replies `{t:"pong"}` |

Server → client:

| msg | fields | meaning |
|-----|--------|---------|
| `snapshot` | `items: [record…]` | full current state, sent once on connect |
| `put` / `del` | `id`, `v`, `by`, `kind`, `ct`, `deleted` | a change from another device |
| `pong` | — | keepalive reply |

- `id` — a random per-object UUID (no content leak).
- `v` — a client logical clock (Lamport / hybrid logical clock) so versions are
  comparable across devices.
- `kind` — coarse routing hint (`waypoint` | `drawing` | `layer`); everything
  meaningful is inside `ct`.
- `ct` — base64 of the AEAD-encrypted object JSON. **Server never decrypts.**

Every snapshot is framed by `snapshot-begin {seq}` and matching
`snapshot-end {seq}`. Mutations accepted after that fence carry a larger `seq`.
Snapshot pages are capped at 900,000 encoded UTF-8 bytes as well as a
100-record storage read page.

### Active v3 protocol

V3 uses string `VersionStamp`s, room-scoped self-certifying actor IDs, and
room-scoped wire object IDs. Before a socket can send put/delete/presence it
must submit a signed proof-of-possession announcement:

```json
{"t":"hello","by":"<actorId>","pub":"<32-byte base64url>",
 "sd":"<32-byte base64url>","vs":"<positive-u64-epoch-hex>:<actorId>",
 "sig":"<64-byte Ed25519 base64url>"}
```

The relay recomputes `actorId` from the room and public key, verifies this
signature, and transactionally requires the signed session epoch to be newer
than the actor's durable epoch. The acknowledgement echoes `by`, `sd`, and `vs`.
The actor record shares the same total record/byte quota as objects and
tombstones. Durable put/delete records carry both `pub` and `sd`,
so a late joiner has all context needed to authenticate the encrypted inner
signature. Presence counters are session-local and cannot advance the durable
room high-water. The exact preimages and state-machine rules are normative in
ADR-001 and `testdata/sync_protocol_v3.json`.

After the pin and per-socket binding are complete, the relay replies on the
announcing socket with
`{"t":"hello-ack","by":"<actorId>","sd":"<sessionDomain>","vs":"<helloEpoch>:<actorId>"}`.
Clients remain snapshot-gated and send no put/delete/presence until that frame
matches their current actor, connection session domain, and signed epoch.

V3 presence uses this outer relay-visible frame:

```json
{"t":"loc","by":"<actorId>","pub":"<32-byte base64url>",
 "sd":"<32-byte base64url>","vs":"<presenceStamp>","ct":"<base64>"}
```

Decrypting `ct` with the v3 presence AAD yields an inner envelope shaped as
follows:

```json
{
  "pv": 1,
  "p": "<standard base64 of the exact presence-payload bytes>",
  "lat": 0.0,
  "lon": 0.0,
  "heading": 0.0,
  "speed": 0.0,
  "callsign": "",
  "affiliation": "UNKNOWN",
  "echelon": "TEAM",
  "function": "INFANTRY",
  "isHQ": false,
  "pub": "<32-byte base64url>",
  "sig": "<64-byte Ed25519 base64url>",
  "prv": 1,
  "pr": "<standard base64 of exact {\"ttl\":seconds} bytes>",
  "prsig": "<64-byte Ed25519 base64url>"
}
```

`pv: 1` selects the exact-payload encoding. `p` is canonical standard Base64
(including padding) and decodes to the exact UTF-8 JSON bytes created by the
sender for the presence payload. The signed preimage's `payloadHash` is
`SHA-256(decodeBase64(p))`; a receiver MUST hash those bytes directly and MUST
NOT parse and reserialize them before verification. After verification, current
clients parse those authenticated bytes as the authoritative presence value.

`prv` / `pr` / `prsig` are an optional backwards-compatible retention
advertisement. If any one is present, all three must validate. `prv` is `1`;
`pr` contains exact JSON bytes with only an integer `ttl` from 45 through 3900
seconds. `prsig` signs the normal v3 preimage using domain `0x03`, the same
actor/session/counter, kind `loc-retention`, and
`SHA-256(decodeBase64(pr))`. A receiver extends a marker beyond 45 seconds only
after this signature verifies and only while the exact authenticated session
remains active. Missing fields retain legacy 45-second behavior; malformed or
invalidly signed fields reject the frame.

The flat `lat` through `isHQ` fields duplicate the payload for compatibility
with legacy v3 clients. `pub` and `sig` remain in the encrypted inner envelope;
the outer `pub` supplies relay/session verification context, while the inner
copy is bound to the encrypted message. Senders include both the exact `p`
encoding and the flat compatibility fields during the migration.

Active-session metadata is not durable. A newly connected or reconnecting v3
observer first receives its durable `snapshot-begin` / `snapshot` /
`snapshot-end` fence, then each currently connected peer's latest signed
`hello`, immediately followed by that peer's current `loc` when one exists.

### TacMap Chat v1 (v3 live extension)

TacMap Chat is enabled only after a verified v3 `hello-ack`. Each socket then
advertises one fresh, memory-only X25519 public key:

```json
{"t":"chat-key","cv":1,"by":"<actorId>","sd":"<session>",
 "kx":"<raw-32 base64url>","kid":"<raw-32 base64url>",
 "sig":"<Ed25519 base64url>"}
```

The relay checks the exact keys, canonical encodings, room/actor/session-bound
`kid`, and signed v3 actor proof before replying:

```json
{"t":"chat-key-ack","cv":1,"by":"<actorId>","sd":"<session>","kid":"<key ID>"}
```

Clients keep Chat unavailable until that exact acknowledgement. An identical
advert retry is acknowledged idempotently; a different key on the same socket
gets `chat-key-nack` with `key_already_announced`. Other key-advert failures use
`invalid` or `invalid_signature`.

An **Entire room** message has these exact outer keys:

```json
{"t":"chat","cv":1,"scope":"room","by":"<sender actor>",
 "sd":"<sender session>","vs":"<counterHex16>:<sender actor>",
 "mid":"<raw-16 base64url>","fromKid":"<sender key ID>",
 "ct":"<padded standard base64>","sig":"<Ed25519 base64url>"}
```

A **Selected unit** message uses `scope:"direct"` and adds the required exact
recipient tuple:

```json
{"to":"<recipient actor>","toSd":"<recipient session>",
 "toKid":"<recipient key ID>"}
```

Room frames must omit the recipient fields; direct frames must include all
three. `cv`, scope, room, sender/session/counter/message/key IDs, and the direct
recipient tuple form one binary header bound into both AES-GCM associated data
and the sender's Ed25519 signature. Entire-room payloads use a
domain-separated room chat key and remain readable by all join-code holders.
Selected-unit payloads use X25519 + HKDF-SHA256 between the two exact live
sessions, so the relay and other room members cannot decrypt them.

The relay verifies the sender signature before advancing its independent Chat
counter. It forwards room frames only to currently connected, Chat-capable v3
sockets, and direct frames only to the exact live `(to,toSd,toKid)` socket. It
never writes a Chat key or frame to Durable Object storage or snapshot pages,
and it never broadens an offline direct target to the room.

Successful handling returns the frame-bound acknowledgement below; direct
acknowledgements also echo `to`, `toSd`, and `toKid`:

```json
{"t":"chat-ack","cv":1,"by":"<sender actor>","sd":"<sender session>",
 "vs":"<chat stamp>","mid":"<message ID>","scope":"room",
 "fromKid":"<sender key ID>"}
```

This is only an untrusted relay transport acknowledgement: the UI may call it
**Routed** or **Sent to room**, never delivered or read. TacMap Chat v1 supports
only encrypted `text` and `report` payloads and has no endpoint receipts or
offline mailbox. Failures use `chat-nack` with `invalid`, `invalid_signature`,
`session_unavailable`, `recipient_offline`, or `counter_rejected`, plus a
bounded `retry` boolean.

The normative key-ID, signature preimage, binary header, AAD, KDF, ciphertext,
counter, storage, and UI rules are in
[`ADR-004`](../docs/security/ADR-004-tacmap-chat-v1.md). Byte-exact Android,
iOS, and relay vectors are in [`testdata/tacmap_chat_v1.json`](../testdata/tacmap_chat_v1.json).

## Legacy v2 end-to-end encryption (client responsibility)

The unit shares a **join code** (a high-entropy secret, ~80 bits, generated
on-device). On each device the key hierarchy is derived in a single PBKDF2 pass
followed by purpose-keyed HMACs:

1. `master = PBKDF2-HMAC-SHA256(joinCode, "tacmap-sync-salt-v2", 210000)` — 32 bytes.
   The high iteration count makes offline brute-force of a captured `roomId`
   impractical even for a short code.
2. `roomId   = base64url(HMAC-SHA256(master, "tacmap-roomid-v2"))` — 43 chars.
   Used only for routing; knowing it lets you relay ciphertext, never read it.
3. `roomKey  = HMAC-SHA256(master, "tacmap-roomkey-v2")` — 32-byte symmetric key.
   Each object is sealed with AES-256-GCM under `roomKey` with a fresh random
   96-bit nonce; `ct` carries nonce‖ciphertext‖tag.
4. `authToken = base64url(HMAC-SHA256(master, "tacmap-auth-v2"))` — 43 chars.
   Sent in the `Authorization: Bearer` header on every WebSocket handshake.
   The relay pins the first token it sees per room (trust-on-first-use) and
   rejects any socket that doesn't match — a leaked `roomId` alone cannot
   connect.

So two devices with the same join code converge; the server (and anyone who only
learns the `roomId`) cannot decrypt. This scheme is implemented in the client
increments (iOS `SyncCrypto.swift`, Android `SyncCrypto.kt`); the backend is
intentionally oblivious to the content.

## Security properties & accepted limitations

Documented so the trade-offs are explicit rather than surprising:

- **AEAD nonce reuse (random 96-bit nonce).** Each object is sealed with a fresh
  cryptographically-random 12-byte nonce. With a single per-room key, the
  birthday bound puts collision risk below 2⁻³² only under ~2³² messages
  (`√(2·2⁹⁶·2⁻³²)`); a unit exchanges a few thousand objects over a room's life,
  so the margin is enormous. We keep random nonces (not a counter) deliberately:
  a counter would have to survive app reinstalls and multi-device races without
  ever repeating, which is *harder* to get right than random at this volume. A
  room that somehow approached that scale should rotate its join code.
- **No forward secrecy / post-compromise security.** The room key is derived
  deterministically from the long-lived join code, so a device (or join code)
  compromised today exposes past *and* future traffic for that code. This is the
  price of a zero-server-trust, offline-capable, no-account design (no key
  exchange to run when devices are off-grid). **Mitigation: rotating the join
  code** re-keys the room — do it on suspected compromise or personnel change.
  A future increment could layer per-epoch ratcheting keyed off a rotation
  counter without changing the relay.
- **Inbound resource ceilings.** The relay caps each WebSocket frame at 1 MiB
  and each socket at 200 frames / 4 MiB per rolling 10-second window, including
  malformed and unknown frames. A protocol `ping` must be exactly
  `{\"t\":\"ping\"}`; padded pings are ignored. Both clients also cap messages
  at 1 MiB and process them serially. Android rejects an oversized declared
  frame before allocating its payload, disables compression and redirects,
  caps fragmented messages at 128 frames, and bounds every raw data/control
  frame during a bounded 512-frame initial-snapshot allowance. iOS configures
  the native WebSocket task's maximum message size and rejects redirects; its
  public API handles Ping/Pong internally, so individual control frames cannot
  be included in the app-level budget. Clients rely on transport keepalive
  where possible, because a valid protocol ping still wakes the Durable Object
  and costs a `pong`.

## Deployed instance

Live relay: **`wss://tacmap-sync.christianbrooker.workers.dev/room/<roomId>`**
for legacy v2 and
**`wss://tacmap-sync.christianbrooker.workers.dev/v3/room/<roomId>`** for v3.
The health endpoint returns `ok` with a no-store
`X-TacMap-Relay-Release` header. A release is not deployment-verified until
that header matches `RELAY_RELEASE_ID` in `src/index.ts`; an `ok` body alone is
only a liveness check.

Verified end-to-end against the deployed Durable Object (two-client WebSocket
test): snapshot-on-connect, peer broadcast with the opaque `ct` preserved, no
self-echo, last-write-wins (stale `v` dropped, newer `v` wins), and
store-and-forward (a late joiner receives current state in its snapshot).

## Run / deploy

```bash
cd sync
npm ci
npm test          # required Workers/Vitest protocol suite
npm run check     # wrangler deploy --dry-run — validate config + bundle (no deploy)
npm run dev       # local: ws://127.0.0.1:8787/room/<roomId>
npm run deploy    # deploy to the configured Cloudflare account
```

**Self-hosting**: a unit clones this folder and runs `npm run deploy` against
their own Cloudflare account — their own private relay, no shared infrastructure.

> Deploy is gated: nothing is pushed to a Cloudflare account without an explicit
> `wrangler deploy`.
