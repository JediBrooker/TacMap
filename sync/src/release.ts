// Keep release metadata outside the Worker entry module. Cloudflare interprets
// every runtime export from index.ts as a handler or Durable Object class.
// /health sends the id, and a deploy only counts as verified once it matches,
// so every relay change needs a new one (README, Deployed instance).
export const RELAY_RELEASE_ID = "tacmap-sync-3.0.1-epoch-floor"
// sha256 of what that id ships: src/index.ts, src/limits.ts and wrangler.jsonc
// (test/contract.test.ts recomputes it). once it goes stale the suite fails,
// which is the reminder to move the id above as well
export const RELAY_RELEASE_SOURCE_SHA256 = "24ba66f78e5dd2aef34d86b31c56300968d9ff6b5477b99c7757c8227a004eeb"
