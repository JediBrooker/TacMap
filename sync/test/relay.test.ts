import { env, SELF, runInDurableObject } from "cloudflare:test"
import { describe, it, expect } from "vitest"
import fixture from "../../testdata/sync_protocol_v3.json"
import chatFixture from "../../testdata/tacmap_chat_v1.json"
import * as workerEntrypoint from "../src/index"
import type { Env } from "../src/index"
import { RELAY_RELEASE_ID } from "../src/release"

declare module "cloudflare:test" {
  interface ProvidedEnv extends Env {}
}

const VALID_ROOM_ID = "abcdefghijklmnopqrstuvwxyz01234567890123"
const AUTH_TOKEN = "test-auth-token-long-enough-for-validation-check"
const V3 = fixture.key_derivation
const A = fixture.identity.device_a
const B = fixture.identity.device_b
const WIRE_ID = fixture.wire_object_ids.cases[0].wire_object_id
const SD = hexToBase64url(fixture.signed_preimage.session_domain_hex)
const HELLO_VECTOR = fixture.signed_preimage.cases.find(c => c.name === "hello_announcement")!
const HELLO_EPOCH_2 = fixture.hello_epoch_cases.find(c => c.name === "next_epoch_new_session")!
const SD_2 = hexToBase64url(HELLO_EPOCH_2.session_domain_hex)
const RID = "request_delivery_0001"
const CHAT_SD_B_RAW = hexBytes("6b".repeat(32))
const CHAT_SD_B = bytesToBase64url(CHAT_SD_B_RAW)
const CHAT_KX_A_RAW = hexBytes("19" + "11".repeat(31))
const CHAT_KX_B_RAW = hexBytes("29" + "22".repeat(31))

function hexToBase64url(hex: string): string {
  let binary = ""
  for (let i = 0; i < hex.length; i += 2) binary += String.fromCharCode(Number.parseInt(hex.slice(i, i + 2), 16))
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
}

function hexBytes(hex: string): Uint8Array {
  return Uint8Array.from(hex.match(/../g) ?? [], value => Number.parseInt(value, 16))
}

function bytesToBase64url(bytes: Uint8Array): string {
  let binary = ""
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
}

function bytesToHex(bytes: Uint8Array): string {
  return [...bytes].map(byte => byte.toString(16).padStart(2, "0")).join("")
}

function base64urlBytes(value: string): Uint8Array {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - value.length % 4) % 4)
  return Uint8Array.from(atob(padded), character => character.charCodeAt(0))
}

function standardBase64Bytes(value: string): Uint8Array {
  return Uint8Array.from(atob(value), character => character.charCodeAt(0))
}

function appendLe16(output: number[], value: number): void {
  output.push(value & 0xff, (value >>> 8) & 0xff)
}

async function sha256(data: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", data))
}

async function signWithSeed(seedHex: string, message: Uint8Array): Promise<string> {
  // RFC 8410 PKCS#8 prefix for a raw 32-byte Ed25519 seed.
  const pkcs8 = Uint8Array.from([...hexBytes("302e020100300506032b657004220420"), ...hexBytes(seedHex)])
  const key = await crypto.subtle.importKey("pkcs8", pkcs8, { name: "Ed25519" }, false, ["sign"])
  return bytesToBase64url(new Uint8Array(await crypto.subtle.sign({ name: "Ed25519" }, key, message)))
}

function typedPreimage(
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

async function signedHello(identity: typeof A, session: Uint8Array, epoch = 1): Promise<Record<string, unknown>> {
  const epochHex = epoch.toString(16).padStart(16, "0")
  const payloadHash = await sha256(hexBytes(identity.pubkey_raw_hex))
  const preimage = typedPreimage(0x04, identity.actor_id, session, epochHex, "hello", payloadHash)
  return {
    t: "hello", by: identity.actor_id, pub: identity.pubkey_base64url,
    sd: bytesToBase64url(session), vs: `${epochHex}:${identity.actor_id}`,
    sig: await signWithSeed(identity.seed_hex, preimage),
  }
}

async function signedExplicitLeave(
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

async function chatKid(identity: typeof A, session: Uint8Array, keyExchange: Uint8Array): Promise<string> {
  const prefix = new TextEncoder().encode("tacmap-chat-kid-v1\0")
  const room = base64urlBytes(V3.room_id)
  const actor = new TextEncoder().encode(identity.actor_id)
  const input: number[] = [...prefix, ...room]
  appendLe16(input, actor.length)
  input.push(...actor, ...session, ...keyExchange)
  return bytesToBase64url(await sha256(Uint8Array.from(input)))
}

async function signedChatKey(identity: typeof A, session: Uint8Array, keyExchange: Uint8Array): Promise<any> {
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

function chatHeader(frame: any): Uint8Array {
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

async function signedChat(
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

async function openChatPair(name: string): Promise<{
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

function roomUrl(roomId = VALID_ROOM_ID): string { return `http://test/room/${roomId}` }
function v3RoomUrl(roomId = V3.room_id): string { return `http://test/v3/room/${roomId}` }
function headers(token = AUTH_TOKEN): Headers {
  return new Headers({ Upgrade: "websocket", Authorization: `Bearer ${token}` })
}
function v3Headers(token = V3.auth_token_base64url): Headers {
  return new Headers({ Upgrade: "websocket", Authorization: `Bearer ${token}`, "X-Protocol": "3", "X-Room-Id": V3.room_id })
}
function sealed(decodedBytes = 32, char = "A"): string { return btoa(char.repeat(decodedBytes)) }
function stamp(counter: number, actor = A.actor_id): string { return counter.toString(16).padStart(16, "0") + ":" + actor }
function hello(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    t: "hello", by: A.actor_id, pub: A.pubkey_base64url, sd: SD,
    vs: stamp(1), sig: HELLO_VECTOR.signature_base64url, ...overrides,
  }
}
function hello2(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    t: "hello", by: A.actor_id, pub: A.pubkey_base64url, sd: SD_2,
    vs: stamp(2), sig: HELLO_EPOCH_2.signature_base64url, ...overrides,
  }
}
function put(counter: number, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    t: "put", id: WIRE_ID, vs: stamp(counter), by: A.actor_id, kind: "waypoint",
    ct: sealed(), pub: A.pubkey_base64url, sd: SD, rid: RID, ...overrides,
  }
}
function del(counter: number, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    t: "del", id: WIRE_ID, vs: stamp(counter), by: A.actor_id, kind: "del",
    ct: sealed(), pub: A.pubkey_base64url, sd: SD, rid: RID, ...overrides,
  }
}

async function hashCiphertext(ct: string): Promise<string> {
  const bytes = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(ct)))
  let binary = ""
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
}

function sleep(ms: number): Promise<void> { return new Promise(resolve => setTimeout(resolve, ms)) }

function collectMessages(ws: WebSocket, count: number, timeoutMs = 1_000): Promise<any[]> {
  return new Promise(resolve => {
    const messages: any[] = []
    const timer = setTimeout(() => resolve(messages), timeoutMs)
    ws.addEventListener("message", event => {
      messages.push(JSON.parse(typeof event.data === "string" ? event.data : new TextDecoder().decode(event.data as ArrayBuffer)))
      if (messages.length >= count) { clearTimeout(timer); resolve(messages) }
    })
  })
}

function collectUntil(ws: WebSocket, predicate: (message: any) => boolean, timeoutMs = 1_000): Promise<any | null> {
  return new Promise(resolve => {
    const timer = setTimeout(() => resolve(null), timeoutMs)
    ws.addEventListener("message", event => {
      const message = JSON.parse(typeof event.data === "string" ? event.data : new TextDecoder().decode(event.data as ArrayBuffer))
      if (predicate(message)) { clearTimeout(timer); resolve(message) }
    })
  })
}

function waitForClose(ws: WebSocket, timeoutMs = 1_000): Promise<CloseEvent | null> {
  return new Promise(resolve => {
    const timer = setTimeout(() => resolve(null), timeoutMs)
    ws.addEventListener("close", event => { clearTimeout(timer); resolve(event) }, { once: true })
  })
}

async function drainSnapshot(ws: WebSocket, timeoutMs = 5_000): Promise<{ items: any[]; frames: any[]; lengths: number[]; begin: any; end: any }> {
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

async function openSocket(stub: DurableObjectStub): Promise<WebSocket> {
  const response = await stub.fetch(roomUrl(), { headers: headers() })
  expect(response.status).toBe(101)
  const ws = response.webSocket!
  ws.accept()
  return ws
}

async function openV3Socket(stub: DurableObjectStub): Promise<WebSocket> {
  const response = await stub.fetch(v3RoomUrl(), { headers: v3Headers() })
  expect(response.status).toBe(101)
  const ws = response.webSocket!
  ws.accept()
  return ws
}

describe("routing and authentication", () => {
  it("exports only valid Cloudflare runtime entrypoints", () => {
    expect(Object.keys(workerEntrypoint).sort()).toEqual(["SyncRoom", "default"])
  })

  it("serves health and rejects invalid routes/origins", async () => {
    const health = await SELF.fetch("http://test/health")
    expect(health.status).toBe(200)
    expect(await health.text()).toBe("ok")
    expect(health.headers.get("cache-control")).toBe("no-store")
    expect(health.headers.get("x-tacmap-relay-release")).toBe(RELAY_RELEASE_ID)
    expect((await SELF.fetch("http://test/nope")).status).toBe(404)
    expect((await SELF.fetch(roomUrl(), { headers: { Authorization: `Bearer ${AUTH_TOKEN}` } })).status).toBe(426)
    expect((await SELF.fetch(roomUrl(), { headers: new Headers({ ...Object.fromEntries(headers()), Origin: "https://evil.example" }) })).status).toBe(403)
  })

  it("enforces v2 token pinning", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("auth-v2"))
    const ws = await openSocket(stub)
    await drainSnapshot(ws)
    const response = await stub.fetch(roomUrl(), { headers: headers("different-token-long-enough-to-check") })
    expect(response.status).toBe(403)
    ws.close()
  })

  it("verifies the v3 token-to-room binding before pinning", async () => {
    const good = await SELF.fetch(v3RoomUrl(), { headers: v3Headers() })
    expect(good.status).toBe(101)
    good.webSocket!.accept(); good.webSocket!.close()
    const wrongToken = hexToBase64url("00".repeat(32))
    const bad = await SELF.fetch(v3RoomUrl(), { headers: v3Headers(wrongToken) })
    expect(bad.status).toBe(403)
  })

  it("isolates a v2 preclaim from the same visible v3 room ID", async () => {
    const v2 = await SELF.fetch(roomUrl(V3.room_id), { headers: headers() })
    expect(v2.status).toBe(101)
    v2.webSocket!.accept()
    await drainSnapshot(v2.webSocket!)
    const v3 = await SELF.fetch(v3RoomUrl(), { headers: v3Headers() })
    expect(v3.status).toBe(101)
    v3.webSocket!.accept()
    const snapshot = await drainSnapshot(v3.webSocket!)
    expect(snapshot.items).toEqual([])
    v2.webSocket!.close(); v3.webSocket!.close()
  })
})

describe("strict input handling", () => {
  it("closes an oversized frame with the documented code", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("oversized-frame"))
    const ws = await openSocket(stub)
    await drainSnapshot(ws)
    const close = waitForClose(ws)
    ws.send(JSON.stringify({ t: "put", id: "x", v: 1, by: "a", kind: "waypoint", ct: "A".repeat(1_048_577) }))
    expect((await close)?.code).toBe(4009)
  })

  it("enforces the frame ceiling in UTF-8 bytes, not UTF-16 characters", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("oversized-unicode-frame"))
    const ws = await openSocket(stub)
    await drainSnapshot(ws)
    const close = waitForClose(ws)
    ws.send(JSON.stringify({ t: "ping", pad: "🔒".repeat(270_000) }))
    expect((await close)?.code).toBe(4009)
  })

  it("rejects oversized binary frames before UTF-8 decoding", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("oversized-binary-frame"))
    const ws = await openSocket(stub)
    await drainSnapshot(ws)
    const close = waitForClose(ws)
    ws.send(new Uint8Array(1_048_577).buffer)
    expect((await close)?.code).toBe(4009)
  })

  it("closes on the first invalid UTF-8 binary frame", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("invalid-utf8-binary-frame"))
    const ws = await openSocket(stub)
    await drainSnapshot(ws)
    const close = waitForClose(ws)
    ws.send(Uint8Array.from([0xc3, 0x28]).buffer)
    expect((await close)?.code).toBe(1007)
  })

  it("bounds aggregate bytes per rate window and accepts only minimal pings", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("frame-byte-rate"))
    const ws = await openSocket(stub)
    await drainSnapshot(ws)

    const paddedPong = collectUntil(ws, frame => frame.t === "pong", 150)
    ws.send(JSON.stringify({ t: "ping", pad: "ignored" }))
    expect(await paddedPong).toBeNull()

    const pong = collectUntil(ws, frame => frame.t === "pong")
    ws.send(JSON.stringify({ t: "ping" }))
    expect(await pong).toEqual({ t: "pong" })

    const close = waitForClose(ws)
    const padding = "A".repeat(900_000)
    for (let index = 0; index < 5; index += 1) {
      ws.send(JSON.stringify({ t: "unknown", padding }))
    }
    expect((await close)?.code).toBe(4008)
  })

  it("drops malformed, phantom-delete, and short-ciphertext frames", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("bad-frames"))
    const a = await openSocket(stub); await drainSnapshot(a)
    const b = await openSocket(stub); await drainSnapshot(b)
    const received = collectMessages(b, 1, 350)
    a.send("not-json")
    a.send(JSON.stringify({ t: "put", id: 12, v: 1, by: "a", kind: "waypoint", ct: sealed() }))
    a.send(JSON.stringify({ t: "put", id: "x", v: 1, by: "a", kind: "waypoint", ct: "AAAA" }))
    a.send(JSON.stringify({ t: "del", id: "ghost", v: 2, by: "a", kind: "del", ct: sealed() }))
    expect(await received).toEqual([])
    a.close(); b.close()
  })
})

describe("durable records, fences, and quotas", () => {
  it("acks modern v2 writes while legacy no-ack frames remain compatible", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("v2-delivery-compat"))
    const a = await openSocket(stub); await drainSnapshot(a)
    const modernCt = sealed()
    const ack = collectUntil(a, message => message.t === "op-ack")
    a.send(JSON.stringify({
      t: "put", id: "modern", v: 1, by: "mobile-a", kind: "waypoint",
      ct: modernCt, rid: RID,
    }))
    expect(await ack).toEqual({
      t: "op-ack", av: 1, rid: RID, by: "mobile-a", id: "modern",
      v: 1, kind: "waypoint", cth: await hashCiphertext(modernCt),
    })

    const noAck = collectUntil(a, message => message.t === "op-ack", 250)
    a.send(JSON.stringify({
      t: "put", id: "legacy", v: 1, by: "mobile-a", kind: "waypoint", ct: sealed(),
    }))
    expect(await noAck).toBeNull()
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.get("obj:legacy")).toBeDefined()
    })
    a.close()
  })

  it("terminates an empty snapshot with an explicit final-page marker", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("empty-snapshot-contract"))
    const ws = await openV3Socket(stub)
    const snapshot = await drainSnapshot(ws)
    const pages = snapshot.frames.filter(frame => frame.t === "snapshot")
    expect(pages).toHaveLength(1)
    expect(pages[0]).toMatchObject({ items: [], more: false })
    expect(snapshot.frames.map(frame => frame.t)).toEqual(["snapshot-begin", "snapshot", "snapshot-end"])
    expect(snapshot.begin.seq).toBe(snapshot.end.seq)
    ws.close()
  })

  it("broadcasts committed mutations with monotonic sequence fences", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("seq"))
    const a = await openSocket(stub); const first = await drainSnapshot(a)
    const b = await openSocket(stub); await drainSnapshot(b)
    expect(first.begin.seq).toBe(first.end.seq)
    const one = collectMessages(b, 1)
    a.send(JSON.stringify({ t: "put", id: "one", v: 1, by: "a", kind: "waypoint", ct: sealed() }))
    const firstDelta = (await one)[0]
    const two = collectMessages(b, 1)
    a.send(JSON.stringify({ t: "put", id: "two", v: 1, by: "a", kind: "waypoint", ct: sealed() }))
    const secondDelta = (await two)[0]
    expect(firstDelta.seq).toBeGreaterThan(first.end.seq)
    expect(secondDelta.seq).toBeGreaterThan(firstDelta.seq)
    a.close(); b.close()
  })

  it("retains tombstones and counts them against the total record quota", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("tombstone-quota"))
    const a = await openSocket(stub); await drainSnapshot(a)
    a.send(JSON.stringify({ t: "put", id: "kept", v: 1, by: "a", kind: "waypoint", ct: sealed() }))
    await sleep(30)
    a.send(JSON.stringify({ t: "del", id: "kept", v: 2, by: "a", kind: "del", ct: sealed() }))
    await sleep(30)
    await runInDurableObject(stub, async (_instance, state) => {
      const tombstone = await state.storage.get<any>("obj:kept")
      expect(tombstone.deleted).toBe(true)
      expect(await state.storage.get<number>("meta:totalRecords")).toBe(1)
      await state.storage.put("meta:totalRecords", 10_000)
    })
    const b = await openSocket(stub); await drainSnapshot(b)
    const received = collectMessages(b, 1, 350)
    a.send(JSON.stringify({ t: "put", id: "over-quota", v: 1, by: "a", kind: "waypoint", ct: sealed() }))
    expect(await received).toEqual([])
    a.close(); b.close()
  })

  it("accepts hardened mobile v2 deletes without outer kind but rejects plaintext legacy deletes", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("mobile-v2-delete-contract"))
    const a = await openSocket(stub); await drainSnapshot(a)
    const b = await openSocket(stub); await drainSnapshot(b)

    const firstPut = collectMessages(b, 1)
    a.send(JSON.stringify({ t: "put", id: "kept", v: 1, by: "mobile-a", kind: "waypoint", ct: sealed() }))
    await firstPut

    // Exact outer shape emitted by both hardened mobile clients: the signed
    // delete proof is inside ct and the redundant outer kind is absent.
    const deleted = collectMessages(b, 1)
    a.send(JSON.stringify({ t: "del", id: "kept", v: 2, by: "mobile-a", ct: sealed() }))
    expect((await deleted)[0]).toMatchObject({ t: "del", id: "kept", kind: "del", deleted: true })

    const secondPut = collectMessages(b, 1)
    a.send(JSON.stringify({ t: "put", id: "still-kept", v: 1, by: "mobile-a", kind: "waypoint", ct: sealed() }))
    await secondPut
    const unexpected = collectMessages(b, 1, 350)
    a.send(JSON.stringify({ t: "del", id: "still-kept", v: 2, by: "legacy-a" }))
    expect(await unexpected).toEqual([])

    await runInDurableObject(stub, async (_instance, state) => {
      expect((await state.storage.get<any>("obj:kept")).kind).toBe("del")
      expect((await state.storage.get<any>("obj:still-kept")).deleted).toBe(false)
    })
    a.close(); b.close()
  })

  it("chunks snapshots by encoded bytes, not only item count", { timeout: 15_000 }, async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("byte-pages"))
    const a = await openSocket(stub); await drainSnapshot(a)
    const large = sealed(500_000)
    a.send(JSON.stringify({ t: "put", id: "large-one", v: 1, by: "a", kind: "waypoint", ct: large }))
    a.send(JSON.stringify({ t: "put", id: "large-two", v: 1, by: "a", kind: "waypoint", ct: large }))
    await sleep(150)
    const b = await openSocket(stub)
    const snapshot = await drainSnapshot(b, 10_000)
    expect(snapshot.items).toHaveLength(2)
    expect(snapshot.frames.filter(frame => frame.t === "snapshot")).toHaveLength(2)
    expect(Math.max(...snapshot.lengths)).toBeLessThanOrEqual(fixture.constants.SNAPSHOT_FRAME_MAX_BYTES)
    expect(snapshot.begin.seq).toBe(snapshot.end.seq)
    a.close(); b.close()
  })
})

describe("v3 authenticated actors and convergence metadata", () => {
  it("acknowledges only durable, operation-bound writes and idempotent retries", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("delivery-ack"))
    const a = await openV3Socket(stub); await drainSnapshot(a)
    a.send(JSON.stringify(hello())); await collectUntil(a, frame => frame.t === "hello-ack")

    const frame = put(1)
    const firstAck = collectUntil(a, message => message.t === "op-ack")
    a.send(JSON.stringify(frame))
    expect(await firstAck).toEqual({
      t: "op-ack", av: 1, rid: RID, by: A.actor_id, sd: SD,
      id: WIRE_ID, vs: stamp(1), kind: "waypoint",
      cth: await hashCiphertext(frame.ct as string),
    })

    const duplicateAck = collectUntil(a, message => message.t === "op-ack")
    a.send(JSON.stringify(frame))
    expect(await duplicateAck).toMatchObject({ rid: RID, id: WIRE_ID, vs: stamp(1), cth: await hashCiphertext(frame.ct as string) })
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.get<number>("meta:seq")).toBe(1)
    })
    a.close()
  })

  it("durably acknowledges a modern tombstone even when its preceding put was lost", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("delivery-phantom-tombstone"))
    const a = await openV3Socket(stub); await drainSnapshot(a)
    a.send(JSON.stringify(hello())); await collectUntil(a, frame => frame.t === "hello-ack")
    const frame = del(1)
    const ack = collectUntil(a, message => message.t === "op-ack")
    a.send(JSON.stringify(frame))
    expect(await ack).toMatchObject({
      rid: RID, id: WIRE_ID, vs: stamp(1), kind: "del",
      cth: await hashCiphertext(frame.ct as string),
    })
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.get<any>(`obj:${WIRE_ID}`)).toMatchObject({ deleted: true, kind: "del" })
    })
    a.close()
  })

  it("sends bounded nacks for stale, session-mismatched, quota, and storage failures", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("delivery-nacks"))
    const a = await openV3Socket(stub); await drainSnapshot(a)
    a.send(JSON.stringify(hello())); await collectUntil(a, frame => frame.t === "hello-ack")

    let response = collectUntil(a, message => message.t === "op-nack")
    a.send(JSON.stringify(put(1, { sd: SD_2 })))
    expect(await response).toEqual({
      t: "op-nack", av: 1, rid: RID, by: A.actor_id, sd: SD,
      code: "session-mismatch", retry: false,
    })

    await runInDurableObject(stub, async instance => {
      ;(instance as any).failNextMutationStorageForTests = true
    })
    response = collectUntil(a, message => message.t === "op-nack")
    a.send(JSON.stringify(put(1)))
    expect(await response).toMatchObject({ rid: RID, code: "storage", retry: true })

    const ack = collectUntil(a, message => message.t === "op-ack")
    a.send(JSON.stringify(put(1)))
    expect(await ack).toMatchObject({ rid: RID, vs: stamp(1) })
    response = collectUntil(a, message => message.t === "op-nack")
    a.send(JSON.stringify(put(1, { rid: "request_delivery_0002", ct: sealed(33) })))
    expect(await response).toMatchObject({ rid: "request_delivery_0002", code: "stale", retry: false })

    await runInDurableObject(stub, async (_instance, state) => {
      await state.storage.put("meta:totalRecords", 10_000)
    })
    response = collectUntil(a, message => message.t === "op-nack")
    a.send(JSON.stringify(put(2, { id: fixture.wire_object_ids.cases[1].wire_object_id, rid: "request_delivery_0003" })))
    expect(await response).toMatchObject({ rid: "request_delivery_0003", code: "quota", retry: false })
    a.close()
  })

  it("loads v3 constants from the shared fixture", () => {
    expect(A.actor_id).toHaveLength(43)
    expect(SD).toHaveLength(43)
    expect(HELLO_VECTOR.domain_byte).toBe("0x04")
    expect(fixture.constants.DURABLE_RECORD_VERIFICATION_FIELDS).toContain("sd")
    expect(fixture.constants.CLIENT_SNAPSHOT_MAX_RECORDS).toBe(10_000)
    expect(fixture.constants.SNAPSHOT_DUPLICATE_ID_POLICY).toBe("reject-entire-snapshot")
  })

  it("accepts a valid signed hello and rejects an altered proof", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("signed-hello"))
    const a = await openV3Socket(stub); await drainSnapshot(a)
    const b = await openV3Socket(stub); await drainSnapshot(b)
    const ack = collectMessages(a, 1)
    const broadcast = collectMessages(b, 1)
    a.send(JSON.stringify(hello()))
    expect((await ack)[0]).toEqual({ t: "hello-ack", by: A.actor_id, sd: SD, vs: stamp(1) })
    expect((await broadcast)[0]).toEqual(hello())

    const attacker = await openV3Socket(stub); await drainSnapshot(attacker)
    const closed = waitForClose(attacker)
    attacker.send(JSON.stringify(hello({ sig: HELLO_VECTOR.signature_base64url.replace(/^./, "A") })))
    expect((await closed)?.code).toBe(4011)
    a.close(); b.close()
  })

  it("broadcasts a session-scoped leave when a hello-only member disconnects", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("hello-only-leave"))
    const peer = await openV3Socket(stub); await drainSnapshot(peer)
    const member = await openV3Socket(stub); await drainSnapshot(member)
    const ack = collectUntil(member, frame => frame.t === "hello-ack")
    const joined = collectUntil(peer, frame => frame.t === "hello")
    member.send(JSON.stringify(hello()))
    expect(await ack).toEqual({ t: "hello-ack", by: A.actor_id, sd: SD, vs: stamp(1) })
    expect(await joined).toEqual(hello())

    const departed = collectUntil(peer, frame => frame.t === "leave")
    member.close()
    expect(await departed).toEqual({ t: "leave", by: A.actor_id, sd: SD, transient: true })
    peer.close()
  })

  it("orders old-session leave before replacement hello without a late duplicate", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("replacement-leave-order"))
    const peer = await openV3Socket(stub); await drainSnapshot(peer)
    const old = await openV3Socket(stub); await drainSnapshot(old)
    old.send(JSON.stringify(hello())); await collectMessages(old, 1); await collectMessages(peer, 1)

    const replacement = await openV3Socket(stub); await drainSnapshot(replacement)
    const oldClosed = waitForClose(old)
    const replacementAck = collectUntil(replacement, frame => frame.t === "hello-ack")
    const peerFrames = collectMessages(peer, 2)
    replacement.send(JSON.stringify(hello2()))

    expect(await replacementAck).toEqual({ t: "hello-ack", by: A.actor_id, sd: SD_2, vs: stamp(2) })
    expect((await oldClosed)?.code).toBe(4015)
    expect(await peerFrames).toEqual([
      { t: "leave", by: A.actor_id, sd: SD, replaced: true },
      hello2(),
    ])
    expect(await collectMessages(peer, 1, 200)).toEqual([])

    const currentDeparture = collectUntil(peer, frame => frame.t === "leave")
    replacement.close()
    expect(await currentDeparture).toEqual({ t: "leave", by: A.actor_id, sd: SD_2, transient: true })
    peer.close()
  })

  it("authenticates an explicit leave and distinguishes it from an abrupt close", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("explicit-vs-transient-leave"))
    const peer = await openV3Socket(stub); await drainSnapshot(peer)

    const explicitMember = await openV3Socket(stub); await drainSnapshot(explicitMember)
    explicitMember.send(JSON.stringify(hello()))
    await collectMessages(explicitMember, 1); await collectMessages(peer, 1)
    const explicitDeparture = collectUntil(peer, frame => frame.t === "leave")
    explicitMember.send(JSON.stringify(await signedExplicitLeave(A, base64urlBytes(SD))))
    expect(await explicitDeparture).toEqual({
      t: "leave", by: A.actor_id, sd: SD, explicit: true,
    })

    const transientMember = await openV3Socket(stub); await drainSnapshot(transientMember)
    transientMember.send(JSON.stringify(await signedHello(B, CHAT_SD_B_RAW)))
    await collectMessages(transientMember, 1); await collectMessages(peer, 1)
    const transientDeparture = collectUntil(peer, frame => frame.t === "leave")
    transientMember.close()
    expect(await transientDeparture).toEqual({
      t: "leave", by: B.actor_id, sd: CHAT_SD_B, transient: true,
    })
    peer.close()
  })

  it("idempotently re-acknowledges an identical hello on the same socket", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("same-socket-hello-idempotent"))
    const member = await openV3Socket(stub); await drainSnapshot(member)
    member.send(JSON.stringify(hello()))
    expect(await collectUntil(member, frame => frame.t === "hello-ack")).toEqual({
      t: "hello-ack", by: A.actor_id, sd: SD, vs: stamp(1),
    })
    const secondAck = collectUntil(member, frame => frame.t === "hello-ack")
    member.send(JSON.stringify(hello()))
    expect(await secondAck).toEqual({
      t: "hello-ack", by: A.actor_id, sd: SD, vs: stamp(1),
    })
    member.close()
  })

  it("never treats a malformed or wrong-session leave proof as explicit", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("invalid-explicit-leave"))
    const peer = await openV3Socket(stub); await drainSnapshot(peer)
    const member = await openV3Socket(stub); await drainSnapshot(member)
    member.send(JSON.stringify(hello()))
    await collectMessages(member, 1); await collectMessages(peer, 1)

    const invalid = await signedExplicitLeave(A, base64urlBytes(SD))
    invalid.sig = String(invalid.sig).replace(/^./, String(invalid.sig).startsWith("A") ? "B" : "A")
    const closed = waitForClose(member)
    const departure = collectUntil(peer, frame => frame.t === "leave")
    member.send(JSON.stringify(invalid))
    expect((await closed)?.code).toBe(4011)
    expect(await departure).toEqual({
      t: "leave", by: A.actor_id, sd: SD, transient: true,
    })
    peer.close()
  })

  it("fences an old-session write only after replacement proof while durable registration waits", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("replacement-handshake-fence"))
    const peer = await openV3Socket(stub); await drainSnapshot(peer)
    const old = await openV3Socket(stub); await drainSnapshot(old)
    old.send(JSON.stringify(hello())); await collectMessages(old, 1); await collectMessages(peer, 1)
    const replacement = await openV3Socket(stub); await drainSnapshot(replacement)
    await runInDurableObject(stub, async instance => {
      ;(instance as any).helloHandshakeDelayMsForTests = 75
    })

    const peerFrames = collectMessages(peer, 2)
    const replacementAck = collectUntil(replacement, frame => frame.t === "hello-ack")
    const replacementFenced = collectUntil(replacement, frame => frame.t === "test-fence-ready")
    const oldNack = collectUntil(old, frame => frame.t === "op-nack")
    replacement.send(JSON.stringify(hello2()))
    expect(await replacementFenced).toEqual({ t: "test-fence-ready" })
    // The hook fires only after proof and fence installation, before durable
    // registration. This write must be rejected in that bounded interval.
    old.send(JSON.stringify(put(1)))

    expect(await oldNack).toMatchObject({ code: "session-replaced", retry: false })
    expect(await replacementAck).toEqual({ t: "hello-ack", by: A.actor_id, sd: SD_2, vs: stamp(2) })
    expect((await peerFrames).map(frame => frame.t)).toEqual(["leave", "hello"])
    expect(await collectMessages(peer, 1, 200)).toEqual([])
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.get(`obj:${WIRE_ID}`)).toBeUndefined()
    })
    peer.close(); old.close(); replacement.close()
  })

  it("excludes replacement-fenced sessions from concurrent v3 live snapshots", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("replacement-fenced-snapshot"))
    const old = await openV3Socket(stub); await drainSnapshot(old)
    old.send(JSON.stringify(hello())); await collectMessages(old, 1)
    const location = {
      t: "loc", by: A.actor_id, pub: A.pubkey_base64url, sd: SD,
      vs: stamp(1), ct: sealed(),
    }
    const processed = collectUntil(old, frame => frame.t === "pong")
    old.send(JSON.stringify(location)); old.send(JSON.stringify({ t: "ping" }))
    expect(await processed).toEqual({ t: "pong" })

    const replacement = await openV3Socket(stub); await drainSnapshot(replacement)
    await runInDurableObject(stub, async instance => {
      ;(instance as any).helloHandshakeDelayMsForTests = 250
    })
    const replacementFenced = collectUntil(replacement, frame => frame.t === "test-fence-ready")
    const replacementAck = collectUntil(replacement, frame => frame.t === "hello-ack")
    replacement.send(JSON.stringify(hello2()))
    expect(await replacementFenced).toEqual({ t: "test-fence-ready" })

    const newcomer = await openV3Socket(stub)
    const leakedOldLiveFrame = collectUntil(
      newcomer,
      frame => frame.t === "hello" || frame.t === "chat-key" || frame.t === "loc",
      100,
    )
    const snapshot = await drainSnapshot(newcomer)
    expect(snapshot.end?.t).toBe("snapshot-end")
    expect(await leakedOldLiveFrame).toBeNull()
    expect(await replacementAck).toEqual({ t: "hello-ack", by: A.actor_id, sd: SD_2, vs: stamp(2) })

    old.close(); replacement.close(); newcomer.close()
  })

  it("restores an old session after an invalid replacement proof fails safely", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("replacement-fence-rollback"))
    const peer = await openV3Socket(stub); await drainSnapshot(peer)
    const old = await openV3Socket(stub); await drainSnapshot(old)
    old.send(JSON.stringify(hello())); await collectMessages(old, 1); await collectMessages(peer, 1)
    const location = {
      t: "loc", by: A.actor_id, pub: A.pubkey_base64url, sd: SD,
      vs: stamp(1), ct: sealed(),
    }
    old.send(JSON.stringify(location)); await collectMessages(peer, 1)

    await runInDurableObject(stub, async instance => {
      ;(instance as any).helloHandshakeDelayMsForTests = 250
    })

    const attacker = await openV3Socket(stub); await drainSnapshot(attacker)
    const closed = waitForClose(attacker)
    const incorrectlyFenced = collectUntil(attacker, frame => frame.t === "test-fence-ready", 200)
    attacker.send(JSON.stringify(hello2({ sig: HELLO_EPOCH_2.signature_base64url.replace(/^./, "A") })))
    expect((await closed)?.code).toBe(4011)
    expect(await incorrectlyFenced).toBeNull()

    const mutation = collectUntil(peer, frame => frame.t === "put")
    old.send(JSON.stringify(put(1)))
    expect(await mutation).toMatchObject({ t: "put", sd: SD, vs: stamp(1) })
    const newcomer = await openV3Socket(stub)
    const newcomerFrames = await collectMessages(newcomer, 5)
    expect(newcomerFrames.map(frame => frame.t)).toEqual([
      "snapshot-begin", "snapshot", "snapshot-end", "hello", "loc",
    ])
    expect(newcomerFrames[3]).toEqual(hello())
    expect(newcomerFrames[4]).toEqual(location)
    peer.close(); old.close(); newcomer.close()
  })

  it("an invalid higher-epoch proof cannot suppress victim chat", async () => {
    const { stub, a, b, keyA } = await openChatPair("invalid-proof-chat-isolation")
    await runInDurableObject(stub, async instance => {
      ;(instance as any).helloHandshakeDelayMsForTests = 250
    })
    const attacker = await openV3Socket(stub); await drainSnapshot(attacker)
    const attackerClosed = waitForClose(attacker)
    attacker.send(JSON.stringify(hello2({
      sig: HELLO_EPOCH_2.signature_base64url.replace(/^./, "A"),
    })))

    const frame = await signedChat(A, base64urlBytes(SD), keyA.kid, 1)
    const routed = collectUntil(b, message => message.t === "chat")
    const ack = collectUntil(a, message => message.t === "chat-ack")
    a.send(JSON.stringify(frame))
    expect(await routed).toEqual(frame)
    expect(await ack).toMatchObject({ t: "chat-ack", mid: frame.mid })
    expect((await attackerClosed)?.code).toBe(4011)
    a.close(); b.close()
  })

  it("restores a proven old session exactly once when durable replacement registration fails", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("replacement-fence-newcomer-restore"))
    const old = await openV3Socket(stub); await drainSnapshot(old)
    old.send(JSON.stringify(hello())); await collectMessages(old, 1)
    const location = {
      t: "loc", by: A.actor_id, pub: A.pubkey_base64url, sd: SD,
      vs: stamp(1), ct: sealed(),
    }
    const processed = collectUntil(old, frame => frame.t === "pong")
    old.send(JSON.stringify(location)); old.send(JSON.stringify({ t: "ping" }))
    expect(await processed).toEqual({ t: "pong" })

    const replacement = await openV3Socket(stub); await drainSnapshot(replacement)
    await runInDurableObject(stub, async instance => {
      ;(instance as any).helloHandshakeDelayMsForTests = 250
      ;(instance as any).failNextActorRegisterForTests = true
    })
    const fenced = collectUntil(replacement, frame => frame.t === "test-fence-ready")
    const replacementClosed = waitForClose(replacement)
    replacement.send(JSON.stringify(hello2()))
    expect(await fenced).toEqual({ t: "test-fence-ready" })

    const newcomer = await openV3Socket(stub)
    const restoredHello = collectUntil(newcomer, frame => frame.t === "hello")
    const restoredLocation = collectUntil(newcomer, frame => frame.t === "loc")
    const snapshot = await drainSnapshot(newcomer)
    expect(snapshot.frames.some(frame => frame.t === "hello" || frame.t === "loc")).toBe(false)
    expect((await replacementClosed)?.code).toBe(1011)
    expect(await restoredHello).toEqual(hello())
    expect(await restoredLocation).toEqual(location)
    expect(await collectMessages(newcomer, 1, 200)).toEqual([])

    old.close(); newcomer.close()
  })

  it("acknowledges binding before an immediate post-hello mutation", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("hello-ack-race"))
    const a = await openV3Socket(stub); await drainSnapshot(a)
    const b = await openV3Socket(stub); await drainSnapshot(b)
    const ack = collectMessages(a, 1)
    const peerFrames = collectMessages(b, 2)
    a.send(JSON.stringify(hello()))
    expect((await ack)[0]).toEqual({ t: "hello-ack", by: A.actor_id, sd: SD, vs: stamp(1) })
    a.send(JSON.stringify(put(1)))
    const frames = await peerFrames
    expect(frames.map(frame => frame.t)).toEqual(["hello", "put"])
    expect(frames[1].vs).toBe(stamp(1))
    a.close(); b.close()
  })

  it("rejects actorId/pubkey impersonation before durable pinning", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("actor-impersonation"))
    const ws = await openV3Socket(stub); await drainSnapshot(ws)
    const closed = waitForClose(ws)
    ws.send(JSON.stringify(hello({ pub: B.pubkey_base64url })))
    expect((await closed)?.code).toBe(4011)
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.get(`actor:${A.actor_id}`)).toBeUndefined()
    })
  })

  it("rejects same and older signed hello epochs for a returning actor", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("hello-epoch-replay"))
    const first = await openV3Socket(stub); await drainSnapshot(first)
    first.send(JSON.stringify(hello())); await collectMessages(first, 1)

    const current = await openV3Socket(stub); await drainSnapshot(current)
    const oldClosed = waitForClose(first)
    const currentAck = collectUntil(current, message => message.t === "hello-ack")
    current.send(JSON.stringify(hello2()))
    expect(await currentAck).toEqual({ t: "hello-ack", by: A.actor_id, sd: SD_2, vs: stamp(2) })
    expect((await oldClosed)?.code).toBe(4015)

    for (const replay of [hello2(), hello()]) {
      const attacker = await openV3Socket(stub); await drainSnapshot(attacker)
      const closed = waitForClose(attacker)
      attacker.send(JSON.stringify(replay))
      expect((await closed)?.code).toBe(4014)
    }
    await runInDurableObject(stub, async (_instance, state) => {
      const actor = await state.storage.get<any>(`actor:${A.actor_id}`)
      expect(actor.helloEpoch).toBe("0000000000000002")
      expect(actor.hello).toEqual(hello2())
    })
    current.close()
  })

  it("rejects zero and non-canonical hello epochs before actor persistence", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("hello-epoch-invalid"))
    for (const vs of [stamp(0), `000000000000000A:${A.actor_id}`]) {
      const ws = await openV3Socket(stub); await drainSnapshot(ws)
      const closed = waitForClose(ws)
      ws.send(JSON.stringify(hello({ vs })))
      expect((await closed)?.code).toBe(4011)
    }
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.get(`actor:${A.actor_id}`)).toBeUndefined()
    })
  })

  it("rejects presence replay from a superseded signed session", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("presence-session-replay"))
    const peer = await openV3Socket(stub); await drainSnapshot(peer)
    const old = await openV3Socket(stub); await drainSnapshot(old)
    old.send(JSON.stringify(hello())); await collectMessages(old, 1); await collectMessages(peer, 1)
    const oldPresence = { t: "loc", by: A.actor_id, pub: A.pubkey_base64url, sd: SD, vs: stamp(1), ct: sealed() }
    old.send(JSON.stringify(oldPresence)); expect((await collectMessages(peer, 1))[0]).toEqual(oldPresence)

    const current = await openV3Socket(stub); await drainSnapshot(current)
    const replacementFrames = collectMessages(peer, 2)
    current.send(JSON.stringify(hello2())); await collectMessages(current, 1)
    expect((await replacementFrames).map(frame => frame.t)).toEqual(["leave", "hello"])
    const replayed = collectMessages(peer, 1, 300)
    current.send(JSON.stringify(oldPresence))
    expect(await replayed).toEqual([])
    const currentPresence = { t: "loc", by: A.actor_id, pub: A.pubkey_base64url, sd: SD_2, vs: stamp(1), ct: sealed() }
    current.send(JSON.stringify(currentPresence))
    expect((await collectMessages(peer, 1))[0]).toEqual(currentPresence)
    peer.close(); current.close()
  })

  it("requires a signed socket announcement and exact session context", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("session-gate"))
    const a = await openV3Socket(stub); await drainSnapshot(a)
    const b = await openV3Socket(stub); await drainSnapshot(b)
    let received = collectMessages(b, 1, 300)
    a.send(JSON.stringify(put(1)))
    expect(await received).toEqual([])
    a.send(JSON.stringify(hello())); await collectMessages(b, 1)
    received = collectMessages(b, 1, 300)
    a.send(JSON.stringify(put(1, { sd: hexToBase64url("01".repeat(32)) })))
    expect(await received).toEqual([])
    const valid = collectMessages(b, 1)
    a.send(JSON.stringify(put(1)))
    expect((await valid)[0].sd).toBe(SD)
    a.close(); b.close()
  })

  it("gives late joiners every context field needed to verify durable signatures", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("late-join"))
    const a = await openV3Socket(stub); await drainSnapshot(a)
    a.send(JSON.stringify(hello())); await sleep(30)
    a.send(JSON.stringify(put(5))); await sleep(50)
    const late = await openV3Socket(stub)
    const snapshot = await drainSnapshot(late)
    expect(snapshot.items).toHaveLength(1)
    expect(snapshot.items[0]).toMatchObject({ by: A.actor_id, pub: A.pubkey_base64url, sd: SD, vs: stamp(5), id: WIRE_ID })
    expect(snapshot.begin.highWater).toBe("0000000000000005")
    expect(snapshot.begin.seq).toBe(snapshot.end.seq)
    a.close(); late.close()
  })

  it("sends a live peer hello then its current location after a reconnect snapshot fence", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("reconnect-live-presence"))
    const live = await openV3Socket(stub); await drainSnapshot(live)

    const ack = collectUntil(live, frame => frame.t === "hello-ack")
    live.send(JSON.stringify(hello()))
    expect(await ack).toEqual({ t: "hello-ack", by: A.actor_id, sd: SD, vs: stamp(1) })

    const location = {
      t: "loc", by: A.actor_id, pub: A.pubkey_base64url, sd: SD,
      vs: stamp(1), ct: sealed(),
    }
    const processed = collectUntil(live, frame => frame.t === "pong")
    live.send(JSON.stringify(location))
    live.send(JSON.stringify({ t: "ping" }))
    expect(await processed).toEqual({ t: "pong" })

    const reconnecting = await openV3Socket(stub)
    const frames = await collectMessages(reconnecting, 5)
    expect(frames.map(frame => frame.t)).toEqual([
      "snapshot-begin", "snapshot", "snapshot-end", "hello", "loc",
    ])
    expect(frames[3]).toEqual(hello())
    expect(frames[4]).toEqual(location)

    live.close(); reconnecting.close()
  })

  it("broadcasts sequential stationary presence and snapshots only the newest frame", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("sequential-live-presence"))
    const sender = await openV3Socket(stub); await drainSnapshot(sender)
    const peer = await openV3Socket(stub); await drainSnapshot(peer)

    const ack = collectUntil(sender, frame => frame.t === "hello-ack")
    const peerHello = collectUntil(peer, frame => frame.t === "hello")
    sender.send(JSON.stringify(hello()))
    expect(await ack).toEqual({ t: "hello-ack", by: A.actor_id, sd: SD, vs: stamp(1) })
    expect(await peerHello).toEqual(hello())

    const first = {
      t: "loc", by: A.actor_id, pub: A.pubkey_base64url, sd: SD,
      vs: stamp(1), ct: sealed(32, "A"),
    }
    const second = {
      t: "loc", by: A.actor_id, pub: A.pubkey_base64url, sd: SD,
      vs: stamp(2), ct: sealed(32, "B"),
    }
    const liveFrames = collectMessages(peer, 2)
    sender.send(JSON.stringify(first))
    sender.send(JSON.stringify(second))
    expect(await liveFrames).toEqual([first, second])

    const late = await openV3Socket(stub)
    const frames = await collectMessages(late, 5)
    expect(frames.map(frame => frame.t)).toEqual([
      "snapshot-begin", "snapshot", "snapshot-end", "hello", "loc",
    ])
    expect(frames[3]).toEqual(hello())
    expect(frames[4]).toEqual(second)

    sender.close(); peer.close(); late.close()
  })

  it("keeps presence counters session-local so they cannot pin object progress", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("presence-counter"))
    const a = await openV3Socket(stub); await drainSnapshot(a)
    const b = await openV3Socket(stub); await drainSnapshot(b)
    a.send(JSON.stringify(hello())); await collectMessages(b, 1)
    const presence = collectMessages(b, 1)
    a.send(JSON.stringify({ t: "loc", by: A.actor_id, pub: A.pubkey_base64url, sd: SD, vs: stamp(10_000), ct: sealed() }))
    expect((await presence)[0].t).toBe("loc")
    const mutation = collectMessages(b, 1)
    a.send(JSON.stringify(put(1)))
    expect((await mutation)[0].t).toBe("put")
    a.close(); b.close()
  })

  it("counts actor pins against room quota and fails closed", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("actor-quota"))
    const ws = await openV3Socket(stub); await drainSnapshot(ws)
    await runInDurableObject(stub, async (_instance, state) => {
      await state.storage.put("meta:totalRecords", 10_000)
    })
    const closed = waitForClose(ws)
    ws.send(JSON.stringify(hello()))
    expect((await closed)?.code).toBe(4013)
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.get(`actor:${A.actor_id}`)).toBeUndefined()
      expect(await state.storage.get<number>("meta:totalRecords")).toBe(10_000)
    })
  })

  it("rejects far-future durable counters but accepts the edge of the window", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("advance-window"))
    const a = await openV3Socket(stub); await drainSnapshot(a)
    const b = await openV3Socket(stub); await drainSnapshot(b)
    a.send(JSON.stringify(hello())); await collectMessages(b, 1)
    let received = collectMessages(b, 1, 300)
    a.send(JSON.stringify(put(10_001)))
    expect(await received).toEqual([])
    received = collectMessages(b, 1)
    a.send(JSON.stringify(put(10_000)))
    expect((await received)[0].vs).toBe(stamp(10_000))
    a.close(); b.close()
  })
})

describe("TacMap Chat v1 live relay", () => {
  it("matches the shared byte-exact key advert and signed header vectors", async () => {
    const sessionA = hexBytes(chatFixture.sessions.a.sd_hex)
    const keyA = await signedChatKey(
      A, sessionA, hexBytes(chatFixture.sessions.a.x25519_public_hex),
    )
    expect(keyA).toEqual(chatFixture.chat_keys.a.frame)

    for (const vector of [chatFixture.room_message, chatFixture.direct_message]) {
      const frame = vector.frame
      const header = chatHeader(frame)
      expect(bytesToHex(header)).toBe(vector.header_hex)
      const cipherHash = await sha256(standardBase64Bytes(frame.ct))
      expect(bytesToHex(cipherHash)).toBe(vector.ciphertext_hash_hex)
      expect(bytesToHex(Uint8Array.from([0x06, 0x03, ...header, ...cipherHash])))
        .toBe(vector.signature_preimage_hex)
    }
  })

  it("acknowledges a verified chat capability and advertises it live", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("chat-key-capability"))
    const a = await openV3Socket(stub); await drainSnapshot(a)
    const b = await openV3Socket(stub); await drainSnapshot(b)
    const helloA = await signedHello(A, base64urlBytes(SD))
    const helloAck = collectUntil(a, frame => frame.t === "hello-ack")
    const helloBroadcast = collectUntil(b, frame => frame.t === "hello")
    a.send(JSON.stringify(helloA)); await helloAck; await helloBroadcast

    const keyA = await signedChatKey(A, base64urlBytes(SD), CHAT_KX_A_RAW)
    const ack = collectUntil(a, frame => frame.t === "chat-key-ack")
    const advertised = collectUntil(b, frame => frame.t === "chat-key")
    a.send(JSON.stringify(keyA))
    expect(await ack).toEqual({
      t: "chat-key-ack", cv: 1, by: A.actor_id, sd: SD, kid: keyA.kid,
    })
    expect(await advertised).toEqual(keyA)

    // A lost capability ack may be retried, but retrying must not create a
    // second peer event or reset the sender's chat replay counter.
    const retryAck = collectUntil(a, frame => frame.t === "chat-key-ack")
    const duplicateAdvert = collectUntil(b, frame => frame.t === "chat-key", 250)
    a.send(JSON.stringify(keyA))
    expect(await retryAck).toMatchObject({ cv: 1, kid: keyA.kid })
    expect(await duplicateAdvert).toBeNull()
    a.close(); b.close()
  })

  it("strictly rejects wrong versions, non-canonical message IDs, and mismatched key IDs", async () => {
    const { a, b, keyA } = await openChatPair("chat-strict-fields")

    const wrongVersion = await signedChat(A, base64urlBytes(SD), keyA.kid, 1)
    wrongVersion.cv = 2
    let nack = collectUntil(a, frame => frame.t === "chat-nack")
    let unexpected = collectUntil(b, frame => frame.t === "chat", 250)
    a.send(JSON.stringify(wrongVersion))
    expect(await nack).toMatchObject({ cv: 1, code: "invalid" })
    expect(await unexpected).toBeNull()

    const badMid = await signedChat(A, base64urlBytes(SD), keyA.kid, 1)
    badMid.mid = "not-a-16-byte-id"
    nack = collectUntil(a, frame => frame.t === "chat-nack")
    a.send(JSON.stringify(badMid))
    expect(await nack).toMatchObject({ code: "invalid" })

    const wrongFromKey = await signedChat(A, base64urlBytes(SD), bytesToBase64url(hexBytes("ab".repeat(32))), 1)
    nack = collectUntil(a, frame => frame.t === "chat-nack")
    a.send(JSON.stringify(wrongFromKey))
    expect(await nack).toMatchObject({ code: "session_unavailable", retry: true })
    a.close(); b.close()
  })

  it("routes room chat only to live chat-capable peers and never stores it", async () => {
    const { stub, a, b, keyA } = await openChatPair("chat-room-live-only")
    const frame = await signedChat(A, base64urlBytes(SD), keyA.kid, 1)
    const received = collectUntil(b, value => value.t === "chat")
    const ack = collectUntil(a, value => value.t === "chat-ack")
    a.send(JSON.stringify(frame))
    expect(await received).toEqual(frame)
    expect(await ack).toEqual({
      t: "chat-ack", cv: 1, by: A.actor_id, sd: SD, vs: frame.vs,
      mid: frame.mid, scope: "room", fromKid: keyA.kid,
    })
    await runInDurableObject(stub, async (_instance, state) => {
      expect((await state.storage.list({ prefix: "chat:" })).size).toBe(0)
      expect((await state.storage.list({ prefix: "message:" })).size).toBe(0)
    })
    a.close(); b.close()
  })

  it("routes direct chat only to the exact recipient actor, session, and key", async () => {
    const { a, b, keyA, keyB } = await openChatPair("chat-direct-exact-target")
    const target = { identity: B, session: CHAT_SD_B_RAW, kid: keyB.kid }
    const valid = await signedChat(A, base64urlBytes(SD), keyA.kid, 1, { scope: "direct", target })
    const delivered = collectUntil(b, frame => frame.t === "chat")
    const ack = collectUntil(a, frame => frame.t === "chat-ack")
    a.send(JSON.stringify(valid))
    expect(await delivered).toEqual(valid)
    expect(await ack).toMatchObject({
      cv: 1, scope: "direct", to: B.actor_id, toSd: CHAT_SD_B, toKid: keyB.kid,
    })

    const wrongSession = await signedChat(A, base64urlBytes(SD), keyA.kid, 2, {
      scope: "direct", target: { ...target, session: hexBytes("7c".repeat(32)) },
    })
    let nack = collectUntil(a, frame => frame.t === "chat-nack")
    let unexpected = collectUntil(b, frame => frame.t === "chat", 250)
    a.send(JSON.stringify(wrongSession))
    expect(await nack).toMatchObject({ code: "recipient_offline", retry: true })
    expect(await unexpected).toBeNull()

    const wrongKey = await signedChat(A, base64urlBytes(SD), keyA.kid, 2, {
      scope: "direct", target: { ...target, kid: bytesToBase64url(hexBytes("cd".repeat(32))) },
    })
    nack = collectUntil(a, frame => frame.t === "chat-nack")
    unexpected = collectUntil(b, frame => frame.t === "chat", 250)
    a.send(JSON.stringify(wrongKey))
    expect(await nack).toMatchObject({ code: "recipient_offline", retry: true })
    expect(await unexpected).toBeNull()
    a.close(); b.close()
  })

  it("returns an offline nack for a selected unit and never broadens delivery", async () => {
    const { a, b, keyA } = await openChatPair("chat-direct-offline")
    const offline = await signedChat(A, base64urlBytes(SD), keyA.kid, 1, {
      scope: "direct",
      target: {
        identity: { ...B, actor_id: bytesToBase64url(hexBytes("ef".repeat(32))) },
        session: CHAT_SD_B_RAW,
        kid: bytesToBase64url(hexBytes("dc".repeat(32))),
      },
    })
    const nack = collectUntil(a, frame => frame.t === "chat-nack")
    const leaked = collectUntil(b, frame => frame.t === "chat", 250)
    a.send(JSON.stringify(offline))
    expect(await nack).toMatchObject({
      cv: 1, mid: offline.mid, code: "recipient_offline", retry: true,
    })
    expect(await leaked).toBeNull()
    a.close(); b.close()
  })

  it("serializes concurrent same-socket chat counters in arrival order", async () => {
    const { stub, a, b, keyA, keyB } = await openChatPair("chat-concurrent-counter-order")
    const first = await signedChat(A, base64urlBytes(SD), keyA.kid, 1)
    const second = await signedChat(A, base64urlBytes(SD), keyA.kid, 2, {
      scope: "direct",
      target: { identity: B, session: CHAT_SD_B_RAW, kid: keyB.kid },
    })
    const routed = collectMessages(b, 2, 1_000)
    const acknowledgements = collectMessages(a, 2, 1_000)

    await runInDurableObject(stub, async (instance, state) => {
      const sender = state.getWebSockets().find(socket => {
        const attachment = socket.deserializeAttachment() as any
        return attachment?.hello?.by === A.actor_id && attachment?.hello?.sd === SD
      })
      expect(sender).toBeDefined()

      const relay = instance as any
      const originalHandleChat = relay.handleChat
      relay.handleChat = async (socket: WebSocket, input: any): Promise<void> => {
        // Deterministically model the first WebCrypto verification completing
        // after the second. Arrival order must still govern the socket counter.
        if (input?.vs === first.vs) await sleep(75)
        return originalHandleChat.call(relay, socket, input)
      }
      try {
        await Promise.all([
          instance.webSocketMessage!(sender!, JSON.stringify(first)),
          instance.webSocketMessage!(sender!, JSON.stringify(second)),
        ])
      } finally {
        relay.handleChat = originalHandleChat
      }

      const attachment = sender!.deserializeAttachment() as any
      expect(attachment.chatCounter).toBe("0000000000000002")
      expect(attachment.lastChat).toMatchObject({
        vs: second.vs,
        mid: second.mid,
      })
    })

    expect(await routed).toEqual([first, second])
    const replies = await acknowledgements
    expect(replies.map(reply => reply.t)).toEqual(["chat-ack", "chat-ack"])
    expect(replies.map(reply => reply.mid)).toEqual([first.mid, second.mid])
    a.close(); b.close()
  })

  it("drains accepted same-socket chat in FIFO order before a normal close departure", async () => {
    const { stub, a, b, keyA, keyB } = await openChatPair("chat-close-drains-accepted-queue")
    const first = await signedChat(A, base64urlBytes(SD), keyA.kid, 1)
    const second = await signedChat(A, base64urlBytes(SD), keyA.kid, 2, {
      scope: "direct",
      target: { identity: B, session: CHAT_SD_B_RAW, kid: keyB.kid },
    })
    const peerFrames = collectMessages(b, 3, 1_500)
    const closed = waitForClose(a, 1_500)

    await runInDurableObject(stub, async (instance, state) => {
      const sender = state.getWebSockets().find(socket => {
        const attachment = socket.deserializeAttachment() as any
        return attachment?.hello?.by === A.actor_id && attachment?.hello?.sd === SD
      })
      expect(sender).toBeDefined()

      const relay = instance as any
      const originalHandleChat = relay.handleChat
      relay.handleChat = async (socket: WebSocket, input: any): Promise<void> => {
        // Hold the first accepted frame so the second frame and close callback
        // are certainly queued behind it rather than relying on wall-clock luck.
        if (input?.vs === first.vs) await sleep(75)
        return originalHandleChat.call(relay, socket, input)
      }
      try {
        const firstPending = instance.webSocketMessage!(sender!, JSON.stringify(first))
        const secondPending = instance.webSocketMessage!(sender!, JSON.stringify(second))
        await instance.webSocketClose!(sender!, 1000, "test normal close", true)
        await Promise.all([firstPending, secondPending])
      } finally {
        relay.handleChat = originalHandleChat
      }
    })

    expect((await closed)?.code).toBe(1000)
    expect(await peerFrames).toEqual([
      first,
      second,
      { t: "leave", by: A.actor_id, sd: SD, transient: true },
    ])
    expect(await collectMessages(b, 1, 200)).toEqual([])
    b.close()
  })

  it("aborts a valid chat queued behind a server-initiated protocol close", async () => {
    const { stub, a, b, keyA } = await openChatPair("chat-protocol-close-aborts-queue")
    const validChat = await signedChat(A, base64urlBytes(SD), keyA.kid, 1)
    const closed = waitForClose(a, 1_500)
    const leakedChat = collectUntil(b, frame => frame.t === "chat", 300)

    await runInDurableObject(stub, async (instance, state) => {
      const sender = state.getWebSockets().find(socket => {
        const attachment = socket.deserializeAttachment() as any
        return attachment?.hello?.by === A.actor_id && attachment?.hello?.sd === SD
      })
      expect(sender).toBeDefined()

      // The second hello is a protocol violation. Both calls enter the same
      // socket queue before its first continuation runs; closing on the first
      // must fence and discard the otherwise-valid chat behind it.
      await Promise.all([
        instance.webSocketMessage!(sender!, JSON.stringify(hello2())),
        instance.webSocketMessage!(sender!, JSON.stringify(validChat)),
      ])
      expect((sender!.deserializeAttachment() as any).chatCounter).toBeUndefined()
    })

    expect((await closed)?.code).toBe(4012)
    expect(await leakedChat).toBeNull()
    b.close()
  })

  it("fences a socket at the aggregate backlog limit without retaining its frames", async () => {
    const { stub, a, b, keyA } = await openChatPair("chat-room-backlog-admission")
    const first = await signedChat(A, base64urlBytes(SD), keyA.kid, 1)
    const second = await signedChat(A, base64urlBytes(SD), keyA.kid, 2)
    const closed = waitForClose(a, 1_500)
    const leakedChat = collectUntil(b, frame => frame.t === "chat", 300)

    await runInDurableObject(stub, async (instance, state) => {
      const sender = state.getWebSockets().find(socket => {
        const attachment = socket.deserializeAttachment() as any
        return attachment?.hello?.by === A.actor_id && attachment?.hello?.sd === SD
      })
      expect(sender).toBeDefined()

      const relay = instance as any
      // Inject the aggregate admission counter at its documented ceiling. This
      // exercises object-wide backpressure without allocating hundreds of
      // queued frames merely to reach the same deterministic boundary.
      relay.roomPendingMessages = 512
      try {
        await Promise.all([
          instance.webSocketMessage!(sender!, JSON.stringify(first)),
          instance.webSocketMessage!(sender!, JSON.stringify(second)),
        ])
        expect(relay.socketMessageQueues.get(sender)).toMatchObject({
          accepting: false,
          abortPending: true,
          pendingMessages: 0,
          pendingBytes: 0,
        })
        expect(relay.roomPendingMessages).toBe(512)
        expect(relay.roomPendingBytes).toBe(0)
        expect((sender!.deserializeAttachment() as any).chatCounter).toBeUndefined()
      } finally {
        relay.roomPendingMessages = 0
      }
    })

    expect((await closed)?.code).toBe(4008)
    expect(await leakedChat).toBeNull()
    b.close()
  })

  it("rejects changed replays and far-future counters while allowing exact retry", async () => {
    const { a, b, keyA } = await openChatPair("chat-counter-replay")
    const first = await signedChat(A, base64urlBytes(SD), keyA.kid, 1)
    let received = collectUntil(b, frame => frame.t === "chat")
    let ack = collectUntil(a, frame => frame.t === "chat-ack")
    a.send(JSON.stringify(first)); await received; await ack

    const changedReplay = await signedChat(A, base64urlBytes(SD), keyA.kid, 1, { byte: 66 })
    let nack = collectUntil(a, frame => frame.t === "chat-nack")
    let unexpected = collectUntil(b, frame => frame.t === "chat", 250)
    a.send(JSON.stringify(changedReplay))
    expect(await nack).toMatchObject({ code: "counter_rejected" })
    expect(await unexpected).toBeNull()

    // Same signed bytes are an idempotent transport retry: re-route so a lost
    // relay acknowledgement can be recovered without changing the message.
    received = collectUntil(b, frame => frame.t === "chat")
    ack = collectUntil(a, frame => frame.t === "chat-ack")
    a.send(JSON.stringify(first))
    expect(await received).toEqual(first)
    expect(await ack).toMatchObject({ mid: first.mid, vs: first.vs })

    const future = await signedChat(A, base64urlBytes(SD), keyA.kid, 10_002)
    nack = collectUntil(a, frame => frame.t === "chat-nack")
    a.send(JSON.stringify(future))
    expect(await nack).toMatchObject({ code: "counter_rejected" })
    a.close(); b.close()
  })

  it("does not advertise or route maliciously signed chat frames", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("chat-invalid-signatures"))
    const a = await openV3Socket(stub); await drainSnapshot(a)
    const b = await openV3Socket(stub); await drainSnapshot(b)
    const helloA = await signedHello(A, base64urlBytes(SD))
    const helloB = await signedHello(B, CHAT_SD_B_RAW)
    let ack = collectUntil(a, frame => frame.t === "hello-ack")
    let seen = collectUntil(b, frame => frame.t === "hello")
    a.send(JSON.stringify(helloA)); await ack; await seen
    ack = collectUntil(b, frame => frame.t === "hello-ack")
    seen = collectUntil(a, frame => frame.t === "hello")
    b.send(JSON.stringify(helloB)); await ack; await seen

    const keyA = await signedChatKey(A, base64urlBytes(SD), CHAT_KX_A_RAW)
    const invalidKey = { ...keyA, sig: (keyA.sig.startsWith("A") ? "B" : "A") + keyA.sig.slice(1) }
    let keyNack = collectUntil(a, frame => frame.t === "chat-key-nack")
    let leakedKey = collectUntil(b, frame => frame.t === "chat-key", 250)
    a.send(JSON.stringify(invalidKey))
    expect(await keyNack).toMatchObject({ code: "invalid_signature" })
    expect(await leakedKey).toBeNull()

    ack = collectUntil(a, frame => frame.t === "chat-key-ack")
    seen = collectUntil(b, frame => frame.t === "chat-key")
    a.send(JSON.stringify(keyA)); await ack; await seen
    const keyB = await signedChatKey(B, CHAT_SD_B_RAW, CHAT_KX_B_RAW)
    ack = collectUntil(b, frame => frame.t === "chat-key-ack")
    seen = collectUntil(a, frame => frame.t === "chat-key")
    b.send(JSON.stringify(keyB)); await ack; await seen

    const validChat = await signedChat(A, base64urlBytes(SD), keyA.kid, 1)
    const invalidChat = {
      ...validChat,
      sig: (validChat.sig.startsWith("A") ? "B" : "A") + validChat.sig.slice(1),
    }
    const chatNack = collectUntil(a, frame => frame.t === "chat-nack")
    const leakedChat = collectUntil(b, frame => frame.t === "chat", 250)
    a.send(JSON.stringify(invalidChat))
    expect(await chatNack).toMatchObject({ code: "invalid_signature" })
    expect(await leakedChat).toBeNull()

    const delivered = collectUntil(b, frame => frame.t === "chat")
    const chatAck = collectUntil(a, frame => frame.t === "chat-ack")
    a.send(JSON.stringify(validChat))
    expect(await delivered).toEqual(validChat)
    expect(await chatAck).toMatchObject({ mid: validChat.mid, vs: validChat.vs })
    a.close(); b.close()
  })
})
