/// <reference types="@cloudflare/workers-types" />

// TacMap sync relay -- E2E-blind store-and-forward for the shared tactical
// picture. One Durable Object per room. Clients exchange opaque AEAD
// ciphertext; the relay never sees plaintext.
//
// Phase 2 hardening (SEC-001, SEC-007, SEC-022, SEC-024):
//  - atomic record+byte quotas via storage.transaction()
//  - phantom delete rejection (delete must target a known record)
//  - paged storage.list() everywhere (never unbounded)
//  - tombstones persist for room lifetime; whole-room idle expiry replaces
//    per-record 7-day GC
//  - snapshot fence with relay sequence counter
//  - frame-size ceiling before JSON.parse
//  - rate limiting bindings for connections and room creation
//  - privacy-safe operational counters

export interface Env {
  SYNC_ROOM: DurableObjectNamespace
  CONN_LIMITER?: RateLimit
  ROOM_LIMITER?: RateLimit
}

const ROOM_RE = /^\/room\/([A-Za-z0-9_-]{32,128})$/

const ALLOWED_ORIGINS = new Set<string>([])

// abuse ceilings
const MAX_CONNECTIONS = 64
const MAX_RECORDS = 10_000
const MAX_STORED_BYTES = 50_000_000
const MAX_V = 1e12
const CT_MAX = 700_000
const MAX_FRAME_BYTES = 1_048_576
const RATE_WINDOW_MS = 10_000
const RATE_MAX_MSGS = 200
const SNAPSHOT_CHUNK = 100
const IDLE_TTL_MS = 7 * 24 * 60 * 60 * 1000

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
    const origin = req.headers.get("Origin")
    if (origin !== null && !ALLOWED_ORIGINS.has(origin)) {
      return new Response("Forbidden origin", { status: 403 })
    }
    // per-IP connection rate limit (eventually consistent)
    if (env.CONN_LIMITER) {
      const ip = req.headers.get("CF-Connecting-IP") ?? "unknown"
      const { success } = await env.CONN_LIMITER.limit({ key: ip })
      if (!success) {
        metric("conn_rate_limited")
        return new Response("Too many requests", { status: 429 })
      }
    }
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName(match[1]))
    return stub.fetch(req)
  },
}

interface SyncRecord {
  id: string
  v: number
  by: string
  kind: string
  ct: string
  deleted: boolean
  deletedAt?: number
}

interface SocketState {
  windowStart: number
  msgs: number
  presence?: PresenceInfo
}

interface PresenceInfo {
  clientId: string
  ct: string
}

function isNewer(a: { v: number; by: string }, b: { v: number; by: string }): boolean {
  return a.v > b.v || (a.v === b.v && a.by > b.by)
}

function constantTimeEqual(a: string, b: string): boolean {
  const enc = new TextEncoder()
  const ab = enc.encode(a)
  const bb = enc.encode(b)
  if (ab.length !== bb.length) return false
  let diff = 0
  for (let i = 0; i < ab.length; i++) diff |= ab[i] ^ bb[i]
  return diff === 0
}

function recordBytes(rec: SyncRecord): number {
  return rec.ct.length + rec.id.length + rec.by.length + rec.kind.length + 64
}

function metric(name: string, fields?: Record<string, string | number>): void {
  console.log(JSON.stringify({ metric: name, ...fields }))
}

export class SyncRoom {
  private state: DurableObjectState
  private env: Env

  constructor(state: DurableObjectState, env: Env) {
    this.state = state
    this.env = env
  }

  async fetch(req: Request): Promise<Response> {
    const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "")
    if (token.length < 20 || token.length > 200) {
      return new Response("Unauthorized", { status: 401 })
    }
    const pinned = await this.state.storage.get<string>("meta:auth")
    if (pinned === undefined) {
      // TOFU: first connection pins the auth token for the room
      if (this.env.ROOM_LIMITER) {
        const ip = req.headers.get("CF-Connecting-IP") ?? "unknown"
        const { success } = await this.env.ROOM_LIMITER.limit({ key: ip })
        if (!success) {
          metric("room_rate_limited")
          return new Response("Too many rooms", { status: 429 })
        }
      }
      await this.state.storage.put("meta:auth", token)
    } else if (!constantTimeEqual(pinned, token)) {
      return new Response("Forbidden", { status: 403 })
    }

    if (this.state.getWebSockets().length >= MAX_CONNECTIONS) {
      return new Response("Room full", { status: 503 })
    }

    const pair = new WebSocketPair()
    const client = pair[0]
    const server = pair[1]
    this.state.acceptWebSocket(server)
    server.serializeAttachment({ windowStart: 0, msgs: 0 } satisfies SocketState)

    await this.sendSnapshot(server)
    await this.touchActivity()

    return new Response(null, { status: 101, webSocket: client })
  }

  // ----- hibernation API -----

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const text = typeof message === "string" ? message : new TextDecoder().decode(message)

    // frame ceiling before any parse
    if (text.length > MAX_FRAME_BYTES) {
      metric("frame_rejected", { reason: "oversized", len: text.length })
      try { ws.close(4009, "frame too large") } catch { /* closed */ }
      return
    }

    if (!this.allow(ws)) {
      try { ws.close(4008, "rate limit") } catch { /* closed */ }
      return
    }

    let msg: any
    try {
      msg = JSON.parse(text)
    } catch {
      metric("frame_rejected", { reason: "bad_json" })
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
      default:
        break
    }

    await this.touchActivity()
  }

  async webSocketClose(ws: WebSocket, code: number, _reason: string, _clean: boolean): Promise<void> {
    const s = ws.deserializeAttachment() as SocketState | null
    if (s?.presence) {
      this.broadcast({ t: "leave", clientId: s.presence.clientId }, ws)
    }
    try { ws.close(code, "closing") } catch { /* already closed */ }

    // if this was the last socket, schedule idle expiry
    if (this.state.getWebSockets().length <= 1) {
      await this.touchActivity()
    }
  }

  async webSocketError(_ws: WebSocket, _err: unknown): Promise<void> {}

  async alarm(): Promise<void> {
    const now = Date.now()
    const sockets = this.state.getWebSockets()

    if (sockets.length > 0) {
      // room is active, reschedule
      await this.state.storage.setAlarm(now + IDLE_TTL_MS)
      return
    }

    const lastActivity = (await this.state.storage.get<number>("meta:lastActivity")) ?? 0
    const elapsed = now - lastActivity

    if (elapsed >= IDLE_TTL_MS) {
      // room has been idle long enough, delete everything
      metric("room_expired")
      await this.state.storage.deleteAll()
    } else {
      // not yet expired, reschedule for remaining TTL
      await this.state.storage.setAlarm(now + (IDLE_TTL_MS - elapsed))
    }
  }

  // ----- presence (ephemeral, never stored) -----

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

  // ----- core -----

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
    // strict field validation
    const id = msg.id
    if (typeof id !== "string" || id.length === 0 || id.length > 256) return
    const v = msg.v
    if (typeof v !== "number" || !Number.isSafeInteger(v) || v < 0 || v > MAX_V) return
    const by = typeof msg.by === "string" ? msg.by.slice(0, 128) : ""
    if (by.length === 0) return
    const kind = typeof msg.kind === "string" ? msg.kind.slice(0, 32) : ""
    if (kind.length === 0 && !deleted) kind || (deleted ? "unknown" : undefined)
    const ct = typeof msg.ct === "string" ? msg.ct : ""
    if (ct.length > CT_MAX) return
    // puts require ciphertext; deletes require sealed proof
    if (ct.length === 0) return

    const key = "obj:" + id
    let seq: number | undefined

    await this.state.storage.transaction(async (txn) => {
      const existing = await txn.get<SyncRecord>(key)

      // reject phantom deletes: can't delete what was never stored
      if (deleted && existing === undefined) {
        metric("phantom_delete_rejected")
        return
      }

      if (existing && !isNewer({ v, by }, existing)) return

      const records = (await txn.get<number>("meta:records")) ?? 0
      const bytes = (await txn.get<number>("meta:bytes")) ?? 0
      const currentSeq = (await txn.get<number>("meta:seq")) ?? 0

      const newRecBytes = recordBytes({ id, v, by, kind: kind || "unknown", ct, deleted })
      const oldRecBytes = existing ? recordBytes(existing) : 0
      const isNewRecord = existing === undefined

      let newRecords = records
      let newBytes = bytes

      if (isNewRecord) {
        newRecords = records + 1
        newBytes = bytes + newRecBytes
      } else {
        newBytes = bytes - oldRecBytes + newRecBytes
      }

      // quota check
      if (newRecords > MAX_RECORDS) {
        metric("quota_exceeded", { type: "records", current: newRecords })
        return
      }
      if (newBytes > MAX_STORED_BYTES) {
        metric("quota_exceeded", { type: "bytes", current: newBytes })
        return
      }

      const record: SyncRecord = {
        id, v, by,
        kind: kind || "unknown",
        ct, deleted,
        ...(deleted ? { deletedAt: Date.now() } : {}),
      }

      seq = currentSeq + 1
      await txn.put(key, record)
      await txn.put("meta:records", newRecords)
      await txn.put("meta:bytes", newBytes)
      await txn.put("meta:seq", seq)
    })

    // broadcast only if transaction committed (seq was set)
    if (seq !== undefined) {
      const key2 = "obj:" + id
      const stored = await this.state.storage.get<SyncRecord>(key2)
      if (stored) {
        this.broadcast({ t: deleted ? "del" : "put", ...stored, seq }, sender)
      }
    }
  }

  private async sendSnapshot(ws: WebSocket): Promise<void> {
    const seq = (await this.state.storage.get<number>("meta:seq")) ?? 0
    const members = this.collectMembers(ws)

    try {
      ws.send(JSON.stringify({ t: "snapshot-begin", seq }))

      let cursor: string | undefined
      let isFirst = true
      let sent = false

      while (true) {
        const opts: DurableObjectListOptions = { prefix: "obj:", limit: SNAPSHOT_CHUNK }
        if (cursor) opts.startAfter = cursor
        const page = await this.state.storage.list<SyncRecord>(opts)

        if (page.size === 0) {
          if (isFirst) {
            ws.send(JSON.stringify({ t: "snapshot", items: [], members }))
          }
          break
        }

        const items = [...page.values()]
        const keys = [...page.keys()]
        cursor = keys[keys.length - 1]
        const hasMore = page.size === SNAPSHOT_CHUNK

        const payload: Record<string, unknown> = { t: "snapshot", items, more: hasMore }
        if (isFirst) payload.members = members
        ws.send(JSON.stringify(payload))

        isFirst = false
        sent = true
        if (!hasMore) break
      }

      ws.send(JSON.stringify({ t: "snapshot-end", seq }))
    } catch {
      // socket closed mid-snapshot
    }
  }

  private broadcast(payload: unknown, except: WebSocket): void {
    const data = JSON.stringify(payload)
    for (const ws of this.state.getWebSockets()) {
      if (ws === except) continue
      try { ws.send(data) } catch { /* dead socket */ }
    }
  }

  private async touchActivity(): Promise<void> {
    const now = Date.now()
    await this.state.storage.put("meta:lastActivity", now)
    const existing = await this.state.storage.getAlarm()
    if (existing === null) {
      await this.state.storage.setAlarm(now + IDLE_TTL_MS)
    }
  }
}
