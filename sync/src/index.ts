/// <reference types="@cloudflare/workers-types" />

import { RELAY_RELEASE_ID } from "./release"

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
const CHAT_CT_MAX = 16_384
const MAX_FRAME_BYTES = 1_048_576
const SNAPSHOT_FRAME_BYTES = 900_000
const RATE_WINDOW_MS = 10_000
const RATE_MAX_MSGS = 200
const RATE_MAX_BYTES = 4 * 1_048_576
const ROOM_PENDING_MAX_MSGS = 512
const ROOM_PENDING_MAX_BYTES = 8 * 1_048_576
const STORAGE_PAGE_SIZE = 100
const IDLE_TTL_MS = 7 * 24 * 60 * 60 * 1000
const MAX_COUNTER = 0x7fffffffffffffffn
const MAX_U64 = 0xffffffffffffffffn
const ADVANCE_WINDOW = 10_000n
const ZERO_COUNTER = "0000000000000000"
const B64URL_32_RE = /^[A-Za-z0-9_-]{43}$/
const B64URL_64_RE = /^[A-Za-z0-9_-]{86}$/
const B64URL_16_RE = /^[A-Za-z0-9_-]{22}$/
const REQUEST_ID_RE = /^[A-Za-z0-9_-]{16,64}$/
const DELIVERY_ACK_VERSION = 1
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

/** Authenticated, exact-session user departure. Transport closes remain
 * relay-attested transient events so clients can retain bounded last-known
 * tactical truth during a network flap. */
interface ExplicitLeaveFrame {
  t: "leave"
  lv: 1
  by: string
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

/** Signed, ephemeral X25519 key advertisement for one authenticated v3 socket. */
interface ChatKeyFrame {
  t: "chat-key"
  cv: 1
  by: string
  sd: string
  kx: string
  kid: string
  sig: string
}

/** E2E ciphertext routed live only; chat frames are never written to relay storage. */
interface ChatFrame {
  t: "chat"
  cv: 1
  scope: "room" | "direct"
  by: string
  sd: string
  vs: string
  mid: string
  fromKid: string
  ct: string
  sig: string
  to?: string
  toSd?: string
  toKid?: string
}

interface ChatRetry {
  vs: string
  mid: string
  fingerprint: string
}

interface SocketState {
  windowStart: number
  msgs: number
  bytes: number
  protocol: 2 | 3
  roomId: string
  hello?: HelloFrame
  chatKey?: ChatKeyFrame
  chatCounter?: string
  lastChat?: ChatRetry
  presenceV2?: PresenceV2
  presenceV3?: PresenceV3
  presenceCounter?: string
  /** Temporarily blocks mutations while a newer session proves and pins itself. */
  replacementFences?: string[]
}

interface SocketMessageQueue {
  tail: Promise<void>
  pendingMessages: number
  pendingBytes: number
  accepting: boolean
  abortPending: boolean
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

function parseStamp(vs: string): { counter: bigint; actorId: string } | null {
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

async function ciphertextHash(ciphertext: string): Promise<string> {
  return uint8ToBase64url(new Uint8Array(
    await crypto.subtle.digest("SHA-256", new TextEncoder().encode(ciphertext)),
  ))
}

function requestId(input: unknown): string | null {
  if (!input || typeof input !== "object") return null
  const rid = (input as Record<string, unknown>).rid
  return typeof rid === "string" && REQUEST_ID_RE.test(rid) ? rid : null
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

async function explicitLeavePreimage(
  roomId: string,
  frame: ExplicitLeaveFrame,
): Promise<Uint8Array | null> {
  const room = base64urlDecode(roomId, 32)
  const session = base64urlDecode(frame.sd, 32)
  const epoch = parseHelloEpoch(frame.vs, frame.by)
  if (!room || !session || !epoch) return null
  const actor = new TextEncoder().encode(frame.by)
  const kind = new TextEncoder().encode("leave-v1")
  const counter = new TextEncoder().encode(epoch.hex)
  const payloadHash = new Uint8Array(await crypto.subtle.digest("SHA-256", new Uint8Array()))
  const out: number[] = [0x04, 0x03, ...room]
  appendLe16(out, actor.length)
  out.push(...actor, ...session, ...counter)
  appendLe16(out, 0)
  out.push(kind.length, ...kind, ...payloadHash)
  return Uint8Array.from(out)
}

async function verifyExplicitLeave(
  roomId: string,
  signingPublicKey: string,
  frame: ExplicitLeaveFrame,
): Promise<boolean> {
  const signature = base64urlDecode(frame.sig, 64)
  const pub = base64urlDecode(signingPublicKey, 32)
  const preimage = await explicitLeavePreimage(roomId, frame)
  if (!signature || !pub || !preimage) return false
  try {
    const key = await crypto.subtle.importKey("raw", pub, { name: "Ed25519" }, false, ["verify"])
    return await crypto.subtle.verify({ name: "Ed25519" }, key, signature, preimage)
  } catch {
    return false
  }
}

async function chatKeyPreimage(roomId: string, frame: ChatKeyFrame): Promise<Uint8Array | null> {
  const room = base64urlDecode(roomId, 32)
  const session = base64urlDecode(frame.sd, 32)
  const keyExchange = base64urlDecode(frame.kx, 32)
  const keyId = base64urlDecode(frame.kid, 32)
  if (!room || !session || !keyExchange || !keyId) return null
  const actor = new TextEncoder().encode(frame.by)
  const kind = new TextEncoder().encode("chat-key-v1")
  const counter = new TextEncoder().encode(ZERO_COUNTER)
  const payloadHash = new Uint8Array(await crypto.subtle.digest(
    "SHA-256", Uint8Array.from([frame.cv, ...keyExchange, ...keyId]),
  ))
  // Same typed, length-prefixed shape as ADR-001 buildPreimage, with a new
  // domain byte. This signs the room, actor, exact live session and X25519 key.
  const out: number[] = [0x05, 0x03, ...room]
  appendLe16(out, actor.length)
  out.push(...actor, ...session, ...counter)
  appendLe16(out, 0)
  out.push(kind.length, ...kind, ...payloadHash)
  return Uint8Array.from(out)
}

async function chatKeyId(roomId: string, actorId: string, sessionDomain: string, keyExchange: string): Promise<string | null> {
  const room = base64urlDecode(roomId, 32)
  const session = base64urlDecode(sessionDomain, 32)
  const key = base64urlDecode(keyExchange, 32)
  if (!room || !session || !key) return null
  const actor = new TextEncoder().encode(actorId)
  const prefix = new TextEncoder().encode("tacmap-chat-kid-v1\0")
  const input: number[] = [...prefix, ...room]
  appendLe16(input, actor.length)
  input.push(...actor, ...session, ...key)
  return uint8ToBase64url(new Uint8Array(await crypto.subtle.digest("SHA-256", Uint8Array.from(input))))
}

async function verifyChatKey(roomId: string, signingPublicKey: string, frame: ChatKeyFrame): Promise<boolean> {
  const signature = base64urlDecode(frame.sig, 64)
  const pub = base64urlDecode(signingPublicKey, 32)
  const preimage = await chatKeyPreimage(roomId, frame)
  if (!signature || !pub || !preimage) return false
  const computedKid = await chatKeyId(roomId, frame.by, frame.sd, frame.kx)
  if (!computedKid) return false
  if (!constantTimeEqual(computedKid, frame.kid)) return false
  try {
    const key = await crypto.subtle.importKey("raw", pub, { name: "Ed25519" }, false, ["verify"])
    return await crypto.subtle.verify({ name: "Ed25519" }, key, signature, preimage)
  } catch {
    return false
  }
}

function exactKeys(value: Record<string, unknown>, expected: readonly string[]): boolean {
  const actual = Object.keys(value).sort()
  const wanted = [...expected].sort()
  return actual.length === wanted.length && actual.every((key, index) => key === wanted[index])
}

function standardBase64Decode(value: string, maxChars: number): Uint8Array | null {
  if (value.length === 0 || value.length > maxChars || value.length % 4 !== 0) return null
  if (!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)) return null
  try {
    const binary = atob(value)
    if (btoa(binary) !== value) return null
    const bytes = Uint8Array.from(binary, char => char.charCodeAt(0))
    return bytes.length >= 28 ? bytes : null
  } catch {
    return null
  }
}

/**
 * Canonical chat-v1 header. The same bytes are AEAD AAD and are nested inside
 * the Ed25519 signature preimage, so room/direct scope and both endpoints can
 * never be relabelled independently of the ciphertext.
 */
function chatHeader(roomId: string, frame: ChatFrame): Uint8Array | null {
  const room = base64urlDecode(roomId, 32)
  const senderSession = base64urlDecode(frame.sd, 32)
  const messageId = base64urlDecode(frame.mid, 16)
  const fromKid = base64urlDecode(frame.fromKid, 32)
  const counter = parseStamp(frame.vs)
  if (!room || !senderSession || !messageId || !fromKid || !counter) return null
  const sender = new TextEncoder().encode(frame.by)
  const recipient = frame.scope === "direct" ? new TextEncoder().encode(frame.to!) : new Uint8Array()
  const recipientSession = frame.scope === "direct" ? base64urlDecode(frame.toSd!, 32) : new Uint8Array(32)
  const toKid = frame.scope === "direct" ? base64urlDecode(frame.toKid!, 32) : new Uint8Array(32)
  if (!recipientSession || !toKid) return null
  const out: number[] = [frame.cv, frame.scope === "room" ? 0x01 : 0x02, ...room]
  appendLe16(out, sender.length)
  out.push(...sender, ...senderSession, ...new TextEncoder().encode(frame.vs.slice(0, 16)), ...messageId, ...fromKid)
  appendLe16(out, recipient.length)
  out.push(...recipient, ...recipientSession, ...toKid)
  return Uint8Array.from(out)
}

async function chatSignaturePreimage(roomId: string, frame: ChatFrame): Promise<Uint8Array | null> {
  const header = chatHeader(roomId, frame)
  const sealed = standardBase64Decode(frame.ct, CHAT_CT_MAX)
  if (!header || !sealed) return null
  const ciphertextHash = new Uint8Array(await crypto.subtle.digest("SHA-256", sealed))
  return Uint8Array.from([0x06, 0x03, ...header, ...ciphertextHash])
}

async function verifyChat(roomId: string, signingPublicKey: string, frame: ChatFrame): Promise<boolean> {
  const signature = base64urlDecode(frame.sig, 64)
  const publicKey = base64urlDecode(signingPublicKey, 32)
  const preimage = await chatSignaturePreimage(roomId, frame)
  if (!signature || !publicKey || !preimage) return false
  try {
    const key = await crypto.subtle.importKey("raw", publicKey, { name: "Ed25519" }, false, ["verify"])
    return await crypto.subtle.verify({ name: "Ed25519" }, key, signature, preimage)
  } catch {
    return false
  }
}

async function chatFingerprint(roomId: string, frame: ChatFrame): Promise<string | null> {
  const preimage = await chatSignaturePreimage(roomId, frame)
  const signature = base64urlDecode(frame.sig, 64)
  if (!preimage || !signature) return null
  return uint8ToBase64url(new Uint8Array(
    await crypto.subtle.digest("SHA-256", Uint8Array.from([...preimage, ...signature])),
  ))
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

function parseExplicitLeave(msg: unknown): ExplicitLeaveFrame | null {
  if (!msg || typeof msg !== "object") return null
  const m = msg as Record<string, unknown>
  if (!exactKeys(m, ["t", "lv", "by", "sd", "vs", "sig"]) ||
      m.t !== "leave" || m.lv !== 1) return null
  if (typeof m.by !== "string" || !base64urlDecode(m.by, 32)) return null
  if (typeof m.sd !== "string" || !base64urlDecode(m.sd, 32)) return null
  if (typeof m.vs !== "string" || !parseHelloEpoch(m.vs, m.by)) return null
  if (typeof m.sig !== "string" || !base64urlDecode(m.sig, 64)) return null
  return { t: "leave", lv: 1, by: m.by, sd: m.sd, vs: m.vs, sig: m.sig }
}

function parseChatKey(msg: unknown): ChatKeyFrame | null {
  if (!msg || typeof msg !== "object") return null
  const m = msg as Record<string, unknown>
  if (!exactKeys(m, ["t", "cv", "by", "sd", "kx", "kid", "sig"]) || m.t !== "chat-key") return null
  if (m.cv !== 1) return null
  if (typeof m.by !== "string" || !base64urlDecode(m.by, 32)) return null
  if (typeof m.sd !== "string" || !base64urlDecode(m.sd, 32)) return null
  if (typeof m.kx !== "string") return null
  const key = base64urlDecode(m.kx, 32)
  if (!key || key.every(byte => byte === 0)) return null
  if (typeof m.kid !== "string" || !base64urlDecode(m.kid, 32)) return null
  if (typeof m.sig !== "string" || !base64urlDecode(m.sig, 64)) return null
  return { t: "chat-key", cv: 1, by: m.by, sd: m.sd, kx: m.kx, kid: m.kid, sig: m.sig }
}

function parseChat(msg: unknown): ChatFrame | null {
  if (!msg || typeof msg !== "object") return null
  const m = msg as Record<string, unknown>
  if (m.cv !== 1) return null
  if (m.scope !== "room" && m.scope !== "direct") return null
  const keys = m.scope === "room"
    ? ["t", "cv", "scope", "by", "sd", "vs", "mid", "fromKid", "ct", "sig"]
    : ["t", "cv", "scope", "by", "sd", "vs", "mid", "fromKid", "to", "toSd", "toKid", "ct", "sig"]
  if (!exactKeys(m, keys) || m.t !== "chat") return null
  if (typeof m.by !== "string" || !base64urlDecode(m.by, 32)) return null
  if (typeof m.sd !== "string" || !base64urlDecode(m.sd, 32)) return null
  if (typeof m.vs !== "string") return null
  const stamp = parseStamp(m.vs)
  if (!stamp || stamp.actorId !== m.by || stamp.counter === 0n) return null
  if (typeof m.mid !== "string" || !B64URL_16_RE.test(m.mid) || !base64urlDecode(m.mid, 16)) return null
  if (typeof m.fromKid !== "string" || !base64urlDecode(m.fromKid, 32)) return null
  if (typeof m.ct !== "string" || !standardBase64Decode(m.ct, CHAT_CT_MAX)) return null
  if (typeof m.sig !== "string" || !base64urlDecode(m.sig, 64)) return null
  if (m.scope === "room") {
    return {
      t: "chat", cv: 1, scope: "room", by: m.by, sd: m.sd, vs: m.vs,
      mid: m.mid, fromKid: m.fromKid, ct: m.ct, sig: m.sig,
    }
  }
  if (typeof m.to !== "string" || !base64urlDecode(m.to, 32) || m.to === m.by) return null
  if (typeof m.toSd !== "string" || !base64urlDecode(m.toSd, 32)) return null
  if (typeof m.toKid !== "string" || !base64urlDecode(m.toKid, 32)) return null
  return {
    t: "chat", cv: 1, scope: "direct", by: m.by, sd: m.sd, vs: m.vs,
    mid: m.mid, fromKid: m.fromKid, ct: m.ct, sig: m.sig,
    to: m.to, toSd: m.toSd, toKid: m.toKid,
  }
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url)
    if (url.pathname === "/health") {
      return new Response("ok", {
        headers: {
          "cache-control": "no-store",
          "content-type": "text/plain; charset=utf-8",
          "x-tacmap-relay-release": RELAY_RELEASE_ID,
        },
      })
    }
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
  // Zero in production. The integration suite sets a bounded delay on the
  // in-memory Durable Object instance to deterministically exercise the
  // replacement-handshake interleaving without changing protocol behaviour.
  private helloHandshakeDelayMsForTests = 0
  private replacementFenceCounter = 0
  // Test-only durable-register fault seam. Production never changes this.
  private failNextActorRegisterForTests = false
  // Test-only deterministic fault seam. Production never changes this value.
  private failNextMutationStorageForTests = false
  // Durable Object callbacks may interleave whenever a handler awaits crypto
  // or storage. Keep each socket's accepted frames in wire order while still
  // allowing different peers to make progress concurrently.
  private readonly socketMessageQueues = new WeakMap<WebSocket, SocketMessageQueue>()
  private roomPendingMessages = 0
  private roomPendingBytes = 0

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
    server.serializeAttachment({ windowStart: 0, msgs: 0, bytes: 0, protocol, roomId } satisfies SocketState)
    try {
      // No mutation event can interleave with this fence/pages/end sequence.
      await this.state.blockConcurrencyWhile(async () => this.sendSnapshot(server, protocol))
      await this.touchActivity()
    } catch {
      metric("storage_error", { operation: "snapshot" })
      this.closeSocket(server, 1011, "snapshot unavailable")
      return new Response("Snapshot unavailable", { status: 503 })
    }
    return new Response(null, { status: 101, webSocket: client })
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    let text: string
    let frameBytes: number
    if (typeof message === "string") {
      // UTF-16 character counts understate non-ASCII traffic. The relay limit
      // is a wire-byte limit, so measure the encoded frame before parsing it.
      if (message.length > MAX_FRAME_BYTES) {
        metric("frame_rejected", { reason: "oversized", bytes: message.length })
        this.closeSocket(ws, 4009, "frame too large")
        return
      }
      text = message
      frameBytes = new TextEncoder().encode(message).byteLength
    } else {
      frameBytes = message.byteLength
      if (frameBytes <= MAX_FRAME_BYTES) {
        try {
          text = new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(message)
        } catch {
          metric("frame_rejected", { reason: "invalid_utf8" })
          this.closeSocket(ws, 1007, "invalid utf-8")
          return
        }
      } else {
        text = ""
      }
    }
    if (frameBytes > MAX_FRAME_BYTES) {
      metric("frame_rejected", { reason: "oversized", bytes: frameBytes })
      this.closeSocket(ws, 4009, "frame too large")
      return
    }

    const receivedAt = Date.now()
    let queue = this.socketMessageQueues.get(ws)
    if (!queue) {
      queue = {
        tail: Promise.resolve(), pendingMessages: 0, pendingBytes: 0,
        accepting: true, abortPending: false,
      }
      this.socketMessageQueues.set(ws, queue)
    }
    if (!queue.accepting) return
    // Admission is independent of the rolling rate limiter because an async
    // crypto/storage operation must not let an attacker retain an unbounded
    // number of otherwise-valid frames in this isolate.
    if (queue.pendingMessages >= RATE_MAX_MSGS || queue.pendingBytes + frameBytes > RATE_MAX_BYTES ||
        this.roomPendingMessages >= ROOM_PENDING_MAX_MSGS ||
        this.roomPendingBytes + frameBytes > ROOM_PENDING_MAX_BYTES) {
      queue.accepting = false
      queue.abortPending = true
      this.closeSocket(ws, 4008, "rate limit")
      return
    }
    queue.pendingMessages += 1
    queue.pendingBytes += frameBytes
    this.roomPendingMessages += 1
    this.roomPendingBytes += frameBytes

    const previous = queue.tail
    const current = previous.catch(() => undefined).then(async () => {
      if (queue!.abortPending) return
      if (!this.allow(ws, frameBytes, receivedAt)) {
        queue!.accepting = false
        queue!.abortPending = true
        this.closeSocket(ws, 4008, "rate limit")
        return
      }
      await this.processWebSocketMessage(ws, text)
    })
    queue.tail = current
    try {
      await current
    } finally {
      queue.pendingMessages -= 1
      queue.pendingBytes -= frameBytes
      this.roomPendingMessages -= 1
      this.roomPendingBytes -= frameBytes
      if (queue.tail === current && queue.pendingMessages === 0) {
        this.socketMessageQueues.delete(ws)
      }
    }
  }

  private async processWebSocketMessage(ws: WebSocket, text: string): Promise<void> {
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
      else if (type === "leave" && protocol === 3) await this.handleExplicitLeave(ws, msg)
      else if (type === "chat-key" && protocol === 3) await this.handleChatKey(ws, msg)
      else if (type === "chat" && protocol === 3) await this.handleChat(ws, msg)
      else if (type === "ping" && Object.keys(msg).length === 1) {
        ws.send(JSON.stringify({ t: "pong" }))
        return
      }
      else return
      await this.touchActivity()
    } catch {
      metric("storage_error", { operation: "message" })
      this.closeSocket(ws, 1011, "storage unavailable")
    }
  }

  async webSocketClose(ws: WebSocket, code: number): Promise<void> {
    const queue = this.socketMessageQueues.get(ws)
    if (queue) {
      // Do not announce departure ahead of an already-accepted frame. Marking
      // the queue first also prevents any later callback from extending it.
      queue.accepting = false
      try { await queue.tail } catch { /* message handler already closed it */ }
      if (this.socketMessageQueues.get(ws) === queue) this.socketMessageQueues.delete(ws)
    }
    const s = ws.deserializeAttachment() as SocketState | null
    if (s?.presenceV2) this.broadcast({ t: "leave", clientId: s.presenceV2.clientId }, ws)
    if (s?.hello) this.announceV3Departure(ws, s, "transient")
    try { ws.close(code, "closing") } catch { /* closed */ }
    if (this.state.getWebSockets().length <= 1) {
      try { await this.touchActivity() } catch { metric("storage_error", { operation: "close" }) }
    }
  }

  async webSocketError(ws: WebSocket, _err: unknown): Promise<void> {
    this.abortSocketQueue(ws)
  }

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

  private abortSocketQueue(ws: WebSocket): void {
    const queue = this.socketMessageQueues.get(ws)
    if (queue) {
      queue.accepting = false
      queue.abortPending = true
    }
  }

  private closeSocket(ws: WebSocket, code: number, reason: string): void {
    this.abortSocketQueue(ws)
    try { ws.close(code, reason) } catch { /* closed */ }
  }

  private allow(ws: WebSocket, frameBytes: number, receivedAt: number): boolean {
    const now = receivedAt
    const s = (ws.deserializeAttachment() as SocketState | null) ?? {
      windowStart: now, msgs: 0, bytes: 0, protocol: 2 as const, roomId: "",
    }
    // Existing hibernated sockets may predate the byte counter.
    if (!Number.isSafeInteger(s.bytes) || s.bytes < 0) s.bytes = 0
    if (now - s.windowStart > RATE_WINDOW_MS) {
      s.windowStart = now
      s.msgs = 0
      s.bytes = 0
    }
    s.msgs += 1
    s.bytes += frameBytes
    ws.serializeAttachment(s)
    return s.msgs <= RATE_MAX_MSGS && s.bytes <= RATE_MAX_BYTES
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
    const rid = requestId(input)
    const reject = (code: string, retry: boolean): void => {
      if (rid) this.sendV2Nack(sender, rid, msg.by, code, retry)
    }
    if (typeof msg.id !== "string" || msg.id.length === 0 || msg.id.length > 256) { reject("invalid", false); return }
    if (typeof msg.v !== "number" || !Number.isSafeInteger(msg.v) || msg.v < 0 || msg.v > MAX_V) { reject("invalid", false); return }
    if (typeof msg.by !== "string" || msg.by.length === 0 || msg.by.length > 128) { reject("invalid", false); return }
    // Hardened Android/iOS v2 clients seal and sign deletes but historically
    // omitted the redundant outer `kind`. Normalize only that exact shape;
    // plaintext legacy deletes still fail the ciphertext check below.
    const kind = deleted && msg.kind === undefined ? "del" : msg.kind
    if (typeof kind !== "string" || !/^[A-Za-z0-9_-]{1,32}$/.test(kind)) { reject("invalid", false); return }
    if ((deleted && kind !== "del") || (!deleted && kind === "del")) { reject("invalid", false); return }
    if (!isValidCiphertext(msg.ct, CT_MAX)) { reject("invalid", false); return }
    const record: SyncRecord = { id: msg.id, v: msg.v, by: msg.by, kind, ct: msg.ct, deleted, ...(deleted ? { deletedAt: Date.now() } : {}) }
    const key = "obj:" + record.id
    let seq: number | undefined
    const result: { outcome: "stored" | "duplicate" | "stale" | "not-found" | "quota" } = { outcome: "stale" }
    try {
      if (this.failNextMutationStorageForTests) {
        this.failNextMutationStorageForTests = false
        throw new Error("injected mutation storage failure")
      }
      await this.state.storage.transaction(async txn => {
        const existing = await txn.get<SyncRecord>(key)
        if (existing && this.sameV2Record(existing, record)) { result.outcome = "duplicate"; return }
        // A request-id-bearing delete is a modern client's durable tombstone
        // intent and must survive even if its preceding put was lost. Preserve
        // legacy no-rid phantom-delete behaviour for old clients/tests.
        if (deleted && existing === undefined && !rid) { result.outcome = "not-found"; return }
        if (existing && !isNewer(record, existing)) { result.outcome = "stale"; return }
        const total = (await txn.get<number>("meta:totalRecords")) ?? 0
        const bytes = (await txn.get<number>("meta:bytes")) ?? 0
        const nextTotal = total + (existing ? 0 : 1)
        const nextBytes = bytes - (existing ? recordBytesV2(existing) : 0) + recordBytesV2(record)
        if (nextTotal > MAX_RECORDS || nextBytes > MAX_STORED_BYTES) {
          metric("quota_exceeded", { type: nextTotal > MAX_RECORDS ? "records" : "bytes" })
          result.outcome = "quota"
          return
        }
        seq = ((await txn.get<number>("meta:seq")) ?? 0) + 1
        await txn.put(key, record)
        await txn.put("meta:totalRecords", nextTotal)
        await txn.put("meta:bytes", nextBytes)
        await txn.put("meta:seq", seq)
        result.outcome = "stored"
      })
    } catch {
      metric("storage_error", { operation: "v2_mutation" })
      reject("storage", true)
      return
    }
    if (result.outcome === "stored") this.broadcast({ t: deleted ? "del" : "put", ...record, seq }, sender)
    if (result.outcome === "stored" || result.outcome === "duplicate") {
      if (rid) sender.send(JSON.stringify({
        t: "op-ack", av: DELIVERY_ACK_VERSION, rid, by: record.by,
        id: record.id, v: record.v, kind: record.kind, cth: await ciphertextHash(record.ct),
      }))
    } else {
      reject(result.outcome, false)
    }
  }

  private async handleHello(ws: WebSocket, input: unknown): Promise<void> {
    const frame = parseHello(input)
    const socket = ws.deserializeAttachment() as SocketState
    if (socket.protocol !== 3) return
    if (!frame) {
      metric("actor_rejected", { reason: "malformed_hello" })
      this.closeSocket(ws, 4011, "invalid actor proof")
      return
    }
    if (socket.hello) {
      if (JSON.stringify(socket.hello) === JSON.stringify(frame) && !socket.replacementFences?.length) {
        // A retried identical frame on the same already-proven socket is safe
        // and must not fail as a durable epoch replay.
        ws.send(JSON.stringify({ t: "hello-ack", by: frame.by, sd: frame.sd, vs: frame.vs }))
      } else if (JSON.stringify(socket.hello) !== JSON.stringify(frame)) {
        this.closeSocket(ws, 4012, "actor already announced")
      }
      return
    }

    // Never let unauthenticated actor text affect another live socket. Actor
    // recomputation and Ed25519 proof both complete before replacement fencing.
    const computed = await actorIdFor(socket.roomId, frame.pub)
    if (!computed || !constantTimeEqual(computed, frame.by) || !await verifyHello(socket.roomId, frame)) {
      metric("actor_rejected", { reason: "invalid_proof" })
      this.closeSocket(ws, 4011, "invalid actor proof")
      return
    }
    const acceptedEpoch = parseHelloEpoch(frame.vs, frame.by)!.value
    const fenceToken = `${frame.by}:${frame.vs}:${frame.sd}:${++this.replacementFenceCounter}`
    const fenced = this.fenceOlderActorSockets(ws, frame.by, acceptedEpoch, fenceToken)
    let accepted = false
    try {
      if (this.helloHandshakeDelayMsForTests > 0) {
        // Test-only synchronization frame: this delay is always zero in
        // production, while the integration test can observe the exact
        // post-proof/post-fence/pre-register boundary without a timing sleep.
        ws.send(JSON.stringify({ t: "test-fence-ready" }))
        await scheduler.wait(this.helloHandshakeDelayMsForTests)
      }
      if (this.failNextActorRegisterForTests) {
        this.failNextActorRegisterForTests = false
        throw new Error("test actor register failure")
      }
      const result = await this.registerActor(frame)
      if (result !== "ok") {
        const code = result === "mismatch" ? 4010 : result === "replay" ? 4014 : 4013
        const reason = result === "mismatch" ? "actor key mismatch" : result === "replay" ? "stale hello epoch" : "room quota"
        this.closeSocket(ws, code, reason)
        return
      }
      accepted = true

      // The old session was fenced only after proof. Retire it after the
      // replacement is durably pinned, keeping leave -> hello ordering and
      // allowing a failed storage transaction to restore the old session.
      for (const other of this.state.getWebSockets()) {
        if (other === ws) continue
        const otherState = other.deserializeAttachment() as SocketState | null
        const otherHello = otherState?.hello
        if (!otherState || !otherHello || otherHello.by !== frame.by) continue
        const otherEpoch = parseHelloEpoch(otherHello.vs, otherHello.by)?.value ?? 0n
        if (otherEpoch < acceptedEpoch) {
          this.announceV3Departure(other, otherState, "replaced", ws)
          this.closeSocket(other, 4015, "actor session superseded")
        }
      }
      socket.hello = frame
      socket.presenceCounter = undefined
      ws.serializeAttachment(socket)
      // Sender gating ends only after both the durable pin and socket binding.
      ws.send(JSON.stringify({ t: "hello-ack", by: frame.by, sd: frame.sd, vs: frame.vs }))
      this.broadcast(frame, ws)
    } finally {
      if (!accepted) this.restoreActorFences(fenced, fenceToken, ws)
    }
  }

  private async handleExplicitLeave(ws: WebSocket, input: unknown): Promise<void> {
    const frame = parseExplicitLeave(input)
    let socket = ws.deserializeAttachment() as SocketState | null
    let hello = socket?.hello
    const rejectInvalidProof = (): void => {
      metric("actor_rejected", { reason: "invalid_leave_proof" })
      // This socket is being closed abnormally, never explicitly. Hibernating
      // WebSocket close callbacks are not guaranteed after a server-initiated
      // close, so announce the transient departure while the binding is known.
      if (socket && hello && !socket.replacementFences?.length) {
        this.announceV3Departure(ws, socket, "transient")
      }
      this.closeSocket(ws, 4011, "invalid leave proof")
    }
    if (!socket || socket.protocol !== 3 || !hello || socket.replacementFences?.length ||
        !frame || frame.by !== hello.by || frame.sd !== hello.sd || frame.vs !== hello.vs) {
      rejectInvalidProof()
      return
    }
    const expectedHello = hello
    if (!await verifyExplicitLeave(socket.roomId, hello.pub, frame)) {
      // Use the current binding for the abnormal departure; another socket may
      // have replaced this one while signature verification was in flight.
      socket = ws.deserializeAttachment() as SocketState | null
      hello = socket?.hello
      rejectInvalidProof()
      return
    }

    socket = ws.deserializeAttachment() as SocketState | null
    hello = socket?.hello
    if (!socket || socket.protocol !== 3 || !hello || socket.replacementFences?.length ||
        hello.by !== expectedHello.by || hello.sd !== expectedHello.sd ||
        hello.pub !== expectedHello.pub || hello.vs !== expectedHello.vs) {
      // The replacement path already announces and classifies this departure.
      // Never let a stale verification overwrite its fence or reason.
      return
    }
    this.announceV3Departure(ws, socket, "explicit")
    this.closeSocket(ws, 1000, "explicit leave")
  }

  private fenceOlderActorSockets(
    replacement: WebSocket,
    actorId: string,
    acceptedEpoch: bigint,
    fenceToken: string,
  ): Array<{ ws: WebSocket; sessionDomain: string }> {
    const fenced: Array<{ ws: WebSocket; sessionDomain: string }> = []
    for (const other of this.state.getWebSockets()) {
      if (other === replacement) continue
      const otherState = other.deserializeAttachment() as SocketState | null
      const otherHello = otherState?.hello
      if (!otherState || !otherHello || otherHello.by !== actorId) continue
      const otherEpoch = parseHelloEpoch(otherHello.vs, otherHello.by)?.value ?? 0n
      if (otherEpoch >= acceptedEpoch) continue
      otherState.replacementFences = [...(otherState.replacementFences ?? []), fenceToken]
      other.serializeAttachment(otherState)
      fenced.push({ ws: other, sessionDomain: otherHello.sd })
    }
    return fenced
  }

  private restoreActorFences(
    fenced: Array<{ ws: WebSocket; sessionDomain: string }>,
    fenceToken: string,
    failedReplacement: WebSocket,
  ): void {
    for (const { ws, sessionDomain } of fenced) {
      const current = ws.deserializeAttachment() as SocketState | null
      if (!current || current.hello?.sd !== sessionDomain || !current.replacementFences?.includes(fenceToken)) continue
      current.replacementFences = current.replacementFences.filter(token => token !== fenceToken)
      const restored = current.replacementFences.length === 0
      if (restored) current.replacementFences = undefined
      ws.serializeAttachment(current)
      if (!restored) continue
      // A socket that joined while proof was pending intentionally received no
      // fenced live metadata. Re-announce the restored old session so it does
      // not remain invisible after the replacement fails. Duplicate frames are
      // safe: clients authenticate hello and reject replayed presence counters.
      if (current.hello) this.broadcast(current.hello, ws, failedReplacement)
      if (current.chatKey) this.broadcast(current.chatKey, ws, failedReplacement)
      if (current.presenceV3) this.broadcast(current.presenceV3, ws, failedReplacement)
    }
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
    let socket = sender.deserializeAttachment() as SocketState
    const rid = requestId(input)
    const reject = (code: string, retry: boolean): void => {
      if (rid) this.sendV3Nack(sender, socket, rid, code, retry)
    }
    if (socket.replacementFences?.length) { reject("session-replaced", false); return }
    const hello = socket.hello
    if (!hello) { reject("hello-required", true); return }
    if (typeof msg.id !== "string" || !B64URL_32_RE.test(msg.id)) { reject("invalid", false); return }
    if (typeof msg.vs !== "string") { reject("invalid", false); return }
    const stamp = parseStamp(msg.vs)
    if (!stamp || stamp.counter > MAX_COUNTER || stamp.actorId !== hello.by) { reject("invalid", false); return }
    if (msg.by !== hello.by || msg.pub !== hello.pub || msg.sd !== hello.sd) { reject("session-mismatch", false); return }
    if (typeof msg.kind !== "string" || !/^[A-Za-z0-9_-]{1,32}$/.test(msg.kind)) { reject("invalid", false); return }
    if (deleted ? msg.kind !== "del" : msg.kind === "del") { reject("invalid", false); return }
    if (!isValidCiphertext(msg.ct, CT_MAX)) { reject("invalid", false); return }
    const record: SyncRecordV3 = { id: msg.id, vs: msg.vs, by: hello.by, kind: msg.kind, ct: msg.ct, deleted, pub: hello.pub, sd: hello.sd }
    const expectedRoomId = socket.roomId
    const expectedHello = hello
    // Hash before entering the storage input gate so the transaction,
    // broadcast and acknowledgement form one no-WebCrypto-await tail.
    const cth = rid ? await ciphertextHash(record.ct) : null
    socket = sender.deserializeAttachment() as SocketState
    const currentHello = socket.hello
    if (socket.replacementFences?.length || !currentHello || socket.roomId !== expectedRoomId ||
        currentHello.by !== expectedHello.by || currentHello.sd !== expectedHello.sd ||
        currentHello.pub !== expectedHello.pub || currentHello.vs !== expectedHello.vs) {
      reject("session-replaced", false)
      return
    }
    const key = "obj:" + record.id
    let seq: number | undefined
    const result: { outcome: "stored" | "duplicate" | "stale" | "not-found" | "counter-window" | "quota" } = { outcome: "stale" }
    try {
      if (this.failNextMutationStorageForTests) {
        this.failNextMutationStorageForTests = false
        throw new Error("injected mutation storage failure")
      }
      await this.state.storage.transaction(async txn => {
        const existing = await txn.get<SyncRecordV3>(key)
        if (existing && this.sameV3Record(existing, record)) { result.outcome = "duplicate"; return }
        if (deleted && existing === undefined && !rid) { result.outcome = "not-found"; return }
        if (existing && !isNewerStamp(record.vs, existing.vs)) { result.outcome = "stale"; return }
        const highWater = (await txn.get<string>("meta:highWater")) ?? ZERO_COUNTER
        const highCounter = BigInt("0x" + highWater)
        if (stamp.counter > highCounter + ADVANCE_WINDOW) { metric("counter_advance_rejected"); result.outcome = "counter-window"; return }
        const total = (await txn.get<number>("meta:totalRecords")) ?? 0
        const bytes = (await txn.get<number>("meta:bytes")) ?? 0
        const nextTotal = total + (existing ? 0 : 1)
        const nextBytes = bytes - (existing ? recordBytesV3(existing) : 0) + recordBytesV3(record)
        if (nextTotal > MAX_RECORDS || nextBytes > MAX_STORED_BYTES) {
          metric("quota_exceeded", { type: nextTotal > MAX_RECORDS ? "records" : "bytes" })
          result.outcome = "quota"
          return
        }
        seq = ((await txn.get<number>("meta:seq")) ?? 0) + 1
        await txn.put(key, record)
        await txn.put("meta:totalRecords", nextTotal)
        await txn.put("meta:bytes", nextBytes)
        await txn.put("meta:seq", seq)
        if (stamp.counter > highCounter) await txn.put("meta:highWater", stamp.counter.toString(16).padStart(16, "0"))
        result.outcome = "stored"
      })
    } catch {
      metric("storage_error", { operation: "v3_mutation" })
      reject("storage", true)
      return
    }
    if (result.outcome === "stored") this.broadcast({ t: deleted ? "del" : "put", ...record, seq }, sender)
    if (result.outcome === "stored" || result.outcome === "duplicate") {
      if (rid) sender.send(JSON.stringify({
        t: "op-ack", av: DELIVERY_ACK_VERSION, rid,
        by: hello.by, sd: hello.sd, id: record.id, vs: record.vs,
        kind: record.kind, cth,
      }))
    } else {
      reject(result.outcome, false)
    }
  }

  private sameV2Record(a: SyncRecord, b: SyncRecord): boolean {
    return a.id === b.id && a.v === b.v && a.by === b.by && a.kind === b.kind &&
      a.ct === b.ct && a.deleted === b.deleted
  }

  private sameV3Record(a: SyncRecordV3, b: SyncRecordV3): boolean {
    return a.id === b.id && a.vs === b.vs && a.by === b.by && a.kind === b.kind &&
      a.ct === b.ct && a.deleted === b.deleted && a.pub === b.pub && a.sd === b.sd
  }

  private sendV2Nack(sender: WebSocket, rid: string, by: unknown, code: string, retry: boolean): void {
    sender.send(JSON.stringify({
      t: "op-nack", av: DELIVERY_ACK_VERSION, rid,
      ...(typeof by === "string" && by.length <= 128 ? { by } : {}), code, retry,
    }))
  }

  private sendV3Nack(sender: WebSocket, socket: SocketState, rid: string, code: string, retry: boolean): void {
    sender.send(JSON.stringify({
      t: "op-nack", av: DELIVERY_ACK_VERSION, rid,
      ...(socket.hello ? { by: socket.hello.by, sd: socket.hello.sd } : {}),
      code, retry,
    }))
  }

  private sendChatNack(sender: WebSocket, mid: string | null, code: string, retry = false): void {
    const socket = sender.deserializeAttachment() as SocketState | null
    sender.send(JSON.stringify({
      t: "chat-nack", cv: 1, ...(mid ? { mid } : {}),
      ...(socket?.hello ? { by: socket.hello.by, sd: socket.hello.sd } : {}),
      code, retry,
    }))
  }

  private sendChatKeyNack(sender: WebSocket, code: string): void {
    const socket = sender.deserializeAttachment() as SocketState | null
    sender.send(JSON.stringify({
      t: "chat-key-nack", cv: 1,
      ...(socket?.hello ? { by: socket.hello.by, sd: socket.hello.sd } : {}),
      code,
    }))
  }

  private async handleChatKey(ws: WebSocket, input: unknown): Promise<void> {
    const frame = parseChatKey(input)
    let socket = ws.deserializeAttachment() as SocketState
    let hello = socket.hello
    if (socket.replacementFences?.length || !hello) return
    if (!frame || frame.by !== hello.by || frame.sd !== hello.sd) {
      return this.sendChatKeyNack(ws, "invalid")
    }
    const expectedRoomId = socket.roomId
    const expectedHello = hello
    if (!await verifyChatKey(socket.roomId, hello.pub, frame)) {
      metric("chat_key_rejected", { reason: "invalid_proof" })
      return this.sendChatKeyNack(ws, "invalid_signature")
    }
    // A newer socket for this actor can fence this one while WebCrypto is in
    // flight. Continue only from the fresh attachment so a stale write cannot
    // erase the replacement fence or advertise a superseded session key.
    socket = ws.deserializeAttachment() as SocketState
    hello = socket.hello
    if (socket.replacementFences?.length || !hello || socket.roomId !== expectedRoomId ||
        frame.by !== hello.by || frame.sd !== hello.sd ||
        hello.pub !== expectedHello.pub || hello.vs !== expectedHello.vs) {
      return this.sendChatKeyNack(ws, "session_unavailable")
    }
    if (socket.chatKey) {
      if (JSON.stringify(socket.chatKey) !== JSON.stringify(frame)) {
        metric("chat_key_rejected", { reason: "already_announced" })
        return this.sendChatKeyNack(ws, "key_already_announced")
      }
      // Idempotent retry: acknowledge again but never reset the chat counter.
      ws.send(JSON.stringify({ t: "chat-key-ack", cv: 1, by: frame.by, sd: frame.sd, kid: frame.kid }))
      return
    }
    socket.chatKey = frame
    socket.chatCounter = undefined
    socket.lastChat = undefined
    ws.serializeAttachment(socket)
    ws.send(JSON.stringify({ t: "chat-key-ack", cv: 1, by: frame.by, sd: frame.sd, kid: frame.kid }))
    this.broadcast(frame, ws)
  }

  private routeChat(sender: WebSocket, frame: ChatFrame): boolean {
    if (frame.scope === "room") {
      const data = JSON.stringify(frame)
      for (const peer of this.state.getWebSockets()) {
        if (peer === sender) continue
        const state = peer.deserializeAttachment() as SocketState | null
        if (state?.protocol !== 3 || !state.hello || !state.chatKey || state.replacementFences?.length) continue
        try { peer.send(data) } catch { /* dead socket */ }
      }
      return true
    }
    const recipient = this.state.getWebSockets().find(peer => {
      if (peer === sender) return false
      const state = peer.deserializeAttachment() as SocketState | null
      return state?.protocol === 3 && !!state.chatKey && !state.replacementFences?.length &&
        state.hello?.by === frame.to && state.hello?.sd === frame.toSd &&
        state.chatKey.kid === frame.toKid
    })
    if (!recipient) return false
    try {
      recipient.send(JSON.stringify(frame))
      return true
    } catch {
      return false
    }
  }

  private sendChatAck(sender: WebSocket, frame: ChatFrame): void {
    sender.send(JSON.stringify({
      t: "chat-ack", cv: 1, by: frame.by, sd: frame.sd,
      vs: frame.vs, mid: frame.mid, scope: frame.scope, fromKid: frame.fromKid,
      ...(frame.scope === "direct"
        ? { to: frame.to, toSd: frame.toSd, toKid: frame.toKid }
        : {}),
    }))
  }

  private async handleChat(sender: WebSocket, input: unknown): Promise<void> {
    const frame = parseChat(input)
    const mid = input && typeof input === "object" && typeof (input as Record<string, unknown>).mid === "string"
      ? (input as Record<string, unknown>).mid as string
      : null
    if (!frame) return this.sendChatNack(sender, mid, "invalid")

    let socket = sender.deserializeAttachment() as SocketState
    let hello = socket.hello
    let chatKey = socket.chatKey
    if (socket.replacementFences?.length || !hello || !chatKey ||
        frame.by !== hello.by || frame.sd !== hello.sd || frame.fromKid !== chatKey.kid) {
      return this.sendChatNack(sender, frame.mid, "session_unavailable", true)
    }
    const expectedRoomId = socket.roomId
    const expectedHello = hello
    const expectedChatKey = chatKey
    if (!await verifyChat(socket.roomId, hello.pub, frame)) {
      metric("chat_rejected", { reason: "invalid_signature" })
      return this.sendChatNack(sender, frame.mid, "invalid_signature")
    }
    const stamp = parseStamp(frame.vs)!
    const fingerprint = await chatFingerprint(socket.roomId, frame)
    if (!fingerprint) return this.sendChatNack(sender, frame.mid, "invalid")

    // All awaited work is complete. Revalidate the exact authenticated
    // session/key and use only this fresh attachment for the atomic route and
    // counter commit below.
    socket = sender.deserializeAttachment() as SocketState
    hello = socket.hello
    chatKey = socket.chatKey
    if (socket.replacementFences?.length || !hello || !chatKey || socket.roomId !== expectedRoomId ||
        frame.by !== hello.by || frame.sd !== hello.sd || frame.fromKid !== chatKey.kid ||
        hello.pub !== expectedHello.pub || hello.vs !== expectedHello.vs ||
        chatKey.by !== expectedChatKey.by || chatKey.sd !== expectedChatKey.sd ||
        chatKey.kx !== expectedChatKey.kx || chatKey.kid !== expectedChatKey.kid ||
        chatKey.sig !== expectedChatKey.sig) {
      return this.sendChatNack(sender, frame.mid, "session_unavailable", true)
    }
    const prior = socket.chatCounter ? BigInt("0x" + socket.chatCounter) : 0n
    if (stamp.counter <= prior) {
      const retry = stamp.counter === prior && socket.lastChat?.vs === frame.vs &&
        socket.lastChat.mid === frame.mid && constantTimeEqual(socket.lastChat.fingerprint, fingerprint)
      if (!retry) return this.sendChatNack(sender, frame.mid, "counter_rejected")
      if (!this.routeChat(sender, frame)) {
        return this.sendChatNack(sender, frame.mid, "recipient_offline", true)
      }
      this.sendChatAck(sender, frame)
      return
    }
    if (stamp.counter > prior + ADVANCE_WINDOW) {
      return this.sendChatNack(sender, frame.mid, "counter_rejected")
    }

    if (!this.routeChat(sender, frame)) {
      return this.sendChatNack(sender, frame.mid, "recipient_offline", true)
    }

    socket.chatCounter = stamp.counter.toString(16).padStart(16, "0")
    socket.lastChat = { vs: frame.vs, mid: frame.mid, fingerprint }
    sender.serializeAttachment(socket)
    this.sendChatAck(sender, frame)
  }

  private async handlePresenceV3(ws: WebSocket, input: unknown): Promise<void> {
    const msg = input as Record<string, unknown>
    const socket = ws.deserializeAttachment() as SocketState
    if (socket.replacementFences?.length) return
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

  private collectV3Live(except: WebSocket): Array<HelloFrame | ChatKeyFrame | PresenceV3> {
    const frames: Array<HelloFrame | ChatKeyFrame | PresenceV3> = []
    for (const ws of this.state.getWebSockets()) {
      if (ws === except) continue
      const s = ws.deserializeAttachment() as SocketState | null
      // A newer signed session has already fenced this socket. Do not leak its
      // superseded hello/location into a concurrent newcomer's live snapshot.
      if (s?.replacementFences?.length) continue
      const hello = s?.hello
      if (!hello) continue
      frames.push(hello)
      if (s.chatKey?.by === hello.by && s.chatKey.sd === hello.sd) frames.push(s.chatKey)
      if (s.presenceV3?.by === hello.by && s.presenceV3.sd === hello.sd) frames.push(s.presenceV3)
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

  private announceV3Departure(
    ws: WebSocket,
    socket: SocketState,
    kind: "explicit" | "transient" | "replaced",
    replacement?: WebSocket,
  ): void {
    const hello = socket.hello
    if (!hello) return
    socket.hello = undefined
    socket.chatKey = undefined
    socket.chatCounter = undefined
    socket.lastChat = undefined
    socket.presenceV3 = undefined
    socket.presenceCounter = undefined
    socket.replacementFences = undefined
    ws.serializeAttachment(socket)
    this.broadcast({
      t: "leave",
      by: hello.by,
      sd: hello.sd,
      ...(kind === "explicit" ? { explicit: true } :
        kind === "replaced" ? { replaced: true } : { transient: true }),
    }, ws, replacement)
  }

  private broadcast(payload: unknown, except: WebSocket, alsoExcept?: WebSocket): void {
    const data = JSON.stringify(payload)
    for (const ws of this.state.getWebSockets()) {
      if (ws === except || ws === alsoExcept) continue
      try { ws.send(data) } catch { /* dead socket */ }
    }
  }

  private async touchActivity(): Promise<void> {
    const now = Date.now()
    await this.state.storage.put("meta:lastActivity", now)
    if (await this.state.storage.getAlarm() === null) await this.state.storage.setAlarm(now + IDLE_TTL_MS)
  }
}
