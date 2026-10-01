#!/usr/bin/env node
// v3 relay soak / convergence harness (S6-11). Real v3 wire format from the
// shared fixture (self-tested before any traffic): PBKDF2 room derivation,
// Ed25519 hello/put/del/loc proofs, AES-GCM sealed inner envelopes with the
// production AADs. Every client verifies every record and presence frame it
// receives, the way the apps do.
//
//   npm run soak -- --spawn --clients 8 --seconds 90 --restart-at 30,60
//   node scripts/soak.mjs --relay ws://127.0.0.1:8799 --clients 6 --seconds 30
//
// --spawn            start `wrangler dev --local` on --port (default 8799) with a
//                    persisted state dir, so --restart-at can bounce it mid-run
// --restart-at <s>   kill and restart the spawned relay (a deploy/eviction) at
//                    second s. comma list or repeat the flag for several
// --inspector-port <n>  devtools port for the spawned wrangler, when something
//                    else on the box already holds the default 9229
// --seed <n>         workload rng seed (crypto nonces stay random)
// --app-nack-reconnect  mimic shipped apps: reconnect on stale/not-found/counter-window
//
// The report attributes every socket close: "drop" (our own random flap),
// "nack" (an --app-nack-reconnect reconnect), "restart" (inside a relay
// restart window) or "relay" (anything else, listed with its second, code
// and reason). closeReasons is the raw code+reason histogram. nackReconnects
// counts reconnects a nack triggered, by code, plus the longest run of them
// with no op-ack in between (a stale loop shows up there).
//
// Client model mirrors the apps: counter = max(local, seen) + 1, a fresh session
// and hello epoch + 1 per connection, unacked ops re-signed for the new session
// with the same stamp after hello-ack, own tombstones the snapshot didn't
// confirm resent after hello-ack, stale/not-found dropped, exponential backoff.
// Exit 1 unless every client and a late joiner converge with zero verify failures.
import crypto from "node:crypto"
import { spawn } from "node:child_process"
import { mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { fileURLToPath } from "node:url"
import WebSocket from "ws"
import {
  actorId, b64u, compareStamps, deriveRoom, generateKeyPair, helloFrame, hex16, objectFrame,
  presenceFrame, selfTest, sha256, verifyPresence, verifyRecord, wireObjectId,
} from "./v3-wire.mjs"

const argv = process.argv.slice(2)
const flag = name => argv.includes(`--${name}`)
const option = (name, fallback) => {
  const index = argv.indexOf(`--${name}`)
  return index >= 0 && argv[index + 1] !== undefined && !argv[index + 1].startsWith("--") ? argv[index + 1] : fallback
}
const SPAWN = flag("spawn")
const PORT = Number(option("port", "8799"))
const INSPECTOR_PORT = option("inspector-port", null)
const RELAY = option("relay", `ws://127.0.0.1:${PORT}`)
const CLIENTS = Number(option("clients", "8"))
const SECONDS = Number(option("seconds", "60"))
// every --restart-at value, comma lists included, as ascending seconds
const RESTARTS = argv.flatMap((arg, index) => arg === "--restart-at" && argv[index + 1] !== undefined ? argv[index + 1].split(",") : [])
  .map(Number).filter(Number.isFinite).sort((a, b) => a - b)
const APP_NACK_RECONNECT = flag("app-nack-reconnect")
const POOL_SIZE = Number(option("pool", "24"))
let seed = Number(option("seed", "11"))
const CODE = option("code", `soak-${crypto.randomBytes(6).toString("hex")}`)
const SYNC_DIR = fileURLToPath(new URL("..", import.meta.url))

// mulberry32, so a failing workload can be replayed by seed
const rnd = n => {
  seed = (seed + 0x6d2b79f5) | 0
  let t = Math.imul(seed ^ (seed >>> 15), 1 | seed)
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
  return ((t ^ (t >>> 14)) >>> 0) % n
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

selfTest()
const room = deriveRoom(CODE)
const POOL = Array.from({ length: POOL_SIZE }, () => crypto.randomUUID())
const POOL_WIRE = POOL.map(uuid => wireObjectId(room.metadataKey, Buffer.from(uuid.replace(/-/g, ""), "hex")))

const metrics = {
  acks: 0, nacks: {}, reconnects: 0, closeCodes: {}, closeReasons: {}, httpStatus: {}, verifyFailures: 0,
  closeCause: { drop: 0, nack: 0, restart: 0, relay: 0 }, relayCloses: [],
  nackReconnects: { total: 0, byCode: {}, longestRun: 0 },
  presence: { sent: 0, received: 0, verifyFailures: 0, latencyMs: [] },
  fanoutMs: [], snapshotBytes: [], tombstoneResends: 0, restarts: [], bytesIn: 0, bytesOut: 0,
}
const sentAt = new Map()
const count = (bucket, key) => { bucket[key] = (bucket[key] ?? 0) + 1 }
let startedAt = Date.now()
const second = at => +((at - startedAt) / 1000).toFixed(1)
// [down, up] per restart, padded a bit for closes the OS reports late
const restartWindows = []
const inRestartWindow = at => restartWindows.some(([down, up]) => at >= down - 500 && at <= (up ?? Infinity) + 2_000)

class Client {
  constructor(index) {
    this.index = index
    Object.assign(this, generateKeyPair())
    this.actor = actorId(room.roomIdRaw, this.pubRaw)
    this.replica = new Map()
    this.counter = 0n
    this.epoch = 0n
    this.pending = new Map()
    this.ownTombstones = new Map()
    this.connected = false
    this.backoff = 250
    this.presenceCounter = 0n
    // why we're about to close our own socket, if we are
    this.closingFor = null
    this.nackRun = 0
  }

  identity() {
    return { room, actor: this.actor, pubRaw: this.pubRaw, privateKey: this.privateKey, sessionDomain: this.sd }
  }

  connect() {
    if (this.stopped) return
    this.sd = crypto.randomBytes(32)
    this.presenceCounter = 0n
    this.connected = false
    this.snapshot = null
    const ws = new WebSocket(`${RELAY}/v3/room/${room.roomId}`, {
      headers: { Authorization: `Bearer ${room.authToken}`, "X-Protocol": "3", "X-Room-Id": room.roomId },
    })
    this.ws = ws
    ws.on("unexpected-response", (request, response) => {
      count(metrics.httpStatus, response.statusCode)
      // detach first so the close this triggers isn't counted twice
      this.ws = undefined
      request.destroy()
      this.scheduleReconnect()
    })
    ws.on("error", () => {})
    ws.on("close", (code, reasonBuffer) => {
      // our own shutdown after the report isn't a close worth counting
      if (this.ws !== ws || this.stopped) return
      const reason = reasonBuffer?.toString() ?? ""
      count(metrics.closeCodes, code)
      count(metrics.closeReasons, reason ? `${code} ${reason}` : String(code))
      const cause = this.closingFor ?? (inRestartWindow(Date.now()) ? "restart" : "relay")
      this.closingFor = null
      metrics.closeCause[cause] += 1
      if (cause === "relay") metrics.relayCloses.push({ second: second(Date.now()), client: this.index, code, reason })
      this.connected = false
      this.scheduleReconnect()
    })
    ws.on("message", data => {
      metrics.bytesIn += data.length
      this.onFrame(JSON.parse(data.toString()), data.length)
    })
  }

  scheduleReconnect() {
    if (this.stopped || this.reconnecting) return
    this.reconnecting = true
    metrics.reconnects += 1
    const delay = this.backoff + rnd(this.backoff)
    this.backoff = Math.min(this.backoff * 2, 8_000)
    setTimeout(() => { this.reconnecting = false; this.connect() }, delay)
  }

  send(frame) {
    const text = JSON.stringify(frame)
    metrics.bytesOut += text.length
    try { this.ws.send(text) } catch { /* reconnect will resend */ }
  }

  fold(record) {
    const current = this.replica.get(record.id)
    if (!current || compareStamps(record.vs, current.vs) > 0) {
      this.replica.set(record.id, { vs: record.vs, deleted: record.deleted === true || record.t === "del", cth: sha256(record.ct).toString("hex") })
    }
    const counter = BigInt("0x" + record.vs.slice(0, 16))
    if (counter > this.counter) this.counter = counter
    // a newer write from anyone means our old delete is no longer ours to resend
    const tomb = this.ownTombstones.get(record.id)
    if (tomb && compareStamps(record.vs, tomb) > 0) this.ownTombstones.delete(record.id)
  }

  accept(record) {
    if (verifyRecord(room, record) === null) {
      metrics.verifyFailures += 1
      return false
    }
    this.fold(record)
    return true
  }

  onFrame(frame, length) {
    switch (frame.t) {
      case "snapshot-begin":
        this.snapshot = { items: [], bytes: length }
        break
      case "snapshot":
        this.snapshot.items.push(...frame.items)
        this.snapshot.bytes += length
        break
      case "snapshot-end": {
        metrics.snapshotBytes.push(this.snapshot.bytes + length)
        this.confirmed = new Map()
        for (const record of this.snapshot.items) {
          if (this.accept(record) && record.deleted) this.confirmed.set(record.id, record.vs)
        }
        this.epoch += 1n
        this.helloFrame = helloFrame({ ...this.identity(), epoch: this.epoch })
        this.send(this.helloFrame)
        break
      }
      case "hello-ack":
        if (frame.vs !== this.helloFrame?.vs) break
        this.connected = true
        this.backoff = 250
        for (const op of [...this.pending.values()]) this.transmit(op)
        for (const [id, vs] of this.ownTombstones) {
          if (this.confirmed?.get(id) === vs || [...this.pending.values()].some(op => op.id === id)) continue
          metrics.tombstoneResends += 1
          this.transmit({ id, vs, kind: "del" })
        }
        break
      case "put":
      case "del": {
        const key = `${frame.id}|${frame.vs}`
        if (sentAt.has(key)) metrics.fanoutMs.push(Date.now() - sentAt.get(key))
        this.accept(frame)
        break
      }
      case "loc":
        metrics.presence.received += 1
        if (!verifyPresence(room, frame)) metrics.presence.verifyFailures += 1
        if (sentAt.has(`loc|${frame.by}|${frame.vs}`)) metrics.presence.latencyMs.push(Date.now() - sentAt.get(`loc|${frame.by}|${frame.vs}`))
        break
      case "op-ack": {
        const op = this.pending.get(frame.rid)
        if (!op) break
        this.pending.delete(frame.rid)
        this.nackRun = 0
        metrics.acks += 1
        this.fold(op.frame)
        if (op.kind === "del") this.ownTombstones.set(op.id, op.vs)
        else this.ownTombstones.delete(op.id)
        break
      }
      case "op-nack": {
        count(metrics.nacks, frame.code)
        const op = this.pending.get(frame.rid)
        if (!op || frame.retry) break
        this.pending.delete(frame.rid)
        if (op.kind === "del" && this.ownTombstones.get(op.id) === op.vs) this.ownTombstones.delete(op.id)
        if (APP_NACK_RECONNECT && ["stale", "not-found", "counter-window"].includes(frame.code) && !this.closingFor) {
          this.closingFor = "nack"
          this.nackRun += 1
          metrics.nackReconnects.total += 1
          count(metrics.nackReconnects.byCode, frame.code)
          metrics.nackReconnects.longestRun = Math.max(metrics.nackReconnects.longestRun, this.nackRun)
          this.ws.terminate()
        }
        break
      }
    }
  }

  newRid() {
    return `soak_${crypto.randomBytes(12).toString("base64url")}`
  }

  // queue and resend are the same thing: a new session means a new signature
  // and request id over the same stamp, which is what the apps do too
  transmit(op) {
    if (op.rid) this.pending.delete(op.rid)
    const rid = this.newRid()
    op.rid = rid
    this.pending.set(rid, op)
    op.frame = objectFrame({ ...this.identity(), wireId: op.id, vs: op.vs, kind: op.kind, content: op.content, rid })
    sentAt.set(`${op.id}|${op.vs}`, Date.now())
    this.send(op.frame)
  }

  mutate() {
    if (!this.connected) return
    const index = rnd(POOL.length)
    this.counter += 1n
    const deleted = rnd(6) === 0
    this.transmit({
      id: POOL_WIRE[index], vs: `${hex16(this.counter)}:${this.actor}`, kind: deleted ? "del" : "waypoint",
      content: deleted ? undefined : JSON.stringify({
        type: "Feature", id: POOL[index],
        geometry: { type: "Point", coordinates: [150 + rnd(1000) / 1000, -33 - rnd(1000) / 1000] },
        properties: { name: `c${this.index}-${this.counter}` },
      }),
    })
  }

  presence() {
    if (!this.connected) return
    this.presenceCounter += 1n
    const frame = presenceFrame({
      ...this.identity(), counter: this.presenceCounter,
      fields: {
        lat: -33.8 + rnd(1000) / 1e5, lon: 151.2 + rnd(1000) / 1e5, heading: rnd(360), speed: rnd(20) / 10,
        callsign: `S${this.index}`, affiliation: "FRIEND", echelon: "TEAM", function: "INFANTRY", isHQ: false,
      },
    })
    metrics.presence.sent += 1
    sentAt.set(`loc|${frame.by}|${frame.vs}`, Date.now())
    this.send(frame)
  }

  stop() {
    this.stopped = true
    try { this.ws?.close() } catch { /* gone */ }
  }
}

let relayProcess = null
let persistDir = null
function startRelay() {
  persistDir ??= mkdtempSync(join(tmpdir(), "tacmap-soak-"))
  const args = ["wrangler", "dev", "--local", "--port", String(PORT), "--ip", "127.0.0.1", "--persist-to", persistDir]
  if (INSPECTOR_PORT !== null) args.push("--inspector-port", String(INSPECTOR_PORT))
  const child = spawn("npx", args, {
    cwd: SYNC_DIR, stdio: ["ignore", "pipe", "pipe"], detached: true,
    env: { ...process.env, NO_COLOR: "1", WRANGLER_SEND_METRICS: "false" },
  })
  child.stdout.on("data", () => {})
  child.stderr.on("data", () => {})
  return child
}

async function stopRelay(child) {
  if (!child || child.exitCode !== null) return
  const exited = new Promise(resolve => child.once("exit", resolve))
  try { process.kill(-child.pid, "SIGINT") } catch { child.kill("SIGINT") }
  const timeout = sleep(5_000).then(() => { try { process.kill(-child.pid, "SIGKILL") } catch { /* gone */ } })
  await Promise.race([exited, timeout])
}

async function waitForHealth() {
  const url = RELAY.replace(/^ws/, "http") + "/health"
  for (let attempt = 0; attempt < 240; attempt += 1) {
    try { if ((await fetch(url)).ok) return } catch { /* not up yet */ }
    await sleep(250)
  }
  throw new Error(`relay at ${url} did not come up`)
}

const percentile = (values, p) => values.length === 0 ? null
  : values.slice().sort((a, b) => a - b)[Math.min(values.length - 1, Math.floor((values.length - 1) * p))]

async function main() {
  if (SPAWN) relayProcess = startRelay()
  await waitForHealth()
  const clients = Array.from({ length: CLIENTS }, (_, index) => new Client(index))
  for (const client of clients) { client.connect(); await sleep(100) }
  const started = Date.now()
  startedAt = started
  const tick = setInterval(() => {
    for (const client of clients) {
      if (rnd(3) === 0) client.mutate()
      if (rnd(10) === 0) client.presence()
      if (client.connected && rnd(400) === 0) { // abrupt mobile flap
        client.closingFor ??= "drop"
        client.ws.terminate()
      }
    }
  }, 50)
  let restart = null
  if (RESTARTS.length > 0) {
    if (!SPAWN) console.error("--restart-at needs --spawn, ignoring")
    else {
      restart = (async () => {
        for (const at of RESTARTS) {
          const wait = started + at * 1000 - Date.now()
          if (wait > 0) await sleep(wait)
          const down = Date.now()
          const span = [down, null]
          restartWindows.push(span)
          await stopRelay(relayProcess)
          relayProcess = startRelay()
          await waitForHealth()
          span[1] = Date.now()
          metrics.restarts.push({ atSecond: at, actualSecond: second(down), downMs: span[1] - down })
        }
      })()
    }
  }
  await sleep(SECONDS * 1000)
  clearInterval(tick)
  await restart

  // quiesce: everyone connected with nothing in flight
  for (let attempt = 0; attempt < 240 && clients.some(client => !client.connected || client.pending.size > 0); attempt += 1) {
    await sleep(250)
  }
  await sleep(500)
  const observer = new Client(99)
  observer.connect()
  for (let attempt = 0; attempt < 120 && !observer.connected; attempt += 1) await sleep(100)
  const truth = JSON.stringify([...observer.replica].sort())
  const diverged = clients.filter(client => JSON.stringify([...client.replica].sort()) !== truth).map(client => client.index)
  const converged = observer.connected && diverged.length === 0 && metrics.verifyFailures === 0 && metrics.presence.verifyFailures === 0

  const report = {
    relay: RELAY, spawned: SPAWN, clients: CLIENTS, seconds: SECONDS, seed: Number(option("seed", "11")),
    elapsedMs: Date.now() - started, converged, diverged, lateJoinerConnected: observer.connected,
    objects: observer.replica.size, liveObjects: [...observer.replica.values()].filter(value => !value.deleted).length,
    stillPending: clients.reduce((sum, client) => sum + client.pending.size, 0),
    acks: metrics.acks, acksPerSec: +(metrics.acks / SECONDS).toFixed(1), nacks: metrics.nacks,
    reconnects: metrics.reconnects, closeCodes: metrics.closeCodes, closeReasons: metrics.closeReasons,
    closeCause: metrics.closeCause, relayCloses: metrics.relayCloses, nackReconnects: metrics.nackReconnects,
    httpStatus: metrics.httpStatus,
    verifyFailures: metrics.verifyFailures, tombstoneResends: metrics.tombstoneResends,
    fanoutLatencyMs: {
      n: metrics.fanoutMs.length, p50: percentile(metrics.fanoutMs, 0.5), p95: percentile(metrics.fanoutMs, 0.95),
      p99: percentile(metrics.fanoutMs, 0.99), max: percentile(metrics.fanoutMs, 1),
    },
    presence: {
      sent: metrics.presence.sent, received: metrics.presence.received, verifyFailures: metrics.presence.verifyFailures,
      p50: percentile(metrics.presence.latencyMs, 0.5), p95: percentile(metrics.presence.latencyMs, 0.95),
    },
    snapshots: {
      count: metrics.snapshotBytes.length, maxBytes: Math.max(0, ...metrics.snapshotBytes),
      totalBytes: metrics.snapshotBytes.reduce((sum, value) => sum + value, 0),
    },
    restarts: metrics.restarts, wireBytes: { in: metrics.bytesIn, out: metrics.bytesOut },
  }
  console.log(JSON.stringify(report, null, 1))
  for (const client of [...clients, observer]) client.stop()
  return converged
}

let ok = false
try {
  ok = await main()
} catch (error) {
  console.error(error)
} finally {
  await stopRelay(relayProcess)
  if (persistDir) rmSync(persistDir, { recursive: true, force: true })
}
process.exit(ok ? 0 : 1)
