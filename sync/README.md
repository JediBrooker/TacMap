# TacMap sync backend

Real-time shared-tactical-picture sync for TacMap — a Cloudflare **Worker + Durable
Object** that relays **end-to-end-encrypted** overlay changes between devices in a
unit. The server is **E2E-blind**: it stores and forwards opaque ciphertext and
never holds the keys.

This is the backend increment of the full-parity sync feature. Client increments
(iOS/Android networking + crypto) build on the wire protocol below.

## Architecture

- **Worker** (`src/index.ts`, default export) routes `wss://…/room/<roomId>`
  WebSocket upgrades to a Durable Object instance keyed by `roomId`.
- **`SyncRoom`** Durable Object: one per unit room. Holds the connected sockets
  (hibernatable WebSockets) and the latest blob per object id in DO storage.
  - **Store-and-forward**: on connect a client receives a `snapshot` of every
    current record, so a device that was offline catches up.
  - **Last-write-wins merge**: an incoming change is kept only if it's newer than
    the stored one — `v` first, then client-id (`by`) as a deterministic
    tie-break. Accepted changes are broadcast to the other sockets.
  - **Tombstones**: deletes are stored (`deleted: true`) so they also reach late
    joiners.

## Wire protocol (JSON over WebSocket)

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

## End-to-end encryption (client responsibility)

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
- **`ping` wakes the Durable Object.** A protocol-level `ping` costs one DO
  wake + `pong`. Clients rely on the WebSocket transport's own keepalive where
  possible and only send protocol pings when needed; the per-socket rate limit
  bounds the cost of a client that pings aggressively.

## Deployed instance

Live relay: **`wss://tacmap-sync.christianbrooker.workers.dev/room/<roomId>`**
(health: `https://tacmap-sync.christianbrooker.workers.dev/health` → `ok`).

Verified end-to-end against the deployed Durable Object (two-client WebSocket
test): snapshot-on-connect, peer broadcast with the opaque `ct` preserved, no
self-echo, last-write-wins (stale `v` dropped, newer `v` wins), and
store-and-forward (a late joiner receives current state in its snapshot).

## Run / deploy

```bash
cd sync
npm install
npm run check     # wrangler deploy --dry-run — validate config + bundle (no deploy)
npm run dev       # local: ws://127.0.0.1:8787/room/<roomId>
npm run deploy    # deploy to the configured Cloudflare account
```

**Self-hosting**: a unit clones this folder and runs `npm run deploy` against
their own Cloudflare account — their own private relay, no shared infrastructure.

> Deploy is gated: nothing is pushed to a Cloudflare account without an explicit
> `wrangler deploy`.
