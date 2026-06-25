/// <reference types="@cloudflare/workers-types" />

/**
 * TacMap sync backend — an end-to-end-**blind** relay for the shared tactical
 * picture. One Durable Object per unit "room"; clients connect over WebSocket
 * and exchange opaque encrypted blobs. The server only ever sees ciphertext
 * plus minimal routing metadata (a random object id, a version, and a coarse
 * `kind`) — the E2E keys are derived on devices from the unit join-code and
 * never leave them (see README + the client increments).
 *
 * Storage gives offline store-and-forward: the DO keeps the latest blob per
 * object id (last-write-wins by version, client-id tie-break) so a device that
 * was offline catches up via a snapshot on reconnect. Self-hostable: this is a
 * stock Worker + DO — a unit can `wrangler deploy` it to their own account.
 */

export interface Env {
  SYNC_ROOM: DurableObjectNamespace
}

// Room ids are high-entropy (a hash of the secret join-code, computed on the
// client) — knowing the room id lets you relay ciphertext but never decrypt it.
const ROOM_RE = /^\/room\/([A-Za-z0-9_-]{8,128})$/

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url)
    if (url.pathname === "/health") {
      return new Response("ok", { headers: { "content-type": "text/plain" } })
    }
    const match = url.pathname.match(ROOM_RE)
    if (!match) return new Response("Not found", { status: 404 })
    if (req.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("Expected a WebSocket upgrade", { status: 426 })
    }
    const stub = env.SYNC_ROOM.get(env.SYNC_ROOM.idFromName(match[1]))
    return stub.fetch(req)
  },
}

/** One stored object: opaque ciphertext + the metadata needed to route + merge. */
interface SyncRecord {
  id: string
  v: number        // logical version (client Lamport/HLC clock)
  by: string       // client id — deterministic tie-break for equal versions
  kind: string     // coarse type hint: "waypoint" | "drawing" | "layer"
  ct: string       // base64 ciphertext (server never decrypts)
  deleted: boolean // tombstone so deletes reach late joiners
}

/** True when `a` should overwrite `b` under last-write-wins. */
function isNewer(a: { v: number; by: string }, b: { v: number; by: string }): boolean {
  return a.v > b.v || (a.v === b.v && a.by > b.by)
}

export class SyncRoom {
  private state: DurableObjectState

  constructor(state: DurableObjectState, _env: Env) {
    this.state = state
  }

  async fetch(_req: Request): Promise<Response> {
    const pair = new WebSocketPair()
    const client = pair[0]
    const server = pair[1]
    // Hibernatable WebSocket — the DO can evict from memory between messages.
    this.state.acceptWebSocket(server)
    await this.sendSnapshot(server)
    return new Response(null, { status: 101, webSocket: client })
  }

  // ----- Hibernation API handlers -----

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    let msg: any
    try {
      const text = typeof message === "string" ? message : new TextDecoder().decode(message)
      msg = JSON.parse(text)
    } catch {
      return
    }
    switch (msg?.t) {
      case "put":
        await this.applyChange(ws, msg, false)
        break
      case "del":
        await this.applyChange(ws, msg, true)
        break
      case "ping":
        try { ws.send(JSON.stringify({ t: "pong" })) } catch { /* closed */ }
        break
    }
  }

  async webSocketClose(ws: WebSocket, code: number, _reason: string, _clean: boolean): Promise<void> {
    try { ws.close(code, "closing") } catch { /* already closed */ }
  }

  async webSocketError(_ws: WebSocket, _err: unknown): Promise<void> {
    // Nothing to do — the socket is torn down by the runtime.
  }

  // ----- Core -----

  private async applyChange(sender: WebSocket, msg: any, deleted: boolean): Promise<void> {
    const id = msg.id
    const v = msg.v
    const by = typeof msg.by === "string" ? msg.by : ""
    const kind = typeof msg.kind === "string" ? msg.kind : "unknown"
    const ct = typeof msg.ct === "string" ? msg.ct : ""
    if (typeof id !== "string" || id.length === 0 || id.length > 256) return
    if (typeof v !== "number" || !Number.isFinite(v)) return
    if (!deleted && ct.length === 0) return
    if (ct.length > 700_000) return // ~512KiB ciphertext ceiling (DO value limit)

    const key = "obj:" + id
    const existing = await this.state.storage.get<SyncRecord>(key)
    if (existing && !isNewer({ v, by }, existing)) return // stale — drop

    const record: SyncRecord = { id, v, by, kind, ct, deleted }
    await this.state.storage.put(key, record)
    this.broadcast({ t: deleted ? "del" : "put", ...record }, sender)
  }

  private async sendSnapshot(ws: WebSocket): Promise<void> {
    const map = await this.state.storage.list<SyncRecord>({ prefix: "obj:" })
    const items = [...map.values()]
    try {
      ws.send(JSON.stringify({ t: "snapshot", items }))
    } catch {
      /* socket closed before snapshot — ignore */
    }
  }

  private broadcast(payload: unknown, except: WebSocket): void {
    const data = JSON.stringify(payload)
    for (const ws of this.state.getWebSockets()) {
      if (ws === except) continue
      try { ws.send(data) } catch { /* drop dead sockets */ }
    }
  }
}
