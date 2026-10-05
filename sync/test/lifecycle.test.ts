import { env, runInDurableObject, runDurableObjectAlarm, evictDurableObject } from "cloudflare:test"
import { describe, it, expect } from "vitest"
import fixture from "../../testdata/sync_protocol_v3.json"
import {
  A, B, CHAT_SD_B_RAW, SD, SD_2, WIRE_ID, base64urlBytes, bytesToBase64url, collectUntil, del, drainSnapshot,
  genHello, genIdentity, hello, hello2, helloOn, headers, instrumentStorage, loc, openChatPair, openSocket,
  openV3Socket, put, restoreStorage, rid, roomUrl, sealed, signedChat, signedHello, sleep, stamp, v3Headers,
  v3RoomUrl, waitForClose, wireIdFor, writeCount,
} from "./support"

// SP1 relay lifecycle regressions. each describe maps to an item in
// plans/03-unit-sync-revision.md and started life as a failing proof of the
// audited defect (S1-xx / S3-xx / S4-xx / S5-xx / S6-xx)

const LIMITS = fixture.relayLimits.values
const HOUR = 60 * 60 * 1000
const DAY = 24 * HOUR

function room(name: string): DurableObjectStub {
  return env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName(name))
}

function utf8(value: string): number {
  return new TextEncoder().encode(value).length
}

function recordBytesV3(record: any): number {
  if (typeof record.sd !== "string") {
    return record.ct.length + record.id.length + record.by.length + record.kind.length + record.vs.length + 80
  }
  return record.ct.length + record.id.length + record.by.length + record.kind.length +
    record.vs.length + record.pub.length + record.sd.length + 128
}

function recordBytesV2(record: any): number {
  return record.ct.length + record.id.length + record.by.length + record.kind.length + 64
}

// what meta:totalRecords / meta:bytes must equal if accounting is exact
async function recomputeAccounting(state: DurableObjectState, protocol: 2 | 3 = 3): Promise<{ total: number; bytes: number }> {
  let total = 0
  let bytes = 0
  // pins and the epoch floors idle expiry leaves in their place
  for (const prefix of ["actor:", "epoch:"]) {
    for (const [key, value] of await state.storage.list<any>({ prefix })) {
      total += 1
      bytes += utf8(key) + utf8(JSON.stringify(value))
    }
  }
  for (const value of (await state.storage.list<any>({ prefix: "obj:" })).values()) {
    total += 1
    bytes += protocol === 3 ? recordBytesV3(value) : recordBytesV2(value)
  }
  return { total, bytes }
}

async function putBatched(state: DurableObjectState, entries: Array<[string, unknown]>): Promise<void> {
  for (let index = 0; index < entries.length; index += 128) {
    await state.storage.put(Object.fromEntries(entries.slice(index, index + 128)))
  }
}

async function ackedOrNacked(ws: WebSocket, frame: Record<string, unknown>): Promise<any> {
  const reply = collectUntil(ws, message =>
    (message.t === "op-ack" || message.t === "op-nack") && message.rid === frame.rid, 3_000)
  ws.send(JSON.stringify(frame))
  return reply
}

// tags the one socket that has no tag yet, ie the one just opened
async function tagNewest(stub: DurableObjectStub, tag: string): Promise<void> {
  await runInDurableObject(stub, (_instance, state) => {
    const untagged = state.getWebSockets().filter(ws => !(ws.deserializeAttachment() as any)?.testTag)
    expect(untagged).toHaveLength(1)
    const attachment = untagged[0].deserializeAttachment() as any
    untagged[0].serializeAttachment({ ...attachment, testTag: tag })
  })
}

async function drainRaw(ws: WebSocket, timeoutMs = 60_000): Promise<string[]> {
  return new Promise(resolve => {
    const texts: string[] = []
    const timer = setTimeout(() => resolve(texts), timeoutMs)
    ws.addEventListener("message", event => {
      const text = typeof event.data === "string" ? event.data : new TextDecoder().decode(event.data as ArrayBuffer)
      texts.push(text)
      if (JSON.parse(text).t === "snapshot-end") { clearTimeout(timer); resolve(texts) }
    })
  })
}

describe("SP1 item 1: no durable write per frame", () => {
  it("steady-state presence and rejected frames perform zero storage writes", async () => {
    const stub = room("sp1-presence-writes")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    const b = await openV3Socket(stub); await drainSnapshot(b)
    const seen = collectUntil(b, frame => frame.t === "hello")
    await helloOn(a, hello()); await seen
    // one accepted write first so the hourly activity marker is already fresh
    expect((await ackedOrNacked(a, put(1))).t).toBe("op-ack")

    const log = await instrumentStorage(stub)
    const frames = 50
    for (let counter = 1; counter <= frames; counter += 1) {
      const relayed = collectUntil(b, frame => frame.t === "loc" && frame.vs === stamp(counter))
      a.send(JSON.stringify(loc(counter)))
      expect(await relayed).not.toBeNull()
    }
    // rejected frames: replayed presence counters and puts from a socket with no hello
    for (let counter = 1; counter <= 10; counter += 1) a.send(JSON.stringify(loc(counter)))
    for (let index = 1; index <= 10; index += 1) {
      expect(await ackedOrNacked(b, put(100 + index, { rid: rid(`nohello-${index}`) })))
        .toMatchObject({ t: "op-nack", code: "hello-required" })
    }
    await sleep(50)
    console.log(JSON.stringify({
      sp1_item1: "presence", presenceFrames: frames, rejectedFrames: 20,
      storageWrites: writeCount(log), writesPerPresenceFrame: writeCount(log) / frames,
      keys: [...new Set(log.puts)],
    }))
    expect(writeCount(log)).toBe(0)
    expect(log.transactions).toBe(0)
    await restoreStorage(stub)
    a.close(); b.close()
  })

  it("persists lastActivity at most once per ACTIVITY_PERSIST_MS for accepted writes", async () => {
    const stub = room("sp1-mutation-activity")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, hello())
    expect((await ackedOrNacked(a, put(1))).t).toBe("op-ack")
    const log = await instrumentStorage(stub)
    for (let index = 0; index < 20; index += 1) {
      const id = await wireIdFor(`activity-${index}`)
      expect((await ackedOrNacked(a, put(10 + index, { id, rid: rid(`activity-${index}`) }))).t).toBe("op-ack")
    }
    const activityWrites = log.puts.filter(key => key === "meta:lastActivity").length
    console.log(JSON.stringify({ sp1_item1: "mutations", acceptedPuts: 20, lastActivityWrites: activityWrites }))
    expect(activityWrites).toBe(0)
    expect(log.puts.filter(key => key.startsWith("obj:"))).toHaveLength(20)
    await restoreStorage(stub)
    a.close()
  })

  it("a failing activity write never closes a socket or fails a join", async () => {
    const stub = room("sp1-activity-write-failure")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, hello())
    await runInDurableObject(stub, instance => {
      const storage = (instance as any).state.storage
      const original = storage.put.bind(storage)
      ;(instance as any).__originalPut = original
      storage.put = (key: unknown, ...rest: unknown[]) => key === "meta:lastActivity"
        ? Promise.reject(new Error("rows written limit exceeded"))
        : original(key, ...rest)
      // make sure the next accepted frame actually tries to persist activity
      ;(instance as any).activityPersistedAt = 0
    })
    const closed = waitForClose(a, 600)
    expect((await ackedOrNacked(a, put(1))).t).toBe("op-ack")
    a.send(JSON.stringify(loc(1)))
    expect(await closed).toBeNull()
    const join = await stub.fetch(v3RoomUrl(), { headers: v3Headers() })
    expect(join.status).toBe(101)
    join.webSocket!.accept()
    await drainSnapshot(join.webSocket!)
    await runInDurableObject(stub, instance => {
      delete (instance as any).state.storage.put
    })
    a.close(); join.webSocket!.close()
  })

  it("does not turn a hibernation wake into an activity write", async () => {
    const stub = room("sp1-hibernation-activity")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    const b = await openV3Socket(stub); await drainSnapshot(b)
    const seen = collectUntil(b, frame => frame.t === "hello")
    await helloOn(a, hello()); await seen
    expect((await ackedOrNacked(a, put(1))).t).toBe("op-ack")

    await evictDurableObject(stub)
    const log = await instrumentStorage(stub)
    for (let counter = 1; counter <= 20; counter += 1) {
      const relayed = collectUntil(b, frame => frame.t === "loc" && frame.vs === stamp(counter))
      a.send(JSON.stringify(loc(counter)))
      expect(await relayed).not.toBeNull()
    }
    expect((await ackedOrNacked(a, put(2, { rid: rid("after-wake") }))).t).toBe("op-ack")
    console.log(JSON.stringify({ sp1_item1: "hibernation", writes: log.puts }))
    expect(log.puts.filter(key => key === "meta:lastActivity")).toHaveLength(0)
    expect(log.puts.filter(key => !key.startsWith("obj:") && !key.startsWith("meta:"))).toEqual([])
    await restoreStorage(stub)
    a.close(); b.close()
  })

  it("a relay-side close of the last open socket records activity even with no close callback", async () => {
    const stub = room("sp1-relay-close-activity")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, hello())
    const stale = Date.now() - 3 * DAY
    await runInDurableObject(stub, async (instance, state) => {
      await state.storage.put("meta:lastActivity", stale)
      ;(instance as any).activityPersistedAt = stale
    })
    // the in-process client never echoes the close, so webSocketClose never runs
    const closed = waitForClose(a)
    a.send("x".repeat(LIMITS.MAX_FRAME_BYTES + 1))
    expect((await closed)?.code).toBe(4009)
    await sleep(50)
    const last = await runInDurableObject(stub, async (_instance, state) => state.storage.get<number>("meta:lastActivity"))
    expect(Date.now() - last!).toBeLessThan(60_000)
  })

  it("an alarm while a socket is open keeps the room, refreshes activity, and re-arms", async () => {
    const stub = room("sp1-alarm-with-socket")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, hello())
    expect((await ackedOrNacked(a, put(1))).t).toBe("op-ack")
    await runInDurableObject(stub, async (_instance, state) => {
      await state.storage.put("meta:lastActivity", Date.now() - 8 * DAY)
      await state.storage.setAlarm(Date.now() + 60_000)
    })
    expect(await runDurableObjectAlarm(stub)).toBe(true)
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.get(`obj:${WIRE_ID}`)).toMatchObject({ deleted: false })
      expect(await state.storage.get(`actor:${A.actor_id}`)).toBeDefined()
      expect(Date.now() - (await state.storage.get<number>("meta:lastActivity"))!).toBeLessThan(LIMITS.ACTIVITY_PERSIST_MS)
      expect(await state.storage.getAlarm()).not.toBeNull()
    })
    a.close()
  })

  it("relay-ref-3: relayed presence, routed chat and pings keep the stored idle clock within the hour, rejected frames still don't", async () => {
    const { stub, a, b, keyA } = await openChatPair("sp1-live-traffic-activity")
    // the room 20 h after the daily alarm last wrote its activity, nothing
    // durable since. presence and chat used to leave it there
    const stale = Date.now() - 20 * HOUR
    const age = (): Promise<void> => runInDurableObject(stub, async (instance, state) => {
      await state.storage.put("meta:lastActivity", stale)
      ;(instance as any).activityAt = stale
      ;(instance as any).activityPersistedAt = stale
    })
    const stored = (): Promise<number> => runInDurableObject(stub, async (_instance, state) =>
      (await state.storage.get<number>("meta:lastActivity"))!)
    // an identical chat-key retry is re-acked and isn't activity itself, so
    // its ack means everything a sent before it is fully handled
    const settle = async (): Promise<void> => {
      const acked = collectUntil(a, frame => frame.t === "chat-key-ack")
      a.send(JSON.stringify(keyA))
      expect(await acked).not.toBeNull()
    }

    await age()
    a.send(JSON.stringify(loc(1, { sd: bytesToBase64url(CHAT_SD_B_RAW) })))
    await settle()
    expect(await stored()).toBe(stale)

    const relayed = collectUntil(b, frame => frame.t === "loc")
    a.send(JSON.stringify(loc(1)))
    expect(await relayed).not.toBeNull()
    await settle()
    expect(Date.now() - await stored()).toBeLessThan(60_000)
    // still at most once an hour
    const log = await instrumentStorage(stub)
    const next = collectUntil(b, frame => frame.t === "loc")
    a.send(JSON.stringify(loc(2)))
    expect(await next).not.toBeNull()
    await settle()
    await restoreStorage(stub)
    expect(writeCount(log)).toBe(0)

    await age()
    const routed = collectUntil(a, frame => frame.t === "chat-ack")
    a.send(JSON.stringify(await signedChat(A, base64urlBytes(SD), keyA.kid, 1)))
    expect(await routed).not.toBeNull()
    await settle()
    expect(Date.now() - await stored()).toBeLessThan(60_000)

    await age()
    const pong = collectUntil(a, frame => frame.t === "pong")
    a.send(JSON.stringify({ t: "ping" }))
    expect(await pong).toEqual({ t: "pong" })
    await settle()
    const last = await stored()
    expect(Date.now() - last).toBeLessThan(60_000)

    // now a deploy drops both sockets with no close callback and nobody comes
    // back. the idle clock has to run from that last frame, not 20 h earlier
    await runInDurableObject(stub, async (instance, state) => {
      const relay = instance as any
      relay.activityAt = 0
      relay.activityPersistedAt = undefined
      relay.alarmAt = undefined
      relay.state.getWebSockets = () => []
      await state.storage.setAlarm(Date.now() + 60_000)
    })
    try {
      expect(await runDurableObjectAlarm(stub)).toBe(true)
      expect(await runInDurableObject(stub, async (_instance, state) => state.storage.getAlarm()))
        .toBe(last + LIMITS.IDLE_TTL_MS)
    } finally {
      await runInDurableObject(stub, instance => { delete (instance as any).state.getWebSockets })
    }
    a.close(); b.close()
  })
})

describe("SP1 item 2: idle expiry keeps monotonic room state", () => {
  it("S1-01: a returning client with mature counters is acked and seq never rolls back", async () => {
    const stub = room("sp1-expiry-monotonic")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, await signedHello(A, base64urlBytes(SD), 1))
    const other = await wireIdFor("sp1-expiry-deleted")
    for (const frame of [
      put(9_000, { rid: rid("mature-9000") }),
      put(18_000, { rid: rid("mature-18000") }),
      put(17_000, { id: other, rid: rid("tomb-put") }),
      del(17_500, { id: other, rid: rid("tomb-del") }),
    ]) {
      expect((await ackedOrNacked(a, frame)).t).toBe("op-ack")
    }
    const before = await runInDurableObject(stub, async (_instance, state) => ({
      seq: (await state.storage.get<number>("meta:seq"))!,
      highWater: await state.storage.get<string>("meta:highWater"),
      auth: await state.storage.get<string>("meta:auth"),
    }))
    const closed = waitForClose(a); a.close(1000, "bye"); await closed
    await sleep(50)

    await runInDurableObject(stub, async (_instance, state) => {
      await state.storage.put("meta:lastActivity", Date.now() - 8 * DAY)
      await state.storage.setAlarm(Date.now() + 60_000)
    })
    expect(await runDurableObjectAlarm(stub)).toBe(true)
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.get("meta:highWater")).toBe(before.highWater)
      expect(await state.storage.get<number>("meta:seq")).toBeGreaterThanOrEqual(before.seq)
      expect(await state.storage.get("meta:auth")).toBe(before.auth)
      expect(await state.storage.get("meta:protocol")).toBe(3)
      // live ciphertext and actor pins are gone, the tombstone stays and A's
      // pin leaves just its hello epoch behind
      expect(await state.storage.get(`obj:${WIRE_ID}`)).toBeUndefined()
      expect(await state.storage.get(`actor:${A.actor_id}`)).toBeUndefined()
      expect(await state.storage.get(`epoch:${A.actor_id}`)).toBe("0000000000000001")
      expect(await state.storage.get(`obj:${other}`)).toMatchObject({ deleted: true, vs: stamp(17_500) })
      const exact = await recomputeAccounting(state)
      expect(exact.total).toBe(2)
      expect(await state.storage.get("meta:totalRecords")).toBe(exact.total)
      expect(await state.storage.get("meta:bytes")).toBe(exact.bytes)
    })

    const b = await openV3Socket(stub)
    const snapshot = await drainSnapshot(b)
    expect(snapshot.begin.seq).toBeGreaterThanOrEqual(before.seq)
    expect(snapshot.begin.highWater).toBe(before.highWater)
    expect(snapshot.items.map(item => item.id)).toEqual([other])
    await helloOn(b, hello2())
    const reply = await ackedOrNacked(b, put(18_001, { sd: SD_2, rid: rid("after-expiry") }))
    console.log(JSON.stringify({ sp1_item2: "S1-01", preExpirySeq: before.seq, postExpirySeq: snapshot.begin.seq, reply }))
    expect(reply).toMatchObject({ t: "op-ack", vs: stamp(18_001) })
    // the kept tombstone still beats a stale resurrection
    expect(await ackedOrNacked(b, put(17_400, { id: other, sd: SD_2, rid: rid("resurrect") })))
      .toMatchObject({ t: "op-nack", code: "stale" })
    b.close()
  })

  it("expiry is idempotent and a second pass does not advance seq again", async () => {
    const stub = room("sp1-expiry-idempotent")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, hello())
    expect((await ackedOrNacked(a, put(1))).t).toBe("op-ack")
    const closed = waitForClose(a); a.close(1000, "bye"); await closed
    await sleep(50)
    const runExpiry = async () => {
      await runInDurableObject(stub, async (_instance, state) => {
        const last = await state.storage.get<number>("meta:lastActivity")
        if (last !== undefined) await state.storage.put("meta:lastActivity", Math.min(last, Date.now() - 8 * DAY))
        await state.storage.setAlarm(Date.now() + 60_000)
      })
      expect(await runDurableObjectAlarm(stub)).toBe(true)
      return runInDurableObject(stub, async (_instance, state) => state.storage.get<number>("meta:seq"))
    }
    const first = await runExpiry()
    const second = await runExpiry()
    expect(first).toBe(2)
    expect(second).toBe(first)
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.get("meta:highWater")).toBe("0000000000000001")
      expect((await state.storage.list({ prefix: "obj:" })).size).toBe(0)
    })
  })

  it("expires v2 rooms the same way without touching their record shape", async () => {
    const stub = room("sp1-expiry-v2")
    const a = await openSocket(stub); await drainSnapshot(a)
    const acked = collectUntil(a, frame => frame.t === "op-ack" && frame.id === "gone")
    a.send(JSON.stringify({ t: "put", id: "live", v: 1, by: "a", kind: "waypoint", ct: sealed(), rid: rid("v2-live") }))
    a.send(JSON.stringify({ t: "put", id: "gone", v: 1, by: "a", kind: "waypoint", ct: sealed(), rid: rid("v2-gone-put") }))
    a.send(JSON.stringify({ t: "del", id: "gone", v: 2, by: "a", ct: sealed(), rid: rid("v2-gone-del") }))
    await acked; await sleep(50)
    const closed = waitForClose(a); a.close(1000, "bye"); await closed
    await sleep(50)
    const seqBefore = await runInDurableObject(stub, async (_instance, state) => {
      await state.storage.put("meta:lastActivity", Date.now() - 8 * DAY)
      await state.storage.setAlarm(Date.now() + 60_000)
      return (await state.storage.get<number>("meta:seq"))!
    })
    expect(await runDurableObjectAlarm(stub)).toBe(true)
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.get("obj:live")).toBeUndefined()
      expect(await state.storage.get("obj:gone")).toMatchObject({ deleted: true, v: 2 })
      // v2 has no hellos, so nothing to keep a floor for
      expect((await state.storage.list({ prefix: "epoch:" })).size).toBe(0)
      expect(await state.storage.get<number>("meta:seq")).toBeGreaterThanOrEqual(seqBefore)
      const exact = await recomputeAccounting(state, 2)
      expect(await state.storage.get("meta:totalRecords")).toBe(exact.total)
      expect(await state.storage.get("meta:bytes")).toBe(exact.bytes)
    })
    const response = await stub.fetch(roomUrl(), { headers: headers() })
    expect(response.status).toBe(101)
    response.webSocket!.accept()
    const snapshot = await drainSnapshot(response.webSocket!)
    expect(snapshot.items.map(item => item.id)).toEqual(["gone"])
    expect(snapshot.begin.seq).toBeGreaterThanOrEqual(seqBefore)
    response.webSocket!.close()
  })

  // breaks every storage.delete that touches a live object, until the returned undo runs
  async function failObjectDeletes(stub: DurableObjectStub): Promise<() => Promise<void>> {
    await runInDurableObject(stub, instance => {
      const storage = (instance as any).state.storage
      const original = storage.delete.bind(storage)
      storage.delete = (keys: unknown, ...rest: unknown[]) => {
        const list = Array.isArray(keys) ? keys : [keys]
        return list.some(key => String(key).startsWith("obj:"))
          ? Promise.reject(new Error("injected delete failure"))
          : original(keys, ...rest)
      }
    })
    return async () => {
      await runInDurableObject(stub, instance => { delete (instance as any).state.storage.delete })
    }
  }

  async function roomWithLiveAndTomb(name: string): Promise<{ stub: DurableObjectStub; other: string; seq: number }> {
    const stub = room(name)
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, hello())
    const other = await wireIdFor(`${name}-tomb`)
    for (const frame of [put(1), put(5, { id: other, rid: rid("tomb-put") }), del(6, { id: other, rid: rid("tomb-del") })]) {
      expect((await ackedOrNacked(a, frame)).t).toBe("op-ack")
    }
    const closed = waitForClose(a); a.close(1000, "bye"); await closed
    await sleep(50)
    const seq = await runInDurableObject(stub, async (_instance, state) => {
      await state.storage.put("meta:lastActivity", Date.now() - 8 * DAY)
      await state.storage.setAlarm(Date.now() + 60_000)
      return (await state.storage.get<number>("meta:seq"))!
    })
    return { stub, other, seq }
  }

  it("a storage failure part way through expiry moves seq first, re-arms the alarm, and the retry finishes exactly", async () => {
    const { stub, other, seq: seqBefore } = await roomWithLiveAndTomb("sp1-expiry-retry")
    const undo = await failObjectDeletes(stub)
    expect(await runDurableObjectAlarm(stub)).toBe(true)
    await undo()
    const afterFailure = await runInDurableObject(stub, async (_instance, state) => ({
      alarm: await state.storage.getAlarm(),
      live: await state.storage.get(`obj:${WIRE_ID}`),
      seq: (await state.storage.get<number>("meta:seq"))!,
      horizon: await state.storage.get<number>("meta:horizonSeq"),
      expiredAt: await state.storage.get("meta:expiredAt"),
    }))
    console.log(JSON.stringify({ sp1_item2: "expiry-failure", seqBefore, ...afterFailure, live: !!afterFailure.live }))
    expect(afterFailure.alarm).not.toBeNull()
    expect(afterFailure.alarm!).toBeLessThanOrEqual(Date.now() + LIMITS.MAINTENANCE_RETRY_MS)
    expect(afterFailure.live).toBeDefined()
    expect(afterFailure.expiredAt).toBeUndefined()
    // seq already moved past whatever the dead pass could have removed
    expect(afterFailure.seq).toBeGreaterThan(seqBefore)
    expect(afterFailure.horizon).toBe(afterFailure.seq)

    expect(await runDurableObjectAlarm(stub)).toBe(true)
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.get(`obj:${WIRE_ID}`)).toBeUndefined()
      expect(await state.storage.get(`actor:${A.actor_id}`)).toBeUndefined()
      expect(await state.storage.get(`obj:${other}`)).toMatchObject({ deleted: true })
      expect(await state.storage.get<number>("meta:seq")).toBeGreaterThanOrEqual(afterFailure.seq)
      expect(await state.storage.get("meta:expiredAt")).toBeDefined()
      expect(await state.storage.get("meta:expiring")).toBeUndefined()
      const exact = await recomputeAccounting(state)
      expect(await state.storage.get("meta:totalRecords")).toBe(exact.total)
      expect(await state.storage.get("meta:bytes")).toBe(exact.bytes)
    })
  })

  it("a join after a failed expiry pass recounts the room exactly", async () => {
    const { stub } = await roomWithLiveAndTomb("sp1-expiry-failed-then-join")
    const undo = await failObjectDeletes(stub)
    expect(await runDurableObjectAlarm(stub)).toBe(true)
    await undo()
    const b = await openV3Socket(stub)
    const snapshot = await drainSnapshot(b)
    expect(snapshot.end).toBeDefined()
    await runInDurableObject(stub, async (_instance, state) => {
      const exact = await recomputeAccounting(state)
      expect(await state.storage.get("meta:totalRecords")).toBe(exact.total)
      expect(await state.storage.get("meta:bytes")).toBe(exact.bytes)
      expect(await state.storage.get("meta:expiring")).toBeUndefined()
    })
    b.close()
  })

  it("purges a room that never accepted a write instead of keeping its meta rows", async () => {
    const stub = room("sp1-expiry-drive-by")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, hello())
    const closed = waitForClose(a); a.close(1000, "bye"); await closed
    await sleep(50)
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.get("meta:seq")).toBeUndefined()
      await state.storage.put("meta:lastActivity", Date.now() - 8 * DAY)
      await state.storage.setAlarm(Date.now() + 60_000)
    })
    expect(await runDurableObjectAlarm(stub)).toBe(true)
    await runInDurableObject(stub, async (_instance, state) => {
      // the pin goes without leaving an epoch floor, there's no record to roll back
      expect([...(await state.storage.list()).keys()]).toEqual([])
      expect(await state.storage.getAlarm()).toBeNull()
    })
    // nothing to roll back to, and v3 room ids are bound to the token so a
    // fresh trust-on-first-use pin can't be squatted
    const again = await openV3Socket(stub)
    const snapshot = await drainSnapshot(again)
    expect(snapshot.begin).toMatchObject({ seq: 0, highWater: "0000000000000000" })
    again.close()
  })
})

describe("SP1 item 2b: bounded retention, the 90 day idle purge", () => {
  async function writtenRoom(name: string): Promise<{ stub: DurableObjectStub; other: string }> {
    const stub = room(name)
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, hello())
    const other = await wireIdFor(`${name}-tomb`)
    for (const frame of [put(1), put(5, { id: other, rid: rid("tomb-put") }), del(6, { id: other, rid: rid("tomb-del") })]) {
      expect((await ackedOrNacked(a, frame)).t).toBe("op-ack")
    }
    const closed = waitForClose(a); a.close(1000, "bye"); await closed
    await sleep(50)
    return { stub, other }
  }

  // pretends the room's last activity was daysAgo days back (and that any
  // expiry already on record ran IDLE_TTL_MS after it), then runs the alarm.
  // the isolate would long since have been evicted, so in-memory activity
  // goes back to what a fresh wake has
  async function idleFor(stub: DurableObjectStub, daysAgo: number): Promise<number> {
    const last = Date.now() - daysAgo * DAY
    await runInDurableObject(stub, async (instance, state) => {
      await state.storage.put("meta:lastActivity", last)
      ;(instance as any).activityAt = 0
      ;(instance as any).activityPersistedAt = undefined
      if (await state.storage.get("meta:expiredAt") !== undefined) {
        await state.storage.put("meta:expiredAt", last + LIMITS.IDLE_TTL_MS)
      }
      await state.storage.setAlarm(Date.now() + 60_000)
    })
    expect(await runDurableObjectAlarm(stub)).toBe(true)
    return last
  }

  async function storedKeys(stub: DurableObjectStub): Promise<string[]> {
    return runInDurableObject(stub, async (_instance, state) => [...(await state.storage.list()).keys()])
  }

  it("keeps a written room's counters, auth and tombstones through 89 idle days and arms the purge for day 90", async () => {
    const { stub, other } = await writtenRoom("sp1-purge-89")
    await idleFor(stub, 8)
    const before = await runInDurableObject(stub, async (_instance, state) => ({
      seq: await state.storage.get<number>("meta:seq"),
      highWater: await state.storage.get<string>("meta:highWater"),
      auth: await state.storage.get<string>("meta:auth"),
      live: await state.storage.get(`obj:${WIRE_ID}`),
    }))
    expect(before.live).toBeUndefined()
    const last = await idleFor(stub, 89)
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.get("meta:auth")).toBe(before.auth)
      expect(await state.storage.get("meta:protocol")).toBe(3)
      expect(await state.storage.get("meta:seq")).toBe(before.seq)
      expect(await state.storage.get("meta:highWater")).toBe(before.highWater)
      expect(await state.storage.get(`obj:${other}`)).toMatchObject({ deleted: true })
      expect(await state.storage.getAlarm()).toBe(last + LIMITS.ROOM_PURGE_TTL_MS)
    })
  })

  it("wipes a written room after 91 idle days, alarm and all; a returning device finds a fresh room", async () => {
    const { stub } = await writtenRoom("sp1-purge-91")
    await idleFor(stub, 8)
    await idleFor(stub, 91)
    expect(await storedKeys(stub)).toEqual([])
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.getAlarm()).toBeNull()
      await state.storage.setAlarm(Date.now() + 60_000)
    })
    // a leftover alarm on the wiped room doesn't write anything back, not
    // even briefly before some later pass wipes it again
    const log = await instrumentStorage(stub)
    expect(await runDurableObjectAlarm(stub)).toBe(true)
    await restoreStorage(stub)
    expect(writeCount(log)).toBe(0)
    expect(log.transactions).toBe(0)
    expect(await storedKeys(stub)).toEqual([])

    const back = await openV3Socket(stub)
    const snapshot = await drainSnapshot(back)
    console.log(JSON.stringify({ sp1_item2b: "purged-return", begin: snapshot.begin, items: snapshot.items.length }))
    // below the seq this device saw before, so shipped clients raise the
    // rollback diagnostic. the docs tell people to move to a new join code
    expect(snapshot.begin).toMatchObject({ seq: 0, highWater: "0000000000000000" })
    expect(snapshot.items).toEqual([])
    await helloOn(back, hello2())
    // and a device whose counters had passed ADVANCE_WINDOW is outside the
    // fresh room's window
    expect(await ackedOrNacked(back, put(20_001, { sd: SD_2, rid: rid("after-purge") })))
      .toMatchObject({ t: "op-nack", code: "counter-window" })
    back.close()
  })

  it("any activity in between restarts the 90 day clock", async () => {
    const { stub, other } = await writtenRoom("sp1-purge-reset")
    // last used 60 days ago: expiry runs, the purge is still 30 days out
    const first = await idleFor(stub, 60)
    expect(await runInDurableObject(stub, async (_instance, state) => state.storage.get(`obj:${WIRE_ID}`))).toBeUndefined()
    // someone opens the room again today and leaves
    const b = await openV3Socket(stub); await drainSnapshot(b)
    const closed = waitForClose(b); b.close(1000, "bye"); await closed
    await sleep(50)
    const revisit = await runInDurableObject(stub, async (_instance, state) => (await state.storage.get<number>("meta:lastActivity"))!)
    expect(revisit).toBeGreaterThan(first + 59 * DAY)
    // 91 days after the first visit is only 31 after the second one
    const second = await idleFor(stub, 31)
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.get("meta:auth")).toBeDefined()
      expect(await state.storage.get("meta:seq")).toBeGreaterThan(0)
      expect(await state.storage.get(`obj:${other}`)).toMatchObject({ deleted: true })
      expect(await state.storage.getAlarm()).toBeLessThanOrEqual(second + LIMITS.ROOM_PURGE_TTL_MS)
    })
    // and 91 days after the second visit it goes
    await idleFor(stub, 91)
    expect(await storedKeys(stub)).toEqual([])
  })

  it("a purge that fails leaves the room whole and comes back for it", async () => {
    const { stub, other } = await writtenRoom("sp1-purge-retry")
    await idleFor(stub, 8)
    await runInDurableObject(stub, instance => {
      ;(instance as any).state.storage.deleteAll = () => Promise.reject(new Error("injected deleteAll failure"))
    })
    await idleFor(stub, 91)
    await runInDurableObject(stub, async (instance, state) => {
      delete (instance as any).state.storage.deleteAll
      expect(await state.storage.get("meta:auth")).toBeDefined()
      expect(await state.storage.get("meta:seq")).toBeGreaterThan(0)
      expect(await state.storage.get(`obj:${other}`)).toMatchObject({ deleted: true })
      const alarm = await state.storage.getAlarm()
      expect(alarm).not.toBeNull()
      expect(alarm!).toBeLessThanOrEqual(Date.now() + LIMITS.MAINTENANCE_RETRY_MS)
    })
    expect(await runDurableObjectAlarm(stub)).toBe(true)
    expect(await storedKeys(stub)).toEqual([])
  })

  it("refuses a join that passed the auth check before an alarm wiped the room, instead of admitting it unpinned", async () => {
    for (const kind of ["drive-by", "idle-purge"] as const) {
      const stub = room(`sp1-join-wipe-race-${kind}`)
      const a = await openV3Socket(stub); await drainSnapshot(a)
      if (kind === "idle-purge") {
        await helloOn(a, hello())
        expect((await ackedOrNacked(a, put(1))).t).toBe("op-ack")
      }
      const closed = waitForClose(a); a.close(1000, "bye"); await closed
      await sleep(50)
      type Outcome = { status: number; body: string; sockets: number; keys: string[] }
      const result = await runInDurableObject(stub, async (instance, state): Promise<Outcome> => {
        const relay = instance as any
        await state.storage.put("meta:lastActivity", Date.now() - (kind === "drive-by" ? 8 : 91) * DAY)
        const original = state.blockConcurrencyWhile.bind(state)
        let armed = true
        // the alarm's pass grabs bCW just ahead of the join's accounting bCW
        relay.state.blockConcurrencyWhile = (callback: any) => {
          if (armed) {
            armed = false
            void (kind === "drive-by" ? relay.expireIdleRoom(Date.now()) : relay.purgeIdleRoom(Date.now()))
          }
          return original(callback)
        }
        try {
          const response: Response = await relay.fetch(new Request(v3RoomUrl(), { headers: v3Headers() }))
          return {
            status: response.status, body: await response.text(),
            sockets: state.getWebSockets().length, keys: [...(await state.storage.list()).keys()],
          }
        } finally {
          delete relay.state.blockConcurrencyWhile
        }
      })
      console.log(JSON.stringify({ sp1_item2b: "join-wipe-race", kind, result }))
      expect(result).toEqual({ status: 503, body: "Room reset during join", sockets: 0, keys: [] })
      // the reconnect pins a fresh room the normal way, with no creation time
      const again = await openV3Socket(stub); await drainSnapshot(again)
      await runInDurableObject(stub, async (_instance, state) => {
        expect(await state.storage.get("meta:auth")).toBeDefined()
        expect(await state.storage.get("meta:tombIndexAt")).toBe(0)
      })
      again.close()
    }
  })

  it("a room whose very first join fails still gets an alarm and is wiped", async () => {
    const stub = room("sp1-first-join-fails")
    await runInDurableObject(stub, instance => {
      const relay = instance as any
      const original = relay.ensureAccounting
      relay.ensureAccounting = async function (): Promise<void> {
        relay.ensureAccounting = original
        throw new Error("injected accounting failure")
      }
    })
    const response = await stub.fetch(v3RoomUrl(), { headers: v3Headers() })
    expect(response.status).toBe(503)
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.get("meta:auth")).toBeDefined()
      expect(await state.storage.getAlarm()).not.toBeNull()
    })
    expect(await runDurableObjectAlarm(stub)).toBe(true)
    expect(await storedKeys(stub)).toEqual([])
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.getAlarm()).toBeNull()
    })
  })
})

// ADR-001 §14: a join-code holder can't activate an older hello session.
// idle expiry used to drop the only durable epoch the relay checked (the pin),
// after which any captured hello and its records went through again
describe("3.0.1 relay-ref-1: the hello epoch floor outlives idle expiry", () => {
  const floorOf = (actor: string): string => `epoch:${actor}`
  const session = (byte: number): Uint8Array => new Uint8Array(32).fill(byte)

  const sockets = (stub: DurableObjectStub): Promise<number> =>
    runInDurableObject(stub, async (_instance, state) => state.getWebSockets().length)

  // a hello on a fresh socket, plus one frame right behind it. result is
  // "hello-ack" or the close code, op whatever the follow-up got back.
  // doesn't return till the relay has dropped the socket: its close callback
  // only runs once the client sends or echoes the close and it writes
  // lastActivity. one that landed inside idle() put the idle clock back to
  // now and the expiry quietly didn't happen (flaked on a cold full run)
  async function tryHello(
    stub: DurableObjectStub, frame: Record<string, unknown>, follow?: Record<string, unknown>,
  ): Promise<{ result: string | number | null; op: any }> {
    const before = await sockets(stub)
    const ws = await openV3Socket(stub); await drainSnapshot(ws)
    const acked = collectUntil(ws, message => message.t === "hello-ack", 2_000)
    const closed = waitForClose(ws, 2_000)
    const op = follow
      ? collectUntil(ws, message => message.t === "op-ack" || message.t === "op-nack", 1_000)
      : Promise.resolve(null)
    ws.send(JSON.stringify(frame))
    if (follow) ws.send(JSON.stringify(follow))
    const result = await Promise.race([
      acked.then(message => message ? "hello-ack" : null),
      closed.then(event => event?.code ?? null),
    ])
    const reply = await op
    try { ws.close() } catch { /* the relay closed it */ }
    for (let tries = 0; await sockets(stub) > before; tries++) {
      if (tries === 200) throw new Error("relay never let go of the tryHello socket")
      await sleep(10)
    }
    return { result, op: reply }
  }

  async function leave(ws: WebSocket): Promise<void> {
    const closed = waitForClose(ws); ws.close(1000, "bye"); await closed
    await sleep(50)
  }

  // last activity daysAgo days back, isolate long since evicted, then the
  // alarm. an expiry already on record ran IDLE_TTL_MS after that activity,
  // or a day before it when earlierExpiry (the device came back in between)
  async function idle(stub: DurableObjectStub, daysAgo: number, earlierExpiry = false): Promise<number> {
    const last = Date.now() - daysAgo * DAY
    await runInDurableObject(stub, async (instance, state) => {
      await state.storage.put("meta:lastActivity", last)
      ;(instance as any).activityAt = 0
      ;(instance as any).activityPersistedAt = undefined
      if (await state.storage.get("meta:expiredAt") !== undefined) {
        await state.storage.put("meta:expiredAt", earlierExpiry ? last - DAY : last + LIMITS.IDLE_TTL_MS)
      }
      await state.storage.setAlarm(Date.now() + 60_000)
    })
    expect(await runDurableObjectAlarm(stub)).toBe(true)
    // make sure the pass actually ran: the room is gone, or an expiry from
    // after that activity is on record. anything that wrote lastActivity in
    // between (a late close) skips it and every check after this is moot
    await runInDurableObject(stub, async (_instance, state) => {
      if ((await state.storage.list({ limit: 1 })).size === 0) return
      expect(await state.storage.get<number>("meta:expiredAt")).toBeGreaterThanOrEqual(last)
    })
    return last
  }

  async function floors(stub: DurableObjectStub): Promise<Record<string, unknown>> {
    return runInDurableObject(stub, async (_instance, state) =>
      Object.fromEntries(await state.storage.list({ prefix: "epoch:" })))
  }

  async function expectExactAccounting(stub: DurableObjectStub): Promise<void> {
    await runInDurableObject(stub, async (_instance, state) => {
      const exact = await recomputeAccounting(state)
      expect(await state.storage.get("meta:totalRecords")).toBe(exact.total)
      expect(await state.storage.get("meta:bytes")).toBe(exact.bytes)
    })
  }

  // A writes WIRE_ID at 10 in session 1 (epoch 1), then at 20 in session 2
  // (epoch 2), and leaves. every member, insider M included, saw all of it
  async function twoSessions(name: string): Promise<DurableObjectStub> {
    const stub = room(name)
    const first = await openV3Socket(stub); await drainSnapshot(first)
    await helloOn(first, hello())
    expect((await ackedOrNacked(first, put(10, { rid: rid("session-1") }))).t).toBe("op-ack")
    const superseded = waitForClose(first)
    const second = await openV3Socket(stub); await drainSnapshot(second)
    await helloOn(second, hello2())
    expect((await superseded)?.code).toBe(4015)
    expect((await ackedOrNacked(second, put(20, { sd: SD_2, rid: rid("session-2") }))).t).toBe("op-ack")
    await leave(second)
    return stub
  }

  it("still refuses a captured older session after idle expiry, so its old record can't roll the object back", async () => {
    const stub = await twoSessions("floor-review-replay")
    // M replays session 1 and its stamp 10 put while A's pin is there
    expect(await tryHello(stub, hello(), put(10, { rid: rid("m-before") }))).toEqual({ result: 4014, op: null })
    await idle(stub, 8)
    // the review got hello-ack and op-ack here, then a fresh joiner saw the
    // object back at stamp 10
    const replays = [
      await tryHello(stub, hello(), put(10, { rid: rid("m-after-1") })),
      // A's last session from a second socket is refused too, same as before expiry
      await tryHello(stub, hello2(), put(20, { sd: SD_2, rid: rid("m-after-2") })),
    ]
    console.log(JSON.stringify({ relay_ref_1: "replay-after-expiry", replays }))
    expect(replays).toEqual([{ result: 4014, op: null }, { result: 4014, op: null }])
    const fresh = await openV3Socket(stub)
    const live = collectUntil(fresh, frame => frame.t === "hello", 500)
    const snapshot = await drainSnapshot(fresh)
    expect(snapshot.items.find(item => item.id === WIRE_ID)).toBeUndefined()
    // and no stale session of A's is advertised as live
    expect(await live).toBeNull()
    // all that's left of A is its newest epoch
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.get(`actor:${A.actor_id}`)).toBeUndefined()
      expect(await state.storage.get(`obj:${WIRE_ID}`)).toBeUndefined()
    })
    expect(await floors(stub)).toEqual({ [floorOf(A.actor_id)]: "0000000000000002" })
    await expectExactAccounting(stub)
    fresh.close()
  })

  it("takes a device back on its next session after expiry, swaps its floor for a pin, and keeps that session's retry idempotent", async () => {
    const stub = room("floor-return")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, hello())
    expect((await ackedOrNacked(a, put(10, { rid: rid("before") }))).t).toBe("op-ack")
    await leave(a)
    await idle(stub, 8)
    expect(await floors(stub)).toEqual({ [floorOf(A.actor_id)]: "0000000000000001" })
    // what the app sends next: its persisted epoch + 1 on a fresh session
    const back = await openV3Socket(stub); await drainSnapshot(back)
    await helloOn(back, hello2())
    // the identical hello again on the same socket is the same session, re-acked
    const again = collectUntil(back, frame => frame.t === "hello-ack")
    back.send(JSON.stringify(hello2()))
    expect(await again).toEqual({ t: "hello-ack", by: A.actor_id, sd: SD_2, vs: stamp(2) })
    expect((await ackedOrNacked(back, put(21, { sd: SD_2, rid: rid("after") }))).t).toBe("op-ack")
    expect(await floors(stub)).toEqual({})
    expect(await runInDurableObject(stub, async (_instance, state) => state.storage.get(`actor:${A.actor_id}`)))
      .toMatchObject({ helloEpoch: "0000000000000002", hello: hello2() })
    await expectExactAccounting(stub)
    // a second socket presenting that live session gets the 4014 it always got
    // (relay.test.ts, same and older epochs), the bound one is left alone
    expect((await tryHello(stub, hello2())).result).toBe(4014)
    expect((await ackedOrNacked(back, put(22, { sd: SD_2, rid: rid("still-bound") }))).t).toBe("op-ack")
    back.close()
  })

  it("holds a device that lost its replay state to the floor, as the pin would, until its recovery epoch clears it", async () => {
    const stub = room("floor-lost-state")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, await signedHello(A, session(0x31), 7))
    expect((await ackedOrNacked(a, put(10, { sd: bytesToBase64url(session(0x31)), rid: rid("before") }))).t).toBe("op-ack")
    await leave(a)
    await idle(stub, 8)
    // a 2.x app with lost state restarts at 1 and climbs one per retry
    expect((await tryHello(stub, await signedHello(A, session(0x32), 1))).result).toBe(4014)
    expect((await tryHello(stub, await signedHello(A, session(0x33), 7))).result).toBe(4014)
    // a 3.0 app restarts at the unix minute (plans/04 s14), well clear of it
    const recovered = await signedHello(A, session(0x34), Math.floor(Date.now() / 60_000))
    expect((await tryHello(stub, recovered)).result).toBe("hello-ack")
  })

  it("keeps the floor through 89 idle days and the 90-day purge takes it with the rest", async () => {
    const stub = await twoSessions("floor-purge")
    await idle(stub, 8)
    await idle(stub, 89)
    expect(await floors(stub)).toEqual({ [floorOf(A.actor_id)]: "0000000000000002" })
    await idle(stub, 91)
    expect(await runInDurableObject(stub, async (_instance, state) => [...(await state.storage.list()).keys()])).toEqual([])
    // the purged room starts over with nothing to hold a hello to, which is
    // why a device back after it has to move to a new join code (ADR-001 §16)
    expect((await tryHello(stub, hello())).result).toBe("hello-ack")
  })

  it("is durable: evicting the Durable Object after expiry changes nothing", async () => {
    const stub = await twoSessions("floor-eviction")
    await idle(stub, 8)
    await evictDurableObject(stub)
    expect((await tryHello(stub, hello())).result).toBe(4014)
    await evictDurableObject(stub)
    expect((await tryHello(stub, await signedHello(A, session(0x41), 3))).result).toBe("hello-ack")
    expect(await floors(stub)).toEqual({})
  })

  it("stores every floor before it drops any pin, so a pass that dies half way loses no epoch and the counts stay exact", async () => {
    const stub = room("floor-crash")
    const other = await genIdentity()
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, hello2())
    expect((await ackedOrNacked(a, put(10, { sd: SD_2, rid: rid("a-put") }))).t).toBe("op-ack")
    const b = await openV3Socket(stub); await drainSnapshot(b)
    await helloOn(b, await genHello(other, 5))
    await leave(a); await leave(b)
    await runInDurableObject(stub, async (instance, state) => {
      await state.storage.put("meta:lastActivity", Date.now() - 8 * DAY)
      await state.storage.setAlarm(Date.now() + 60_000)
      // the pass dies at its first pin delete
      const storage = (instance as any).state.storage
      const original = storage.delete.bind(storage)
      storage.delete = (keys: unknown, ...rest: unknown[]) => {
        const list = (Array.isArray(keys) ? keys : [keys]).map(String)
        return list.some(key => key.startsWith("actor:"))
          ? Promise.reject(new Error("injected pin delete failure"))
          : original(keys, ...rest)
      }
    })
    expect(await runDurableObjectAlarm(stub)).toBe(true)
    const afterFailure = await runInDurableObject(stub, async (instance, state) => {
      delete (instance as any).state.storage.delete
      return {
        pins: (await state.storage.list({ prefix: "actor:" })).size,
        expiring: await state.storage.get("meta:expiring"),
      }
    })
    expect(afterFailure.pins).toBe(2)
    expect(afterFailure.expiring).toBeDefined()
    expect(await floors(stub)).toEqual({
      [floorOf(A.actor_id)]: "0000000000000002", [floorOf(other.actor)]: "0000000000000005",
    })
    // a join in between recounts pins and floors, and A coming back on that
    // socket swaps its floor for a pin without the counts drifting
    const back = await openV3Socket(stub); await drainSnapshot(back)
    await expectExactAccounting(stub)
    await helloOn(back, await signedHello(A, session(0x51), 3))
    expect(await floors(stub)).toEqual({ [floorOf(other.actor)]: "0000000000000005" })
    await expectExactAccounting(stub)
    await leave(back)
    // the retry finishes the job
    await idle(stub, 8)
    await runInDurableObject(stub, async (_instance, state) => {
      expect((await state.storage.list({ prefix: "actor:" })).size).toBe(0)
      expect(await state.storage.get("meta:expiring")).toBeUndefined()
      expect(await state.storage.get("meta:expiredAt")).toBeDefined()
    })
    expect(await floors(stub)).toEqual({
      [floorOf(A.actor_id)]: "0000000000000003", [floorOf(other.actor)]: "0000000000000005",
    })
    await expectExactAccounting(stub)
  })

  it("adds no write to a hello: floors are written by expiry and only the hello that replaces one removes it", async () => {
    const stub = room("floor-writes")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, hello())
    expect((await ackedOrNacked(a, put(1))).t).toBe("op-ack")
    let log = await instrumentStorage(stub)
    const superseded = waitForClose(a)
    const b = await openV3Socket(stub); await drainSnapshot(b)
    await helloOn(b, hello2())
    expect((await superseded)?.code).toBe(4015)
    await restoreStorage(stub)
    expect([...log.puts, ...log.deletes].filter(key => key.startsWith("epoch:"))).toEqual([])
    await leave(b)
    await idle(stub, 8)
    log = await instrumentStorage(stub)
    const c = await openV3Socket(stub); await drainSnapshot(c)
    await helloOn(c, await signedHello(A, session(0x61), 3))
    await restoreStorage(stub)
    console.log(JSON.stringify({ relay_ref_1: "hello-after-expiry-writes", puts: log.puts, deletes: log.deletes }))
    expect(log.puts.filter(key => key.startsWith("epoch:"))).toEqual([])
    expect(log.deletes).toEqual([floorOf(A.actor_id)])
    c.close()
  })

  it("arms nothing of its own, and a later expiry only rewrites the floors that moved", async () => {
    const stub = room("floor-alarms")
    const other = await genIdentity()
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, hello())
    expect((await ackedOrNacked(a, put(1))).t).toBe("op-ack")
    const b = await openV3Socket(stub); await drainSnapshot(b)
    await helloOn(b, await genHello(other, 4))
    await leave(a); await leave(b)
    const last = await idle(stub, 8)
    expect(await runInDurableObject(stub, async (_instance, state) => state.storage.getAlarm()))
      .toBe(last + LIMITS.ROOM_PURGE_TTL_MS)
    expect(await floors(stub)).toEqual({
      [floorOf(A.actor_id)]: "0000000000000001", [floorOf(other.actor)]: "0000000000000004",
    })
    // A comes back for a while, the other device never does
    const back = await openV3Socket(stub); await drainSnapshot(back)
    await helloOn(back, hello2())
    await leave(back)
    const log = await instrumentStorage(stub)
    await idle(stub, 8, true)
    await restoreStorage(stub)
    expect(log.puts.filter(key => key.startsWith("epoch:"))).toEqual([floorOf(A.actor_id)])
    expect(await floors(stub)).toEqual({
      [floorOf(A.actor_id)]: "0000000000000002", [floorOf(other.actor)]: "0000000000000004",
    })
    expect(await runInDurableObject(stub, async (_instance, state) => (await state.storage.list({ prefix: "actor:" })).size)).toBe(0)
    await expectExactAccounting(stub)
  })

  it("counts a floor against the room quota like the pin it replaced, so a returning device still fits a full room", async () => {
    const stub = room("floor-quota")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, hello())
    expect((await ackedOrNacked(a, put(1))).t).toBe("op-ack")
    await leave(a)
    await idle(stub, 8)
    await runInDurableObject(stub, async (_instance, state) => {
      // A's floor is all that's left to count
      expect(await state.storage.get("meta:totalRecords")).toBe(1)
      await state.storage.put("meta:totalRecords", LIMITS.MAX_RECORDS)
    })
    expect((await tryHello(stub, await genHello(await genIdentity()))).result).toBe(4013)
    const back = await openV3Socket(stub); await drainSnapshot(back)
    await helloOn(back, hello2())
    expect(await runInDurableObject(stub, async (_instance, state) => state.storage.get("meta:totalRecords")))
      .toBe(LIMITS.MAX_RECORDS)
    back.close()
  })
})

describe("SP1 item 3: tombstone compaction", () => {
  it("rechecks a returning author's authenticated durable hello between compaction batches", async () => {
    const stub = room("threat-compaction-returning-author")
    const client = await openV3Socket(stub); await drainSnapshot(client)
    try {
      await runInDurableObject(stub, async (instance, state) => {
        const relay = instance as any
        const now = Date.now()
        const entries: Array<[string, unknown]> = [[`actor:${A.actor_id}`, {
          pubkey: A.pubkey_base64url, firstSeen: now - 60 * DAY, lastSeen: now - 40 * DAY,
          helloEpoch: "0000000000000001",
        }]]
        for (let index = 0; index < 65; index += 1) {
          const id = await wireIdFor(`returning-author-${index}`)
          entries.push([`obj:${id}`, {
            id, vs: stamp(index + 1), by: A.actor_id, kind: "del", ct: sealed(),
            deleted: true, pub: A.pubkey_base64url, sd: SD,
          }], [`tomb:${id}`, now - 35 * DAY])
        }
        await putBatched(state, entries)
        const exact = await recomputeAccounting(state)
        await state.storage.put({ "meta:totalRecords": exact.total, "meta:bytes": exact.bytes })
        const socket = state.getWebSockets()[0]
        const returningHello = await signedHello(A, base64urlBytes(SD_2), 2)
        const transaction = state.storage.transaction.bind(state.storage)
        let returnAfterFirstBatch = true
        ;(state.storage as any).transaction = async (callback: any) => {
          const result = await transaction(callback)
          if (returnAfterFirstBatch) {
            returnAfterFirstBatch = false
            // A real signature, actor recomputation, pin transaction and socket
            // binding complete after batch one, before batch two is admitted.
            expect(await relay.handleHello(socket, returningHello)).toBe(true)
          }
          return result
        }
        let nextDue: number | null
        try { nextDue = await relay.compactTombstones(now) }
        finally { delete (state.storage as any).transaction }
        expect(socket.deserializeAttachment().hello).toEqual(returningHello)
        const pin = await state.storage.get<any>(`actor:${A.actor_id}`)
        expect(pin.helloEpoch).toBe("0000000000000002")
        expect(pin.lastSeen).toBeGreaterThan(now - DAY)
        expect((await state.storage.list({ prefix: "obj:" })).size).toBe(1)
        expect((await state.storage.list({ prefix: "tomb:" })).size).toBe(1)
        const remaining = await recomputeAccounting(state)
        expect(await state.storage.get("meta:totalRecords")).toBe(remaining.total)
        expect(await state.storage.get("meta:bytes")).toBe(remaining.bytes)
        expect(await state.storage.get("meta:horizonSeq")).toBe(await state.storage.get("meta:seq"))
        expect(nextDue!).toBeGreaterThan(now + 29 * DAY)
      })
    } finally { client.close() }
  })

  it("keeps compaction time in relay-only rows, never in the snapshotted record", async () => {
    const stub = room("sp1-tomb-rows")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, hello())
    expect((await ackedOrNacked(a, put(1))).t).toBe("op-ack")
    expect((await ackedOrNacked(a, del(2, { rid: rid("tomb-del") }))).t).toBe("op-ack")
    await runInDurableObject(stub, async (_instance, state) => {
      const record = await state.storage.get<any>(`obj:${WIRE_ID}`)
      expect(Object.keys(record).sort()).toEqual(["by", "ct", "deleted", "id", "kind", "pub", "sd", "vs"])
      expect(typeof await state.storage.get(`tomb:${WIRE_ID}`)).toBe("number")
    })
    const late = await openV3Socket(stub)
    const snapshot = await drainSnapshot(late)
    expect(Object.keys(snapshot.items[0]).sort()).toEqual(["by", "ct", "deleted", "id", "kind", "pub", "sd", "vs"])
    expect((await ackedOrNacked(a, put(3, { rid: rid("tomb-recreate") }))).t).toBe("op-ack")
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.get(`tomb:${WIRE_ID}`)).toBeUndefined()
    })
    a.close(); late.close()
  })

  it("frees MAX_RECORDS churn from departed authors, keeps active authors' tombstones, and stays exact", { timeout: 120_000 }, async () => {
    const stub = room("sp1-compaction-churn")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, hello())
    const departed = await genIdentity()
    const now = Date.now()
    const departedTombs = LIMITS.MAX_RECORDS - 2 - 8
    const activeTombIds: string[] = []
    await runInDurableObject(stub, async (_instance, state) => {
      const entries: Array<[string, unknown]> = []
      entries.push([`actor:${departed.actor}`, {
        pubkey: departed.pub, firstSeen: now - 60 * DAY, lastSeen: now - 40 * DAY,
        helloEpoch: "0000000000000001",
      }])
      for (let index = 0; index < departedTombs; index += 1) {
        const id = await wireIdFor(`departed-${index}`)
        entries.push([`obj:${id}`, {
          id, vs: stamp(index + 1, departed.actor), by: departed.actor, kind: "del", ct: sealed(),
          deleted: true, pub: departed.pub, sd: bytesToBase64url(departed.sd),
        }])
        entries.push([`tomb:${id}`, now - 35 * DAY])
      }
      for (let index = 0; index < 8; index += 1) {
        const id = await wireIdFor(`active-${index}`)
        activeTombIds.push(id)
        entries.push([`obj:${id}`, {
          id, vs: stamp(index + 1), by: A.actor_id, kind: "del", ct: sealed(),
          deleted: true, pub: A.pubkey_base64url, sd: SD,
        }])
        entries.push([`tomb:${id}`, now - 35 * DAY])
      }
      await putBatched(state, entries)
      const exact = await recomputeAccounting(state)
      expect(exact.total).toBe(LIMITS.MAX_RECORDS)
      await state.storage.put({ "meta:totalRecords": exact.total, "meta:bytes": exact.bytes })
      await state.storage.setAlarm(Date.now() + 60_000)
    })
    const fresh = await wireIdFor("sp1-after-compaction")
    expect(await ackedOrNacked(a, put(9_999, { id: fresh, rid: rid("quota-before") })))
      .toMatchObject({ t: "op-nack", code: "quota" })
    const seqBefore = await runInDurableObject(stub, async (_instance, state) => (await state.storage.get<number>("meta:seq")) ?? 0)

    expect(await runDurableObjectAlarm(stub)).toBe(true)
    await runInDurableObject(stub, async (_instance, state) => {
      const objects = await state.storage.list<any>({ prefix: "obj:" })
      expect([...objects.values()].filter(record => record.by === departed.actor)).toHaveLength(0)
      expect([...objects.keys()].map(key => key.slice(4)).sort()).toEqual([...activeTombIds].sort())
      expect((await state.storage.list({ prefix: "tomb:" })).size).toBe(8)
      const exact = await recomputeAccounting(state)
      expect(await state.storage.get("meta:totalRecords")).toBe(exact.total)
      expect(await state.storage.get("meta:bytes")).toBe(exact.bytes)
      const seq = (await state.storage.get<number>("meta:seq"))!
      expect(seq).toBeGreaterThan(seqBefore)
      expect(await state.storage.get("meta:horizonSeq")).toBe(seq)
    })
    expect(await ackedOrNacked(a, put(9_999, { id: fresh, rid: rid("quota-after") })))
      .toMatchObject({ t: "op-ack" })
    const late = await openV3Socket(stub)
    const snapshot = await drainSnapshot(late, 10_000)
    expect(snapshot.items).toHaveLength(9)
    a.close(); late.close()
  })

  it("keeps tombstones of an author whose socket has been open the whole TTL", async () => {
    const stub = room("sp1-compaction-connected-author")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, hello())
    expect((await ackedOrNacked(a, put(1))).t).toBe("op-ack")
    expect((await ackedOrNacked(a, del(2, { rid: rid("long-lived-del") }))).t).toBe("op-ack")
    // hello happened 40 days ago and the socket never dropped since
    await runInDurableObject(stub, async (_instance, state) => {
      const pin = await state.storage.get<any>(`actor:${A.actor_id}`)
      await state.storage.put(`actor:${A.actor_id}`, { ...pin, lastSeen: Date.now() - 40 * DAY })
      await state.storage.put(`tomb:${WIRE_ID}`, Date.now() - 35 * DAY)
      await state.storage.setAlarm(Date.now() + 60_000)
    })
    expect(await runDurableObjectAlarm(stub)).toBe(true)
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.get(`obj:${WIRE_ID}`)).toMatchObject({ deleted: true })
    })
    a.close()
  })

  // A deletes, last says hello lastSeenDaysAgo, the room idles and expires
  // (dropping A's pin), then (withPeer) another device uses the room and the
  // delete ages past the TTL. returns whether A's tombstone survived the next
  // alarm, the alarm it left, and the stored dropped-pin bound
  async function departedAfterExpiry(name: string, lastSeenDaysAgo: number, withPeer = true): Promise<{
    kept: boolean; alarm: number | null; droppedSeen: number | undefined
  }> {
    const stub = room(name)
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, hello())
    expect((await ackedOrNacked(a, put(1))).t).toBe("op-ack")
    expect((await ackedOrNacked(a, del(2, { rid: rid("departed-del") }))).t).toBe("op-ack")
    const closed = waitForClose(a); a.close(1000, "bye"); await closed
    await sleep(50)
    await runInDurableObject(stub, async (_instance, state) => {
      const pin = await state.storage.get<any>(`actor:${A.actor_id}`)
      await state.storage.put(`actor:${A.actor_id}`, { ...pin, lastSeen: Date.now() - lastSeenDaysAgo * DAY })
      await state.storage.put("meta:lastActivity", Date.now() - 8 * DAY)
      await state.storage.setAlarm(Date.now() + 60_000)
    })
    expect(await runDurableObjectAlarm(stub)).toBe(true)
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.get(`actor:${A.actor_id}`)).toBeUndefined()
      expect(await state.storage.get(`obj:${WIRE_ID}`)).toMatchObject({ deleted: true })
    })
    let b: WebSocket | undefined
    if (withPeer) {
      b = await openV3Socket(stub); await drainSnapshot(b)
      await helloOn(b, await genHello(await genIdentity()))
    }
    await runInDurableObject(stub, async (_instance, state) => {
      await state.storage.put(`tomb:${WIRE_ID}`, Date.now() - 40 * DAY)
      await state.storage.setAlarm(Date.now() + 60_000)
    })
    expect(await runDurableObjectAlarm(stub)).toBe(true)
    const result = await runInDurableObject(stub, async (_instance, state) => {
      const exact = await recomputeAccounting(state)
      expect(await state.storage.get("meta:totalRecords")).toBe(exact.total)
      expect(await state.storage.get("meta:bytes")).toBe(exact.bytes)
      return {
        kept: (await state.storage.get(`obj:${WIRE_ID}`)) !== undefined,
        alarm: await state.storage.getAlarm(),
        droppedSeen: await state.storage.get<number>("meta:droppedPinsSeen"),
      }
    })
    b?.close()
    return result
  }

  it("compacts a departed author's tombstone after idle expiry dropped its pin, while others keep using the room", async () => {
    const result = await departedAfterExpiry("sp1-compaction-after-expiry", 41)
    console.log(JSON.stringify({ sp1_item3: "departed-after-expiry", ...result }))
    expect(result.kept).toBe(false)
  })

  it("keeps that tombstone while the devices dropped at expiry were seen within the TTL", async () => {
    const busy = await departedAfterExpiry("sp1-compaction-after-expiry-recent", 10)
    expect(busy.kept).toBe(true)
    // with nobody connected there's no daily pass to hide behind: the alarm
    // has to be exactly when the newest dropped pin turns a TTL old, well
    // before the purge, or an already-expired room never comes back for it
    const idle = await departedAfterExpiry("sp1-compaction-after-expiry-recent-idle", 10, false)
    console.log(JSON.stringify({ sp1_item3: "blocked-tomb-alarm", ...idle, now: Date.now() }))
    expect(idle.kept).toBe(true)
    expect(idle.droppedSeen).toBeDefined()
    expect(idle.alarm).toBe(idle.droppedSeen! + LIMITS.TOMBSTONE_TTL_MS)
    expect(idle.alarm!).toBeGreaterThan(Date.now() + LIMITS.MAINTENANCE_INTERVAL_MS)
  })

  it("a pass that dropped pins and then failed still bounds unpinned authors by their real last hello", async () => {
    const stub = room("sp1-compaction-after-failed-expiry")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, hello())
    expect((await ackedOrNacked(a, put(1))).t).toBe("op-ack")
    expect((await ackedOrNacked(a, del(2, { rid: rid("recent-del") }))).t).toBe("op-ack")
    const closed = waitForClose(a); a.close(1000, "bye"); await closed
    await sleep(50)
    // A said hello 5 days ago, the room last expired 60 days ago
    await runInDurableObject(stub, async (instance, state) => {
      const pin = await state.storage.get<any>(`actor:${A.actor_id}`)
      await state.storage.put({
        [`actor:${A.actor_id}`]: { ...pin, lastSeen: Date.now() - 5 * DAY },
        [`tomb:${WIRE_ID}`]: Date.now() - 40 * DAY,
        "meta:expiredAt": Date.now() - 60 * DAY,
        "meta:lastActivity": Date.now() - 8 * DAY,
      })
      await state.storage.setAlarm(Date.now() + 60_000)
      // the closing write of the pass fails after every delete went through
      const storage = (instance as any).state.storage
      const original = storage.transaction.bind(storage)
      storage.transaction = (callback: (txn: any) => Promise<unknown>) => original(async (txn: any) => {
        const put = txn.put.bind(txn)
        txn.put = (arg: unknown, ...rest: unknown[]) => arg && typeof arg === "object" && "meta:expiredAt" in arg
          ? Promise.reject(new Error("injected final write failure"))
          : put(arg, ...rest)
        return callback(txn)
      })
    })
    expect(await runDurableObjectAlarm(stub)).toBe(true)
    await runInDurableObject(stub, async (instance, state) => {
      delete (instance as any).state.storage.transaction
      expect(await state.storage.get(`actor:${A.actor_id}`)).toBeUndefined()
      expect(await state.storage.get<number>("meta:expiredAt")).toBeLessThan(Date.now() - 59 * DAY)
      // A was around 5 days ago, its tombstone has to stay
      expect(await state.storage.get(`obj:${WIRE_ID}`)).toMatchObject({ deleted: true })
      expect(await state.storage.getAlarm()).not.toBeNull()
    })
  })

  it("keeps meta:droppedPinsSeen a running maximum when a pass dies after dropping the newest pin", async () => {
    const stub = room("sp1-dropped-seen-max")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    const closed = waitForClose(a); a.close(1000, "bye"); await closed
    await sleep(50)
    const now = Date.now()
    const newest = now - 5 * DAY
    // 130 pins, so dropping them takes two 128-key deletes. the newest hello
    // sorts first and goes out with the first chunk, then the second one fails
    const actor = (index: number): string => `a${index.toString().padStart(3, "0")}`.padEnd(43, "x")
    const author = actor(0)
    const tombId = await wireIdFor("sp1-dropped-seen-max")
    await runInDurableObject(stub, async (instance, state) => {
      const entries: Array<[string, unknown]> = []
      for (let index = 0; index < 130; index += 1) {
        entries.push([`actor:${actor(index)}`, {
          pubkey: A.pubkey_base64url, firstSeen: now - 60 * DAY,
          lastSeen: index === 0 ? newest : now - 50 * DAY, helloEpoch: "0000000000000001",
        }])
      }
      entries.push([`obj:${tombId}`, {
        id: tombId, vs: stamp(2, author), by: author, kind: "del", ct: sealed(),
        deleted: true, pub: A.pubkey_base64url, sd: SD,
      }])
      entries.push([`tomb:${tombId}`, now - 40 * DAY])
      await putBatched(state, entries)
      await state.storage.put({ "meta:seq": 2, "meta:lastActivity": now - 8 * DAY })
      await state.storage.setAlarm(now + 60_000)
      const storage = (instance as any).state.storage
      const original = storage.delete.bind(storage)
      storage.delete = (keys: unknown, ...rest: unknown[]) => {
        const list = (Array.isArray(keys) ? keys : [keys]).map(String)
        return list.some(key => key.startsWith("actor:")) && !list.includes(`actor:${author}`)
          ? Promise.reject(new Error("injected second chunk failure"))
          : original(keys, ...rest)
      }
    })
    expect(await runDurableObjectAlarm(stub)).toBe(true)
    const afterFailure = await runInDurableObject(stub, async (instance, state) => {
      delete (instance as any).state.storage.delete
      return {
        authorPin: await state.storage.get(`actor:${author}`),
        pinsLeft: (await state.storage.list({ prefix: "actor:" })).size,
        seen: await state.storage.get<number>("meta:droppedPinsSeen"),
        alarm: await state.storage.getAlarm(),
      }
    })
    expect(afterFailure.authorPin).toBeUndefined()
    expect(afterFailure.pinsLeft).toBe(2)
    expect(afterFailure.seen).toBe(newest)
    expect(afterFailure.alarm).not.toBeNull()
    // the retry only sees the two 50 day old pins. the bound can't drop to them
    expect(await runDurableObjectAlarm(stub)).toBe(true)
    await runInDurableObject(stub, async (_instance, state) => {
      expect((await state.storage.list({ prefix: "actor:" })).size).toBe(0)
      expect(await state.storage.get("meta:expiredAt")).toBeDefined()
      expect(await state.storage.get<number>("meta:droppedPinsSeen")).toBe(newest)
      // its author said hello 5 days ago, so the 40 day old tombstone stays
      expect(await state.storage.get(`obj:${tombId}`)).toMatchObject({ deleted: true })
    })
  })

  it("indexes legacy tombstones once and compacts idle v2 rooms by room activity", async () => {
    const stub = room("sp1-compaction-legacy")
    const a = await openSocket(stub); await drainSnapshot(a)
    const acked = collectUntil(a, frame => frame.t === "op-ack" && frame.rid === rid("legacy-del"))
    a.send(JSON.stringify({ t: "put", id: "legacy", v: 1, by: "a", kind: "waypoint", ct: sealed(), rid: rid("legacy-put") }))
    a.send(JSON.stringify({ t: "del", id: "legacy", v: 2, by: "a", ct: sealed(), rid: rid("legacy-del") }))
    expect(await acked).not.toBeNull()
    const closed = waitForClose(a); a.close(1000, "bye"); await closed
    await sleep(50)
    // simulate a room written before compaction rows existed
    await runInDurableObject(stub, async (_instance, state) => {
      await state.storage.delete(["tomb:legacy", "meta:tombIndexAt"])
      await state.storage.put("meta:lastActivity", Date.now() - 8 * DAY)
      await state.storage.setAlarm(Date.now() + 60_000)
    })
    expect(await runDurableObjectAlarm(stub)).toBe(true)
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.get("obj:legacy")).toMatchObject({ deleted: true })
      expect(typeof await state.storage.get("tomb:legacy")).toBe("number")
      // a month later the room is still idle, so the unknown author is too
      await state.storage.put("tomb:legacy", Date.now() - 31 * DAY)
      await state.storage.put("meta:lastActivity", Date.now() - 31 * DAY)
      await state.storage.put("meta:tombIndexAt", Date.now() - 31 * DAY)
      await state.storage.setAlarm(Date.now() + 60_000)
    })
    expect(await runDurableObjectAlarm(stub)).toBe(true)
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.get("obj:legacy")).toBeUndefined()
      expect(await state.storage.get("tomb:legacy")).toBeUndefined()
      expect(await state.storage.get("meta:totalRecords")).toBe(0)
      expect(await state.storage.get("meta:bytes")).toBe(0)
      expect(await state.storage.get("meta:auth")).toBeDefined()
    })
  })
})

describe("SP1 item 4: failure isolation", () => {
  it("a throw inside the join snapshot fails only the joining socket", async () => {
    const stub = room("sp1-snapshot-throw")
    const bystander = await openV3Socket(stub); await drainSnapshot(bystander)
    const bystanderClosed = waitForClose(bystander, 1_500)
    await runInDurableObject(stub, instance => {
      const relay = instance as any
      const original = relay.sendSnapshot
      relay.sendSnapshot = async function (): Promise<void> {
        relay.sendSnapshot = original
        throw new Error("injected snapshot storage failure")
      }
    })
    let status: number | string
    try {
      status = (await stub.fetch(v3RoomUrl(), { headers: v3Headers() })).status
    } catch (error) {
      status = `threw: ${String(error).slice(0, 120)}`
    }
    console.log(JSON.stringify({ sp1_item4: "snapshot-throw", joinerStatus: status }))
    expect(status).toBe(503)
    expect(await bystanderClosed).toBeNull()
    const pong = collectUntil(bystander, frame => frame.t === "pong")
    bystander.send(JSON.stringify({ t: "ping" }))
    expect(await pong).toEqual({ t: "pong" })
    const next = await openV3Socket(stub); await drainSnapshot(next)
    bystander.close(); next.close()
  })

  it("a throw inside the accounting migration fails only the joining request", async () => {
    const stub = room("sp1-accounting-throw")
    const bystander = await openV3Socket(stub); await drainSnapshot(bystander)
    const bystanderClosed = waitForClose(bystander, 1_500)
    await runInDurableObject(stub, instance => {
      const relay = instance as any
      const original = relay.ensureAccounting
      relay.ensureAccounting = async function (): Promise<void> {
        relay.ensureAccounting = original
        throw new Error("injected accounting failure")
      }
    })
    let status: number | string
    try {
      status = (await stub.fetch(v3RoomUrl(), { headers: v3Headers() })).status
    } catch (error) {
      status = `threw: ${String(error).slice(0, 120)}`
    }
    expect(status).toBe(503)
    expect(await bystanderClosed).toBeNull()
    bystander.close()
  })

  it("S1-04: a v2 member list plus a CT_MAX first record gets its own page instead of resetting the room", { timeout: 60_000 }, async () => {
    const stub = room("sp1-v2-members-ceiling")
    const bystander = await openSocket(stub); await drainSnapshot(bystander)
    const bystanderClosed = waitForClose(bystander, 2_000)
    const members: WebSocket[] = []
    for (let index = 0; index < 25; index += 1) {
      const member = await openSocket(stub); await drainSnapshot(member, 10_000)
      member.send(JSON.stringify({ t: "loc", clientId: `member-${index}`.padEnd(100, "x"), ct: sealed(6_144) }))
      members.push(member)
    }
    const stored = collectUntil(bystander, frame => frame.t === "put" && frame.id === "!first", 5_000)
    members[0].send(JSON.stringify({ t: "put", id: "!first", v: 1, by: "a", kind: "waypoint", ct: sealed(525_000) }))
    expect(await stored).not.toBeNull()
    await sleep(200)
    let status: number | string
    let joiner: WebSocket | undefined
    try {
      const response = await stub.fetch(roomUrl(), { headers: headers() })
      status = response.status
      joiner = response.webSocket ?? undefined
    } catch (error) {
      status = `threw: ${String(error).slice(0, 120)}`
    }
    console.log(JSON.stringify({ sp1_item4: "v2-members", joinerStatus: status }))
    expect(status).toBe(101)
    joiner!.accept()
    const snapshot = await drainSnapshot(joiner!, 10_000)
    expect(snapshot.items.map(item => item.id)).toEqual(["!first"])
    const pages = snapshot.frames.filter(frame => frame.t === "snapshot")
    expect(pages[0].members).toHaveLength(25)
    expect(pages.slice(1).every(page => page.members === undefined)).toBe(true)
    expect(Math.max(...snapshot.lengths)).toBeLessThanOrEqual(LIMITS.SNAPSHOT_FRAME_BYTES)
    expect(await bystanderClosed).toBeNull()
    for (const member of members) member.close()
    bystander.close(); joiner!.close()
  })
})

describe("SP1 item 5 + 9: linear, memory-bounded snapshot build", () => {
  it("builds a 10k-record v3 snapshot quickly with canonical byte-bounded pages", { timeout: 120_000 }, async () => {
    const stub = room("sp1-snapshot-10k")
    const warm = await openV3Socket(stub); await drainSnapshot(warm); warm.close()
    const total = 10_000
    await runInDurableObject(stub, async (_instance, state) => {
      const entries: Array<[string, unknown]> = []
      for (let index = 0; index < total; index += 1) {
        const id = `r${index.toString(36)}`.padEnd(43, "A").slice(0, 43)
        entries.push([`obj:${id}`, {
          id, vs: stamp(index + 1), by: A.actor_id, kind: "waypoint", ct: sealed(750),
          deleted: false, pub: A.pubkey_base64url, sd: SD,
        }])
      }
      await putBatched(state, entries)
    })
    const started = Date.now()
    const ws = await openV3Socket(stub)
    const buildMs = Date.now() - started
    const texts = await drainRaw(ws)
    const pages = texts.filter(text => JSON.parse(text).t === "snapshot")
    const items = pages.flatMap(text => JSON.parse(text).items)
    console.log(JSON.stringify({
      sp1_item5: "snapshot-10k", records: items.length, buildMs, pages: pages.length,
      maxPageBytes: Math.max(...pages.map(utf8)), totalBytes: texts.reduce((sum, text) => sum + utf8(text), 0),
    }))
    expect(items).toHaveLength(total)
    for (const text of pages) {
      expect(utf8(text)).toBeLessThanOrEqual(LIMITS.SNAPSHOT_FRAME_BYTES)
      expect(JSON.stringify(JSON.parse(text))).toBe(text)
    }
    expect(buildMs).toBeLessThan(400)
    ws.close()
  })

  it("bounds how much record data one storage read pulls in while snapshotting", { timeout: 120_000 }, async () => {
    const stub = room("sp1-snapshot-read-bound")
    const warm = await openV3Socket(stub); await drainSnapshot(warm); warm.close()
    const recordChars = 500_000
    await runInDurableObject(stub, async (_instance, state) => {
      const entries: Array<[string, unknown]> = []
      for (let index = 0; index < 24; index += 1) {
        const id = `big${index.toString().padStart(2, "0")}`.padEnd(43, "B")
        entries.push([`obj:${id}`, {
          id, vs: stamp(index + 1), by: A.actor_id, kind: "drawing", ct: sealed(recordChars * 3 / 4),
          deleted: false, pub: A.pubkey_base64url, sd: SD,
        }])
      }
      await putBatched(state, entries)
    })
    const log = await instrumentStorage(stub)
    const ws = await openV3Socket(stub)
    const snapshot = await drainSnapshot(ws, 60_000)
    await restoreStorage(stub)
    const reads = log.lists.filter(read => read.prefix === "obj:")
    const maxChars = Math.max(...reads.map(read => read.valueChars))
    console.log(JSON.stringify({ sp1_item9: "read-bound", reads: reads.length, maxCharsPerRead: maxChars }))
    expect(snapshot.items).toHaveLength(24)
    expect(maxChars).toBeLessThanOrEqual(LIMITS.STORAGE_READ_BUDGET_BYTES + recordChars + 1_000)
    ws.close()
  })

  it("only adapts reads to sizes it has seen: small records sorting first can pull most of the byte quota in one read", { timeout: 120_000 }, async () => {
    const stub = room("sp1-snapshot-read-mixed")
    const warm = await openV3Socket(stub); await drainSnapshot(warm); warm.close()
    const bigChars = 666_668
    await runInDurableObject(stub, async (_instance, state) => {
      const small: Record<string, unknown> = {}
      for (let index = 0; index < LIMITS.STORAGE_FIRST_PAGE_SIZE; index += 1) {
        const id = `AAAA${index}`.padEnd(43, "A")
        small[`obj:${id}`] = { id, vs: stamp(index + 1), by: A.actor_id, kind: "waypoint", ct: sealed(), deleted: false, pub: A.pubkey_base64url, sd: SD }
      }
      await state.storage.put(small)
      for (let index = 0; index < 60; index += 1) {
        const id = `zz${index.toString().padStart(2, "0")}`.padEnd(43, "z")
        await state.storage.put(`obj:${id}`, {
          id, vs: stamp(100 + index), by: A.actor_id, kind: "drawing", ct: sealed(bigChars * 3 / 4),
          deleted: false, pub: A.pubkey_base64url, sd: SD,
        })
      }
    })
    const log = await instrumentStorage(stub)
    const ws = await openV3Socket(stub)
    const snapshot = await drainSnapshot(ws, 60_000)
    await restoreStorage(stub)
    const reads = log.lists.filter(read => read.prefix === "obj:")
    const maxChars = Math.max(...reads.map(read => read.valueChars))
    console.log(JSON.stringify({ sp1_item9: "read-mixed", reads: reads.map(read => [read.limit, read.size, read.valueChars]) }))
    expect(snapshot.items).toHaveLength(64)
    // the documented worst case (ADR-001 §15): no better than the byte quota,
    // same as the old fixed 100-record read
    expect(maxChars).toBeGreaterThan(LIMITS.STORAGE_READ_BUDGET_BYTES * 4)
    expect(maxChars).toBeLessThanOrEqual(LIMITS.MAX_STORED_BYTES)
    ws.close()
  })
})

describe("SP1 item 6: admission and liveness", () => {
  it("caps a room at MAX_CONNECTIONS, then frees slots held past HELLO_DEADLINE_MS without hello", { timeout: 60_000 }, async () => {
    const stub = room("sp1-hello-deadline")
    const sockets: WebSocket[] = []
    for (let index = 0; index < LIMITS.MAX_CONNECTIONS; index += 1) {
      const ws = await openV3Socket(stub); await drainSnapshot(ws)
      sockets.push(ws)
    }
    const refused = await stub.fetch(v3RoomUrl(), { headers: v3Headers() })
    expect(refused.status).toBe(503)
    expect(await refused.text()).toBe("Room full")

    // age every socket past the deadline, the oldest one the most
    await runInDurableObject(stub, (_instance, state) => {
      state.getWebSockets().forEach((ws, index) => {
        const attachment = ws.deserializeAttachment() as any
        ws.serializeAttachment({ ...attachment, acceptedAt: Date.now() - LIMITS.HELLO_DEADLINE_MS - 10_000 + index })
      })
    })
    const closes = sockets.map(ws => waitForClose(ws, 1_000))
    const admitted = await stub.fetch(v3RoomUrl(), { headers: v3Headers() })
    expect(admitted.status).toBe(101)
    admitted.webSocket!.accept()
    const codes = (await Promise.all(closes)).map(event => event?.code ?? null)
    expect(codes.filter(code => code === 1013)).toHaveLength(1)
    expect(codes.filter(code => code !== null && code !== 1013)).toHaveLength(0)
    for (const ws of sockets) ws.close()
    admitted.webSocket!.close()
  })

  it("does not count server-closed CLOSING sockets against the cap", { timeout: 60_000 }, async () => {
    const stub = room("sp1-closing-slots")
    const sockets: WebSocket[] = []
    for (let index = 0; index < LIMITS.MAX_CONNECTIONS; index += 1) {
      const ws = await openV3Socket(stub); await drainSnapshot(ws)
      sockets.push(ws)
    }
    // the relay closes 63 of them and the clients never echo the close
    const states = await runInDurableObject(stub, (_instance, state) => {
      state.getWebSockets().slice(1).forEach(ws => ws.close(4008, "test close"))
      return state.getWebSockets().map(ws => ws.readyState)
    })
    console.log(JSON.stringify({ sp1_item6: "closing", readyStates: states.reduce((acc: Record<number, number>, value) => ({ ...acc, [value]: (acc[value] ?? 0) + 1 }), {}) }))
    const admitted = await stub.fetch(v3RoomUrl(), { headers: v3Headers() })
    expect(admitted.status).toBe(101)
    admitted.webSocket!.accept()
    admitted.webSocket!.close()
    for (const ws of sockets) ws.close()
  })

  it("refuses upgrades once accepted sockets, open or closing, reach MAX_ACCEPTED_SOCKETS", { timeout: 120_000 }, async () => {
    const stub = room("sp1-accepted-ceiling")
    const held: WebSocket[] = []
    const open = async (count: number): Promise<void> => {
      for (let index = 0; index < count; index += 1) {
        const ws = await openV3Socket(stub); await drainSnapshot(ws)
        held.push(ws)
      }
    }
    const relayClose = async (count: number): Promise<number> => runInDurableObject(stub, (_instance, state) => {
      state.getWebSockets().filter(ws => ws.readyState === 1).slice(0, count).forEach(ws => ws.close(4011, "invalid actor proof"))
      return state.getWebSockets().length
    })
    await open(LIMITS.MAX_CONNECTIONS)
    await relayClose(LIMITS.MAX_CONNECTIONS)
    await open(LIMITS.MAX_CONNECTIONS - 1)
    await relayClose(1)
    await open(1)
    const counts = await runInDurableObject(stub, (_instance, state) => ({
      accepted: state.getWebSockets().length,
      open: state.getWebSockets().filter(ws => ws.readyState === 1).length,
    }))
    expect(counts).toEqual({ accepted: LIMITS.MAX_ACCEPTED_SOCKETS, open: LIMITS.MAX_CONNECTIONS - 1 })
    const refused = await stub.fetch(v3RoomUrl(), { headers: v3Headers() })
    console.log(JSON.stringify({ sp1_item6: "accepted-ceiling", ...counts, status: refused.status }))
    expect(refused.status).toBe(503)
    expect(await refused.text()).toBe("Room full")
    for (const ws of held) ws.close()
  })

  it("records the snapshot bytes each socket was sent", async () => {
    const stub = room("sp1-snapshot-bytes")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, hello())
    expect((await ackedOrNacked(a, put(1, { ct: sealed(3_000) }))).t).toBe("op-ack")
    const closed = waitForClose(a); a.close(1000, "bye"); await closed
    const b = await openV3Socket(stub)
    const texts = await drainRaw(b, 5_000)
    const sentBytes = texts.reduce((sum, text) => sum + utf8(text), 0)
    const recorded = await runInDurableObject(stub, (_instance, state) =>
      state.getWebSockets().filter(ws => ws.readyState === 1).map(ws => (ws.deserializeAttachment() as any).snapshotBytes))
    expect(recorded).toEqual([sentBytes])
    b.close()
  })

  it("gives a pre-hello socket extra deadline for the snapshot it is still draining", { timeout: 60_000 }, async () => {
    const stub = room("sp1-hello-deadline-scaled")
    const sockets: WebSocket[] = []
    for (let index = 0; index < LIMITS.MAX_CONNECTIONS; index += 1) {
      const ws = await openV3Socket(stub); await drainSnapshot(ws)
      sockets.push(ws)
    }
    // every socket is 65 s old. all but one were sent a 2 MB snapshot (20 s
    // more at the slow-link rate) and are older than the small one
    const smallIndex = 7
    await runInDurableObject(stub, (_instance, state) => {
      state.getWebSockets().forEach((ws, index) => {
        const attachment = ws.deserializeAttachment() as any
        const small = index === smallIndex
        ws.serializeAttachment({
          ...attachment, testTag: small ? "small" : `big-${index}`,
          acceptedAt: Date.now() - LIMITS.HELLO_DEADLINE_MS - 5_000 - (small ? 0 : 1_000 + index),
          snapshotBytes: small ? 200 : 2_000_000,
        })
      })
    })
    const admitted = await stub.fetch(v3RoomUrl(), { headers: v3Headers() })
    expect(admitted.status).toBe(101)
    admitted.webSocket!.accept()
    const evicted = await runInDurableObject(stub, (_instance, state) =>
      state.getWebSockets().filter(ws => ws.readyState !== 1).map(ws => (ws.deserializeAttachment() as any).testTag))
    console.log(JSON.stringify({ sp1_item6: "deadline-scaled", evicted }))
    expect(evicted).toEqual(["small"])
    for (const ws of sockets) ws.close()
    admitted.webSocket!.close()
  })

  it("announces a fenced session the relay closed as a transient leave instead of restoring it when the replacement fails", async () => {
    const stub = room("sp1-restore-closed-fenced")
    const peer = await openV3Socket(stub); await drainSnapshot(peer)
    const old = await openV3Socket(stub); await drainSnapshot(old)
    const seen = collectUntil(peer, frame => frame.t === "hello")
    await helloOn(old, hello()); await seen
    const replacement = await openV3Socket(stub); await drainSnapshot(replacement)
    await runInDurableObject(stub, instance => {
      ;(instance as any).helloHandshakeDelayMsForTests = 250
      ;(instance as any).failNextActorRegisterForTests = true
    })
    const peerFrames: any[] = []
    peer.addEventListener("message", event => peerFrames.push(JSON.parse(event.data as string)))
    const fenced = collectUntil(replacement, frame => frame.t === "test-fence-ready")
    const replacementClosed = waitForClose(replacement)
    replacement.send(JSON.stringify(hello2()))
    expect(await fenced).toEqual({ t: "test-fence-ready" })
    // the relay drops the old socket while it is fenced, client never echoes
    await runInDurableObject(stub, (instance, state) => {
      const ws = state.getWebSockets().find(socket => (socket.deserializeAttachment() as any)?.hello?.sd === SD)!
      ;(instance as any).closeSocket(ws, 4008, "rate limit")
    })
    expect((await replacementClosed)?.code).toBe(1011)
    await sleep(100)
    console.log(JSON.stringify({ sp1_item6: "restore-closed-fenced", peerFrames }))
    expect(peerFrames).toEqual([{ t: "leave", by: A.actor_id, sd: SD, transient: true }])
    peer.close(); old.close()
  })

  it("a hello still registering when the relay closes its socket binds nothing and retires no other session", async () => {
    const stub = room("sp1-hello-inflight-closed")
    const peer = await openV3Socket(stub); await drainSnapshot(peer)
    await tagNewest(stub, "peer")
    const old = await openV3Socket(stub); await drainSnapshot(old)
    await tagNewest(stub, "old")
    const seen = collectUntil(peer, frame => frame.t === "hello")
    await helloOn(old, hello()); await seen
    const replacement = await openV3Socket(stub); await drainSnapshot(replacement)
    await tagNewest(stub, "replacement")
    await runInDurableObject(stub, instance => { (instance as any).helloHandshakeDelayMsForTests = 250 })
    const peerFrames: any[] = []
    peer.addEventListener("message", event => peerFrames.push(JSON.parse(event.data as string)))
    const oldClosed = waitForClose(old, 800)
    const fenced = collectUntil(replacement, frame => frame.t === "test-fence-ready")
    const ack = collectUntil(replacement, frame => frame.t === "hello-ack", 800)
    replacement.send(JSON.stringify(hello2()))
    expect(await fenced).toEqual({ t: "test-fence-ready" })
    await runInDurableObject(stub, (instance, state) => {
      const ws = state.getWebSockets().find(socket => (socket.deserializeAttachment() as any)?.testTag === "replacement")!
      ;(instance as any).closeSocket(ws, 1013, "hello deadline")
    })
    expect(await ack).toBeNull()
    expect(await oldClosed).toBeNull()
    console.log(JSON.stringify({ sp1_item6: "hello-inflight-closed", peerFrames }))
    expect(peerFrames.filter(frame => frame.sd === SD_2 || frame.replaced === true)).toEqual([])
    // the old session still works
    const relayed = collectUntil(peer, frame => frame.t === "put")
    expect((await ackedOrNacked(old, put(1))).t).toBe("op-ack")
    expect(await relayed).toMatchObject({ sd: SD })
    peer.close(); old.close()
  })

  it("sheds the largest backlog at the room backlog cap and keeps an idle presence sender", async () => {
    const stub = room("sp1-backlog-shed")
    const observer = await openV3Socket(stub); await drainSnapshot(observer)
    await tagNewest(stub, "observer")
    const idle = await openV3Socket(stub); await drainSnapshot(idle)
    await tagNewest(stub, "idle")
    await helloOn(idle, hello())
    const bursters: WebSocket[] = []
    for (let index = 0; index < 3; index += 1) {
      const ws = await openV3Socket(stub); await drainSnapshot(ws)
      await tagNewest(stub, `burst-${index}`)
      bursters.push(ws)
    }
    const idleClosed = waitForClose(idle, 1_500)
    const burstCloses = bursters.map(ws => waitForClose(ws, 1_500))
    const relayed = collectUntil(observer, frame => frame.t === "loc", 1_500)

    await runInDurableObject(stub, async (instance, state) => {
      const relay = instance as any
      const byTag = (tag: string) => state.getWebSockets().find(ws => (ws.deserializeAttachment() as any)?.testTag === tag)!
      const original = relay.processWebSocketMessage
      let release!: () => void
      const gate = new Promise<void>(resolve => { release = resolve })
      relay.processWebSocketMessage = async function (ws: WebSocket, text: string): Promise<void> {
        if (String((ws.deserializeAttachment() as any)?.testTag).startsWith("burst")) await gate
        return original.call(relay, ws, text)
      }
      try {
        const pending: Promise<void>[] = []
        // 200 + 200 + 112 queued frames puts the room exactly at its backlog cap
        const counts = [LIMITS.RATE_MAX_MSGS, LIMITS.RATE_MAX_MSGS, LIMITS.ROOM_PENDING_MAX_MSGS - 2 * LIMITS.RATE_MAX_MSGS]
        counts.forEach((count, index) => {
          const ws = byTag(`burst-${index}`)
          for (let frame = 0; frame < count; frame += 1) pending.push(relay.webSocketMessage(ws, JSON.stringify({ t: "ping" })))
        })
        expect(relay.roomPendingMessages).toBe(LIMITS.ROOM_PENDING_MAX_MSGS)
        await relay.webSocketMessage(byTag("idle"), JSON.stringify(loc(1)))
        release()
        await Promise.all(pending)
        expect(relay.roomPendingMessages).toBe(0)
        expect(relay.roomPendingBytes).toBe(0)
      } finally {
        relay.processWebSocketMessage = original
      }
    })
    expect(await idleClosed).toBeNull()
    expect(await relayed).toMatchObject({ t: "loc", vs: stamp(1) })
    const codes = (await Promise.all(burstCloses)).map(event => event?.code ?? null)
    console.log(JSON.stringify({ sp1_item6: "backlog-shed", burstCloseCodes: codes }))
    expect(codes[0]).toBe(4008)
    idle.close(); observer.close()
    for (const ws of bursters) ws.close()
  })

  it("live fan-out and chat routing skip a socket the relay closed, even before its client echoes", async () => {
    const { stub, a, b, keyA, keyB } = await openChatPair("sp1-fanout-skips-closed")
    // raw close, not closeSocket: b keeps its hello and chat key in the
    // attachment, so readyState is the only thing that says it's gone
    const probe = await runInDurableObject(stub, (instance, state) => {
      const socket = state.getWebSockets().find(ws => (ws.deserializeAttachment() as any)?.hello?.by === B.actor_id)!
      socket.close(4008, "test close")
      const original = socket.send.bind(socket)
      ;(instance as any).closedSends = 0
      socket.send = (...args: Parameters<WebSocket["send"]>) => {
        ;(instance as any).closedSends += 1
        return original(...args)
      }
      const attachment = socket.deserializeAttachment() as any
      return { readyState: socket.readyState, bound: !!attachment.hello && !!attachment.chatKey }
    })
    expect(probe.readyState).not.toBe(1)
    expect(probe.bound).toBe(true)
    expect((await ackedOrNacked(a, put(1))).t).toBe("op-ack")
    a.send(JSON.stringify(loc(1)))
    const roomChat = await signedChat(A, base64urlBytes(SD), keyA.kid, 1)
    const roomAck = collectUntil(a, frame => frame.t === "chat-ack")
    a.send(JSON.stringify(roomChat))
    expect(await roomAck).not.toBeNull()
    const direct = await signedChat(A, base64urlBytes(SD), keyA.kid, 2, {
      scope: "direct", target: { identity: B, session: CHAT_SD_B_RAW, kid: keyB.kid },
    })
    const offline = collectUntil(a, frame => frame.t === "chat-nack")
    a.send(JSON.stringify(direct))
    expect(await offline).toMatchObject({ code: "recipient_offline" })
    await sleep(50)
    const sends = await runInDurableObject(stub, instance => (instance as any).closedSends as number)
    console.log(JSON.stringify({ sp1_item6: "fanout-skips-closed", ...probe, sendsToClosedSocket: sends }))
    expect(sends).toBe(0)
    a.close(); b.close()
  })

  it("announces a transient leave when the relay closes a bound socket and never advertises it later", async () => {
    const stub = room("sp1-server-close-leave")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    const b = await openV3Socket(stub); await drainSnapshot(b)
    const seen = collectUntil(b, frame => frame.t === "hello")
    await helloOn(a, hello()); await seen
    const leave = collectUntil(b, frame => frame.t === "leave", 1_500)
    const closed = waitForClose(a, 1_500)
    const padding = "A".repeat(900_000)
    for (let index = 0; index < 5; index += 1) a.send(JSON.stringify({ t: "unknown", padding }))
    expect((await closed)?.code).toBe(4008)
    expect(await leave).toEqual({ t: "leave", by: A.actor_id, sd: SD, transient: true })
    const late = await openV3Socket(stub)
    await drainSnapshot(late)
    const ghost = collectUntil(late, frame => frame.t === "hello", 300)
    expect(await ghost).toBeNull()
    b.close(); late.close()
  })
})
