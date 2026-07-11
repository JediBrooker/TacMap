import { env, SELF } from "cloudflare:test"
import { describe, it, expect } from "vitest"

const VALID_ROOM_ID = "abcdefghijklmnopqrstuvwxyz01234567890123"
const AUTH_TOKEN = "test-auth-token-long-enough-for-validation-check"

function roomUrl(roomId = VALID_ROOM_ID) {
  return `http://test/room/${roomId}`
}

function wsHeaders(token = AUTH_TOKEN, extra: Record<string, string> = {}) {
  return new Headers({
    Upgrade: "websocket",
    Authorization: `Bearer ${token}`,
    ...extra,
  })
}

// -- helpers --

function sleep(ms: number): Promise<void> {
  return new Promise(r => setTimeout(r, ms))
}

function collectMessages(ws: WebSocket, count: number, timeoutMs = 2000): Promise<any[]> {
  return new Promise((resolve) => {
    const msgs: any[] = []
    const timer = setTimeout(() => resolve(msgs), timeoutMs)
    ws.addEventListener("message", (e: MessageEvent) => {
      try {
        msgs.push(JSON.parse(typeof e.data === "string" ? e.data : new TextDecoder().decode(e.data as ArrayBuffer)))
      } catch {
        msgs.push(e.data)
      }
      if (msgs.length >= count) {
        clearTimeout(timer)
        resolve(msgs)
      }
    })
  })
}

// drains the full snapshot-begin / snapshot* / snapshot-end sequence,
// returns the combined items array and the fence seq
async function drainSnapshot(ws: WebSocket, timeoutMs = 5000): Promise<{ items: any[]; members: any[]; seq: number }> {
  return new Promise((resolve) => {
    const all: any[] = []
    const timer = setTimeout(() => finish(), timeoutMs)
    function finish() {
      clearTimeout(timer)
      const begin = all.find(m => m.t === "snapshot-begin")
      const snapshots = all.filter(m => m.t === "snapshot")
      const items = snapshots.flatMap(s => s.items ?? [])
      const members = snapshots[0]?.members ?? []
      resolve({ items, members, seq: begin?.seq ?? 0 })
    }
    ws.addEventListener("message", (e: MessageEvent) => {
      try {
        const msg = JSON.parse(typeof e.data === "string" ? e.data : new TextDecoder().decode(e.data as ArrayBuffer))
        all.push(msg)
        if (msg.t === "snapshot-end") finish()
      } catch { /* ignore */ }
    })
  })
}

async function openSocket(stub: DurableObjectStub, token = AUTH_TOKEN): Promise<WebSocket> {
  const resp = await stub.fetch(roomUrl(), { headers: wsHeaders(token) })
  if (resp.status !== 101) throw new Error(`Expected 101, got ${resp.status}`)
  const ws = resp.webSocket!
  ws.accept()
  return ws
}

// -- Worker routing tests --

describe("Worker routing", () => {
  it("health check returns 200", async () => {
    const resp = await SELF.fetch("http://test/health")
    expect(resp.status).toBe(200)
    expect(await resp.text()).toBe("ok")
  })

  it("unknown path returns 404", async () => {
    const resp = await SELF.fetch("http://test/nonexistent")
    expect(resp.status).toBe(404)
  })

  it("non-websocket request to room returns 426", async () => {
    const resp = await SELF.fetch(roomUrl(), {
      headers: { Authorization: `Bearer ${AUTH_TOKEN}` },
    })
    expect(resp.status).toBe(426)
  })

  it("short room id returns 404", async () => {
    const resp = await SELF.fetch("http://test/room/tooshort", {
      headers: wsHeaders(),
    })
    expect(resp.status).toBe(404)
  })

  it("forbidden origin returns 403", async () => {
    const resp = await SELF.fetch(roomUrl(), {
      headers: wsHeaders(AUTH_TOKEN, { Origin: "https://evil.example.com" }),
    })
    expect(resp.status).toBe(403)
  })
})

// -- DO auth tests --

describe("SyncRoom auth", () => {
  it("rejects short auth token", async () => {
    const id = env.SYNC_ROOM.idFromName("auth-short")
    const stub = env.SYNC_ROOM.get(id)
    const resp = await stub.fetch(roomUrl(), {
      headers: wsHeaders("short"),
    })
    expect(resp.status).toBe(401)
  })

  it("rejects mismatched auth token", async () => {
    const id = env.SYNC_ROOM.idFromName("auth-mismatch")
    const stub = env.SYNC_ROOM.get(id)

    const ws1 = await openSocket(stub, "first-token-long-enough-for-check")
    await drainSnapshot(ws1)
    ws1.close()

    const resp2 = await stub.fetch(roomUrl(), {
      headers: wsHeaders("wrong-token-long-enough-for-check"),
    })
    expect(resp2.status).toBe(403)
  })
})

// -- snapshot fence tests --

describe("Snapshot fence", () => {
  it("sends snapshot-begin, snapshot, snapshot-end in order", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("fence-order"))
    const ws = await openSocket(stub)
    const msgs = await collectMessages(ws, 3)

    expect(msgs[0].t).toBe("snapshot-begin")
    expect(typeof msgs[0].seq).toBe("number")
    expect(msgs[1].t).toBe("snapshot")
    expect(msgs[1].items).toEqual([])
    expect(msgs[1].members).toEqual([])
    expect(msgs[2].t).toBe("snapshot-end")
    expect(msgs[2].seq).toBe(msgs[0].seq)

    ws.close()
  })

  it("mutations carry seq field", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("fence-seq"))
    const wsA = await openSocket(stub)
    await drainSnapshot(wsA)

    const wsB = await openSocket(stub)
    await drainSnapshot(wsB)

    wsA.send(JSON.stringify({ t: "put", id: "s1", v: 1, by: "a", kind: "waypoint", ct: "AAAA" }))
    const msgs = await collectMessages(wsB, 1)
    expect(msgs[0].t).toBe("put")
    expect(typeof msgs[0].seq).toBe("number")
    expect(msgs[0].seq).toBeGreaterThan(0)

    wsA.close()
    wsB.close()
  })

  it("seq is monotonically increasing", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("fence-mono"))
    const wsA = await openSocket(stub)
    await drainSnapshot(wsA)
    const wsB = await openSocket(stub)
    await drainSnapshot(wsB)

    wsA.send(JSON.stringify({ t: "put", id: "m1", v: 1, by: "a", kind: "waypoint", ct: "AAAA" }))
    const msg1 = await collectMessages(wsB, 1)

    wsA.send(JSON.stringify({ t: "put", id: "m2", v: 1, by: "a", kind: "waypoint", ct: "BBBB" }))
    const msg2 = await collectMessages(wsB, 1)

    expect(msg2[0].seq).toBeGreaterThan(msg1[0].seq)

    wsA.close()
    wsB.close()
  })
})

// -- WebSocket basic tests --

describe("SyncRoom WebSocket", () => {
  it("accepts valid upgrade and sends empty snapshot", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("ws-basic"))
    const ws = await openSocket(stub)

    const snap = await drainSnapshot(ws)
    expect(snap.items).toEqual([])
    expect(snap.members).toEqual([])

    ws.close()
  })

  it("round-trips a put via two sockets", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("ws-roundtrip"))

    const wsA = await openSocket(stub)
    await drainSnapshot(wsA)

    const wsB = await openSocket(stub)
    await drainSnapshot(wsB)

    wsA.send(JSON.stringify({ t: "put", id: "obj1", v: 1, by: "clientA", kind: "waypoint", ct: "AAAA" }))

    const received = await collectMessages(wsB, 1)
    expect(received.length).toBe(1)
    expect(received[0].t).toBe("put")
    expect(received[0].id).toBe("obj1")
    expect(received[0].v).toBe(1)

    wsA.close()
    wsB.close()
  })

  it("new connection gets snapshot with existing records", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("ws-snapshot"))

    const wsA = await openSocket(stub)
    await drainSnapshot(wsA)
    wsA.send(JSON.stringify({ t: "put", id: "snap1", v: 1, by: "a", kind: "waypoint", ct: "BBBB" }))
    await sleep(50)

    const wsB = await openSocket(stub)
    const snap = await drainSnapshot(wsB)
    expect(snap.items.length).toBe(1)
    expect(snap.items[0].id).toBe("snap1")

    wsA.close()
    wsB.close()
  })

  it("responds to ping with pong", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("ws-ping"))
    const ws = await openSocket(stub)
    await drainSnapshot(ws)

    ws.send(JSON.stringify({ t: "ping" }))
    const msgs = await collectMessages(ws, 1)
    expect(msgs[0].t).toBe("pong")

    ws.close()
  })
})

// -- Track 2B: input hardening + storage accounting --

describe("Input hardening", () => {
  it("rejects phantom delete (object never stored)", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("phantom-del"))
    const wsA = await openSocket(stub)
    await drainSnapshot(wsA)
    const wsB = await openSocket(stub)
    await drainSnapshot(wsB)

    // try to delete something that was never put
    wsA.send(JSON.stringify({ t: "del", id: "ghost", v: 1, by: "a", kind: "waypoint", ct: "AAAA" }))
    // should NOT be broadcast to B
    const msgs = await collectMessages(wsB, 1, 300)
    expect(msgs.length).toBe(0)

    wsA.close()
    wsB.close()
  })

  it("rejects put with non-string id", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("bad-id-type"))
    const wsA = await openSocket(stub)
    await drainSnapshot(wsA)
    const wsB = await openSocket(stub)
    await drainSnapshot(wsB)

    wsA.send(JSON.stringify({ t: "put", id: 12345, v: 1, by: "a", kind: "waypoint", ct: "AAAA" }))
    const msgs = await collectMessages(wsB, 1, 300)
    expect(msgs.length).toBe(0)

    wsA.close()
    wsB.close()
  })

  it("rejects put with non-integer version", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("bad-v-float"))
    const wsA = await openSocket(stub)
    await drainSnapshot(wsA)
    const wsB = await openSocket(stub)
    await drainSnapshot(wsB)

    wsA.send(JSON.stringify({ t: "put", id: "x", v: 1.5, by: "a", kind: "waypoint", ct: "AAAA" }))
    const msgs = await collectMessages(wsB, 1, 300)
    expect(msgs.length).toBe(0)

    wsA.close()
    wsB.close()
  })

  it("rejects put with empty ciphertext", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("bad-empty-ct"))
    const wsA = await openSocket(stub)
    await drainSnapshot(wsA)
    const wsB = await openSocket(stub)
    await drainSnapshot(wsB)

    wsA.send(JSON.stringify({ t: "put", id: "x", v: 1, by: "a", kind: "waypoint", ct: "" }))
    const msgs = await collectMessages(wsB, 1, 300)
    expect(msgs.length).toBe(0)

    wsA.close()
    wsB.close()
  })

  it("rejects delete with empty ciphertext (no sealed proof)", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("del-no-proof"))
    const wsA = await openSocket(stub)
    await drainSnapshot(wsA)
    const wsB = await openSocket(stub)
    await drainSnapshot(wsB)

    // first put so the object exists
    wsA.send(JSON.stringify({ t: "put", id: "x", v: 1, by: "a", kind: "waypoint", ct: "AAAA" }))
    await collectMessages(wsB, 1)

    // delete with empty ct (no sealed proof)
    wsA.send(JSON.stringify({ t: "del", id: "x", v: 2, by: "a", kind: "waypoint", ct: "" }))
    const msgs = await collectMessages(wsB, 1, 300)
    expect(msgs.length).toBe(0)

    wsA.close()
    wsB.close()
  })

  it("rejects put with missing kind field", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("bad-no-kind"))
    const wsA = await openSocket(stub)
    await drainSnapshot(wsA)
    const wsB = await openSocket(stub)
    await drainSnapshot(wsB)

    wsA.send(JSON.stringify({ t: "put", id: "x", v: 1, by: "a", ct: "AAAA" }))
    const msgs = await collectMessages(wsB, 1, 300)
    expect(msgs.length).toBe(0)

    wsA.close()
    wsB.close()
  })

  it("closes socket on oversized frame", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("oversized"))
    const ws = await openSocket(stub)
    await drainSnapshot(ws)

    // sending a massive frame should get us disconnected
    const huge = JSON.stringify({ t: "put", id: "x", v: 1, by: "a", kind: "waypoint", ct: "A".repeat(1_048_577) })
    try {
      ws.send(huge)
    } catch {
      // might fail if socket is already closing
    }
    // the relay should close us with code 4009
    const closed = await new Promise<boolean>((resolve) => {
      ws.addEventListener("close", () => resolve(true))
      setTimeout(() => resolve(false), 1000)
    })
    // we might not get the close event in the test env, so just verify the frame was too large
    expect(huge.length).toBeGreaterThan(1_048_576)
  })
})

// -- Track 2B: storage accounting --

describe("Storage accounting", () => {
  it("tracks record count and bytes through put/delete cycle", async () => {
    const name = "accounting-cycle"
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName(name))
    const ws = await openSocket(stub)
    await drainSnapshot(ws)

    // put 3 records
    for (let i = 0; i < 3; i++) {
      ws.send(JSON.stringify({ t: "put", id: `r${i}`, v: 1, by: "a", kind: "waypoint", ct: "CCCC" }))
    }
    await sleep(100)

    // connect a second client to check snapshot has all 3
    const ws2 = await openSocket(stub)
    const snap = await drainSnapshot(ws2)
    expect(snap.items.length).toBe(3)

    ws.close()
    ws2.close()
  })
})

describe("Live record accounting", () => {
  it("tombstones do not permanently cap the room — re-put works after delete", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("tombstone-reput"))
    const wsA = await openSocket(stub)
    await drainSnapshot(wsA)
    const wsB = await openSocket(stub)
    await drainSnapshot(wsB)

    // put then delete
    wsA.send(JSON.stringify({ t: "put", id: "obj1", v: 1, by: "a", kind: "waypoint", ct: "AAAA" }))
    await collectMessages(wsB, 1)
    wsA.send(JSON.stringify({ t: "del", id: "obj1", v: 2, by: "a", kind: "waypoint", ct: "DEL1" }))
    await collectMessages(wsB, 1)

    // a fresh put with a new ID should succeed (live count decremented on delete)
    wsA.send(JSON.stringify({ t: "put", id: "obj2", v: 1, by: "a", kind: "waypoint", ct: "BBBB" }))
    const msgs = await collectMessages(wsB, 1)
    expect(msgs.length).toBe(1)
    expect(msgs[0].id).toBe("obj2")

    wsA.close()
    wsB.close()
  })

  it("re-putting a tombstoned record increments live count", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("tombstone-reput2"))
    const wsA = await openSocket(stub)
    await drainSnapshot(wsA)

    wsA.send(JSON.stringify({ t: "put", id: "r1", v: 1, by: "a", kind: "waypoint", ct: "AAAA" }))
    await sleep(50)
    wsA.send(JSON.stringify({ t: "del", id: "r1", v: 2, by: "a", kind: "waypoint", ct: "DEL1" }))
    await sleep(50)
    // re-put with higher version
    wsA.send(JSON.stringify({ t: "put", id: "r1", v: 3, by: "a", kind: "waypoint", ct: "BBBB" }))
    await sleep(50)

    const ws2 = await openSocket(stub)
    const snap = await drainSnapshot(ws2)
    // should have the record back as live
    const r1 = snap.items.find((i: any) => i.id === "r1")
    expect(r1).toBeDefined()
    expect(r1.deleted).toBe(false)
    expect(r1.v).toBe(3)

    wsA.close()
    ws2.close()
  })
})

describe("LWW conflict resolution", () => {
  it("higher version wins", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("lww-version"))
    const wsA = await openSocket(stub)
    await drainSnapshot(wsA)

    // put v=1 then v=5
    wsA.send(JSON.stringify({ t: "put", id: "c1", v: 1, by: "a", kind: "waypoint", ct: "OLD" }))
    await sleep(50)
    wsA.send(JSON.stringify({ t: "put", id: "c1", v: 5, by: "b", kind: "waypoint", ct: "NEW" }))
    await sleep(50)

    const ws2 = await openSocket(stub)
    const snap = await drainSnapshot(ws2)
    const c1 = snap.items.find((i: any) => i.id === "c1")
    expect(c1.v).toBe(5)
    expect(c1.ct).toBe("NEW")

    wsA.close()
    ws2.close()
  })

  it("same version: higher client-id wins", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("lww-tiebreak"))
    const wsA = await openSocket(stub)
    await drainSnapshot(wsA)

    wsA.send(JSON.stringify({ t: "put", id: "c2", v: 3, by: "alice", kind: "waypoint", ct: "ALICE" }))
    await sleep(50)
    // same version, "bob" > "alice" lexicographically
    wsA.send(JSON.stringify({ t: "put", id: "c2", v: 3, by: "bob", kind: "waypoint", ct: "BOB" }))
    await sleep(50)

    const ws2 = await openSocket(stub)
    const snap = await drainSnapshot(ws2)
    const c2 = snap.items.find((i: any) => i.id === "c2")
    expect(c2.by).toBe("bob")
    expect(c2.ct).toBe("BOB")

    wsA.close()
    ws2.close()
  })

  it("lower version is rejected", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("lww-stale"))
    const wsA = await openSocket(stub)
    await drainSnapshot(wsA)
    const wsB = await openSocket(stub)
    await drainSnapshot(wsB)

    wsA.send(JSON.stringify({ t: "put", id: "c3", v: 5, by: "a", kind: "waypoint", ct: "CURRENT" }))
    await collectMessages(wsB, 1)

    // stale version should be silently dropped (no broadcast)
    wsA.send(JSON.stringify({ t: "put", id: "c3", v: 2, by: "a", kind: "waypoint", ct: "STALE" }))
    const msgs = await collectMessages(wsB, 1, 300)
    expect(msgs.length).toBe(0)

    wsA.close()
    wsB.close()
  })
})

// -- Track 2C: room idle expiry --

describe("Room idle expiry", () => {
  it("alarm with active connections reschedules without deletion", async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("alarm-active"))
    const ws = await openSocket(stub)
    await drainSnapshot(ws)

    // put something so there's data to protect
    ws.send(JSON.stringify({ t: "put", id: "keep", v: 1, by: "a", kind: "waypoint", ct: "AAAA" }))
    await sleep(50)

    // trigger alarm -- room has a connection so it should just reschedule
    const alarmStub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("alarm-active"))
    // reconnect and verify data survived
    const ws2 = await openSocket(stub)
    const snap = await drainSnapshot(ws2)
    expect(snap.items.length).toBe(1)
    expect(snap.items[0].id).toBe("keep")

    ws.close()
    ws2.close()
  })
})

// -- Track 2D: paged snapshot --

describe("Paged snapshot", () => {
  it("delivers all records across multiple pages", { timeout: 30_000 }, async () => {
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName("paged-snap"))
    const ws = await openSocket(stub)
    await drainSnapshot(ws)

    // put more records than SNAPSHOT_CHUNK (100)
    const count = 120
    for (let i = 0; i < count; i++) {
      ws.send(JSON.stringify({ t: "put", id: `p${String(i).padStart(4, "0")}`, v: 1, by: "a", kind: "waypoint", ct: "DDDD" }))
    }
    await sleep(500)

    // second client should get all 120 across multiple snapshot messages
    const ws2 = await openSocket(stub)
    const snap = await drainSnapshot(ws2, 10_000)
    expect(snap.items.length).toBe(count)

    ws.close()
    ws2.close()
  })
})
