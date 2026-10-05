// Every relay limit in one place. testdata/sync_protocol_v3.json relayLimits.values
// has to match this object exactly (test/contract.test.ts), so clients can pace
// under the same numbers the relay enforces. Kept out of index.ts because the
// worker entry module may only export runtime entrypoints.
export const RELAY_LIMITS = {
  MAX_CONNECTIONS: 64,
  // hard cap on accepted sockets incl ones the relay closed that never echoed
  MAX_ACCEPTED_SOCKETS: 128,
  // pre-hello v3 sockets past this lose their slot when the room is full. the
  // clock gets extra time for the snapshot they were sent, at a slow-link rate
  HELLO_DEADLINE_MS: 60_000,
  HELLO_DEADLINE_BYTES_PER_SEC: 100_000,
  MAX_RECORDS: 10_000, // objects + retained tombstones + actor pins + epoch floors
  MAX_STORED_BYTES: 50_000_000,
  MAX_V: 1e12, // v2 only
  CT_MAX: 700_000,
  PRESENCE_CT_MAX: 8_192,
  CHAT_CT_MAX: 16_384,
  MAX_FRAME_BYTES: 1_048_576,
  SNAPSHOT_FRAME_BYTES: 900_000,
  STORAGE_PAGE_SIZE: 100,
  // first read of a scan is small, later reads size themselves to the budget
  STORAGE_FIRST_PAGE_SIZE: 4,
  STORAGE_READ_BUDGET_BYTES: 4 * 1_048_576,
  RATE_WINDOW_MS: 10_000,
  RATE_MAX_MSGS: 200,
  RATE_MAX_BYTES: 4 * 1_048_576,
  ROOM_PENDING_MAX_MSGS: 512,
  ROOM_PENDING_MAX_BYTES: 8 * 1_048_576,
  ADVANCE_WINDOW: 10_000,
  IDLE_TTL_MS: 7 * 24 * 60 * 60 * 1000,
  TOMBSTONE_TTL_MS: 30 * 24 * 60 * 60 * 1000,
  // after this long with no activity at all the room is wiped outright, meta
  // rows and tombstones included. a device coming back later sees a fresh room
  ROOM_PURGE_TTL_MS: 90 * 24 * 60 * 60 * 1000,
  ACTIVITY_PERSIST_MS: 60 * 60 * 1000,
  MAINTENANCE_INTERVAL_MS: 24 * 60 * 60 * 1000,
  // a maintenance pass that failed part way comes back this soon
  MAINTENANCE_RETRY_MS: 60 * 60 * 1000,
} as const
