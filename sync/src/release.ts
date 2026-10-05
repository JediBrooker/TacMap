// Keep release metadata outside the Worker entry module. Cloudflare interprets
// every runtime export from index.ts as a handler or Durable Object class.
// /health sends the id, and a deploy only counts as verified once it matches,
// so every relay change needs a new one (README, Deployed instance).
export const RELAY_RELEASE_ID = "tacmap-sync-3.0.1-epoch-floor"
// sha256 of what that id ships: src/index.ts, src/limits.ts and wrangler.jsonc
// (test/contract.test.ts recomputes it). once it goes stale the suite fails,
// which is the reminder to move the id above as well
export const RELAY_RELEASE_SOURCE_SHA256 = "7c5ee0dba567605ffc955d4808d06c0743f19ee8b7ce1486205c558f16ab6808"
