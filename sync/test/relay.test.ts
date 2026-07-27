import { env, SELF, runInDurableObject } from "cloudflare:test"
import { describe, it, expect } from "vitest"
import fixture from "../../testdata/sync_protocol_v3.json"
import type { Env } from "../src/index"

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

function hexToBase64url(hex: string): string {
  let binary = ""
  for (let i = 0; i < hex.length; i += 2) binary += String.fromCharCode(Number.parseInt(hex.slice(i, i + 2), 16))
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
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
    ct: sealed(), pub: A.pubkey_base64url, sd: SD, ...overrides,
  }
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
  it("serves health and rejects invalid routes/origins", async () => {
    const health = await SELF.fetch("http://test/health")
    expect(health.status).toBe(200)
    expect(await health.text()).toBe("ok")
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
    current.send(JSON.stringify(hello2())); await collectMessages(current, 1); await collectMessages(peer, 1)
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
