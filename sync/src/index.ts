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

const ROOM_V2_RE = /^\/room\/([A-Za-z0-9_-]{32,128})$/
const ROOM_V3_RE = /^\/v3\/room\/([A-Za-z0-9_-]{32,128})$/

const ALLOWED_ORIGINS = new Set<string>([])

// abuse ceilings
const MAX_CONNECTIONS = 64
const MAX_RECORDS = 10_000
const MAX_STORED_BYTES = 50_000_000
const MAX_V = 1e12
const CT_MAX = 700_000
const MAX_FRAME_CHARS = 1_048_576
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
    const v2Match = url.pathname.match(ROOM_V2_RE)
    const v3Match = url.pathname.match(ROOM_V3_RE)
    const match = v2Match || v3Match
    if (!match) return new Response("Not found", { status: 404 })
    const protocol = v3Match ? 3 : 2
    if (req.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("Expected a WebSocket upgrade", { status: 426 })
    }
    const origin = req.headers.get("Origin")
    if (origin !== null && !ALLOWED_ORIGINS.has(origin)) {
      return new Response("Forbidden origin", { status: 403 })
    }
    if (env.CONN_LIMITER) {
      const ip = req.headers.get("CF-Connecting-IP") ?? "unknown"
      const { success } = await env.CONN_LIMITER.limit({ key: ip })
      if (!success) {
        metric("conn_rate_limited")
        return new Response("Too many requests", { status: 429 })
      }
    }
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName(match[1]))
    // pass protocol version to the DO via header
    const doReq = new Request(req.url, req)
    doReq.headers.set("X-Protocol", String(protocol))
    doReq.headers.set("X-Room-Id", match[1])
    return stub.fetch(doReq)
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

interface SyncRecordV3 {
  id: string
  vs: string
  by: string
  kind: string
  ct: string
  deleted: boolean
  pub: string
}

interface SocketState {
  windowStart: number
  msgs: number
  protocol: 2 | 3
  presence?: PresenceInfo
}

interface PresenceInfo {
  clientId: string
  ct: string
}

function isNewer(a: { v: number; by: string }, b: { v: number; by: string }): boolean {
  return a.v > b.v || (a.v === b.v && a.by > b.by)
}

// v3 VersionStamp: "counterHex16:actorId"
function parseStamp(vs: string): { counter: bigint; actorId: string } | null {
  const colon = vs.indexOf(":")
  if (colon !== 16) return null
  const hex = vs.slice(0, 16)
  if (!/^[0-9a-f]{16}$/.test(hex)) return null
  const actorId = vs.slice(17)
  if (actorId.length === 0 || actorId.length > 64) return null
  return { counter: BigInt("0x" + hex), actorId }
}

function isNewerStamp(incoming: string, existing: string): boolean {
  const a = parseStamp(incoming)
  const b = parseStamp(existing)
  if (!a || !b) return false
  if (a.counter !== b.counter) return a.counter > b.counter
  return a.actorId > b.actorId
}

const MAX_COUNTER = 0x7FFFFFFFFFFFFFFFn
const ADVANCE_WINDOW = 10_000n

function constantTimeEqual(a: string, b: string): boolean {
  const enc = new TextEncoder()
  const ab = enc.encode(a)
  const bb = enc.encode(b)
  const maxLen = Math.max(ab.length, bb.length)
  let diff = ab.length ^ bb.length
  for (let i = 0; i < maxLen; i++) diff |= (ab[i] ?? 0) ^ (bb[i] ?? 0)
  return diff === 0
}

async function hashToken(token: string): Promise<string> {
  const data = new TextEncoder().encode(token)
  const hash = await crypto.subtle.digest("SHA-256", data)
  return [...new Uint8Array(hash)].map(b => b.toString(16).padStart(2, "0")).join("")
}

function base64urlDecode(s: string): ArrayBuffer | null {
  try {
    const padded = s.replace(/-/g, "+").replace(/_/g, "/")
    const pad = (4 - (padded.length % 4)) % 4
    const b64 = padded + "=".repeat(pad)
    const bin = atob(b64)
    const bytes = new Uint8Array(bin.length)
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
    return bytes.buffer
  } catch {
    return null
  }
}

function uint8ToBase64url(bytes: Uint8Array): string {
  let bin = ""
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i])
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
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

    const protocol = parseInt(req.headers.get("X-Protocol") ?? "2", 10) as 2 | 3
    const requestedRoomId = req.headers.get("X-Room-Id") ?? ""

    // check protocol version consistency
    const storedProtocol = await this.state.storage.get<number>("meta:protocol")
    if (storedProtocol !== undefined && storedProtocol !== protocol) {
      return new Response("Protocol mismatch", { status: 426 })
    }

    if (protocol === 3) {
      // v3: verified auth binding -- SHA-256("tacmap-room-id-v3\0" || rawToken) must == roomId
      const rawToken = base64urlDecode(token)
      if (!rawToken || rawToken.byteLength !== 32) {
        return new Response("Unauthorized", { status: 401 })
      }
      const prefix = new TextEncoder().encode("tacmap-room-id-v3\0")
      const concat = new Uint8Array(prefix.length + rawToken.byteLength)
      concat.set(prefix)
      concat.set(new Uint8Array(rawToken), prefix.length)
      const computed = await crypto.subtle.digest("SHA-256", concat)
      const computedB64 = uint8ToBase64url(new Uint8Array(computed))
      if (computedB64 !== requestedRoomId) {
        return new Response("Forbidden", { status: 403 })
      }
      // v3 pins the verified hash (same as v2 pattern but we verified first)
      const tokenHash = await hashToken(token)
      const pinned = await this.state.storage.get<string>("meta:auth")
      if (pinned === undefined) {
        if (this.env.ROOM_LIMITER) {
          const ip = req.headers.get("CF-Connecting-IP") ?? "unknown"
          const { success } = await this.env.ROOM_LIMITER.limit({ key: ip })
          if (!success) {
            metric("room_rate_limited")
            return new Response("Too many rooms", { status: 429 })
          }
        }
        await this.state.storage.put("meta:auth", tokenHash)
        await this.state.storage.put("meta:protocol", 3)
      } else if (!constantTimeEqual(pinned, tokenHash)) {
        return new Response("Forbidden", { status: 403 })
      }
    } else {
      // v2: blind TOFU (existing behavior)
      const tokenHash = await hashToken(token)
      const pinned = await this.state.storage.get<string>("meta:auth")
      if (pinned === undefined) {
        if (this.env.ROOM_LIMITER) {
          const ip = req.headers.get("CF-Connecting-IP") ?? "unknown"
          const { success } = await this.env.ROOM_LIMITER.limit({ key: ip })
          if (!success) {
            metric("room_rate_limited")
            return new Response("Too many rooms", { status: 429 })
          }
        }
        await this.state.storage.put("meta:auth", tokenHash)
        await this.state.storage.put("meta:protocol", 2)
      } else if (!constantTimeEqual(pinned, tokenHash)) {
        return new Response("Forbidden", { status: 403 })
      }
    }

    if (this.state.getWebSockets().length >= MAX_CONNECTIONS) {
      return new Response("Room full", { status: 503 })
    }

    const pair = new WebSocketPair()
    const client = pair[0]
    const server = pair[1]
    this.state.acceptWebSocket(server)
    server.serializeAttachment({ windowStart: 0, msgs: 0, protocol } satisfies SocketState)

    await this.sendSnapshot(server)
    await this.touchActivity()

    return new Response(null, { status: 101, webSocket: client })
  }

  // ----- hibernation API -----

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const text = typeof message === "string" ? message : new TextDecoder().decode(message)

    // frame ceiling before any parse
    if (text.length > MAX_FRAME_CHARS) {
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

    const socketState = ws.deserializeAttachment() as SocketState | null
    const proto = socketState?.protocol ?? 2

    switch (msg?.t) {
      case "put":
        if (proto === 3) {
          await this.applyChangeV3(ws, msg, false)
        } else {
          await this.applyChange(ws, msg, false)
        }
        await this.touchActivity()
        break
      case "del":
        if (proto === 3) {
          await this.applyChangeV3(ws, msg, true)
        } else {
          await this.applyChange(ws, msg, true)
        }
        await this.touchActivity()
        break
      case "loc":
        if (proto === 3) {
          await this.handlePresenceV3(ws, msg)
        } else {
          this.handlePresence(ws, msg)
        }
        await this.touchActivity()
        break
      case "hello":
        if (proto === 3) await this.handleHello(ws, msg)
        break
      case "ping":
        try { ws.send(JSON.stringify({ t: "pong" })) } catch { /* closed */ }
        break
      default:
        break
    }
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
    const s = (sender.deserializeAttachment() as SocketState | null) ?? { windowStart: 0, msgs: 0, protocol: 2 as const }
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
    const s = (ws.deserializeAttachment() as SocketState | null) ?? { windowStart: now, msgs: 0, protocol: 2 as const }
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
    if (kind.length === 0 && !deleted) return
    const ct = typeof msg.ct === "string" ? msg.ct : ""
    if (ct.length > CT_MAX) return
    // puts require ciphertext; deletes require sealed proof
    if (ct.length === 0) return

    const key = "obj:" + id
    let seq: number | undefined
    let committed: SyncRecord | undefined

    await this.state.storage.transaction(async (txn) => {
      const existing = await txn.get<SyncRecord>(key)

      // reject phantom deletes: can't delete what was never stored
      if (deleted && existing === undefined) {
        metric("phantom_delete_rejected")
        return
      }

      if (existing && !isNewer({ v, by }, existing)) return

      const liveRecords = (await txn.get<number>("meta:liveRecords")) ?? 0
      const totalRecords = (await txn.get<number>("meta:totalRecords")) ?? 0
      const bytes = (await txn.get<number>("meta:bytes")) ?? 0
      const currentSeq = (await txn.get<number>("meta:seq")) ?? 0

      const newRecBytes = recordBytes({ id, v, by, kind: kind || "unknown", ct, deleted })
      const oldRecBytes = existing ? recordBytes(existing) : 0
      const isNewRecord = existing === undefined
      const wasPreviouslyDeleted = existing?.deleted === true

      let newLiveRecords = liveRecords
      let newTotalRecords = totalRecords
      let newBytes = bytes

      if (isNewRecord) {
        newTotalRecords = totalRecords + 1
        if (!deleted) newLiveRecords = liveRecords + 1
        newBytes = bytes + newRecBytes
      } else {
        // transitioning live -> deleted: decrement live count
        if (deleted && !wasPreviouslyDeleted) newLiveRecords = liveRecords - 1
        // transitioning deleted -> live (re-put after tombstone): increment live count
        if (!deleted && wasPreviouslyDeleted) newLiveRecords = liveRecords + 1
        newBytes = bytes - oldRecBytes + newRecBytes
      }

      // quota check: cap on live records (tombstones don't count against it)
      if (newLiveRecords > MAX_RECORDS) {
        metric("quota_exceeded", { type: "records", current: newLiveRecords })
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
      committed = record
      await txn.put(key, record)
      await txn.put("meta:liveRecords", newLiveRecords)
      await txn.put("meta:totalRecords", newTotalRecords)
      await txn.put("meta:bytes", newBytes)
      await txn.put("meta:seq", seq)
    })

    if (seq !== undefined && committed) {
      this.broadcast({ t: deleted ? "del" : "put", ...committed, seq }, sender)
    }
  }

  // ----- v3 protocol handlers -----

  private async handleHello(ws: WebSocket, msg: any): Promise<void> {
    const by = typeof msg.by === "string" ? msg.by : ""
    const pub = typeof msg.pub === "string" ? msg.pub : ""
    const sd = typeof msg.sd === "string" ? msg.sd : ""
    if (by.length < 20 || by.length > 64) return
    if (pub.length < 20 || pub.length > 64) return
    if (sd.length < 20 || sd.length > 64) return

    const ok = await this.registerActor(by, pub)
    if (!ok) {
      try { ws.close(4010, "actor key mismatch") } catch { /* closed */ }
      return
    }
    // broadcast hello to all other sockets
    this.broadcast({ t: "hello", by, pub, sd }, ws)
  }

  private async registerActor(actorId: string, pubkey: string): Promise<boolean> {
    const key = "actor:" + actorId
    const existing = await this.state.storage.get<{ pubkey: string; firstSeen: number }>(key)
    if (existing === undefined) {
      await this.state.storage.put(key, { pubkey, firstSeen: Date.now() })
      return true
    }
    return existing.pubkey === pubkey
  }

  private async applyChangeV3(sender: WebSocket, msg: any, deleted: boolean): Promise<void> {
    const id = msg.id
    if (typeof id !== "string" || id.length === 0 || id.length > 64) return
    const vs = msg.vs
    if (typeof vs !== "string") return
    const parsed = parseStamp(vs)
    if (!parsed) return
    if (parsed.counter < 0n || parsed.counter > MAX_COUNTER) return
    const by = typeof msg.by === "string" ? msg.by : ""
    if (by.length < 20 || by.length > 64) return
    // actorId in stamp must match by field
    if (parsed.actorId !== by) return
    const kind = typeof msg.kind === "string" ? msg.kind.slice(0, 32) : ""
    if (kind.length === 0) return
    const ct = typeof msg.ct === "string" ? msg.ct : ""
    if (ct.length > CT_MAX || ct.length === 0) return
    const pub = typeof msg.pub === "string" ? msg.pub : ""
    if (pub.length < 20 || pub.length > 64) return

    // register/verify actor
    const actorOk = await this.registerActor(by, pub)
    if (!actorOk) {
      try { sender.close(4010, "actor key mismatch") } catch { /* closed */ }
      return
    }

    const key = "obj:" + id
    let seq: number | undefined
    let committed: SyncRecordV3 | undefined

    await this.state.storage.transaction(async (txn) => {
      const existing = await txn.get<SyncRecordV3>(key)

      if (deleted && existing === undefined) {
        metric("phantom_delete_rejected")
        return
      }

      if (existing && !isNewerStamp(vs, existing.vs)) return

      // counter advance window check
      const roomHW = (await txn.get<string>("meta:highWater")) ?? "0000000000000000"
      const hwParsed = parseStamp(roomHW + ":x")
      const hwCounter = hwParsed ? hwParsed.counter : 0n
      if (parsed.counter > hwCounter + ADVANCE_WINDOW) {
        metric("counter_advance_rejected", { counter: vs })
        return
      }

      const liveRecords = (await txn.get<number>("meta:liveRecords")) ?? 0
      const totalRecords = (await txn.get<number>("meta:totalRecords")) ?? 0
      const bytes = (await txn.get<number>("meta:bytes")) ?? 0
      const currentSeq = (await txn.get<number>("meta:seq")) ?? 0

      const record: SyncRecordV3 = { id, vs, by, kind, ct, deleted, pub }
      const newRecBytes = record.ct.length + record.id.length + record.by.length + record.kind.length + record.vs.length + 80
      const oldRecBytes = existing ? existing.ct.length + existing.id.length + existing.by.length + existing.kind.length + existing.vs.length + 80 : 0
      const isNewRecord = existing === undefined
      const wasPreviouslyDeleted = existing?.deleted === true

      let newLiveRecords = liveRecords
      let newTotalRecords = totalRecords
      let newBytes = bytes

      if (isNewRecord) {
        newTotalRecords = totalRecords + 1
        if (!deleted) newLiveRecords = liveRecords + 1
        newBytes = bytes + newRecBytes
      } else {
        if (deleted && !wasPreviouslyDeleted) newLiveRecords = liveRecords - 1
        if (!deleted && wasPreviouslyDeleted) newLiveRecords = liveRecords + 1
        newBytes = bytes - oldRecBytes + newRecBytes
      }

      if (newLiveRecords > MAX_RECORDS) {
        metric("quota_exceeded", { type: "records", current: newLiveRecords })
        return
      }
      if (newBytes > MAX_STORED_BYTES) {
        metric("quota_exceeded", { type: "bytes", current: newBytes })
        return
      }

      seq = currentSeq + 1
      committed = record
      await txn.put(key, record)
      await txn.put("meta:liveRecords", newLiveRecords)
      await txn.put("meta:totalRecords", newTotalRecords)
      await txn.put("meta:bytes", newBytes)
      await txn.put("meta:seq", seq)

      // update room high-water counter
      if (parsed.counter > hwCounter) {
        await txn.put("meta:highWater", parsed.counter.toString(16).padStart(16, "0"))
      }
    })

    if (seq !== undefined && committed) {
      this.broadcast({ t: deleted ? "del" : "put", ...committed, seq }, sender)
    }
  }

  private async handlePresenceV3(ws: WebSocket, msg: any): Promise<void> {
    const by = typeof msg.by === "string" ? msg.by : ""
    const ct = typeof msg.ct === "string" ? msg.ct : ""
    const pub = typeof msg.pub === "string" ? msg.pub : ""
    const vs = typeof msg.vs === "string" ? msg.vs : ""
    if (by.length < 20 || by.length > 64) return
    if (!ct || ct.length > CT_MAX) return
    if (pub.length < 20 || pub.length > 64) return
    if (!parseStamp(vs)) return

    const actorOk = await this.registerActor(by, pub)
    if (!actorOk) {
      try { ws.close(4010, "actor key mismatch") } catch { /* closed */ }
      return
    }

    const s = (ws.deserializeAttachment() as SocketState | null) ?? { windowStart: 0, msgs: 0, protocol: 3 as const }
    s.presence = { clientId: by, ct }
    ws.serializeAttachment(s)
    this.broadcast({ t: "loc", by, ct, pub, vs }, ws)
  }

  // ----- snapshot -----

  private async sendSnapshot(ws: WebSocket): Promise<void> {
    const seq = (await this.state.storage.get<number>("meta:seq")) ?? 0
    const members = this.collectMembers(ws)

    try {
      ws.send(JSON.stringify({ t: "snapshot-begin", seq }))

      let cursor: string | undefined
      let isFirst = true

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
