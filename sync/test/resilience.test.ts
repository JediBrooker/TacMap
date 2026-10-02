import { env, runInDurableObject, runDurableObjectAlarm, evictDurableObject } from "cloudflare:test"
import { describe, it, expect } from "vitest"
import fixture from "../../testdata/sync_protocol_v3.json"
import {
  A, B, CHAT_SD_B_RAW, SD, WIRE_ID, base64urlBytes, collectMessages, collectUntil, drainSnapshot,
  genHello, genIdentity, genPut, hello, helloOn, loc, openChatPair, openV3Socket, put, rid, sealed,
  signedChat, sleep, stamp, wireIdFor,
} from "./support"

// S6-08: the relay paths that had no tests at all. hibernation/eviction,
// alarm scheduling, v3 paging, accounting migration, socket errors, and a
// small randomized convergence check

const LIMITS = fixture.relayLimits.values

function room(name: string): DurableObjectStub {
  return env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName(name))
}

describe("hibernation and eviction", () => {
  it("keeps session binding, presence and chat counters, and the live snapshot across an eviction", async () => {
    const { stub, a, b, keyA, keyB } = await openChatPair("resilience-evict")
    const first = loc(1)
    let seen = collectUntil(b, frame => frame.t === "loc"); a.send(JSON.stringify(first)); expect(await seen).toEqual(first)
    const chat1 = await signedChat(A, base64urlBytes(SD), keyA.kid, 1)
    let routed = collectUntil(b, frame => frame.t === "chat"); let chatAck = collectUntil(a, frame => frame.t === "chat-ack")
    a.send(JSON.stringify(chat1)); await routed; await chatAck

    await evictDurableObject(stub)

    const replayed = collectUntil(b, frame => frame.t === "loc", 300)
    a.send(JSON.stringify(first))
    expect(await replayed).toBeNull()
    const second = loc(2, { ct: sealed(32, "B") })
    seen = collectUntil(b, frame => frame.t === "loc"); a.send(JSON.stringify(second)); expect(await seen).toEqual(second)
    const changedReplay = await signedChat(A, base64urlBytes(SD), keyA.kid, 1, { byte: 66 })
    const nack = collectUntil(a, frame => frame.t === "chat-nack"); a.send(JSON.stringify(changedReplay))
    expect(await nack).toMatchObject({ code: "counter_rejected" })
    const chat2 = await signedChat(A, base64urlBytes(SD), keyA.kid, 2, {
      scope: "direct", target: { identity: B, session: CHAT_SD_B_RAW, kid: keyB.kid },
    })
    routed = collectUntil(b, frame => frame.t === "chat"); chatAck = collectUntil(a, frame => frame.t === "chat-ack")
    a.send(JSON.stringify(chat2)); expect(await routed).toEqual(chat2); await chatAck
    const opAck = collectUntil(a, frame => frame.t === "op-ack"); const relayed = collectUntil(b, frame => frame.t === "put")
    a.send(JSON.stringify(put(1)))
    expect(await opAck).toMatchObject({ vs: stamp(1) })
    expect(await relayed).toMatchObject({ vs: stamp(1) })

    const newcomer = await openV3Socket(stub)
    const frames = await collectMessages(newcomer, 8, 2_000)
    expect(frames.slice(0, 3).map(frame => frame.t)).toEqual(["snapshot-begin", "snapshot", "snapshot-end"])
    expect(frames.slice(3).map(frame => frame.t).sort()).toEqual(["chat-key", "chat-key", "hello", "hello", "loc"])
    const left = collectUntil(b, frame => frame.t === "leave")
    a.close()
    expect(await left).toEqual({ t: "leave", by: A.actor_id, sd: SD, transient: true })
    b.close(); newcomer.close()
  })
})

describe("alarm scheduling", () => {
  it("leaves only the purge alarm on an expired room with nothing left to compact, and a join pulls it back to daily", async () => {
    const stub = room("resilience-dead-room")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, hello())
    const ack = collectUntil(a, frame => frame.t === "op-ack"); a.send(JSON.stringify(put(1))); await ack
    a.close(); await sleep(50)
    const lastActivity = Date.now() - 8 * 24 * 60 * 60 * 1000
    await runInDurableObject(stub, async (_instance, state) => {
      await state.storage.put("meta:lastActivity", lastActivity)
      await state.storage.setAlarm(Date.now() + 60_000)
    })
    expect(await runDurableObjectAlarm(stub)).toBe(true)
    await runInDurableObject(stub, async (_instance, state) => {
      expect(await state.storage.getAlarm()).toBe(lastActivity + LIMITS.ROOM_PURGE_TTL_MS)
      expect((await state.storage.list({ prefix: "obj:" })).size).toBe(0)
      expect(await state.storage.get("meta:seq")).toBe(2)
    })
    const b = await openV3Socket(stub); await drainSnapshot(b)
    await runInDurableObject(stub, async (_instance, state) => {
      // open sockets get the daily pass, not the far-off purge
      expect(await state.storage.getAlarm()).toBeLessThanOrEqual(Date.now() + LIMITS.MAINTENANCE_INTERVAL_MS)
    })
    b.close()
  })

  it("schedules the next alarm for when an idle room's youngest tombstone could compact", async () => {
    const stub = room("resilience-compaction-schedule")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, hello())
    let ack = collectUntil(a, frame => frame.t === "op-ack"); a.send(JSON.stringify(put(1))); await ack
    ack = collectUntil(a, frame => frame.t === "op-ack")
    a.send(JSON.stringify({ ...put(2), t: "del", kind: "del", rid: rid("sched-del") })); await ack
    a.close(); await sleep(50)
    await runInDurableObject(stub, async (_instance, state) => {
      await state.storage.put("meta:lastActivity", Date.now() - 8 * 24 * 60 * 60 * 1000)
      await state.storage.setAlarm(Date.now() + 60_000)
    })
    expect(await runDurableObjectAlarm(stub)).toBe(true)
    await runInDurableObject(stub, async (_instance, state) => {
      const tombAt = (await state.storage.get<number>(`tomb:${WIRE_ID}`))!
      const alarm = (await state.storage.getAlarm())!
      expect(alarm).toBeGreaterThanOrEqual(tombAt + LIMITS.TOMBSTONE_TTL_MS)
      expect(alarm).toBeLessThanOrEqual(Date.now() + LIMITS.TOMBSTONE_TTL_MS + 60_000)
    })
  })
})

describe("snapshot paging and accounting", () => {
  it("pages two 500 KB v3 records into two byte-bounded frames", { timeout: 20_000 }, async () => {
    const stub = room("resilience-v3-paging")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, hello())
    for (const [index, id] of [WIRE_ID, await wireIdFor("page-two")].entries()) {
      const ack = collectUntil(a, frame => frame.t === "op-ack", 5_000)
      a.send(JSON.stringify(put(index + 1, { id, ct: sealed(375_000), rid: rid(`page-${index}`) })))
      expect(await ack).not.toBeNull()
    }
    const late = await openV3Socket(stub)
    const snapshot = await drainSnapshot(late, 10_000)
    expect(snapshot.items).toHaveLength(2)
    expect(snapshot.frames.filter(frame => frame.t === "snapshot")).toHaveLength(2)
    expect(Math.max(...snapshot.lengths)).toBeLessThanOrEqual(LIMITS.SNAPSHOT_FRAME_BYTES)
    expect(snapshot.begin.seq).toBe(snapshot.end.seq)
    a.close(); late.close()
  })

  it("migrates pre-ADR records without sd to exact accounting", async () => {
    const stub = room("resilience-accounting-migration")
    const first = await openV3Socket(stub); await drainSnapshot(first); first.close()
    const legacy = { id: WIRE_ID, vs: stamp(1), by: A.actor_id, kind: "waypoint", ct: sealed(), deleted: false, pub: A.pubkey_base64url }
    const modernId = await wireIdFor("modern")
    const modern = { id: modernId, vs: stamp(2), by: A.actor_id, kind: "waypoint", ct: sealed(64), deleted: false, pub: A.pubkey_base64url, sd: SD }
    const pin = { pubkey: A.pubkey_base64url, firstSeen: 1, helloEpoch: "0000000000000001" }
    await runInDurableObject(stub, async (_instance, state) => {
      await state.storage.put({ [`obj:${WIRE_ID}`]: legacy, [`obj:${modernId}`]: modern, [`actor:${A.actor_id}`]: pin })
      await state.storage.put({ "meta:totalRecords": 999, "meta:bytes": 999 })
      await state.storage.delete("meta:accountingSchema")
    })
    const again = await openV3Socket(stub); await drainSnapshot(again)
    await runInDurableObject(stub, async (_instance, state) => {
      const legacyBytes = legacy.ct.length + legacy.id.length + legacy.by.length + legacy.kind.length + legacy.vs.length + 80
      const modernBytes = modern.ct.length + modern.id.length + modern.by.length + modern.kind.length +
        modern.vs.length + modern.pub.length + modern.sd.length + 128
      const pinBytes = new TextEncoder().encode(`actor:${A.actor_id}`).length + new TextEncoder().encode(JSON.stringify(pin)).length
      expect(await state.storage.get("meta:totalRecords")).toBe(3)
      expect(await state.storage.get("meta:bytes")).toBe(legacyBytes + modernBytes + pinBytes)
      expect(await state.storage.get("meta:accountingSchema")).toBe(2)
    })
    again.close()
  })
})

describe("socket errors and dead sockets", () => {
  it("announces a transient leave on webSocketError and drops frames queued behind it", async () => {
    const stub = room("resilience-socket-error")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    const b = await openV3Socket(stub); await drainSnapshot(b)
    const seen = collectUntil(b, frame => frame.t === "hello"); await helloOn(a, hello()); await seen
    const leave = collectUntil(b, frame => frame.t === "leave")
    const leaked = collectUntil(b, frame => frame.t === "put", 300)
    await runInDurableObject(stub, async (instance, state) => {
      const relay = instance as any
      const socket = state.getWebSockets().find(ws => (ws.deserializeAttachment() as any)?.hello?.sd === SD)!
      await relay.webSocketError(socket, new Error("transport reset"))
      await relay.webSocketMessage(socket, JSON.stringify(put(1)))
      expect(await state.storage.get(`obj:${WIRE_ID}`)).toBeUndefined()
    })
    expect(await leave).toEqual({ t: "leave", by: A.actor_id, sd: SD, transient: true })
    expect(await leaked).toBeNull()
    a.close(); b.close()
  })

  it("never processes a straggler frame on a socket the relay already closed", async () => {
    const stub = room("resilience-straggler")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, hello())
    await runInDurableObject(stub, async (instance, state) => {
      const relay = instance as any
      const socket = state.getWebSockets().find(ws => (ws.deserializeAttachment() as any)?.hello?.sd === SD)!
      // an oversize close happens before any queue exists for the socket
      await relay.webSocketMessage(socket, "x".repeat(LIMITS.MAX_FRAME_BYTES + 1))
      await relay.webSocketMessage(socket, JSON.stringify(put(1)))
      expect(await state.storage.get(`obj:${WIRE_ID}`)).toBeUndefined()
    })
  })
})

describe("randomized convergence", () => {
  it("relay storage, every client's LWW fold and a late joiner agree after random concurrent traffic", { timeout: 60_000 }, async () => {
    const stub = room("resilience-fuzz")
    const ids = await Promise.all(Array.from({ length: 4 }, genIdentity))
    const sockets: WebSocket[] = []
    const folds: Array<Map<string, { vs: string; deleted: boolean; ct: string }>> = []
    const counters = ids.map(() => 0)
    const compare = (x: string, y: string): number => {
      const [cx, ax] = [BigInt("0x" + x.slice(0, 16)), x.slice(17)]
      const [cy, ay] = [BigInt("0x" + y.slice(0, 16)), y.slice(17)]
      return cx === cy ? (ax > ay ? 1 : ax < ay ? -1 : 0) : cx > cy ? 1 : -1
    }
    const fold = (map: Map<string, any>, record: any): void => {
      const current = map.get(record.id)
      if (!current || compare(record.vs, current.vs) > 0) {
        map.set(record.id, { vs: record.vs, deleted: !!record.deleted || record.t === "del", ct: record.ct })
      }
    }
    for (const id of ids) {
      const ws = await openV3Socket(stub); await drainSnapshot(ws)
      await helloOn(ws, await genHello(id))
      const map = new Map()
      folds.push(map)
      ws.addEventListener("message", event => {
        const frame = JSON.parse(event.data as string)
        if (frame.t === "put" || frame.t === "del") fold(map, frame)
      })
      sockets.push(ws)
    }
    const wireIds = fixture.wire_object_ids.cases.map(entry => entry.wire_object_id)
    let seed = 0x9270
    const random = (n: number): number => {
      seed = (seed + 0x6d2b79f5) | 0
      let t = Math.imul(seed ^ (seed >>> 15), 1 | seed)
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
      return ((t ^ (t >>> 14)) >>> 0) % n
    }
    const replies: Array<Promise<any>> = []
    for (let op = 0; op < 150; op += 1) {
      const who = random(ids.length)
      counters[who] += 1 + random(3)
      const frame = genPut(ids[who], wireIds[random(wireIds.length)], counters[who], rid(`fuzz-${op}`), random(5) === 0, String.fromCharCode(65 + random(26)))
      const response = collectUntil(sockets[who], message =>
        (message.t === "op-ack" || message.t === "op-nack") && message.rid === frame.rid, 3_000)
      sockets[who].send(JSON.stringify(frame))
      replies.push(response.then(result => { if (result?.t === "op-ack") fold(folds[who], frame); return result }))
      if (random(4) === 0) await sleep(1)
    }
    const results = await Promise.all(replies)
    expect(results.every(result => result !== null)).toBe(true)
    await sleep(200)
    const relay = new Map<string, any>()
    await runInDurableObject(stub, async (_instance, state) => {
      for (const [key, value] of await state.storage.list<any>({ prefix: "obj:" })) {
        relay.set(key.slice(4), { vs: value.vs, deleted: value.deleted, ct: value.ct })
      }
    })
    for (const map of folds) expect(Object.fromEntries(map)).toEqual(Object.fromEntries(relay))
    const late = await openV3Socket(stub)
    const snapshot = await drainSnapshot(late)
    const lateFold = new Map()
    for (const record of snapshot.items) fold(lateFold, record)
    expect(Object.fromEntries(lateFold)).toEqual(Object.fromEntries(relay))
    for (const ws of [...sockets, late]) ws.close()
  })
})
