// Keep release metadata outside the Worker entry module. Cloudflare interprets
// every runtime export from index.ts as a handler or Durable Object class.
export const RELAY_RELEASE_ID = "tacmap-sync-2.1.0-sp1"
