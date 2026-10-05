/// <reference types="@cloudflare/workers-types" />

import { RELAY_LIMITS } from "./limits"
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

const {
  MAX_CONNECTIONS, MAX_ACCEPTED_SOCKETS, HELLO_DEADLINE_MS, HELLO_DEADLINE_BYTES_PER_SEC,
  MAX_RECORDS, MAX_STORED_BYTES, MAX_V, CT_MAX, PRESENCE_CT_MAX, CHAT_CT_MAX, MAX_FRAME_BYTES,
  SNAPSHOT_FRAME_BYTES, STORAGE_PAGE_SIZE, STORAGE_FIRST_PAGE_SIZE, STORAGE_READ_BUDGET_BYTES,
  RATE_WINDOW_MS, RATE_MAX_MSGS, RATE_MAX_BYTES, ROOM_PENDING_MAX_MSGS, ROOM_PENDING_MAX_BYTES,
  IDLE_TTL_MS, TOMBSTONE_TTL_MS, ROOM_PURGE_TTL_MS, ACTIVITY_PERSIST_MS, MAINTENANCE_INTERVAL_MS,
  MAINTENANCE_RETRY_MS,
} = RELAY_LIMITS
const MAX_COUNTER = 0x7fffffffffffffffn
const MAX_U64 = 0xffffffffffffffffn
const ADVANCE_WINDOW = BigInt(RELAY_LIMITS.ADVANCE_WINDOW)
// WebSocket.READY_STATE_OPEN, spelled out so it doesn't depend on typings
const WS_OPEN = 1
const HOUR_MS = 60 * 60 * 1000
// a relay close and the client's echo both land on the last-socket path,
// one activity write covers both
const LAST_CLOSE_DEDUPE_MS = 60_000
// relay-only row per tombstone: tomb:<id> -> hour the delete landed. never
// part of the record, so snapshots keep the exact shape clients verify
const TOMB_PREFIX = "tomb:"
// relay-only row per actor whose pin idle expiry dropped: epoch:<actorId> ->
// the 16 hex hello epoch that pin held, nothing else. a hello still has to
// beat it, so one captured before expiry can't be replayed after it (ADR-001
// §14). the actor's next accepted hello turns it back into a pin
const EPOCH_PREFIX = "epoch:"
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
  // hour of the latest accepted hello. only used to tell whether an author
  // could still come back and resend its own tombstones
  lastSeen?: number
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
  /** When the upgrade was accepted. Pre-hello sockets past the deadline can be evicted. */
  acceptedAt?: number
  /** UTF-8 bytes queued on this socket before the 101, stretches its hello deadline. */
  snapshotBytes?: number
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
  ws: WebSocket
  tail: Promise<void>
  pendingMessages: number
  pendingBytes: number
  accepting: boolean
  abortPending: boolean
  /** False once aborted: its frames are dropped and no longer count toward the room backlog. */
  counted: boolean
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

// a stored epoch (pin or floor) as a number, 0 for none or anything malformed
function storedEpoch(hex: unknown): bigint {
  return typeof hex === "string" && /^[0-9a-f]{16}$/.test(hex) ? BigInt("0x" + hex) : 0n
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

// same answer as TextEncoder().encode(s).length (lone surrogates become
// U+FFFD, 3 bytes) without allocating, since snapshots call it per record
function utf8Length(s: string): number {
  let bytes = s.length
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i)
    if (c < 0x80) continue
    if (c < 0x800) {
      bytes += 1
    } else if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length && (s.charCodeAt(i + 1) & 0xfc00) === 0xdc00) {
      bytes += 2
      i++
    } else {
      bytes += 2
    }
  }
  return bytes
}

function coarseHour(ms: number): number {
  return ms - (ms % HOUR_MS)
}

// rough in-memory size of a listed value, only used to size the next read
function recordReadSize(value: unknown): number {
  const ct = (value as { ct?: unknown } | null)?.ct
  return (typeof ct === "string" ? ct.length : 0) + 512
}

function recordBytes(protocol: 2 | 3, record: SyncRecord | SyncRecordV3): number {
  return protocol === 3 ? recordBytesV3(record as SyncRecordV3) : recordBytesV2(record as SyncRecord)
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
  // queues that currently count toward the room backlog, so the heaviest can be shed
  private readonly pendingQueues = new Set<SocketMessageQueue>()
  private roomPendingMessages = 0
  private roomPendingBytes = 0
  // activity bookkeeping lives in memory and only hits storage hourly, on the
  // last close and from the alarm. both reset on a hibernation wake, which is
  // fine: persisted time is re-read lazily and the alarm is re-checked once
  private activityAt = 0
  private activityPersistedAt: number | undefined
  // when the alarm we last armed fires, null for none, undefined after a wake
  // (unknown, read it once). lets a join pull a far idle/purge alarm back to
  // the daily cadence without a storage read per accepted frame
  private alarmAt: number | null | undefined

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
          // a brand new room has no tombstones to backfill into tomb: rows.
          // 0 and not the current hour, so the relay doesn't keep a creation
          // time around. only legacy rooms get a real index hour (alarm())
          if (existingProtocol === undefined) await txn.put("meta:tombIndexAt", 0)
        })
      } catch {
        return new Response("Room initialization failed", { status: 503 })
      }
      // if this first join dies further down nothing else arms an alarm, and
      // the pin would sit there with nobody ever cleaning it up
      await this.ensureAlarm(Date.now() + MAINTENANCE_INTERVAL_MS)
    }

    // A throw inside blockConcurrencyWhile resets the whole object and drops
    // every member, so both callbacks catch and report instead.
    // The auth check above ran outside any bCW, so an alarm can wipe the room
    // (drive-by expiry or the idle purge) before we get here. Look again under
    // the same bCW as the accounting, otherwise the socket lands in a room
    // with no pin at all.
    const accounted = await this.state.blockConcurrencyWhile(async (): Promise<"ok" | "reset" | "failed"> => {
      try {
        const meta = await this.state.storage.get(["meta:auth", "meta:protocol"])
        const auth = meta.get("meta:auth")
        if (typeof auth !== "string" || !constantTimeEqual(auth, tokenHash) || meta.get("meta:protocol") !== protocol) {
          return "reset"
        }
        await this.ensureAccounting(protocol)
        return "ok"
      } catch {
        return "failed"
      }
    })
    if (accounted === "reset") {
      // transient for clients, the reconnect goes through the normal pin path
      metric("join_rejected", { reason: "room_reset" })
      return new Response("Room reset during join", { status: 503 })
    }
    if (accounted === "failed") {
      metric("storage_error", { operation: "accounting_migration" })
      return new Response("Room accounting unavailable", { status: 503 })
    }

    const acceptedAt = Date.now()
    if (!this.admitConnection(acceptedAt)) return new Response("Room full", { status: 503 })
    const pair = new WebSocketPair()
    const client = pair[0]
    const server = pair[1]
    this.state.acceptWebSocket(server)
    server.serializeAttachment({ windowStart: 0, msgs: 0, bytes: 0, protocol, roomId, acceptedAt } satisfies SocketState)
    // No mutation event can interleave with this fence/pages/end sequence.
    const sentBytes = await this.state.blockConcurrencyWhile(async () => {
      try {
        return await this.sendSnapshot(server, protocol)
      } catch {
        return null
      }
    })
    if (sentBytes === null) {
      metric("storage_error", { operation: "snapshot" })
      this.closeSocket(server, 1011, "snapshot unavailable")
      return new Response("Snapshot unavailable", { status: 503 })
    }
    // the client can't send anything before the 101, so nothing races this write
    const attachment = server.deserializeAttachment() as SocketState
    attachment.snapshotBytes = sentBytes
    server.serializeAttachment(attachment)
    await this.noteActivity()
    return new Response(null, { status: 101, webSocket: client })
  }

  private openSockets(): WebSocket[] {
    return this.state.getWebSockets().filter(ws => ws.readyState === WS_OPEN)
  }

  // Server-closed sockets whose client never echoed sit in CLOSING and must not
  // hold slots, but they still cost memory and fan-out until the transport
  // dies, so all accepted sockets get a separate hard cap. A full room also
  // reclaims v3 sockets that never proved an actor by their hello deadline,
  // most overdue first. Nothing else gets evicted.
  private admitConnection(now: number): boolean {
    if (this.state.getWebSockets().length >= MAX_ACCEPTED_SOCKETS) {
      metric("room_full", { reason: "accepted_sockets" })
      return false
    }
    const open = this.openSockets()
    let excess = open.length - MAX_CONNECTIONS + 1
    if (excess <= 0) return true
    // shipped clients only say hello after snapshot-end, so a socket that was
    // sent a big snapshot on a slow link gets that much longer
    const deadline = (socket: SocketState): number => (socket.acceptedAt ?? 0) + HELLO_DEADLINE_MS +
      Math.ceil((socket.snapshotBytes ?? 0) * 1000 / HELLO_DEADLINE_BYTES_PER_SEC)
    const stale = open
      .map(ws => ({ ws, socket: ws.deserializeAttachment() as SocketState | null }))
      .filter(({ socket }) => socket?.protocol === 3 && !socket.hello && now >= deadline(socket))
      .sort((a, b) => deadline(a.socket!) - deadline(b.socket!))
    for (const { ws } of stale) {
      if (excess <= 0) break
      metric("socket_evicted", { reason: "hello_deadline" })
      this.closeSocket(ws, 1013, "hello deadline")
      excess -= 1
    }
    return excess <= 0
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
      queue = this.newMessageQueue(ws)
      this.socketMessageQueues.set(ws, queue)
    }
    if (!queue.accepting) return
    // Admission is independent of the fixed-window rate limiter because an
    // async crypto/storage operation must not let an attacker retain an
    // unbounded number of otherwise-valid frames in this isolate.
    if (queue.pendingMessages >= RATE_MAX_MSGS || queue.pendingBytes + frameBytes > RATE_MAX_BYTES ||
        !this.makeRoomBacklogSpace(queue, frameBytes)) {
      this.closeSocket(ws, 4008, "rate limit")
      return
    }
    queue.pendingMessages += 1
    queue.pendingBytes += frameBytes
    this.roomPendingMessages += 1
    this.roomPendingBytes += frameBytes
    this.pendingQueues.add(queue)

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
      if (queue.counted) {
        this.roomPendingMessages -= 1
        this.roomPendingBytes -= frameBytes
        if (queue.pendingMessages === 0) this.pendingQueues.delete(queue)
      }
      // an aborted queue stays mapped so a straggler frame on the dead socket
      // can't open a fresh accepting queue. the WeakMap drops it with the socket
      if (queue.accepting && queue.tail === current && queue.pendingMessages === 0) {
        this.socketMessageQueues.delete(ws)
      }
    }
  }

  private newMessageQueue(ws: WebSocket): SocketMessageQueue {
    return {
      ws, tail: Promise.resolve(), pendingMessages: 0, pendingBytes: 0,
      accepting: true, abortPending: false, counted: true,
    }
  }

  // The room backlog cap used to close whichever socket's frame arrived next,
  // so one bursting peer could get an idle presence sender kicked. Now the
  // socket holding the largest backlog goes, counting the arriving frame on
  // its own sender. Returns false when that is the arriving socket itself.
  private makeRoomBacklogSpace(incoming: SocketMessageQueue, frameBytes: number): boolean {
    const load = (messages: number, bytes: number): number =>
      Math.max(messages / ROOM_PENDING_MAX_MSGS, bytes / ROOM_PENDING_MAX_BYTES)
    while (this.roomPendingMessages + 1 > ROOM_PENDING_MAX_MSGS ||
        this.roomPendingBytes + frameBytes > ROOM_PENDING_MAX_BYTES) {
      let heaviest = incoming
      let heaviestLoad = load(incoming.pendingMessages + 1, incoming.pendingBytes + frameBytes)
      for (const queue of this.pendingQueues) {
        if (queue === incoming) continue
        const candidate = load(queue.pendingMessages, queue.pendingBytes)
        if (candidate > heaviestLoad) {
          heaviest = queue
          heaviestLoad = candidate
        }
      }
      if (heaviest === incoming) return false
      metric("room_backlog_shed", { messages: heaviest.pendingMessages, bytes: heaviest.pendingBytes })
      this.closeSocket(heaviest.ws, 4008, "rate limit")
    }
    return true
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
    // accepted work and live traffic the relay acted on (relayed presence,
    // routed chat, an answered ping) count as room activity. noteActivity
    // writes at most once per ACTIVITY_PERSIST_MS, so the stored idle clock
    // stays within the hour of real use even if the sockets later vanish with
    // no close callback (deploy, restart). rejected frames never count
    let accepted = false
    try {
      if (type === "put") accepted = await (protocol === 3 ? this.applyChangeV3(ws, msg, false) : this.applyChange(ws, msg, false))
      else if (type === "del") accepted = await (protocol === 3 ? this.applyChangeV3(ws, msg, true) : this.applyChange(ws, msg, true))
      else if (type === "loc") accepted = await (protocol === 3 ? this.handlePresenceV3(ws, msg) : this.handlePresence(ws, msg))
      else if (type === "hello" && protocol === 3) accepted = await this.handleHello(ws, msg)
      else if (type === "leave" && protocol === 3) await this.handleExplicitLeave(ws, msg)
      else if (type === "chat-key" && protocol === 3) await this.handleChatKey(ws, msg)
      else if (type === "chat" && protocol === 3) accepted = await this.handleChat(ws, msg)
      else if (type === "ping" && Object.keys(msg).length === 1) {
        ws.send(JSON.stringify({ t: "pong" }))
        accepted = true
      }
    } catch {
      metric("storage_error", { operation: "message" })
      this.closeSocket(ws, 1011, "storage unavailable")
      return
    }
    if (accepted) await this.noteActivity()
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
    if (s) this.announceDeparture(ws, s)
    try { ws.close(code, "closing") } catch { /* closed */ }
    await this.noteSocketGone(ws)
  }

  async webSocketError(ws: WebSocket, _err: unknown): Promise<void> {
    this.abortSocketQueue(ws)
    // a close callback may never follow an error, so peers hear about it here.
    // anything already running finishes first, same ordering as a close
    try { await this.socketMessageQueues.get(ws)?.tail } catch { /* already handled */ }
    const s = ws.deserializeAttachment() as SocketState | null
    if (s) this.announceDeparture(ws, s)
    await this.noteSocketGone(ws)
  }

  // the idle clock starts when the last open socket goes, whichever path
  // notices it first (relay close, client close, transport error)
  private async noteSocketGone(ws: WebSocket): Promise<void> {
    if (this.openSockets().some(other => other !== ws)) return
    const now = Date.now()
    // skip only when the stored value is already this fresh and nothing newer is pending
    if (this.activityPersistedAt !== undefined && this.activityAt <= this.activityPersistedAt &&
        now - this.activityPersistedAt < LAST_CLOSE_DEDUPE_MS) return
    await this.persistActivity(now)
  }

  // Maintenance. Runs at least daily while sockets are open and otherwise only
  // when idle expiry, the idle purge or a tombstone compaction is due. Every
  // room that still holds anything leaves with one of those armed, so nothing
  // outlives the purge. Every step is best effort and a step that fails asks
  // for a retry, so one bad pass can't strand the room without an alarm.
  async alarm(): Promise<void> {
    const now = Date.now()
    // the alarm that's firing is used up. anything a join arms meanwhile is
    // folded in at the end
    this.alarmAt = null
    const open = this.openSockets().length > 0
    if (!open && await this.roomIsWiped()) {
      // leftover alarm on a purged room (the compat date predates deleteAll
      // taking the alarm with it). touch nothing, or it stops being empty
      return
    }
    if (open) {
      await this.persistActivity(now)
    } else if (this.activityPersistedAt !== undefined && this.activityAt > this.activityPersistedAt) {
      // throttled activity whose last socket left without a close callback
      await this.persistActivity(this.activityAt)
    }
    let next: number | null = open ? now + MAINTENANCE_INTERVAL_MS : null
    // never spin: anything due but blocked waits at least a minute
    const later = (at: number): void => {
      const when = Math.max(at, now + 60_000)
      next = next === null ? when : Math.min(next, when)
    }
    const retry = (): void => later(now + MAINTENANCE_RETRY_MS)
    if (!await this.ensureTombstoneIndex(now)) retry()
    if (!open) {
      try {
        const { idleUntil, purgeAt } = await this.idleDeadlines()
        if (purgeAt !== null && purgeAt <= now) {
          // drive-by or idle purge: nothing left to look after, not even an alarm
          if (await this.purgeIdleRoom(now) === "purged") return
        } else if (idleUntil !== null && idleUntil <= now) {
          if (await this.expireIdleRoom(now) === "purged") return
        }
      } catch {
        metric("storage_error", { operation: "expiry_check" })
        retry()
      }
    }
    const compactAt = await this.compactTombstones(now)
    if (compactAt !== null) later(compactAt)
    if (this.openSockets().length > 0) {
      // a join can land while this pass awaits storage; it still needs a
      // daily pass, not an idle deadline worked out before it showed up
      later(now + MAINTENANCE_INTERVAL_MS)
    } else {
      // an idle room always leaves with its next idle deadline armed, read
      // fresh since the steps above (or a join and leave meanwhile) move it.
      // one that's still past due means its step failed or got skipped, so
      // that's a retry, not a spin
      try {
        const { idleUntil, purgeAt } = await this.idleDeadlines()
        for (const due of [idleUntil, purgeAt]) {
          if (due === null) continue
          if (due > now) later(due)
          else retry()
        }
      } catch {
        retry()
      }
    }
    // a join during this pass may have armed its own daily alarm
    if (typeof this.alarmAt === "number") later(this.alarmAt)
    if (next === null) return
    await this.state.storage.setAlarm(next)
    this.alarmAt = next
  }

  // true only when there's provably nothing stored. a failed read says no,
  // the normal pass has its own error handling
  private async roomIsWiped(): Promise<boolean> {
    try {
      return (await this.state.storage.list({ limit: 1 })).size === 0
    } catch {
      return false
    }
  }

  private abortSocketQueue(ws: WebSocket): void {
    let queue = this.socketMessageQueues.get(ws)
    if (!queue) {
      // remember the close even with nothing queued, otherwise the next frame
      // on this dead socket would open a fresh queue and get processed
      queue = this.newMessageQueue(ws)
      this.socketMessageQueues.set(ws, queue)
    }
    queue.accepting = false
    queue.abortPending = true
    if (queue.counted) {
      queue.counted = false
      this.roomPendingMessages -= queue.pendingMessages
      this.roomPendingBytes -= queue.pendingBytes
      this.pendingQueues.delete(queue)
    }
  }

  private closeSocket(ws: WebSocket, code: number, reason: string): void {
    this.abortSocketQueue(ws)
    // Hibernating close callbacks aren't guaranteed after a server-initiated
    // close (the client may never echo it), so announce while the binding is
    // still known. A fenced socket is left to the replacement path.
    const s = ws.deserializeAttachment() as SocketState | null
    if (s && !s.replacementFences?.length) this.announceDeparture(ws, s)
    try { ws.close(code, reason) } catch { /* closed */ }
    // no close callback is promised after this either. the put is issued
    // before the first await, and persistActivity swallows its own errors
    void this.noteSocketGone(ws)
  }

  private announceDeparture(ws: WebSocket, socket: SocketState): void {
    if (socket.presenceV2) {
      const clientId = socket.presenceV2.clientId
      socket.presenceV2 = undefined
      ws.serializeAttachment(socket)
      this.broadcast({ t: "leave", clientId }, ws)
    }
    if (socket.hello) this.announceV3Departure(ws, socket, "transient")
  }

  private async noteActivity(): Promise<void> {
    const now = Date.now()
    this.activityAt = Math.max(this.activityAt, now)
    try {
      if (this.activityPersistedAt === undefined) {
        // first accepted frame after a wake: one read instead of a write
        this.activityPersistedAt = (await this.state.storage.get<number>("meta:lastActivity")) ?? 0
      }
      if (now - this.activityPersistedAt >= ACTIVITY_PERSIST_MS) {
        await this.state.storage.put("meta:lastActivity", now)
        this.activityPersistedAt = now
      }
    } catch {
      // bookkeeping only. never close a socket or fail a join over it
      metric("storage_error", { operation: "activity" })
    }
    await this.ensureAlarm(now + MAINTENANCE_INTERVAL_MS)
  }

  // makes sure some alarm fires no later than at. one read after a wake, a
  // write only when the armed one is missing or further out (an idle room's
  // expiry or purge alarm when someone joins)
  private async ensureAlarm(at: number): Promise<void> {
    if (typeof this.alarmAt === "number" && this.alarmAt <= at) return
    try {
      if (this.alarmAt === undefined) this.alarmAt = await this.state.storage.getAlarm()
      if (this.alarmAt === null || this.alarmAt > at) {
        await this.state.storage.setAlarm(at)
        this.alarmAt = at
      }
    } catch {
      metric("storage_error", { operation: "alarm" })
    }
  }

  private async persistActivity(at: number): Promise<void> {
    this.activityAt = Math.max(this.activityAt, at)
    try {
      await this.state.storage.put("meta:lastActivity", at)
      this.activityPersistedAt = at
    } catch {
      metric("storage_error", { operation: "activity" })
    }
  }

  /**
   * idleUntil: when idle expiry is due, or null when the room already expired
   * since its last activity. purgeAt: when the whole room goes, counted from
   * the last activity (from the last expiry if no activity was ever recorded),
   * or null when neither is known. Throws on storage errors so callers retry.
   */
  private async idleDeadlines(): Promise<{ idleUntil: number | null; purgeAt: number | null }> {
    const meta = await this.state.storage.get<number>(["meta:lastActivity", "meta:expiredAt"])
    const last = meta.get("meta:lastActivity")
    const expiredAt = meta.get("meta:expiredAt")
    const idleUntil = expiredAt !== undefined && expiredAt >= (last ?? 0) ? null : (last ?? 0) + IDLE_TTL_MS
    const since = last ?? expiredAt
    return { idleUntil, purgeAt: since === undefined ? null : since + ROOM_PURGE_TTL_MS }
  }

  // Wipes a room that has been idle ROOM_PURGE_TTL_MS: meta rows, tombstones,
  // bookkeeping, the lot, same as a drive-by room at expiry. A device that
  // comes back after this gets a fresh room at seq 0 (a rollback diagnostic on
  // shipped clients) and needs a new join code. Crash safety comes from
  // deleteAll being atomic on the sqlite backend this relay is configured
  // for: a pass that dies leaves the room exactly as it was, the deadline is
  // recomputed from durable state next time, and the retry alarm (or the
  // runtime's own alarm retry) just does it again. Under bCW so a join either
  // lands before (and resets the clock) or after (and finds no pin).
  private async purgeIdleRoom(now: number): Promise<"purged" | "skipped" | "failed"> {
    return this.state.blockConcurrencyWhile(async () => {
      try {
        if (this.openSockets().length > 0) return "skipped"
        const { purgeAt } = await this.idleDeadlines()
        if (purgeAt === null || purgeAt > now) return "skipped"
        await this.wipeRoom()
        metric("room_purged", { reason: "idle" })
        return "purged"
      } catch {
        metric("storage_error", { operation: "purge" })
        return "failed"
      }
    })
  }

  private async wipeRoom(): Promise<void> {
    await this.state.storage.deleteAll()
    this.activityAt = 0
    this.activityPersistedAt = undefined
    // our compat date predates deleteAll dropping the alarm too. if this
    // fails the leftover alarm finds an empty room and does nothing
    try {
      await this.state.storage.deleteAlarm()
      this.alarmAt = null
    } catch {
      this.alarmAt = undefined
    }
  }

  // Idle expiry drops what an idle room doesn't need: live object ciphertext
  // and actor pins. Each pin leaves only its hello epoch behind, as an
  // epoch:<actor> floor counted like the pin was. It keeps auth, protocol,
  // seq, highWater, accounting, every tombstone and the floors, so a
  // returning client neither rolls back, nor trips the counter window, nor
  // sees a delete come back, and nobody holding the join code can replay an
  // older session of someone else's (ADR-001 §14), until the idle purge takes
  // the rest (purgeIdleRoom). A room that never accepted a write has no
  // records to roll back and is wiped outright, pins and all, like before SP1.
  // Runs under bCW so no join or write lands between the scan and the rewrite.
  //
  // Crash safety: seq, horizonSeq and the meta:expiring marker are written
  // first, then the floors, then the deletes. A pass that dies half way has
  // already moved seq past what it removed and never drops a pin before its
  // floor is stored. "failed" makes alarm() retry soon, and a join in the
  // meantime recounts the counters because of the marker (ensureAccounting).
  private async expireIdleRoom(now: number): Promise<"expired" | "skipped" | "purged" | "failed"> {
    return this.state.blockConcurrencyWhile(async () => {
      try {
        if (this.openSockets().length > 0) return "skipped"
        const { idleUntil } = await this.idleDeadlines()
        if (idleUntil === null || idleUntil > now) return "skipped"
        const protocol = ((await this.state.storage.get<number>("meta:protocol")) ?? 2) as 2 | 3
        const indexedAt = (await this.state.storage.get<number>("meta:tombIndexAt")) ?? now
        const doomed: string[] = []
        // newest hello among the pins about to go. compaction uses it as the
        // last-seen bound for authors that no longer have a pin
        let droppedSeen: number | undefined
        // floors already stored (an earlier expiry, or a pass that died) plus
        // the ones the pins below leave. only the new or raised ones get written
        const floors = new Map<string, string>()
        for await (const [key, epoch] of this.scanRecords<string>(EPOCH_PREFIX, () => 64)) floors.set(key, epoch)
        const floorWrites: Array<[string, string]> = []
        for await (const [key, pin] of this.scanRecords<ActorRecord>("actor:", () => 1024)) {
          doomed.push(key)
          const seen = pin.lastSeen ?? Math.max(pin.firstSeen ?? 0, indexedAt)
          droppedSeen = droppedSeen === undefined ? seen : Math.max(droppedSeen, seen)
          // a pin from before hello epochs has nothing to hold a hello to
          const epoch = storedEpoch(pin.helloEpoch)
          const floorKey = EPOCH_PREFIX + key.slice("actor:".length)
          if (epoch === 0n || epoch <= storedEpoch(floors.get(floorKey))) continue
          floors.set(floorKey, pin.helloEpoch!)
          floorWrites.push([floorKey, pin.helloEpoch!])
        }
        let keptRecords = 0
        let keptBytes = 0
        for await (const [key, record] of this.scanRecords<SyncRecord | SyncRecordV3>("obj:", recordReadSize)) {
          if (record.deleted === true) {
            keptRecords += 1
            keptBytes += recordBytes(protocol, record)
          } else {
            doomed.push(key)
          }
        }
        const seq = (await this.state.storage.get<number>("meta:seq")) ?? 0
        const highWater = (await this.state.storage.get<string>("meta:highWater")) ?? ZERO_COUNTER
        if (seq === 0 && keptRecords === 0 && highWater === ZERO_COUNTER) {
          // no seq, counter or delete a returning device could be rolled back
          // on. v3 room ids are bound to the token so this can't be squatted
          await this.wipeRoom()
          metric("room_expired", { removed: doomed.length, kept: 0, purged: 1 })
          return "purged"
        }
        for (const [key, epoch] of floors) {
          keptRecords += 1
          keptBytes += storageBytes(key, epoch)
        }
        if (doomed.length > 0) {
          await this.state.storage.transaction(async txn => {
            const nextSeq = ((await txn.get<number>("meta:seq")) ?? 0) + 1
            const previousSeen = await txn.get<number>("meta:droppedPinsSeen")
            const seen = droppedSeen === undefined ? previousSeen
              : previousSeen === undefined ? droppedSeen : Math.max(previousSeen, droppedSeen)
            await txn.put({
              "meta:seq": nextSeq,
              "meta:horizonSeq": nextSeq,
              "meta:expiring": now,
              ...(seen !== undefined ? { "meta:droppedPinsSeen": seen } : {}),
            })
          })
          // every floor is down before the first pin goes
          for (let index = 0; index < floorWrites.length; index += 128) {
            await this.state.storage.put(Object.fromEntries(floorWrites.slice(index, index + 128)))
          }
          for (let index = 0; index < doomed.length; index += 128) {
            await this.state.storage.delete(doomed.slice(index, index + 128))
          }
        }
        await this.state.storage.transaction(async txn => {
          await txn.put({ "meta:totalRecords": keptRecords, "meta:bytes": keptBytes, "meta:expiredAt": now })
          await txn.delete("meta:expiring")
        })
        metric("room_expired", { removed: doomed.length, kept: keptRecords })
        return "expired"
      } catch {
        metric("storage_error", { operation: "expiry" })
        return "failed"
      }
    })
  }

  // One-time pass for rooms written before tomb: rows existed. Legacy v3
  // tombstones have no delete time, so they count from now (conservative).
  // False when it failed and needs another go.
  private async ensureTombstoneIndex(now: number): Promise<boolean> {
    try {
      if (await this.state.storage.get("meta:tombIndexAt") !== undefined) return true
      let pending: Array<{ key: string; at: number }> = []
      const flush = async (): Promise<void> => {
        if (pending.length === 0) return
        const rows = pending
        pending = []
        await this.state.storage.transaction(async txn => {
          for (const { key, at } of rows) {
            const id = key.slice(4)
            if (await txn.get(TOMB_PREFIX + id) !== undefined) continue
            const current = await txn.get<SyncRecord | SyncRecordV3>(key)
            if (current?.deleted === true) await txn.put(TOMB_PREFIX + id, at)
          }
        })
      }
      for await (const [key, record] of this.scanRecords<SyncRecord | SyncRecordV3>("obj:", recordReadSize)) {
        if (record.deleted !== true) continue
        const deletedAt = (record as SyncRecord).deletedAt
        pending.push({ key, at: coarseHour(typeof deletedAt === "number" ? deletedAt : now) })
        if (pending.length === 64) await flush()
      }
      await flush()
      await this.state.storage.put("meta:tombIndexAt", coarseHour(now))
      return true
    } catch {
      metric("storage_error", { operation: "tomb_index" })
      return false
    }
  }

  // Compacts tombstones older than TOMBSTONE_TTL_MS whose author has not said
  // hello for TOMBSTONE_TTL_MS either. The author check matters: shipped
  // clients resend every own tombstone a snapshot doesn't confirm, so dropping
  // a live author's tombstone would just come straight back (and burst the
  // rate limit on each reconnect). Returns when the next tombstone could
  // become compactable, or null if none remain.
  //
  // Authors without a pin. v2 has no pins at all, so its authors count as
  // seen at the room's last activity. A v3 pin only goes at idle expiry and
  // any hello since would have made a new one, so an unpinned v3 author was
  // last seen no later than the newest hello among the dropped pins, even if
  // other devices keep the room busy afterwards. Not meta:expiredAt: a pass
  // that dropped pins and then failed never wrote it, droppedPinsSeen goes
  // in before the first delete.
  private async compactTombstones(now: number): Promise<number | null> {
    try {
      const cutoff = now - TOMBSTONE_TTL_MS
      const protocol = ((await this.state.storage.get<number>("meta:protocol")) ?? 2) as 2 | 3
      // indexedAt is 0 in rooms this relay created and the first index hour in
      // legacy ones, whose pins and activity predate any of this tracking
      const indexedAt = (await this.state.storage.get<number>("meta:tombIndexAt")) ?? now
      // no recorded activity at all: treat everyone as just seen, never as gone
      let nextDue: number | null = null
      const later = (at: number): void => { nextDue = nextDue === null ? at : Math.min(nextDue, at) }
      const due: string[] = []
      for await (const [key, at] of this.scanRecords<number>(TOMB_PREFIX, () => 64)) {
        const deletedAt = typeof at === "number" && Number.isFinite(at) ? at : now
        if (deletedAt > cutoff) later(deletedAt + TOMBSTONE_TTL_MS)
        else due.push(key.slice(TOMB_PREFIX.length))
      }
      for (let index = 0; index < due.length; index += 64) {
        const ids = due.slice(index, index + 64)
        await this.state.storage.transaction(async txn => {
          // Eligibility belongs to this transaction, not the entire scan. A
          // valid hello can refresh a pin between batches. Cache author reads
          // only within one batch and refresh the conservative unpinned bound.
          const roomSeen = Math.max((await txn.get<number>("meta:lastActivity")) ?? now, indexedAt)
          let unpinnedSeen = roomSeen
          if (protocol === 3) {
            const droppedSeen = await txn.get<number>("meta:droppedPinsSeen")
            if (typeof droppedSeen === "number") unpinnedSeen = Math.min(unpinnedSeen, droppedSeen)
          }
          // A continuously bound author is current even when its last durable
          // hello is older than the TTL.
          const authorSeen = new Map<string, number>()
          for (const ws of this.openSockets()) {
            const by = (ws.deserializeAttachment() as SocketState | null)?.hello?.by
            if (by) authorSeen.set(by, now)
          }
          const doomed: string[] = []
          let removed = 0
          let removedBytes = 0
          for (const id of ids) {
            const at = await txn.get<number>(TOMB_PREFIX + id)
            if (at === undefined) continue
            const record = await txn.get<SyncRecord | SyncRecordV3>("obj:" + id)
            if (record?.deleted !== true) {
              // recreated since, the row is stale
              doomed.push(TOMB_PREFIX + id)
              continue
            }
            if (at > cutoff) continue
            let seen = authorSeen.get(record.by)
            if (seen === undefined) {
              const pin = protocol === 3 ? await txn.get<ActorRecord>("actor:" + record.by) : undefined
              seen = pin ? (pin.lastSeen ?? Math.max(pin.firstSeen, indexedAt)) : unpinnedSeen
              authorSeen.set(record.by, seen)
            }
            if (seen > cutoff) {
              later(seen + TOMBSTONE_TTL_MS)
              continue
            }
            doomed.push("obj:" + id, TOMB_PREFIX + id)
            removed += 1
            removedBytes += recordBytes(protocol, record)
          }
          if (doomed.length > 0) await txn.delete(doomed)
          if (removed === 0) return
          const total = (await txn.get<number>("meta:totalRecords")) ?? removed
          const bytes = (await txn.get<number>("meta:bytes")) ?? removedBytes
          const seq = ((await txn.get<number>("meta:seq")) ?? 0) + 1
          await txn.put({
            "meta:totalRecords": Math.max(0, total - removed),
            "meta:bytes": Math.max(0, bytes - removedBytes),
            "meta:seq": seq,
            "meta:horizonSeq": seq,
          })
        })
      }
      if (due.length > 0) metric("tombstones_compacted", { examined: due.length })
      return nextDue
    } catch {
      metric("storage_error", { operation: "compaction" })
      return now + MAINTENANCE_RETRY_MS
    }
  }

  // Lists a prefix in pages sized to STORAGE_READ_BUDGET_BYTES of values, so a
  // room of 700 KB drawings no longer pulls 100 of them (70 MB) into one read.
  private async *scanRecords<T>(prefix: string, sizeOf: (value: T) => number): AsyncGenerator<[string, T]> {
    let cursor: string | undefined
    let limit: number = STORAGE_FIRST_PAGE_SIZE
    while (true) {
      const options: DurableObjectListOptions = { prefix, limit }
      if (cursor !== undefined) options.startAfter = cursor
      const page = await this.state.storage.list<T>(options)
      let bytes = 0
      for (const [key, value] of page) {
        bytes += sizeOf(value)
        cursor = key
        yield [key, value]
      }
      if (page.size < limit) return
      const average = Math.max(1, bytes / page.size)
      limit = Math.max(1, Math.min(STORAGE_PAGE_SIZE, Math.floor(STORAGE_READ_BUDGET_BYTES / average)))
    }
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

  private async handlePresence(sender: WebSocket, input: unknown): Promise<boolean> {
    const msg = input as Record<string, unknown>
    const clientId = typeof msg.clientId === "string" ? msg.clientId : ""
    if (!clientId || clientId.length > 128 || !isValidCiphertext(msg.ct, PRESENCE_CT_MAX)) return false
    const s = sender.deserializeAttachment() as SocketState
    s.presenceV2 = { clientId, ct: msg.ct }
    sender.serializeAttachment(s)
    this.broadcast({ t: "loc", ...s.presenceV2 }, sender)
    return true
  }

  private async applyChange(sender: WebSocket, input: unknown, deleted: boolean): Promise<boolean> {
    const msg = input as Record<string, unknown>
    const rid = requestId(input)
    const reject = (code: string, retry: boolean): void => {
      if (rid) this.sendV2Nack(sender, rid, msg.by, code, retry)
    }
    if (typeof msg.id !== "string" || msg.id.length === 0 || msg.id.length > 256) { reject("invalid", false); return false }
    if (typeof msg.v !== "number" || !Number.isSafeInteger(msg.v) || msg.v < 0 || msg.v > MAX_V) { reject("invalid", false); return false }
    if (typeof msg.by !== "string" || msg.by.length === 0 || msg.by.length > 128) { reject("invalid", false); return false }
    // Hardened Android/iOS v2 clients seal and sign deletes but historically
    // omitted the redundant outer `kind`. Normalize only that exact shape;
    // plaintext legacy deletes still fail the ciphertext check below.
    const kind = deleted && msg.kind === undefined ? "del" : msg.kind
    if (typeof kind !== "string" || !/^[A-Za-z0-9_-]{1,32}$/.test(kind)) { reject("invalid", false); return false }
    if ((deleted && kind !== "del") || (!deleted && kind === "del")) { reject("invalid", false); return false }
    if (!isValidCiphertext(msg.ct, CT_MAX)) { reject("invalid", false); return false }
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
        if (deleted) await txn.put(TOMB_PREFIX + record.id, coarseHour(record.deletedAt!))
        else if (existing?.deleted) await txn.delete(TOMB_PREFIX + record.id)
        result.outcome = "stored"
      })
    } catch {
      metric("storage_error", { operation: "v2_mutation" })
      reject("storage", true)
      return false
    }
    if (result.outcome === "stored") this.broadcast({ t: deleted ? "del" : "put", ...record, seq }, sender)
    if (result.outcome === "stored" || result.outcome === "duplicate") {
      if (rid) sender.send(JSON.stringify({
        t: "op-ack", av: DELIVERY_ACK_VERSION, rid, by: record.by,
        id: record.id, v: record.v, kind: record.kind, cth: await ciphertextHash(record.ct),
      }))
      return true
    }
    reject(result.outcome, false)
    return false
  }

  private async handleHello(ws: WebSocket, input: unknown): Promise<boolean> {
    const frame = parseHello(input)
    const socket = ws.deserializeAttachment() as SocketState
    if (socket.protocol !== 3) return false
    if (!frame) {
      metric("actor_rejected", { reason: "malformed_hello" })
      this.closeSocket(ws, 4011, "invalid actor proof")
      return false
    }
    if (socket.hello) {
      if (JSON.stringify(socket.hello) === JSON.stringify(frame) && !socket.replacementFences?.length) {
        // A retried identical frame on the same already-proven socket is safe
        // and must not fail as a durable epoch replay.
        ws.send(JSON.stringify({ t: "hello-ack", by: frame.by, sd: frame.sd, vs: frame.vs }))
      } else if (JSON.stringify(socket.hello) !== JSON.stringify(frame)) {
        this.closeSocket(ws, 4012, "actor already announced")
      }
      return false
    }

    // Never let unauthenticated actor text affect another live socket. Actor
    // recomputation and Ed25519 proof both complete before replacement fencing.
    const computed = await actorIdFor(socket.roomId, frame.pub)
    if (!computed || !constantTimeEqual(computed, frame.by) || !await verifyHello(socket.roomId, frame)) {
      metric("actor_rejected", { reason: "invalid_proof" })
      this.closeSocket(ws, 4011, "invalid actor proof")
      return false
    }
    // the relay may have closed this socket (eviction, backlog shed) while the
    // proof was being checked. a dead socket must never fence or retire anyone
    if (!this.socketStillLive(ws)) return false
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
        return false
      }
      // same check again after the durable pin. the pin stays (its epoch is
      // spent, the client reconnects with a newer one) but nothing gets bound,
      // and the finally below hands the older session back its fence
      if (!this.socketStillLive(ws)) return false
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
      return true
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

  private socketStillLive(ws: WebSocket): boolean {
    return ws.readyState === WS_OPEN && this.socketMessageQueues.get(ws)?.abortPending !== true
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
      if (ws.readyState !== WS_OPEN) {
        // the relay closed it while fenced and closeSocket left the leave to
        // the replacement path. that path failed, so say it here instead of
        // bringing back a session nobody can reach
        this.announceV3Departure(ws, current, "transient", failedReplacement)
        continue
      }
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
    const floorKey = EPOCH_PREFIX + frame.by
    const incomingEpoch = parseHelloEpoch(frame.vs, frame.by)!
    let result: "ok" | "mismatch" | "replay" | "quota" = "ok"
    await this.state.storage.transaction(async txn => {
      const existing = await txn.get<ActorRecord>(key)
      if (existing?.pubkey !== undefined && existing.pubkey !== frame.pub) { result = "mismatch"; return }
      // once idle expiry dropped the pin its epoch floor stands in for it, so
      // the same strictly-newer rule holds across expiry. normally only one
      // of the two exists, a pass that died half way can leave both
      const floor = await txn.get<string>(floorKey)
      const pinned = storedEpoch(existing?.helloEpoch)
      const floored = storedEpoch(floor)
      if (incomingEpoch.value <= (pinned > floored ? pinned : floored)) { result = "replay"; return }
      const actor: ActorRecord = {
        pubkey: frame.pub,
        firstSeen: existing?.firstSeen ?? Date.now(),
        lastSeen: coarseHour(Date.now()),
        helloEpoch: incomingEpoch.hex,
        hello: frame,
      }
      const total = (await txn.get<number>("meta:totalRecords")) ?? 0
      const bytes = (await txn.get<number>("meta:bytes")) ?? 0
      // the new pin takes over from the floor, still one counted row per actor
      const nextTotal = total + (existing ? 0 : 1) - (floor !== undefined ? 1 : 0)
      const nextBytes = bytes - (existing ? storageBytes(key, existing) : 0) -
        (floor !== undefined ? storageBytes(floorKey, floor) : 0) + storageBytes(key, actor)
      if (nextTotal > MAX_RECORDS || nextBytes > MAX_STORED_BYTES) { result = "quota"; return }
      await txn.put(key, actor)
      if (floor !== undefined) await txn.delete(floorKey)
      await txn.put("meta:totalRecords", nextTotal)
      await txn.put("meta:bytes", nextBytes)
    })
    return result
  }

  private async applyChangeV3(sender: WebSocket, input: unknown, deleted: boolean): Promise<boolean> {
    const msg = input as Record<string, unknown>
    let socket = sender.deserializeAttachment() as SocketState
    const rid = requestId(input)
    const reject = (code: string, retry: boolean): void => {
      if (rid) this.sendV3Nack(sender, socket, rid, code, retry)
    }
    if (socket.replacementFences?.length) { reject("session-replaced", false); return false }
    const hello = socket.hello
    if (!hello) { reject("hello-required", true); return false }
    if (typeof msg.id !== "string" || !B64URL_32_RE.test(msg.id)) { reject("invalid", false); return false }
    if (typeof msg.vs !== "string") { reject("invalid", false); return false }
    const stamp = parseStamp(msg.vs)
    if (!stamp || stamp.counter > MAX_COUNTER || stamp.actorId !== hello.by) { reject("invalid", false); return false }
    if (msg.by !== hello.by || msg.pub !== hello.pub || msg.sd !== hello.sd) { reject("session-mismatch", false); return false }
    if (typeof msg.kind !== "string" || !/^[A-Za-z0-9_-]{1,32}$/.test(msg.kind)) { reject("invalid", false); return false }
    if (deleted ? msg.kind !== "del" : msg.kind === "del") { reject("invalid", false); return false }
    if (!isValidCiphertext(msg.ct, CT_MAX)) { reject("invalid", false); return false }
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
      return false
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
        if (deleted) await txn.put(TOMB_PREFIX + record.id, coarseHour(Date.now()))
        else if (existing?.deleted) await txn.delete(TOMB_PREFIX + record.id)
        result.outcome = "stored"
      })
    } catch {
      metric("storage_error", { operation: "v3_mutation" })
      reject("storage", true)
      return false
    }
    if (result.outcome === "stored") this.broadcast({ t: deleted ? "del" : "put", ...record, seq }, sender)
    if (result.outcome === "stored" || result.outcome === "duplicate") {
      if (rid) sender.send(JSON.stringify({
        t: "op-ack", av: DELIVERY_ACK_VERSION, rid,
        by: hello.by, sd: hello.sd, id: record.id, vs: record.vs,
        kind: record.kind, cth,
      }))
      return true
    }
    reject(result.outcome, false)
    return false
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

  // false so handleChat can return it straight: a nacked chat routed nothing
  private sendChatNack(sender: WebSocket, mid: string | null, code: string, retry = false): false {
    const socket = sender.deserializeAttachment() as SocketState | null
    sender.send(JSON.stringify({
      t: "chat-nack", cv: 1, ...(mid ? { mid } : {}),
      ...(socket?.hello ? { by: socket.hello.by, sd: socket.hello.sd } : {}),
      code, retry,
    }))
    return false
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
        if (peer === sender || peer.readyState !== WS_OPEN) continue
        const state = peer.deserializeAttachment() as SocketState | null
        if (state?.protocol !== 3 || !state.hello || !state.chatKey || state.replacementFences?.length) continue
        try { peer.send(data) } catch { /* dead socket */ }
      }
      return true
    }
    const recipient = this.state.getWebSockets().find(peer => {
      if (peer === sender || peer.readyState !== WS_OPEN) return false
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

  private async handleChat(sender: WebSocket, input: unknown): Promise<boolean> {
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
      return true
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
    return true
  }

  private async handlePresenceV3(ws: WebSocket, input: unknown): Promise<boolean> {
    const msg = input as Record<string, unknown>
    const socket = ws.deserializeAttachment() as SocketState
    if (socket.replacementFences?.length) return false
    const hello = socket.hello
    if (!hello || msg.by !== hello.by || msg.pub !== hello.pub || msg.sd !== hello.sd) return false
    if (typeof msg.vs !== "string" || !isValidCiphertext(msg.ct, PRESENCE_CT_MAX)) return false
    const stamp = parseStamp(msg.vs)
    if (!stamp || stamp.actorId !== hello.by || stamp.counter === 0n) return false
    const prior = socket.presenceCounter ? BigInt("0x" + socket.presenceCounter) : 0n
    if (stamp.counter <= prior || stamp.counter > prior + ADVANCE_WINDOW) return false
    socket.presenceCounter = stamp.counter.toString(16).padStart(16, "0")
    socket.presenceV3 = { t: "loc", by: hello.by, pub: hello.pub, sd: hello.sd, vs: msg.vs, ct: msg.ct }
    ws.serializeAttachment(socket)
    this.broadcast(socket.presenceV3, ws)
    return true
  }

  private collectV2Members(except: WebSocket): PresenceV2[] {
    return this.state.getWebSockets().flatMap(ws => {
      if (ws === except || ws.readyState !== WS_OPEN) return []
      const s = ws.deserializeAttachment() as SocketState | null
      return s?.presenceV2 ? [s.presenceV2] : []
    })
  }

  private collectV3Live(except: WebSocket): Array<HelloFrame | ChatKeyFrame | PresenceV3> {
    const frames: Array<HelloFrame | ChatKeyFrame | PresenceV3> = []
    for (const ws of this.state.getWebSockets()) {
      // a socket the relay already closed is not a live member, even if its
      // client never echoed the close
      if (ws === except || ws.readyState !== WS_OPEN) continue
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

  // Recounts meta:totalRecords / meta:bytes from what is actually stored. v3
  // rooms from before exact accounting get it once (accountingSchema 2), and
  // any room whose idle expiry died half way gets it on the next join, since
  // that pass never rewrote its counters. Runs under bCW from fetch.
  private async ensureAccounting(protocol: 2 | 3): Promise<void> {
    const meta = await this.state.storage.get(["meta:accountingSchema", "meta:expiring"])
    const migrated = protocol === 2 || meta.get("meta:accountingSchema") === 2
    if (migrated && !meta.has("meta:expiring")) return
    let total = 0
    let bytes = 0
    if (protocol === 3) {
      for await (const [key, value] of this.scanRecords<ActorRecord>("actor:", () => 1024)) {
        total += 1
        bytes += storageBytes(key, value)
      }
      for await (const [key, value] of this.scanRecords<string>(EPOCH_PREFIX, () => 64)) {
        total += 1
        bytes += storageBytes(key, value)
      }
    }
    for await (const [, value] of this.scanRecords<SyncRecord | SyncRecordV3>("obj:", recordReadSize)) {
      total += 1
      bytes += recordBytes(protocol, value)
    }
    await this.state.storage.transaction(async txn => {
      await txn.put("meta:totalRecords", total)
      await txn.put("meta:bytes", bytes)
      if (protocol === 3) await txn.put("meta:accountingSchema", 2)
      await txn.delete("meta:expiring")
    })
  }

  // returns the UTF-8 bytes queued on the socket
  private async sendSnapshot(ws: WebSocket, protocol: 2 | 3): Promise<number> {
    const snapshot: SnapshotState = {
      seq: (await this.state.storage.get<number>("meta:seq")) ?? 0,
      highWater: (await this.state.storage.get<string>("meta:highWater")) ?? ZERO_COUNTER,
    }
    const liveFrames = protocol === 3 ? this.collectV3Live(ws) : []
    let sentBytes = 0
    const send = (text: string, bytes = utf8Length(text)): void => {
      ws.send(text)
      sentBytes += bytes
    }
    send(JSON.stringify({ t: "snapshot-begin", seq: snapshot.seq, ...(protocol === 3 ? { highWater: snapshot.highWater } : {}) }))

    // Pages are byte-for-byte JSON.stringify({ t, items, more, members? }), just
    // assembled by hand so each record is stringified and measured once. The
    // old loop re-stringified the whole pending page per record (O(n x page)).
    const head = '{"t":"snapshot","items":['
    const membersTail = protocol === 2 ? ',"members":' + JSON.stringify(this.collectV2Members(ws)) : ""
    const membersTailBytes = utf8Length(membersTail)
    // sized for the longer "false" flag so the last page can't overshoot
    const tailBytes = '],"more":false}'.length
    let parts: string[] = []
    let partsBytes = 0
    let first = true
    const pageBytes = (next: number): number => head.length + partsBytes + (parts.length > 0 ? 1 : 0) + next +
      tailBytes + (first ? membersTailBytes : 0)
    const flush = (more: boolean): void => {
      const tail = '],"more":' + more + (first ? membersTail : "") + "}"
      send(head + parts.join(",") + tail, head.length + partsBytes + utf8Length(tail))
      parts = []
      partsBytes = 0
      first = false
    }

    for await (const [, record] of this.scanRecords<SyncRecord | SyncRecordV3>("obj:", recordReadSize)) {
      const text = JSON.stringify(record)
      const bytes = utf8Length(text)
      if (pageBytes(bytes) > SNAPSHOT_FRAME_BYTES) {
        // v2 puts the member list on the first page. If that plus the first
        // record won't fit, the members go out alone rather than throwing
        if (parts.length > 0 || (first && membersTail !== "")) flush(true)
        if (pageBytes(bytes) > SNAPSHOT_FRAME_BYTES) throw new Error("record exceeds snapshot ceiling")
      }
      partsBytes += bytes + (parts.length > 0 ? 1 : 0)
      parts.push(text)
    }
    flush(false)
    send(JSON.stringify({ t: "snapshot-end", seq: snapshot.seq }))
    // Active-session metadata is ephemeral and follows the durable fence. Each
    // frame is independently bounded by the normal incoming client ceiling.
    for (const frame of liveFrames) send(JSON.stringify(frame))
    return sentBytes
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
      // closed-but-not-echoed sockets can't take anything, skip the work
      if (ws === except || ws === alsoExcept || ws.readyState !== WS_OPEN) continue
      try { ws.send(data) } catch { /* dead socket */ }
    }
  }
}
