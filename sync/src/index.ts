/// <reference types="@cloudflare/workers-types" />

// TacMap sync relay. The relay is E2E-blind: it validates routing metadata,
// storage budgets and v3 actor proofs, but never receives the room key.

export interface Env {
  SYNC_ROOM: DurableObjectNamespace
  CONN_LIMITER?: RateLimit
  ROOM_LIMITER?: RateLimit
}

const ROOM_V2_RE = /^\/room\/([A-Za-z0-9_-]{32,128})$/
const ROOM_V3_RE = /^\/v3\/room\/([A-Za-z0-9_-]{43})$/
const ALLOWED_ORIGINS = new Set<string>([])

const MAX_CONNECTIONS = 64
const MAX_RECORDS = 10_000 // objects + retained tombstones + actor pins
const MAX_STORED_BYTES = 50_000_000
const MAX_V = 1e12
const CT_MAX = 700_000
const PRESENCE_CT_MAX = 8_192
const MAX_FRAME_CHARS = 1_048_576
const SNAPSHOT_FRAME_BYTES = 900_000
const RATE_WINDOW_MS = 10_000
const RATE_MAX_MSGS = 200
const STORAGE_PAGE_SIZE = 100
const IDLE_TTL_MS = 7 * 24 * 60 * 60 * 1000
const MAX_COUNTER = 0x7fffffffffffffffn
const MAX_U64 = 0xffffffffffffffffn
const ADVANCE_WINDOW = 10_000n
const ZERO_COUNTER = "0000000000000000"
const B64URL_32_RE = /^[A-Za-z0-9_-]{43}$/
const B64URL_64_RE = /^[A-Za-z0-9_-]{86}$/

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
  sd: string
}

interface ActorRecord {
  pubkey: string
  firstSeen: number
  helloEpoch?: string
  hello?: HelloFrame
}

interface HelloFrame {
  t: "hello"
  by: string
  pub: string
  sd: string
  vs: string
  sig: string
}

interface PresenceV2 {
  clientId: string
  ct: string
}

interface PresenceV3 {
  t: "loc"
  by: string
  pub: string
  sd: string
  vs: string
  ct: string
}

interface SocketState {
  windowStart: number
  msgs: number
  protocol: 2 | 3
  roomId: string
  hello?: HelloFrame
  presenceV2?: PresenceV2
  presenceV3?: PresenceV3
  presenceCounter?: string
}

interface SnapshotState {
  seq: number
  highWater: string
}

function metric(name: string, fields?: Record<string, string | number>): void {
  console.log(JSON.stringify({ metric: name, ...fields }))
}

function isNewer(a: { v: number; by: string }, b: { v: number; by: string }): boolean {
  return a.v > b.v || (a.v === b.v && a.by > b.by)
}

export function parseStamp(vs: string): { counter: bigint; actorId: string } | null {
  if (vs.length !== 60 || vs[16] !== ":") return null
  const hex = vs.slice(0, 16)
  const actorId = vs.slice(17)
  if (!/^[0-7][0-9a-f]{15}$/.test(hex) || !B64URL_32_RE.test(actorId)) return null
  return { counter: BigInt("0x" + hex), actorId }
}

function isNewerStamp(incoming: string, existing: string): boolean {
  const a = parseStamp(incoming)
  const b = parseStamp(existing)
  if (!a || !b) return false
  return a.counter === b.counter ? a.actorId > b.actorId : a.counter > b.counter
}

function parseHelloEpoch(vs: string, actorId: string): { hex: string; value: bigint } | null {
  if (vs.length !== 60 || vs.slice(17) !== actorId || vs[16] !== ":") return null
  const hex = vs.slice(0, 16)
  if (!/^[0-9a-f]{16}$/.test(hex)) return null
  const value = BigInt("0x" + hex)
  if (value <= 0n || value > MAX_U64) return null
  return { hex, value }
}

function constantTimeEqual(a: string, b: string): boolean {
  const ab = new TextEncoder().encode(a)
  const bb = new TextEncoder().encode(b)
  const maxLen = Math.max(ab.length, bb.length)
  let diff = ab.length ^ bb.length
  for (let i = 0; i < maxLen; i++) diff |= (ab[i] ?? 0) ^ (bb[i] ?? 0)
  return diff === 0
}

function base64urlDecode(s: string, expectedBytes?: number): Uint8Array | null {
  if (!/^[A-Za-z0-9_-]+$/.test(s)) return null
  try {
    const padded = s.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - s.length % 4) % 4)
    const bin = atob(padded)
    const bytes = Uint8Array.from(bin, c => c.charCodeAt(0))
    if (expectedBytes !== undefined && bytes.length !== expectedBytes) return null
    if (uint8ToBase64url(bytes) !== s) return null
    return bytes
  } catch {
    return null
  }
}

function uint8ToBase64url(bytes: Uint8Array): string {
  let bin = ""
  for (const byte of bytes) bin += String.fromCharCode(byte)
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
}

function isValidCiphertext(s: unknown, maxChars: number): s is string {
  if (typeof s !== "string" || s.length === 0 || s.length > maxChars || s.length % 4 !== 0) return false
  if (!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(s)) return false
  // AES-GCM combined form is nonce(12) + at least tag(16).
  const padding = s.endsWith("==") ? 2 : s.endsWith("=") ? 1 : 0
  return (s.length / 4) * 3 - padding >= 28
}

function utf8Length(s: string): number {
  return new TextEncoder().encode(s).length
}

function storageBytes(key: string, value: unknown): number {
  return utf8Length(key) + utf8Length(JSON.stringify(value))
}

// Preserve the accounting formula used by already-deployed v2 rooms so an
// update cannot make meta:bytes drift when replacing an existing record.
function recordBytesV2(record: SyncRecord): number {
  return record.ct.length + record.id.length + record.by.length + record.kind.length + 64
}

function recordBytesV3(record: SyncRecordV3): number {
  // Records written by the pre-ADR implementation did not contain sd and used
  // the older +80 formula. Detect them during dormant-v3 migration.
  if (typeof record.sd !== "string") {
    return record.ct.length + record.id.length + record.by.length + record.kind.length + record.vs.length + 80
  }
  return record.ct.length + record.id.length + record.by.length + record.kind.length + record.vs.length + record.pub.length + record.sd.length + 128
}

async function hashToken(token: string): Promise<string> {
  const hash = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token))
  return [...new Uint8Array(hash)].map(b => b.toString(16).padStart(2, "0")).join("")
}

async function actorIdFor(roomId: string, pub: string): Promise<string | null> {
  const room = base64urlDecode(roomId, 32)
  const pubBytes = base64urlDecode(pub, 32)
  if (!room || !pubBytes) return null
  const prefix = new TextEncoder().encode("tacmap-actor-v3\0")
  const input = new Uint8Array(prefix.length + room.length + pubBytes.length)
  input.set(prefix)
  input.set(room, prefix.length)
  input.set(pubBytes, prefix.length + room.length)
  return uint8ToBase64url(new Uint8Array(await crypto.subtle.digest("SHA-256", input)))
}

function appendLe16(out: number[], value: number): void {
  out.push(value & 0xff, (value >>> 8) & 0xff)
}

async function helloPreimage(roomId: string, by: string, sd: string, pub: string, epochHex: string): Promise<Uint8Array | null> {
  const room = base64urlDecode(roomId, 32)
  const session = base64urlDecode(sd, 32)
  const pubBytes = base64urlDecode(pub, 32)
  if (!room || !session || !pubBytes) return null
  const actor = new TextEncoder().encode(by)
  const kind = new TextEncoder().encode("hello")
  const counter = new TextEncoder().encode(epochHex)
  const payloadHash = new Uint8Array(await crypto.subtle.digest("SHA-256", pubBytes))
  const out: number[] = [0x04, 0x03, ...room]
  appendLe16(out, actor.length)
  out.push(...actor, ...session, ...counter)
  appendLe16(out, 0)
  out.push(kind.length, ...kind, ...payloadHash)
  return Uint8Array.from(out)
}

async function verifyHello(roomId: string, frame: HelloFrame): Promise<boolean> {
  const signature = base64urlDecode(frame.sig, 64)
  const pub = base64urlDecode(frame.pub, 32)
  const epoch = parseHelloEpoch(frame.vs, frame.by)
  if (!epoch) return false
  const preimage = await helloPreimage(roomId, frame.by, frame.sd, frame.pub, epoch.hex)
  if (!signature || !pub || !preimage) return false
  try {
    const key = await crypto.subtle.importKey("raw", pub, { name: "Ed25519" }, false, ["verify"])
    return await crypto.subtle.verify({ name: "Ed25519" }, key, signature, preimage)
  } catch {
    return false
  }
}

function parseHello(msg: unknown): HelloFrame | null {
  if (!msg || typeof msg !== "object") return null
  const m = msg as Record<string, unknown>
  const by = m.by
  const pub = m.pub
  const sd = m.sd
  const vs = m.vs
  const sig = m.sig
  if (typeof by !== "string" || !B64URL_32_RE.test(by)) return null
  if (typeof pub !== "string" || !B64URL_32_RE.test(pub)) return null
  if (typeof sd !== "string" || !B64URL_32_RE.test(sd)) return null
  if (typeof sig !== "string" || !B64URL_64_RE.test(sig)) return null
  if (typeof vs !== "string" || !parseHelloEpoch(vs, by)) return null
  return { t: "hello", by, pub, sd, vs, sig }
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url)
    if (url.pathname === "/health") return new Response("ok", { headers: { "content-type": "text/plain" } })
    const v2 = url.pathname.match(ROOM_V2_RE)
    const v3 = url.pathname.match(ROOM_V3_RE)
    const match = v2 || v3
    if (!match) return new Response("Not found", { status: 404 })
    if (req.headers.get("Upgrade")?.toLowerCase() !== "websocket") return new Response("Expected a WebSocket upgrade", { status: 426 })
    const origin = req.headers.get("Origin")
    if (origin !== null && !ALLOWED_ORIGINS.has(origin)) return new Response("Forbidden origin", { status: 403 })
    if (env.CONN_LIMITER) {
      const { success } = await env.CONN_LIMITER.limit({ key: req.headers.get("CF-Connecting-IP") ?? "unknown" })
      if (!success) {
        metric("conn_rate_limited")
        return new Response("Too many requests", { status: 429 })
      }
    }
    // Protocol namespaces are deliberately disjoint. A v2 room created first
    // with the same visible room ID can never pin or preclaim the v3 object.
    const objectName = v3 ? `v3:${match[1]}` : match[1]
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName(objectName))
    const forwarded = new Request(req.url, req)
    forwarded.headers.set("X-Protocol", v3 ? "3" : "2")
    forwarded.headers.set("X-Room-Id", match[1])
    return stub.fetch(forwarded)
  },
}

export class SyncRoom {
  constructor(private state: DurableObjectState, private env: Env) {}

  async fetch(req: Request): Promise<Response> {
    const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "")
    if (token.length < 20 || token.length > 200) return new Response("Unauthorized", { status: 401 })
    const protocolRaw = req.headers.get("X-Protocol") ?? "2"
    if (protocolRaw !== "2" && protocolRaw !== "3") return new Response("Protocol mismatch", { status: 426 })
    const protocol = Number(protocolRaw) as 2 | 3
    const roomId = req.headers.get("X-Room-Id") ?? ""

    if (protocol === 3) {
      const rawToken = base64urlDecode(token, 32)
      const rawRoom = base64urlDecode(roomId, 32)
      if (!rawToken || !rawRoom) return new Response("Unauthorized", { status: 401 })
      const prefix = new TextEncoder().encode("tacmap-room-id-v3\0")
      const input = new Uint8Array(prefix.length + rawToken.length)
      input.set(prefix)
      input.set(rawToken, prefix.length)
      const computed = uint8ToBase64url(new Uint8Array(await crypto.subtle.digest("SHA-256", input)))
      if (!constantTimeEqual(computed, roomId)) return new Response("Forbidden", { status: 403 })
    }

    const tokenHash = await hashToken(token)
    const storedProtocol = await this.state.storage.get<number>("meta:protocol")
    const pinned = await this.state.storage.get<string>("meta:auth")
    if ((storedProtocol === undefined) !== (pinned === undefined)) return new Response("Room metadata incomplete", { status: 503 })
    if (storedProtocol !== undefined && storedProtocol !== protocol) return new Response("Protocol mismatch", { status: 426 })
    if (pinned !== undefined && !constantTimeEqual(pinned, tokenHash)) return new Response("Forbidden", { status: 403 })

    if (pinned === undefined) {
      if (this.env.ROOM_LIMITER) {
        const { success } = await this.env.ROOM_LIMITER.limit({ key: req.headers.get("CF-Connecting-IP") ?? "unknown" })
        if (!success) {
          metric("room_rate_limited")
          return new Response("Too many rooms", { status: 429 })
        }
      }
      try {
        await this.state.storage.transaction(async txn => {
          const auth = await txn.get<string>("meta:auth")
          const existingProtocol = await txn.get<number>("meta:protocol")
          if (auth !== undefined && !constantTimeEqual(auth, tokenHash)) throw new Error("auth race")
          if (existingProtocol !== undefined && existingProtocol !== protocol) throw new Error("protocol race")
          await txn.put("meta:auth", tokenHash)
          await txn.put("meta:protocol", protocol)
        })
      } catch {
        return new Response("Room initialization failed", { status: 503 })
      }
    }

    if (protocol === 3) {
      try {
        await this.state.blockConcurrencyWhile(async () => this.ensureV3Accounting())
      } catch {
        metric("storage_error", { operation: "accounting_migration" })
        return new Response("Room accounting unavailable", { status: 503 })
      }
    }

    if (this.state.getWebSockets().length >= MAX_CONNECTIONS) return new Response("Room full", { status: 503 })
    const pair = new WebSocketPair()
    const client = pair[0]
    const server = pair[1]
    this.state.acceptWebSocket(server)
    server.serializeAttachment({ windowStart: 0, msgs: 0, protocol, roomId } satisfies SocketState)
    try {
      // No mutation event can interleave with this fence/pages/end sequence.
      await this.state.blockConcurrencyWhile(async () => this.sendSnapshot(server, protocol))
      await this.touchActivity()
    } catch {
      metric("storage_error", { operation: "snapshot" })
      try { server.close(1011, "snapshot unavailable") } catch { /* closed */ }
      return new Response("Snapshot unavailable", { status: 503 })
    }
    return new Response(null, { status: 101, webSocket: client })
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const text = typeof message === "string" ? message : new TextDecoder().decode(message)
    if (text.length > MAX_FRAME_CHARS) {
      metric("frame_rejected", { reason: "oversized", len: text.length })
      try { ws.close(4009, "frame too large") } catch { /* closed */ }
      return
    }
    if (!this.allow(ws)) {
      try { ws.close(4008, "rate limit") } catch { /* closed */ }
      return
    }
    let msg: unknown
    try { msg = JSON.parse(text) } catch {
      metric("frame_rejected", { reason: "bad_json" })
      return
    }
    if (!msg || typeof msg !== "object") return
    const type = (msg as { t?: unknown }).t
    const socket = ws.deserializeAttachment() as SocketState | null
    const protocol = socket?.protocol ?? 2
    try {
      if (type === "put") await (protocol === 3 ? this.applyChangeV3(ws, msg, false) : this.applyChange(ws, msg, false))
      else if (type === "del") await (protocol === 3 ? this.applyChangeV3(ws, msg, true) : this.applyChange(ws, msg, true))
      else if (type === "loc") await (protocol === 3 ? this.handlePresenceV3(ws, msg) : this.handlePresence(ws, msg))
      else if (type === "hello" && protocol === 3) await this.handleHello(ws, msg)
      else if (type === "ping") { ws.send(JSON.stringify({ t: "pong" })); return }
      else return
      await this.touchActivity()
    } catch {
      metric("storage_error", { operation: "message" })
      try { ws.close(1011, "storage unavailable") } catch { /* closed */ }
    }
  }

  async webSocketClose(ws: WebSocket, code: number): Promise<void> {
    const s = ws.deserializeAttachment() as SocketState | null
    if (s?.presenceV2) this.broadcast({ t: "leave", clientId: s.presenceV2.clientId }, ws)
    if (s?.presenceV3) this.broadcast({ t: "leave", by: s.presenceV3.by }, ws)
    try { ws.close(code, "closing") } catch { /* closed */ }
    if (this.state.getWebSockets().length <= 1) {
      try { await this.touchActivity() } catch { metric("storage_error", { operation: "close" }) }
    }
  }

  async webSocketError(_ws: WebSocket, _err: unknown): Promise<void> {}

  async alarm(): Promise<void> {
    const now = Date.now()
    if (this.state.getWebSockets().length > 0) {
      await this.state.storage.setAlarm(now + IDLE_TTL_MS)
      return
    }
    const last = (await this.state.storage.get<number>("meta:lastActivity")) ?? 0
    if (now - last >= IDLE_TTL_MS) {
      metric("room_expired")
      await this.state.storage.deleteAll()
    } else {
      await this.state.storage.setAlarm(now + IDLE_TTL_MS - (now - last))
    }
  }

  private allow(ws: WebSocket): boolean {
    const now = Date.now()
    const s = (ws.deserializeAttachment() as SocketState | null) ?? { windowStart: now, msgs: 0, protocol: 2 as const, roomId: "" }
    if (now - s.windowStart > RATE_WINDOW_MS) { s.windowStart = now; s.msgs = 0 }
    s.msgs += 1
    ws.serializeAttachment(s)
    return s.msgs <= RATE_MAX_MSGS
  }

  private async handlePresence(sender: WebSocket, input: unknown): Promise<void> {
    const msg = input as Record<string, unknown>
    const clientId = typeof msg.clientId === "string" ? msg.clientId : ""
    if (!clientId || clientId.length > 128 || !isValidCiphertext(msg.ct, PRESENCE_CT_MAX)) return
    const s = sender.deserializeAttachment() as SocketState
    s.presenceV2 = { clientId, ct: msg.ct }
    sender.serializeAttachment(s)
    this.broadcast({ t: "loc", ...s.presenceV2 }, sender)
  }

  private async applyChange(sender: WebSocket, input: unknown, deleted: boolean): Promise<void> {
    const msg = input as Record<string, unknown>
    if (typeof msg.id !== "string" || msg.id.length === 0 || msg.id.length > 256) return
    if (typeof msg.v !== "number" || !Number.isSafeInteger(msg.v) || msg.v < 0 || msg.v > MAX_V) return
    if (typeof msg.by !== "string" || msg.by.length === 0 || msg.by.length > 128) return
    // Hardened Android/iOS v2 clients seal and sign deletes but historically
    // omitted the redundant outer `kind`. Normalize only that exact shape;
    // plaintext legacy deletes still fail the ciphertext check below.
    const kind = deleted && msg.kind === undefined ? "del" : msg.kind
    if (typeof kind !== "string" || !/^[A-Za-z0-9_-]{1,32}$/.test(kind)) return
    if ((deleted && kind !== "del") || (!deleted && kind === "del")) return
    if (!isValidCiphertext(msg.ct, CT_MAX)) return
    const record: SyncRecord = { id: msg.id, v: msg.v, by: msg.by, kind, ct: msg.ct, deleted, ...(deleted ? { deletedAt: Date.now() } : {}) }
    const key = "obj:" + record.id
    let seq: number | undefined
    await this.state.storage.transaction(async txn => {
      const existing = await txn.get<SyncRecord>(key)
      if ((deleted && existing === undefined) || (existing && !isNewer(record, existing))) return
      const total = (await txn.get<number>("meta:totalRecords")) ?? 0
      const bytes = (await txn.get<number>("meta:bytes")) ?? 0
      const nextTotal = total + (existing ? 0 : 1)
      const nextBytes = bytes - (existing ? recordBytesV2(existing) : 0) + recordBytesV2(record)
      if (nextTotal > MAX_RECORDS || nextBytes > MAX_STORED_BYTES) { metric("quota_exceeded", { type: nextTotal > MAX_RECORDS ? "records" : "bytes" }); return }
      seq = ((await txn.get<number>("meta:seq")) ?? 0) + 1
      await txn.put(key, record)
      await txn.put("meta:totalRecords", nextTotal)
      await txn.put("meta:bytes", nextBytes)
      await txn.put("meta:seq", seq)
    })
    if (seq !== undefined) this.broadcast({ t: deleted ? "del" : "put", ...record, seq }, sender)
  }

  private async handleHello(ws: WebSocket, input: unknown): Promise<void> {
    const frame = parseHello(input)
    const socket = ws.deserializeAttachment() as SocketState
    if (socket.protocol !== 3) return
    if (!frame) {
      metric("actor_rejected", { reason: "malformed_hello" })
      try { ws.close(4011, "invalid actor proof") } catch { /* closed */ }
      return
    }
    const computed = await actorIdFor(socket.roomId, frame.pub)
    if (!computed || !constantTimeEqual(computed, frame.by) || !await verifyHello(socket.roomId, frame)) {
      metric("actor_rejected", { reason: "invalid_proof" })
      try { ws.close(4011, "invalid actor proof") } catch { /* closed */ }
      return
    }
    if (socket.hello && JSON.stringify(socket.hello) !== JSON.stringify(frame)) {
      try { ws.close(4012, "actor already announced") } catch { /* closed */ }
      return
    }
    const result = await this.registerActor(frame)
    if (result !== "ok") {
      const code = result === "mismatch" ? 4010 : result === "replay" ? 4014 : 4013
      const reason = result === "mismatch" ? "actor key mismatch" : result === "replay" ? "stale hello epoch" : "room quota"
      try { ws.close(code, reason) } catch { /* closed */ }
      return
    }
    // Supersede an older live socket for the same actor. This closes the small
    // window in which an already-connected old session could keep replaying.
    const acceptedEpoch = parseHelloEpoch(frame.vs, frame.by)!.value
    for (const other of this.state.getWebSockets()) {
      if (other === ws) continue
      const otherHello = (other.deserializeAttachment() as SocketState | null)?.hello
      if (!otherHello || otherHello.by !== frame.by) continue
      const otherEpoch = parseHelloEpoch(otherHello.vs, otherHello.by)?.value ?? 0n
      if (otherEpoch < acceptedEpoch) {
        try { other.close(4015, "actor session superseded") } catch { /* closed */ }
      }
    }
    socket.hello = frame
    socket.presenceCounter = undefined
    ws.serializeAttachment(socket)
    // Sender gating ends only after both the durable pin and socket binding.
    ws.send(JSON.stringify({ t: "hello-ack", by: frame.by, sd: frame.sd, vs: frame.vs }))
    this.broadcast(frame, ws)
  }

  private async registerActor(frame: HelloFrame): Promise<"ok" | "mismatch" | "replay" | "quota"> {
    const key = "actor:" + frame.by
    const incomingEpoch = parseHelloEpoch(frame.vs, frame.by)!
    let result: "ok" | "mismatch" | "replay" | "quota" = "ok"
    await this.state.storage.transaction(async txn => {
      const existing = await txn.get<ActorRecord>(key)
      if (existing?.pubkey !== undefined && existing.pubkey !== frame.pub) { result = "mismatch"; return }
      const storedEpoch = existing?.helloEpoch && /^[0-9a-f]{16}$/.test(existing.helloEpoch)
        ? BigInt("0x" + existing.helloEpoch)
        : 0n
      if (incomingEpoch.value <= storedEpoch) { result = "replay"; return }
      const actor: ActorRecord = {
        pubkey: frame.pub,
        firstSeen: existing?.firstSeen ?? Date.now(),
        helloEpoch: incomingEpoch.hex,
        hello: frame,
      }
      const total = (await txn.get<number>("meta:totalRecords")) ?? 0
      const bytes = (await txn.get<number>("meta:bytes")) ?? 0
      const nextTotal = total + (existing ? 0 : 1)
      const nextBytes = bytes - (existing ? storageBytes(key, existing) : 0) + storageBytes(key, actor)
      if (nextTotal > MAX_RECORDS || nextBytes > MAX_STORED_BYTES) { result = "quota"; return }
      await txn.put(key, actor)
      await txn.put("meta:totalRecords", nextTotal)
      await txn.put("meta:bytes", nextBytes)
    })
    return result
  }

  private async applyChangeV3(sender: WebSocket, input: unknown, deleted: boolean): Promise<void> {
    const msg = input as Record<string, unknown>
    const socket = sender.deserializeAttachment() as SocketState
    const hello = socket.hello
    if (!hello) return
    if (typeof msg.id !== "string" || !B64URL_32_RE.test(msg.id)) return
    if (typeof msg.vs !== "string") return
    const stamp = parseStamp(msg.vs)
    if (!stamp || stamp.counter > MAX_COUNTER || stamp.actorId !== hello.by) return
    if (msg.by !== hello.by || msg.pub !== hello.pub || msg.sd !== hello.sd) return
    if (typeof msg.kind !== "string" || !/^[A-Za-z0-9_-]{1,32}$/.test(msg.kind)) return
    if (deleted ? msg.kind !== "del" : msg.kind === "del") return
    if (!isValidCiphertext(msg.ct, CT_MAX)) return
    const record: SyncRecordV3 = { id: msg.id, vs: msg.vs, by: hello.by, kind: msg.kind, ct: msg.ct, deleted, pub: hello.pub, sd: hello.sd }
    const key = "obj:" + record.id
    let seq: number | undefined
    await this.state.storage.transaction(async txn => {
      const existing = await txn.get<SyncRecordV3>(key)
      if ((deleted && existing === undefined) || (existing && !isNewerStamp(record.vs, existing.vs))) return
      const highWater = (await txn.get<string>("meta:highWater")) ?? ZERO_COUNTER
      const highCounter = BigInt("0x" + highWater)
      if (stamp.counter > highCounter + ADVANCE_WINDOW) { metric("counter_advance_rejected"); return }
      const total = (await txn.get<number>("meta:totalRecords")) ?? 0
      const bytes = (await txn.get<number>("meta:bytes")) ?? 0
      const nextTotal = total + (existing ? 0 : 1)
      const nextBytes = bytes - (existing ? recordBytesV3(existing) : 0) + recordBytesV3(record)
      if (nextTotal > MAX_RECORDS || nextBytes > MAX_STORED_BYTES) { metric("quota_exceeded", { type: nextTotal > MAX_RECORDS ? "records" : "bytes" }); return }
      seq = ((await txn.get<number>("meta:seq")) ?? 0) + 1
      await txn.put(key, record)
      await txn.put("meta:totalRecords", nextTotal)
      await txn.put("meta:bytes", nextBytes)
      await txn.put("meta:seq", seq)
      if (stamp.counter > highCounter) await txn.put("meta:highWater", stamp.counter.toString(16).padStart(16, "0"))
    })
    if (seq !== undefined) this.broadcast({ t: deleted ? "del" : "put", ...record, seq }, sender)
  }

  private async handlePresenceV3(ws: WebSocket, input: unknown): Promise<void> {
    const msg = input as Record<string, unknown>
    const socket = ws.deserializeAttachment() as SocketState
    const hello = socket.hello
    if (!hello || msg.by !== hello.by || msg.pub !== hello.pub || msg.sd !== hello.sd) return
    if (typeof msg.vs !== "string" || !isValidCiphertext(msg.ct, PRESENCE_CT_MAX)) return
    const stamp = parseStamp(msg.vs)
    if (!stamp || stamp.actorId !== hello.by || stamp.counter === 0n) return
    const prior = socket.presenceCounter ? BigInt("0x" + socket.presenceCounter) : 0n
    if (stamp.counter <= prior || stamp.counter > prior + ADVANCE_WINDOW) return
    socket.presenceCounter = stamp.counter.toString(16).padStart(16, "0")
    socket.presenceV3 = { t: "loc", by: hello.by, pub: hello.pub, sd: hello.sd, vs: msg.vs, ct: msg.ct }
    ws.serializeAttachment(socket)
    this.broadcast(socket.presenceV3, ws)
  }

  private collectV2Members(except: WebSocket): PresenceV2[] {
    return this.state.getWebSockets().flatMap(ws => {
      if (ws === except) return []
      const s = ws.deserializeAttachment() as SocketState | null
      return s?.presenceV2 ? [s.presenceV2] : []
    })
  }

  private collectV3Live(except: WebSocket): Array<HelloFrame | PresenceV3> {
    const frames: Array<HelloFrame | PresenceV3> = []
    for (const ws of this.state.getWebSockets()) {
      if (ws === except) continue
      const s = ws.deserializeAttachment() as SocketState | null
      if (s?.hello) frames.push(s.hello)
      if (s?.presenceV3) frames.push(s.presenceV3)
    }
    return frames
  }

  private async ensureV3Accounting(): Promise<void> {
    if (await this.state.storage.get<number>("meta:accountingSchema") === 2) return
    let total = 0
    let bytes = 0
    for (const prefix of ["actor:", "obj:"]) {
      let cursor: string | undefined
      while (true) {
        const options: DurableObjectListOptions = { prefix, limit: STORAGE_PAGE_SIZE }
        if (cursor) options.startAfter = cursor
        const page = await this.state.storage.list<ActorRecord | SyncRecordV3>(options)
        if (page.size === 0) break
        for (const [key, value] of page) {
          total += 1
          bytes += prefix === "actor:"
            ? storageBytes(key, value)
            : recordBytesV3(value as SyncRecordV3)
        }
        const keys = [...page.keys()]
        cursor = keys[keys.length - 1]
        if (page.size < STORAGE_PAGE_SIZE) break
      }
    }
    await this.state.storage.transaction(async txn => {
      await txn.put("meta:totalRecords", total)
      await txn.put("meta:bytes", bytes)
      await txn.put("meta:accountingSchema", 2)
    })
  }

  private async sendSnapshot(ws: WebSocket, protocol: 2 | 3): Promise<void> {
    const snapshot: SnapshotState = {
      seq: (await this.state.storage.get<number>("meta:seq")) ?? 0,
      highWater: (await this.state.storage.get<string>("meta:highWater")) ?? ZERO_COUNTER,
    }
    const members = protocol === 2 ? this.collectV2Members(ws) : []
    const liveFrames = protocol === 3 ? this.collectV3Live(ws) : []
    ws.send(JSON.stringify({ t: "snapshot-begin", seq: snapshot.seq, ...(protocol === 3 ? { highWater: snapshot.highWater } : {}) }))

    let cursor: string | undefined
    let chunk: unknown[] = []
    let first = true
    const payload = (items: unknown[], more: boolean) => ({ t: "snapshot", items, more, ...(first && protocol === 2 ? { members } : {}) })
    const flush = (more: boolean) => {
      const text = JSON.stringify(payload(chunk, more))
      if (utf8Length(text) > SNAPSHOT_FRAME_BYTES) throw new Error("snapshot frame ceiling")
      ws.send(text)
      chunk = []
      first = false
    }

    while (true) {
      const options: DurableObjectListOptions = { prefix: "obj:", limit: STORAGE_PAGE_SIZE }
      if (cursor) options.startAfter = cursor
      const page = await this.state.storage.list<SyncRecord | SyncRecordV3>(options)
      if (page.size === 0) break
      for (const record of page.values()) {
        const candidate = [...chunk, record]
        if (utf8Length(JSON.stringify(payload(candidate, true))) > SNAPSHOT_FRAME_BYTES) {
          if (chunk.length === 0) throw new Error("record exceeds snapshot ceiling")
          flush(true)
        }
        chunk.push(record)
      }
      const keys = [...page.keys()]
      cursor = keys[keys.length - 1]
      if (page.size < STORAGE_PAGE_SIZE) break
    }
    flush(false)
    ws.send(JSON.stringify({ t: "snapshot-end", seq: snapshot.seq }))
    // Active-session metadata is ephemeral and follows the durable fence. Each
    // frame is independently bounded by the normal incoming client ceiling.
    for (const frame of liveFrames) ws.send(JSON.stringify(frame))
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
    if (await this.state.storage.getAlarm() === null) await this.state.storage.setAlarm(now + IDLE_TTL_MS)
  }
}
