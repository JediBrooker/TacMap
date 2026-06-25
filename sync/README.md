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

The unit shares a **join code** (a high-entropy secret). On each device:

- `roomId = base64url(SHA-256("tacmap-room|" + joinCode))` — used only for
  routing. Knowing it lets you relay ciphertext, never read it.
- `roomKey = HKDF(joinCode, info="tacmap-e2e")` — the symmetric key. Each object
  is sealed with an AEAD (e.g. AES-GCM / ChaCha20-Poly1305) under `roomKey` with
  a fresh nonce; `ct` carries nonce‖ciphertext.

So two devices with the same join code converge; the server (and anyone who only
learns the `roomId`) cannot decrypt. This scheme is implemented in the client
increments; the backend is intentionally oblivious to it.

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
