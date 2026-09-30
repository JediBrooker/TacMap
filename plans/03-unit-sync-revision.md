# Unit Sync revision (relay + iOS + Android): efficiency, stability, assurance

Status: in progress. Source audit: 6 specialist auditors + skeptic verification
(2026-09-30), ~80 confirmed findings (S1-xx .. S6-xx). Relay proofs live in the
audit scratch copies; they are re-created as real tests here.

## Hard constraints

- Shipped store clients (v3, strict `exactKeys` frame parsers) must keep working
  against the revised relay with behaviour at least as good as today. Do not add
  fields to existing server->client frames, do not send new frame types or new nack
  codes to clients that did not ask for them, do not change close-code meanings.
- New capabilities are negotiated, never assumed: a client opts in (for example a
  query parameter on the v3 WebSocket upgrade URL that old clients never send, or a
  new optional hello field that the relay already tolerates - verify which is safe).
  Old relay + new client must also still work (a new client must detect the missing
  capability and fall back).
- `docs/THREAT_MODEL.md` is binding. Any semantic change (retention, what the relay
  stores, metadata it sees) updates THREAT_MODEL, ADR-001/002/004 and sync/README in
  the same change. Relay-visible metadata must not grow without a documented reason.
- Every new or changed protocol constant/rule goes in the shared fixtures
  (`testdata/sync_protocol_v3.json`, `malicious_frames.json`, ...) loaded by relay,
  Android and iOS tests.
- Relay deploys to production only with the owner's explicit approval. Local
  verification uses vitest-pool-workers and `wrangler dev`.

## Phase SP1 - relay (sync/src/index.ts), wire-compatible

1. No durable write per frame (S1-02, S5-05, S3-16, S4-11, S6-06, verifier notes):
   track activity in memory and persist `meta:lastActivity` at most once per
   ACTIVITY_PERSIST_MS (and on last-socket close / before alarm evaluation); never on
   rejected frames; a failed activity write never closes a socket.
2. Idle expiry keeps monotonic room state (S1-01, S3-02, S6-01): expiry deletes the
   ciphertext of live objects and actor pins but keeps meta:auth, meta:protocol,
   meta:seq, meta:highWater and tombstones (opaque ids + stamps), so returning
   clients neither roll back nor get counter-window-locked nor resurrect deletes.
3. Tombstone/record compaction (S1-08, S3-13, S4-05): tombstones older than
   TOMBSTONE_TTL (e.g. 30 days, recorded server-side without changing the stored
   record shape that snapshots send) are compacted; quotas count what is actually
   retained; document the resurrection trade-off for clients offline longer than the
   TTL.
4. Failure isolation (S1-04, S4-08): nothing thrown inside blockConcurrencyWhile
   resets the room; a failed join snapshot fails only the joining socket.
5. Linear snapshot chunking (S1-05, S4-09, S5-04): running byte count, no
   re-serialisation per record. Measure before/after at 10k records.
6. Admission and liveness (S1-03, S4-10, S4 verifier note): sockets that never send
   hello within HELLO_DEADLINE, and CLOSING/CLOSED sockets, do not hold room slots;
   when the room-wide pending cap is hit, shed the socket responsible (largest
   pending) instead of whichever frame arrives next.
7. Rate limiting that does not drop valid work silently (S1-06): keep the close for
   old clients if nothing better is compatible, but make limits and their effect
   explicit in the fixture so new clients pace below them (SP2).
8. Size limits in the fixture (S1-09): CT_MAX, PRESENCE_CT_MAX, CHAT_CT_MAX,
   MAX_FRAME_BYTES, RATE_*, ROOM_PENDING_*, MAX_RECORDS, MAX_STORED_BYTES, snapshot
   page size.
9. Snapshot memory: bound concurrent full-snapshot builds / buffered bytes (S1
   verifier note 3); if not provable locally, document the limit.
10. README/ADR drift (S1-10).
11. Tests: every item above gets a vitest; add eviction/hibernation, alarm/expiry,
    connection cap, production rate limits, v3 snapshot paging and accounting
    migration tests (S6-08); move the audit soak harness into the repo
    (`sync/scripts/soak.mjs`, real v3 crypto, self-tested against the fixture) and the
    v3 fixture generator (S6-13).

## Phase SP2 - client stability (Android + iOS, same behaviour)

- Poison records (S2-10, S3-03, S4-01, S3 verifier note 3): a record that fails AEAD,
  signature, actor binding or parse is skipped and surfaced once; only structural
  and seq-fence violations fail the snapshot. A skipped id must not cause a
  stale-nack reconnect loop.
- Nack handling (S1-01 client side, S3-15, S6-03, S3 verifier note 2): losing an
  ordinary LWW race (stale) is not a SECURITY event and does not reconnect;
  counter-window stops the loop and surfaces once; backoff resets only after the
  session proved stable (first op-ack or N seconds), not at hello-ack.
- Watchdogs (S2-03, S3-05, S1 verifier note 1): progress-based on both platforms
  (re-armed per snapshot page), generous enough for slow links; Android gets a
  connect/upgrade timeout (S2-02).
- Flow control (S2-04, S3-06, S3-07, S5-08, S5-09, S6-07, S6 verifier notes): outbound
  pacing with a bounded in-flight window below the relay's per-socket budget,
  retransmit timers start when a frame is actually written and back off with queue
  depth, receive budget comfortably above the relay's per-sender allowance; the
  hello-ack resend of recoverable deletes and first-join publishes go through the same
  pacer. Pre-send size check against CT_MAX (S1-09) with a clear message.
- Lifecycle parity: Android keeps room membership across Activity pause like iOS
  (S2-01); iOS does not rotate the session on transient .inactive (S2-12); both use
  reachability to reconnect promptly (S2-13); jitter stays random at the cap (S2-14);
  HTTP upgrade status and close codes map to permanent vs transient (S2-11).
- Chat: replay fences pruned (S3-04, S4-06); history cap enforced by bytes (S4-07).
- Epoch recovery when replay state is lost (S3-11) without weakening replay rules.
- v2 legacy rooms: iOS/Android id casing parity (S3-01), equal-version tie (S3-14).
- Snapshot layer-metadata ordering (S3-08).

## Phase SP3 - client efficiency

- One persist per batch, never per record or per presence frame (S5-01, S5-03,
  S4-04, S2-09, S2 verifier note 1, S5 verifier notes 1 and 4): batched replay-state
  and journal writes, running high-water mark, no deep copies per transaction.
- Wire-id reverse lookup via a map built once (S5-02, S4-03, S3-10, S4 verifier note
  2).
- Snapshot validation/apply off the main thread (S3 verifier note 4).
- Presence: skip unchanged stationary resends within a keepalive window (S5-10); iOS
  redraws only the presence layer on a presence frame (S5-12); PBKDF2 off the UI
  thread (S5-13).
- Background presence (S2-05, S2-06, S2-07, S2-08, S5-07, S6-04, S6-05): a wake-safe
  keepalive and reconnect for opted-in background presence; background deliveries
  stop retrying without dropping acks; chat to a peer that cannot receive is not
  reported as delivered. Egress-only subscription (relay stops fanning out to a
  background presence socket) is a negotiated capability.

## Phase SP4 - negotiated protocol additions (only if SP1-SP3 are green)

- Resume from seq (S1-07, S5-06, S6-12): reconnect sends the last applied seq and
  receives only records changed since, with a full snapshot whenever the relay cannot
  prove continuity (expiry, compaction, seq gap). ADR-001 update + fixtures.
- Compact presence frame (S5-11).

## Verification

- Relay: vitest for every change, soak harness (N clients, random puts/dels/presence,
  random disconnects, relay restart) converging with zero stale-loop reconnects.
- Clients: deterministic state-machine tests through a transport seam (S6-02),
  malicious v3 frames through the real handlers (S4-12, S6-09).
- Live interop: iOS simulator + Android emulator in one room on local `wrangler dev`,
  asserting object and presence convergence both ways (S6-10).
