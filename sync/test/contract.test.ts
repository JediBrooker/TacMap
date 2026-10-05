import { env, runInDurableObject } from "cloudflare:test"
import { describe, it, expect } from "vitest"
import fixture from "../../testdata/sync_protocol_v3.json"
import worker from "../src/index"
import { RELAY_LIMITS } from "../src/limits"
import { RELAY_RELEASE_ID, RELAY_RELEASE_SOURCE_SHA256 } from "../src/release"
import relaySource from "../src/index.ts?raw"
import limitsSource from "../src/limits.ts?raw"
import wranglerConfig from "../wrangler.jsonc?raw"
import {
  A, SD, SD_2, base64urlBytes, collectUntil, del, drainSnapshot, hello, hello2, helloOn,
  openV3Socket, put, rid, sealed, signedExplicitLeave, signedHello, sleep, stamp, v3Headers,
  v3RoomUrl, waitForClose, wireIdFor,
} from "./support"

// SP1 items 7, 8 and 10: the relay's limits and delivery codes are a shared
// contract. these tests fail if the relay, its deploy config or the fixture
// drift apart, so clients (SP2) can pace off testdata alone

const LIMITS = fixture.relayLimits

function room(name: string): DurableObjectStub {
  return env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName(name))
}

function stripJsonc(text: string): string {
  // good enough for our config: drop // line comments outside strings
  return text.split("\n").map(line => {
    let inString = false
    for (let i = 0; i < line.length; i++) {
      const c = line[i]
      if (c === "\"" && line[i - 1] !== "\\") inString = !inString
      if (!inString && c === "/" && line[i + 1] === "/") return line.slice(0, i)
    }
    return line
  }).join("\n")
}

async function reply(ws: WebSocket, frame: Record<string, unknown>): Promise<any> {
  const response = collectUntil(ws, message =>
    (message.t === "op-ack" || message.t === "op-nack") && message.rid === frame.rid, 3_000)
  ws.send(JSON.stringify(frame))
  return response
}

// every id that has gone out, pinned to the source hash it shipped with. when
// you bump the id add the new pair here, never edit an existing one
const SHIPPED_RELEASES: Record<string, string> = {
  "tacmap-sync-3.0.1-epoch-floor": "24ba66f78e5dd2aef34d86b31c56300968d9ff6b5477b99c7757c8227a004eeb",
}
// ids that shipped before the hash existed (2.1.0-sp1 went out with 2 different
// sources), so there's no single hash to pin. never ship one again
const RETIRED_RELEASE_IDS = ["tacmap-sync-2.1.0-sp1"]

// null when the id/hash pair is fine to ship, otherwise why not
function releaseIdProblem(id: string, sourceHash: string): string | null {
  if (RETIRED_RELEASE_IDS.includes(id)) return `${id} is retired`
  const pinned = SHIPPED_RELEASES[id]
  if (pinned === undefined) return `${id} has no pinned hash, add it to SHIPPED_RELEASES`
  if (pinned !== sourceHash) return `${id} already shipped with ${pinned}, give the new source a new id`
  return null
}

async function relaySourceHash(): Promise<string> {
  const files: Array<[string, string]> = [
    ["src/index.ts", relaySource], ["src/limits.ts", limitsSource], ["wrangler.jsonc", wranglerConfig],
  ]
  const digest = new Uint8Array(await crypto.subtle.digest(
    "SHA-256", new TextEncoder().encode(files.map(([name, text]) => `${name}\n${text}\n`).join("")),
  ))
  return [...digest].map(byte => byte.toString(16).padStart(2, "0")).join("")
}

describe("release id", () => {
  it("moves whenever the relay source or its deploy config does (relay-ref-2)", async () => {
    const hex = await relaySourceHash()
    // a mismatch means the relay changed under an id /health already reports:
    // give src/release.ts a new id and this hash, then pin the pair above
    expect(hex).toBe(RELAY_RELEASE_SOURCE_SHA256)
    expect(releaseIdProblem(RELAY_RELEASE_ID, hex)).toBeNull()
  })

  it("refuses a new hash under an id that already shipped (SEC-3)", () => {
    // the slip this guards: paste the new hash into release.ts, forget the id
    const otherSource = "0".repeat(64)
    expect(releaseIdProblem(RELAY_RELEASE_ID, otherSource)).toMatch(/already shipped/)
    for (const [id, hash] of Object.entries(SHIPPED_RELEASES)) {
      expect(releaseIdProblem(id, hash)).toBeNull()
      expect(releaseIdProblem(id, otherSource)).not.toBeNull()
    }
    expect(releaseIdProblem("tacmap-sync-2.1.0-sp1", otherSource)).toMatch(/retired/)
    expect(releaseIdProblem("tacmap-sync-next", otherSource)).toMatch(/no pinned hash/)
    for (const id of RETIRED_RELEASE_IDS) expect(SHIPPED_RELEASES[id]).toBeUndefined()
  })
})

describe("relay limits contract", () => {
  it("mirrors testdata relayLimits.values exactly", () => {
    expect(RELAY_LIMITS).toEqual(LIMITS.values)
  })

  it("agrees with the older shared constants and the client ceilings", () => {
    const constants = fixture.constants
    expect(RELAY_LIMITS.SNAPSHOT_FRAME_BYTES).toBe(constants.SNAPSHOT_FRAME_MAX_BYTES)
    expect(RELAY_LIMITS.ADVANCE_WINDOW).toBe(constants.ADVANCE_WINDOW)
    // every stored record fits a client snapshot, count and bytes
    expect(RELAY_LIMITS.MAX_RECORDS).toBeLessThanOrEqual(constants.CLIENT_SNAPSHOT_MAX_RECORDS)
    expect(RELAY_LIMITS.MAX_STORED_BYTES).toBeLessThan(constants.CLIENT_SNAPSHOT_MAX_AGGREGATE_UTF8_BYTES)
    expect(RELAY_LIMITS.MAX_FRAME_BYTES).toBe(1_048_576)
    // recommended client pacing sits under the relay window
    expect(LIMITS.clientPacing.windowMs).toBe(RELAY_LIMITS.RATE_WINDOW_MS)
    expect(LIMITS.clientPacing.maxFramesPerWindow).toBeLessThan(RELAY_LIMITS.RATE_MAX_MSGS)
    expect(LIMITS.clientPacing.maxBytesPerWindow).toBeLessThan(RELAY_LIMITS.RATE_MAX_BYTES)
    expect(RELAY_LIMITS.TOMBSTONE_TTL_MS).toBeGreaterThan(RELAY_LIMITS.IDLE_TTL_MS)
  })

  it("fits a CT_MAX record in one frame and one snapshot page", () => {
    const id = "x".repeat(43)
    const actor = "y".repeat(43)
    const ct = "A".repeat(RELAY_LIMITS.CT_MAX)
    const record = { id, vs: `${"7".repeat(16)}:${actor}`, by: actor, kind: "drawing", ct, deleted: false, pub: "p".repeat(43), sd: "s".repeat(43) }
    const page = JSON.stringify({ t: "snapshot", items: [record], more: false })
    expect(page.length).toBeLessThanOrEqual(RELAY_LIMITS.SNAPSHOT_FRAME_BYTES)
    const frame = JSON.stringify({ t: "put", ...record, rid: "r".repeat(64) })
    expect(frame.length).toBeLessThanOrEqual(RELAY_LIMITS.MAX_FRAME_BYTES)
  })

  it("matches the per-IP limiter defaults in wrangler.jsonc", () => {
    const config = JSON.parse(stripJsonc(wranglerConfig)) as { ratelimits: Array<{ name: string; simple: { period: number; limit: number } }> }
    const deployed = Object.fromEntries(config.ratelimits.map(limiter => [limiter.name, limiter.simple]))
    const { _comment, ...expected } = LIMITS.deployment
    expect(deployed).toEqual(expected)
  })

  it("documents exactly the close codes the relay can send", () => {
    const sent = new Set<string>()
    for (const match of relaySource.matchAll(/closeSocket\([^,]+,\s*(\d{4})/g)) sent.add(match[1])
    const computed = relaySource.match(/const code = result === [^\n]+/)?.[0] ?? ""
    for (const match of computed.matchAll(/\b(\d{4})\b/g)) sent.add(match[1])
    expect([...sent].sort()).toEqual(Object.keys(LIMITS.closeCodes).sort())
  })

  it("returns 429 when the per-IP connection limiter refuses", async () => {
    const limited = { limit: async () => ({ success: false }) } as unknown as RateLimit
    const response = await worker.fetch(new Request(v3RoomUrl(), { headers: v3Headers() }), { ...env, CONN_LIMITER: limited } as unknown as Parameters<typeof worker.fetch>[1])
    expect(response.status).toBe(429)
  })

  it("returns 429 when the per-IP room limiter refuses a new room, and not for an existing one", async () => {
    const stub = room("contract-room-limiter")
    await runInDurableObject(stub, instance => {
      ;(instance as any).env = { ...env, ROOM_LIMITER: { limit: async () => ({ success: false }) } }
    })
    const refused = await stub.fetch(v3RoomUrl(), { headers: v3Headers() })
    expect(refused.status).toBe(429)
    await runInDurableObject(stub, instance => { (instance as any).env = env })
    const ws = await openV3Socket(stub); await drainSnapshot(ws)
    await runInDurableObject(stub, instance => {
      ;(instance as any).env = { ...env, ROOM_LIMITER: { limit: async () => ({ success: false }) } }
    })
    const existing = await stub.fetch(v3RoomUrl(), { headers: v3Headers() })
    expect(existing.status).toBe(101)
    existing.webSocket!.accept(); existing.webSocket!.close(); ws.close()
    await runInDurableObject(stub, instance => { (instance as any).env = env })
  })

  it("closes a socket on frame RATE_MAX_MSGS + 1 inside one fixed window", { timeout: 15_000 }, async () => {
    const stub = room("contract-fixed-window")
    const ws = await openV3Socket(stub); await drainSnapshot(ws)
    for (let index = 1; index < RELAY_LIMITS.RATE_MAX_MSGS; index += 1) ws.send(JSON.stringify({ t: "noop" }))
    // generous waits: the whole window is 10 s and a busy shared workerd can lag
    const pong = collectUntil(ws, frame => frame.t === "pong", 5_000)
    ws.send(JSON.stringify({ t: "ping" }))
    expect(await pong).toEqual({ t: "pong" })
    const closed = waitForClose(ws, 5_000)
    ws.send(JSON.stringify({ t: "noop" }))
    expect((await closed)?.code).toBe(4008)
  })
})

describe("delivery codes contract", () => {
  it("emits the documented op-nack codes with the documented retry flags", async () => {
    const stub = room("contract-nack-codes")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    const seen: Record<string, boolean> = {}
    const record = (response: any): void => {
      expect(response?.t).toBe("op-nack")
      seen[response.code] = response.retry
    }
    record(await reply(a, put(1, { rid: rid("hello-required") })))
    await helloOn(a, hello())
    record(await reply(a, put(1, { kind: "del", rid: rid("invalid") })))
    record(await reply(a, put(1, { sd: SD_2, rid: rid("session-mismatch") })))
    expect((await reply(a, put(2, { rid: rid("stored") }))).t).toBe("op-ack")
    record(await reply(a, put(1, { rid: rid("stale") })))
    record(await reply(a, put(20_003, { id: await wireIdFor("far"), rid: rid("counter-window") })))
    await runInDurableObject(stub, async instance => {
      ;(instance as any).failNextMutationStorageForTests = true
    })
    record(await reply(a, put(3, { rid: rid("storage") })))
    await runInDurableObject(stub, async (_instance, state) => {
      await state.storage.put("meta:totalRecords", RELAY_LIMITS.MAX_RECORDS)
    })
    record(await reply(a, put(4, { id: await wireIdFor("quota"), rid: rid("quota") })))
    // session-replaced is exercised by relay.test.ts (replacement fence), and
    // not-found is reserved: it only happens to deletes without rid, which never get a nack
    const documented = LIMITS.opNackCodes as Record<string, { retry: boolean; reserved?: string }>
    for (const [code, retry] of Object.entries(seen)) expect(documented[code]?.retry).toBe(retry)
    expect(Object.keys(seen).sort()).toEqual(
      Object.keys(documented).filter(code => code !== "session-replaced" && !documented[code].reserved).sort())
    expect(documented["not-found"].reserved).toBeTruthy()
    a.close()
  })

  it("emits every documented leave variant", async () => {
    const stub = room("contract-leave-variants")
    const peer = await openV3Socket(stub); await drainSnapshot(peer)
    const variants: string[] = []
    peer.addEventListener("message", event => {
      const frame = JSON.parse(event.data as string)
      if (frame.t !== "leave") return
      variants.push(...["transient", "explicit", "replaced"].filter(key => frame[key] === true))
    })
    const first = await openV3Socket(stub); await drainSnapshot(first)
    await helloOn(first, hello())
    const second = await openV3Socket(stub); await drainSnapshot(second)
    await helloOn(second, hello2())
    const explicitClose = waitForClose(second)
    second.send(JSON.stringify(await signedExplicitLeave(A, base64urlBytes(SD_2), 2)))
    expect((await explicitClose)?.code).toBe(1000)
    const third = await openV3Socket(stub); await drainSnapshot(third)
    const session3 = new Uint8Array(32).fill(3)
    await helloOn(third, await signedHello(A, session3, 3))
    third.close()
    await sleep(100)
    expect(variants).toEqual(["replaced", "explicit", "transient"])
    expect([...variants].sort()).toEqual([...LIMITS.leaveVariants].sort())
    peer.close(); first.close()
  })

  it("rejects a CT_MAX + 1 put as invalid and stores an exact CT_MAX one", { timeout: 20_000 }, async () => {
    const stub = room("contract-ct-max")
    const a = await openV3Socket(stub); await drainSnapshot(a)
    await helloOn(a, hello())
    const over = sealed(RELAY_LIMITS.CT_MAX * 3 / 4 + 3)
    expect(over.length).toBeGreaterThan(RELAY_LIMITS.CT_MAX)
    expect(await reply(a, put(1, { ct: over, rid: rid("ct-over") }))).toMatchObject({ code: "invalid", retry: false })
    const exact = sealed(RELAY_LIMITS.CT_MAX * 3 / 4)
    expect(exact.length).toBe(RELAY_LIMITS.CT_MAX)
    expect((await reply(a, put(2, { ct: exact, rid: rid("ct-exact") }))).t).toBe("op-ack")
    expect((await reply(a, del(3, { id: await wireIdFor("ct-other"), rid: rid("ct-del") }))).t).toBe("op-ack")
    const late = await openV3Socket(stub)
    const snapshot = await drainSnapshot(late, 10_000)
    expect(snapshot.items.map(item => item.vs).sort()).toEqual([stamp(2), stamp(3)])
    expect(Math.max(...snapshot.lengths)).toBeLessThanOrEqual(RELAY_LIMITS.SNAPSHOT_FRAME_BYTES)
    a.close(); late.close()
  })
})
