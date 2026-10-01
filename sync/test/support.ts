import { env, runInDurableObject as runInDurableObjectForSupport } from "cloudflare:test"
import { expect } from "vitest"
import fixture from "../../testdata/sync_protocol_v3.json"
import type { Env } from "../src/index"

// shared helpers for the relay suites. moved out of relay.test.ts so the
// lifecycle tests can sign real v3 frames the same way

declare module "cloudflare:test" {
  interface ProvidedEnv extends Env {}
}

export const VALID_ROOM_ID = "abcdefghijklmnopqrstuvwxyz01234567890123"
export const AUTH_TOKEN = "test-auth-token-long-enough-for-validation-check"
export const V3 = fixture.key_derivation
export const A = fixture.identity.device_a
export const B = fixture.identity.device_b
export const WIRE_ID = fixture.wire_object_ids.cases[0].wire_object_id
export const SD = hexToBase64url(fixture.signed_preimage.session_domain_hex)
export const HELLO_VECTOR = fixture.signed_preimage.cases.find(c => c.name === "hello_announcement")!
export const HELLO_EPOCH_2 = fixture.hello_epoch_cases.find(c => c.name === "next_epoch_new_session")!
export const SD_2 = hexToBase64url(HELLO_EPOCH_2.session_domain_hex)
export const RID = "request_delivery_0001"
export const CHAT_SD_B_RAW = hexBytes("6b".repeat(32))
export const CHAT_SD_B = bytesToBase64url(CHAT_SD_B_RAW)
export const CHAT_KX_A_RAW = hexBytes("19" + "11".repeat(31))
export const CHAT_KX_B_RAW = hexBytes("29" + "22".repeat(31))

export function hexToBase64url(hex: string): string {
  let binary = ""
  for (let i = 0; i < hex.length; i += 2) binary += String.fromCharCode(Number.parseInt(hex.slice(i, i + 2), 16))
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
}

export function hexBytes(hex: string): Uint8Array {
  return Uint8Array.from(hex.match(/../g) ?? [], value => Number.parseInt(value, 16))
}

export function bytesToBase64url(bytes: Uint8Array): string {
  let binary = ""
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
}

export function bytesToHex(bytes: Uint8Array): string {
  return [...bytes].map(byte => byte.toString(16).padStart(2, "0")).join("")
}

export function base64urlBytes(value: string): Uint8Array {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - value.length % 4) % 4)
  return Uint8Array.from(atob(padded), character => character.charCodeAt(0))
}

export function standardBase64Bytes(value: string): Uint8Array {
  return Uint8Array.from(atob(value), character => character.charCodeAt(0))
}

export function appendLe16(output: number[], value: number): void {
  output.push(value & 0xff, (value >>> 8) & 0xff)
}

export async function sha256(data: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", data))
}

export async function signWithSeed(seedHex: string, message: Uint8Array): Promise<string> {
  // RFC 8410 PKCS#8 prefix for a raw 32-byte Ed25519 seed.
  const pkcs8 = Uint8Array.from([...hexBytes("302e020100300506032b657004220420"), ...hexBytes(seedHex)])
  const key = await crypto.subtle.importKey("pkcs8", pkcs8, { name: "Ed25519" }, false, ["sign"])
  return bytesToBase64url(new Uint8Array(await crypto.subtle.sign({ name: "Ed25519" }, key, message)))
}

export function typedPreimage(
  domain: number,
  actorId: string,
  session: Uint8Array,
  counterHex: string,
  kind: string,
  payloadHash: Uint8Array,
): Uint8Array {
  const room = base64urlBytes(V3.room_id)
  const actor = new TextEncoder().encode(actorId)
  const kindBytes = new TextEncoder().encode(kind)
  const out: number[] = [domain, 0x03, ...room]
  appendLe16(out, actor.length)
  out.push(...actor, ...session, ...new TextEncoder().encode(counterHex))
  appendLe16(out, 0)
  out.push(kindBytes.length, ...kindBytes, ...payloadHash)
  return Uint8Array.from(out)
}

export async function signedHello(identity: typeof A, session: Uint8Array, epoch = 1): Promise<Record<string, unknown>> {
  const epochHex = epoch.toString(16).padStart(16, "0")
  const payloadHash = await sha256(hexBytes(identity.pubkey_raw_hex))
  const preimage = typedPreimage(0x04, identity.actor_id, session, epochHex, "hello", payloadHash)
  return {
    t: "hello", by: identity.actor_id, pub: identity.pubkey_base64url,
    sd: bytesToBase64url(session), vs: `${epochHex}:${identity.actor_id}`,
    sig: await signWithSeed(identity.seed_hex, preimage),
  }
}

export async function signedExplicitLeave(
  identity: typeof A,
  session: Uint8Array,
  epoch = 1,
): Promise<Record<string, unknown>> {
  const epochHex = epoch.toString(16).padStart(16, "0")
  const preimage = typedPreimage(
    0x04,
    identity.actor_id,
    session,
    epochHex,
    "leave-v1",
    await sha256(new Uint8Array()),
  )
  return {
    t: "leave", lv: 1, by: identity.actor_id, sd: bytesToBase64url(session),
    vs: `${epochHex}:${identity.actor_id}`,
    sig: await signWithSeed(identity.seed_hex, preimage),
  }
}

export async function chatKid(identity: typeof A, session: Uint8Array, keyExchange: Uint8Array): Promise<string> {
  const prefix = new TextEncoder().encode("tacmap-chat-kid-v1\0")
  const room = base64urlBytes(V3.room_id)
  const actor = new TextEncoder().encode(identity.actor_id)
  const input: number[] = [...prefix, ...room]
  appendLe16(input, actor.length)
  input.push(...actor, ...session, ...keyExchange)
  return bytesToBase64url(await sha256(Uint8Array.from(input)))
}

export async function signedChatKey(identity: typeof A, session: Uint8Array, keyExchange: Uint8Array): Promise<any> {
  const kid = await chatKid(identity, session, keyExchange)
  const keyRaw = keyExchange
  const kidRaw = base64urlBytes(kid)
  const payloadHash = await sha256(Uint8Array.from([0x01, ...keyRaw, ...kidRaw]))
  const preimage = typedPreimage(
    0x05, identity.actor_id, session, "0000000000000000", "chat-key-v1", payloadHash,
  )
  return {
    t: "chat-key", cv: 1, by: identity.actor_id, sd: bytesToBase64url(session),
    kx: bytesToBase64url(keyExchange), kid,
    sig: await signWithSeed(identity.seed_hex, preimage),
  }
}

export function chatHeader(frame: any): Uint8Array {
  const room = base64urlBytes(V3.room_id)
  const sender = new TextEncoder().encode(frame.by)
  const recipient = frame.scope === "direct" ? new TextEncoder().encode(frame.to) : new Uint8Array()
  const out: number[] = [0x01, frame.scope === "room" ? 0x01 : 0x02, ...room]
  appendLe16(out, sender.length)
  out.push(
    ...sender,
    ...base64urlBytes(frame.sd),
    ...new TextEncoder().encode(frame.vs.slice(0, 16)),
    ...base64urlBytes(frame.mid),
    ...base64urlBytes(frame.fromKid),
  )
  appendLe16(out, recipient.length)
  out.push(
    ...recipient,
    ...(frame.scope === "direct" ? base64urlBytes(frame.toSd) : new Uint8Array(32)),
    ...(frame.scope === "direct" ? base64urlBytes(frame.toKid) : new Uint8Array(32)),
  )
  return Uint8Array.from(out)
}

export async function signedChat(
  identity: typeof A,
  session: Uint8Array,
  fromKid: string,
  counter: number,
  options: { scope?: "room" | "direct"; target?: { identity: typeof A; session: Uint8Array; kid: string }; byte?: number } = {},
): Promise<any> {
  const scope = options.scope ?? "room"
  const frame: any = {
    t: "chat", cv: 1, scope, by: identity.actor_id, sd: bytesToBase64url(session),
    vs: stamp(counter, identity.actor_id),
    mid: bytesToBase64url(Uint8Array.from({ length: 16 }, (_, index) => (counter + index) & 0xff)),
    fromKid, ct: sealed(32, String.fromCharCode(options.byte ?? 65)), sig: "",
  }
  if (scope === "direct") {
    if (!options.target) throw new Error("direct target required")
    frame.to = options.target.identity.actor_id
    frame.toSd = bytesToBase64url(options.target.session)
    frame.toKid = options.target.kid
  }
  const ciphertextHash = await sha256(standardBase64Bytes(frame.ct))
  const preimage = Uint8Array.from([0x06, 0x03, ...chatHeader(frame), ...ciphertextHash])
  frame.sig = await signWithSeed(identity.seed_hex, preimage)
  return frame
}

export async function openChatPair(name: string): Promise<{
  stub: DurableObjectStub; a: WebSocket; b: WebSocket; keyA: any; keyB: any
}> {
  const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName(name))
  const a = await openV3Socket(stub); await drainSnapshot(a)
  const b = await openV3Socket(stub); await drainSnapshot(b)

  const helloA = await signedHello(A, base64urlBytes(SD))
  const ackA = collectUntil(a, frame => frame.t === "hello-ack")
  const seenA = collectUntil(b, frame => frame.t === "hello")
  a.send(JSON.stringify(helloA)); await ackA; await seenA

  const helloB = await signedHello(B, CHAT_SD_B_RAW)
  const ackB = collectUntil(b, frame => frame.t === "hello-ack")
  const seenB = collectUntil(a, frame => frame.t === "hello")
  b.send(JSON.stringify(helloB)); await ackB; await seenB

  const keyA = await signedChatKey(A, base64urlBytes(SD), CHAT_KX_A_RAW)
  const keyAckA = collectUntil(a, frame => frame.t === "chat-key-ack")
  const seenKeyA = collectUntil(b, frame => frame.t === "chat-key")
  a.send(JSON.stringify(keyA)); await keyAckA; await seenKeyA

  const keyB = await signedChatKey(B, CHAT_SD_B_RAW, CHAT_KX_B_RAW)
  const keyAckB = collectUntil(b, frame => frame.t === "chat-key-ack")
  const seenKeyB = collectUntil(a, frame => frame.t === "chat-key")
  b.send(JSON.stringify(keyB)); await keyAckB; await seenKeyB
  return { stub, a, b, keyA, keyB }
}

export function roomUrl(roomId = VALID_ROOM_ID): string { return `http://test/room/${roomId}` }
export function v3RoomUrl(roomId = V3.room_id): string { return `http://test/v3/room/${roomId}` }
export function headers(token = AUTH_TOKEN): Headers {
  return new Headers({ Upgrade: "websocket", Authorization: `Bearer ${token}` })
}
export function v3Headers(token = V3.auth_token_base64url): Headers {
  return new Headers({ Upgrade: "websocket", Authorization: `Bearer ${token}`, "X-Protocol": "3", "X-Room-Id": V3.room_id })
}
export function sealed(decodedBytes = 32, char = "A"): string { return btoa(char.repeat(decodedBytes)) }
export function stamp(counter: number, actor = A.actor_id): string { return counter.toString(16).padStart(16, "0") + ":" + actor }
export function hello(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    t: "hello", by: A.actor_id, pub: A.pubkey_base64url, sd: SD,
    vs: stamp(1), sig: HELLO_VECTOR.signature_base64url, ...overrides,
  }
}
export function hello2(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    t: "hello", by: A.actor_id, pub: A.pubkey_base64url, sd: SD_2,
    vs: stamp(2), sig: HELLO_EPOCH_2.signature_base64url, ...overrides,
  }
}
export function put(counter: number, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    t: "put", id: WIRE_ID, vs: stamp(counter), by: A.actor_id, kind: "waypoint",
    ct: sealed(), pub: A.pubkey_base64url, sd: SD, rid: RID, ...overrides,
  }
}
export function del(counter: number, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    t: "del", id: WIRE_ID, vs: stamp(counter), by: A.actor_id, kind: "del",
    ct: sealed(), pub: A.pubkey_base64url, sd: SD, rid: RID, ...overrides,
  }
}

export async function hashCiphertext(ct: string): Promise<string> {
  const bytes = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(ct)))
  let binary = ""
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
}

export function sleep(ms: number): Promise<void> { return new Promise(resolve => setTimeout(resolve, ms)) }

export function collectMessages(ws: WebSocket, count: number, timeoutMs = 1_000): Promise<any[]> {
  return new Promise(resolve => {
    const messages: any[] = []
    const timer = setTimeout(() => resolve(messages), timeoutMs)
    ws.addEventListener("message", event => {
      messages.push(JSON.parse(typeof event.data === "string" ? event.data : new TextDecoder().decode(event.data as ArrayBuffer)))
      if (messages.length >= count) { clearTimeout(timer); resolve(messages) }
    })
  })
}

export function collectUntil(ws: WebSocket, predicate: (message: any) => boolean, timeoutMs = 1_000): Promise<any | null> {
  return new Promise(resolve => {
    const timer = setTimeout(() => resolve(null), timeoutMs)
    ws.addEventListener("message", event => {
      const message = JSON.parse(typeof event.data === "string" ? event.data : new TextDecoder().decode(event.data as ArrayBuffer))
      if (predicate(message)) { clearTimeout(timer); resolve(message) }
    })
  })
}

export function waitForClose(ws: WebSocket, timeoutMs = 1_000): Promise<CloseEvent | null> {
  return new Promise(resolve => {
    const timer = setTimeout(() => resolve(null), timeoutMs)
    ws.addEventListener("close", event => { clearTimeout(timer); resolve(event) }, { once: true })
  })
}

export async function drainSnapshot(ws: WebSocket, timeoutMs = 5_000): Promise<{ items: any[]; frames: any[]; lengths: number[]; begin: any; end: any }> {
  return new Promise(resolve => {
    const frames: any[] = []
    const lengths: number[] = []
    const timer = setTimeout(finish, timeoutMs)
    function finish(): void {
      clearTimeout(timer)
      resolve({
        items: frames.filter(m => m.t === "snapshot").flatMap(m => m.items), frames, lengths,
        begin: frames.find(m => m.t === "snapshot-begin"), end: frames.find(m => m.t === "snapshot-end"),
      })
    }
    ws.addEventListener("message", event => {
      const text = typeof event.data === "string" ? event.data : new TextDecoder().decode(event.data as ArrayBuffer)
      lengths.push(text.length)
      const frame = JSON.parse(text)
      frames.push(frame)
      if (frame.t === "snapshot-end") finish()
    })
  })
}

export async function openSocket(stub: DurableObjectStub): Promise<WebSocket> {
  const response = await stub.fetch(roomUrl(), { headers: headers() })
  expect(response.status).toBe(101)
  const ws = response.webSocket!
  ws.accept()
  return ws
}

export async function openV3Socket(stub: DurableObjectStub): Promise<WebSocket> {
  const response = await stub.fetch(v3RoomUrl(), { headers: v3Headers() })
  expect(response.status).toBe(101)
  const ws = response.webSocket!
  ws.accept()
  return ws
}

export interface GenIdentity { actor: string; pub: string; priv: CryptoKey; sd: Uint8Array }

// fresh ed25519 actor for tests that need more than the two fixture devices
export async function genIdentity(): Promise<GenIdentity> {
  const pair = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]) as CryptoKeyPair
  const raw = new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey) as ArrayBuffer)
  const prefix = new TextEncoder().encode("tacmap-actor-v3\0")
  const actor = bytesToBase64url(await sha256(Uint8Array.from([...prefix, ...base64urlBytes(V3.room_id), ...raw])))
  return { actor, pub: bytesToBase64url(raw), priv: pair.privateKey, sd: crypto.getRandomValues(new Uint8Array(32)) }
}

export async function genHello(id: GenIdentity, epoch = 1): Promise<Record<string, unknown>> {
  const epochHex = epoch.toString(16).padStart(16, "0")
  const preimage = typedPreimage(0x04, id.actor, id.sd, epochHex, "hello", await sha256(base64urlBytes(id.pub)))
  const sig = bytesToBase64url(new Uint8Array(await crypto.subtle.sign({ name: "Ed25519" }, id.priv, preimage)))
  return { t: "hello", by: id.actor, pub: id.pub, sd: bytesToBase64url(id.sd), vs: `${epochHex}:${id.actor}`, sig }
}

export function genPut(
  id: GenIdentity, wireId: string, counter: number, rid: string, deleted = false, fill = "A",
): Record<string, unknown> {
  return {
    t: deleted ? "del" : "put", id: wireId, vs: stamp(counter, id.actor), by: id.actor,
    kind: deleted ? "del" : "waypoint", ct: sealed(32, fill), pub: id.pub, sd: bytesToBase64url(id.sd), rid,
  }
}

export async function wireIdFor(label: string): Promise<string> {
  return bytesToBase64url(await sha256(new TextEncoder().encode(label)))
}

export function rid(label: string | number): string {
  return `request_${String(label).padStart(12, "0")}`.slice(0, 64)
}

export async function helloOn(ws: WebSocket, frame: Record<string, unknown>): Promise<void> {
  const ack = collectUntil(ws, message => message.t === "hello-ack", 2_000)
  ws.send(JSON.stringify(frame))
  expect(await ack).not.toBeNull()
}

export function loc(counter: number, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return { t: "loc", by: A.actor_id, pub: A.pubkey_base64url, sd: SD, vs: stamp(counter), ct: sealed(), ...overrides }
}

export interface StorageLog {
  puts: string[]
  deletes: string[]
  setAlarms: number
  transactions: number
  deleteAlls: number
  lists: Array<{ prefix?: string; limit?: number; size: number; valueChars: number }>
}

// wraps the live instance's storage so a test can count billed row writes.
// eviction swaps the instance, so call again after evictDurableObject
export async function instrumentStorage(stub: DurableObjectStub): Promise<StorageLog> {
  const log: StorageLog = { puts: [], deletes: [], setAlarms: 0, transactions: 0, deleteAlls: 0, lists: [] }
  await runInDurableObjectForSupport(stub, instance => {
    const storage = (instance as any).state.storage
    const original = {
      put: storage.put.bind(storage), delete: storage.delete.bind(storage),
      setAlarm: storage.setAlarm.bind(storage), transaction: storage.transaction.bind(storage),
      deleteAll: storage.deleteAll.bind(storage), list: storage.list.bind(storage),
    }
    const ownBefore = Object.fromEntries(Object.keys(original).map(name => [name, Object.hasOwn(storage, name)]))
    ;(instance as any).__storageOriginal = { original, ownBefore }
    const keysOf = (arg: unknown): string[] =>
      typeof arg === "string" ? [arg] : Array.isArray(arg) ? arg.map(String) : Object.keys(arg as object)
    storage.put = (...args: any[]) => { log.puts.push(...keysOf(args[0])); return original.put(...args) }
    storage.delete = (...args: any[]) => { log.deletes.push(...keysOf(args[0])); return original.delete(...args) }
    storage.setAlarm = (...args: any[]) => { log.setAlarms += 1; return original.setAlarm(...args) }
    storage.deleteAll = (...args: any[]) => { log.deleteAlls += 1; return original.deleteAll(...args) }
    storage.list = async (...args: any[]) => {
      const page = await original.list(...args)
      let valueChars = 0
      for (const value of page.values()) valueChars += JSON.stringify(value)?.length ?? 0
      log.lists.push({ prefix: args[0]?.prefix, limit: args[0]?.limit, size: page.size, valueChars })
      return page
    }
    storage.transaction = (callback: (txn: any) => Promise<unknown>) => {
      log.transactions += 1
      return original.transaction(async (txn: any) => {
        const txnPut = txn.put.bind(txn)
        const txnDelete = txn.delete.bind(txn)
        txn.put = (...args: any[]) => { log.puts.push(...keysOf(args[0])); return txnPut(...args) }
        txn.delete = (...args: any[]) => { log.deletes.push(...keysOf(args[0])); return txnDelete(...args) }
        return callback(txn)
      })
    }
  })
  return log
}

export async function restoreStorage(stub: DurableObjectStub): Promise<void> {
  await runInDurableObjectForSupport(stub, instance => {
    const saved = (instance as any).__storageOriginal
    if (!saved) return
    const storage = (instance as any).state.storage
    for (const name of Object.keys(saved.original)) {
      if (saved.ownBefore[name]) storage[name] = saved.original[name]
      else delete storage[name]
    }
    delete (instance as any).__storageOriginal
  })
}

export function writeCount(log: StorageLog): number {
  return log.puts.length + log.deletes.length + log.setAlarms + log.deleteAlls
}
