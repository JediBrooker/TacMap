/// <reference types="@cloudflare/workers-types" />

/**
 * TacMap sync backend — an end-to-end-**blind** relay for the shared tactical
 * picture. One Durable Object per unit "room"; clients connect over WebSocket
 * and exchange opaque encrypted blobs. The server only ever sees ciphertext
 * plus minimal routing metadata (a random object id, a version, and a coarse
 * `kind`) — the E2E keys are derived on devices from the unit join-code and
 * never leave them (see README + the client increments).
 *
 * Access control (the relay stays blind to content but is NOT blind to who may
 * write):
 *  - The room id in the URL is a 256-bit value derived from the join code via
 *    210k-iteration PBKDF2, so it is unguessable without the code.
 *  - Every socket must present a separate writer-auth token in the
 *    `Authorization: Bearer` handshake header (never in the URL, so it never
 *    lands in infra logs). The room pins the first token it sees and rejects any
 *    socket that doesn't match — a leaked room id alone can neither read the
 *    snapshot nor write to the room.
 *
 * Abuse limits: bounded `v`, per-room object cap, per-socket message rate limit,
 * per-room connection cap, and a per-message ciphertext ceiling keep a single
 * client from inflating Durable-Object storage/billing or DoS-ing a room.
 *
 * Storage gives offline store-and-forward: the DO keeps the latest blob per
 * object id (last-write-wins by version, client-id tie-break) so a device that
 * was offline catches up via a snapshot on reconnect. Self-hostable: this is a
 * stock Worker + DO — a unit can `wrangler deploy` it to their own account.
 */

export interface Env {
  SYNC_ROOM: DurableObjectNamespace
}

// Room ids are the 256-bit (43-char base64url) output of the client PBKDF2 → so
// require ≥32 chars: rejects short/guessable ids and raises the enumeration bar.
const ROOM_RE = /^\/room\/([A-Za-z0-9_-]{32,128})$/

// Web origins permitted to open a socket. Empty by default: TacMap ships only
// native clients, which never send an `Origin` header. A self-hoster adding a
// browser client would list its origin(s) here (e.g. "https://map.example.mil").
const ALLOWED_ORIGINS = new Set<string>([])

// Abuse ceilings.
const MAX_CONNECTIONS = 64          // sockets per room
const MAX_OBJECTS = 5000            // stored objects per room (live only)
const MAX_V = 1e12                  // sane Lamport/HLC upper bound
const CT_MAX = 700_000             // ~512 KiB ciphertext (DO value limit)
const RATE_WINDOW_MS = 10_000
const RATE_MAX_MSGS = 200           // per socket per window
const SNAPSHOT_CHUNK = 100          // objects per snapshot message
const TOMBSTONE_TTL_MS = 7 * 24 * 60 * 60 * 1000  // 7 days
const GC_INTERVAL_MS = 6 * 60 * 60 * 1000         // run GC every 6 hours

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url)
    if (url.pathname === "/health") {
      return new Response("ok", { headers: { "content-type": "text/plain" } })
    }
    const match = url.pathname.match(ROOM_RE)
    if (!match) return new Response("Not found", { status: 404 })
    if (req.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("Expected a WebSocket upgrade", { status: 426 })
    }
    // Cross-site WebSocket hijacking defense: browsers always attach an `Origin`
    // header to WS handshakes and cannot set the `Authorization` header on one,
    // so an Origin-bearing request is a drive-by from a web page, never a real
    // TacMap client. Native clients send no Origin and always pass; a self-hosted
    // web client would be added to ALLOWED_ORIGINS. Belt-and-suspenders on top of
    // the bearer-token check, which such a request would also fail.
    const origin = req.headers.get("Origin")
    if (origin !== null && !ALLOWED_ORIGINS.has(origin)) {
      return new Response("Forbidden origin", { status: 403 })
    }
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName(match[1]))
    return stub.fetch(req)
  },
}

/** One stored object: opaque ciphertext + the metadata needed to route + merge. */
interface SyncRecord {
  id: string
  v: number        // logical version (client Lamport/HLC clock)
  by: string       // client id — deterministic tie-break for equal versions
  kind: string     // coarse type hint: "waypoint" | "drawing" | "layer"
  ct: string       // base64 ciphertext (server never decrypts)
  deleted: boolean // tombstone so deletes reach late joiners
  deletedAt?: number // epoch-ms when this record became a tombstone (for GC)
}

/** Per-socket state carried across hibernation. Includes rate-limit counters
 *  and the optional presence identity the client announced at join time. */
interface SocketState {
  windowStart: number
  msgs: number
  presence?: PresenceInfo
}

/** Opaque encrypted presence blob. The relay is E2E-blind to presence content
 *  (location, callsign, unit composition) — it stores only the client id
 *  (a random UUID, not PII) for routing `leave` broadcasts, and the ciphertext
 *  for relaying to peers and including in snapshots. */
interface PresenceInfo {
  clientId: string
  ct: string           // base64 AEAD ciphertext of the presence payload
}

/** True when `a` should overwrite `b` under last-write-wins. */
function isNewer(a: { v: number; by: string }, b: { v: number; by: string }): boolean {
  return a.v > b.v || (a.v === b.v && a.by > b.by)
}

/** Constant-time string comparison (tokens are fixed-length base64url). */
function constantTimeEqual(a: string, b: string): boolean {
  const enc = new TextEncoder()
  const ab = enc.encode(a)
  const bb = enc.encode(b)
  if (ab.length !== bb.length) return false
  let diff = 0
  for (let i = 0; i < ab.length; i++) diff |= ab[i] ^ bb[i]
  return diff === 0
}

export class SyncRoom {
  private state: DurableObjectState

  constructor(state: DurableObjectState, _env: Env) {
    this.state = state
  }

  async fetch(req: Request): Promise<Response> {
    // Writer-auth: the bearer token proves possession of the join-code-derived
    // auth key without revealing it (the relay stays content-blind). It rides in
    // a header, never the URL, so it never reaches request logs.
    const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "")
    if (token.length < 20 || token.length > 200) {
      return new Response("Unauthorized", { status: 401 })
    }
    const pinned = await this.state.storage.get<string>("meta:auth")
    if (pinned === undefined) {
      // Trust-on-first-use: the first client (who must already know the room id,
      // itself only derivable from the join code) pins the token for the room.
      await this.state.storage.put("meta:auth", token)
    } else if (!constantTimeEqual(pinned, token)) {
      return new Response("Forbidden", { status: 403 })
    }

    // Cap concurrent connections so one room can't be used to exhaust the DO.
    if (this.state.getWebSockets().length >= MAX_CONNECTIONS) {
      return new Response("Room full", { status: 503 })
    }

    const pair = new WebSocketPair()
    const client = pair[0]
    const server = pair[1]
    // Hibernatable WebSocket — the DO can evict from memory between messages.
    this.state.acceptWebSocket(server)
    server.serializeAttachment({ windowStart: 0, msgs: 0 } satisfies SocketState)
    await this.sendSnapshot(server)
    const existing = await this.state.storage.getAlarm()
    if (existing === null) {
      await this.state.storage.setAlarm(Date.now() + GC_INTERVAL_MS)
    }
    return new Response(null, { status: 101, webSocket: client })
  }

  // ----- Hibernation API handlers -----

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (!this.allow(ws)) {
      try { ws.close(4008, "rate limit") } catch { /* already closed */ }
      return
    }
    let msg: any
    try {
      const text = typeof message === "string" ? message : new TextDecoder().decode(message)
      msg = JSON.parse(text)
    } catch {
      return
    }
    switch (msg?.t) {
      case "put":
        await this.applyChange(ws, msg, false)
        break
      case "del":
        await this.applyChange(ws, msg, true)
        break
      case "loc":
        this.handlePresence(ws, msg)
        break
      case "ping":
        try { ws.send(JSON.stringify({ t: "pong" })) } catch { /* closed */ }
        break
    }
  }

  async webSocketClose(ws: WebSocket, code: number, _reason: string, _clean: boolean): Promise<void> {
    const s = ws.deserializeAttachment() as SocketState | null
    if (s?.presence) {
      this.broadcast({ t: "leave", clientId: s.presence.clientId }, ws)
    }
    try { ws.close(code, "closing") } catch { /* already closed */ }
  }

  async webSocketError(_ws: WebSocket, _err: unknown): Promise<void> {
    // Nothing to do — the socket is torn down by the runtime.
  }

  async alarm(): Promise<void> {
    const now = Date.now()
    const map = await this.state.storage.list<SyncRecord>({ prefix: "obj:" })
    const toDelete: string[] = []
    for (const [key, rec] of map) {
      if (rec.deleted && rec.deletedAt && now - rec.deletedAt > TOMBSTONE_TTL_MS) {
        toDelete.push(key)
      }
    }
    if (toDelete.length > 0) {
      await this.state.storage.delete(toDelete)
    }
    if (this.state.getWebSockets().length > 0) {
      await this.state.storage.setAlarm(now + GC_INTERVAL_MS)
    }
  }

  // ----- Presence (ephemeral, never stored) -----

  private handlePresence(sender: WebSocket, msg: any): void {
    const clientId = typeof msg.clientId === "string" ? msg.clientId.slice(0, 128) : ""
    const ct = typeof msg.ct === "string" ? msg.ct : ""
    if (!clientId || !ct || ct.length > CT_MAX) return
    const p: PresenceInfo = { clientId, ct }
    const s = (sender.deserializeAttachment() as SocketState | null) ?? { windowStart: 0, msgs: 0 }
    s.presence = p
    sender.serializeAttachment(s)
    this.broadcast({ t: "loc", clientId, ct }, sender)
  }

  private collectMembers(except?: WebSocket): PresenceInfo[] {
    const members: PresenceInfo[] = []
    for (const ws of this.state.getWebSockets()) {
      if (ws === except) continue
      const s = ws.deserializeAttachment() as SocketState | null
      if (s?.presence) members.push(s.presence)
    }
    return members
  }

  // ----- Core -----

  /** Sliding-window per-socket rate limit. Returns false when the socket has
   *  exceeded its message budget for the current window. */
  private allow(ws: WebSocket): boolean {
    const now = Date.now()
    const s = (ws.deserializeAttachment() as SocketState | null) ?? { windowStart: now, msgs: 0 }
    if (now - s.windowStart > RATE_WINDOW_MS) {
      s.windowStart = now
      s.msgs = 0
    }
    s.msgs += 1
    ws.serializeAttachment(s)
    return s.msgs <= RATE_MAX_MSGS
  }

  private async applyChange(sender: WebSocket, msg: any, deleted: boolean): Promise<void> {
    const id = msg.id
    const v = msg.v
    const by = typeof msg.by === "string" ? msg.by.slice(0, 128) : ""
    const kind = typeof msg.kind === "string" ? msg.kind.slice(0, 32) : "unknown"
    const ct = typeof msg.ct === "string" ? msg.ct : ""
    if (typeof id !== "string" || id.length === 0 || id.length > 256) return
    // Bound `v`: a non-negative safe integer within a sane window. Without this,
    // an attacker (or a client clock bug) could send v = Number.MAX_VALUE and
    // permanently pin a record so no honest update ever wins last-write-wins.
    if (typeof v !== "number" || !Number.isSafeInteger(v) || v < 0 || v > MAX_V) return
    if (!deleted && ct.length === 0) return
    if (ct.length > CT_MAX) return

    const key = "obj:" + id
    const existing = await this.state.storage.get<SyncRecord>(key)
    if (existing && !isNewer({ v, by }, existing)) return // stale — drop

    const wasLive = existing != null && !existing.deleted
    const willLive = !deleted

    if (!wasLive && willLive) {
      const count = (await this.state.storage.get<number>("meta:count")) ?? 0
      if (count >= MAX_OBJECTS) return
      await this.state.storage.put("meta:count", count + 1)
    } else if (wasLive && !willLive) {
      const count = (await this.state.storage.get<number>("meta:count")) ?? 1
      await this.state.storage.put("meta:count", Math.max(0, count - 1))
    }

    const record: SyncRecord = { id, v, by, kind, ct, deleted, ...(deleted ? { deletedAt: Date.now() } : {}) }
    await this.state.storage.put(key, record)
    this.broadcast({ t: deleted ? "del" : "put", ...record }, sender)
  }

  private async sendSnapshot(ws: WebSocket): Promise<void> {
    const map = await this.state.storage.list<SyncRecord>({ prefix: "obj:" })
    const items = [...map.values()]
    const members = this.collectMembers(ws)
    try {
      if (items.length === 0) {
        ws.send(JSON.stringify({ t: "snapshot", items: [], members }))
        return
      }
      for (let i = 0; i < items.length; i += SNAPSHOT_CHUNK) {
        const slice = items.slice(i, i + SNAPSHOT_CHUNK)
        const isFirst = i === 0
        const payload: any = { t: "snapshot", items: slice, more: i + SNAPSHOT_CHUNK < items.length }
        if (isFirst) payload.members = members
        ws.send(JSON.stringify(payload))
      }
    } catch {
      /* socket closed before snapshot — ignore */
    }
  }

  private broadcast(payload: unknown, except: WebSocket): void {
    const data = JSON.stringify(payload)
    for (const ws of this.state.getWebSockets()) {
      if (ws === except) continue
      try { ws.send(data) } catch { /* drop dead sockets */ }
    }
  }
}
