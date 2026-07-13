import { cloudflareTest } from "@cloudflare/vitest-pool-workers"
import { defineConfig } from "vitest/config"

export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.jsonc" },
      // Keep the shared Miniflare process from rate-limiting unrelated test
      // setup; production still uses the limits in wrangler.jsonc.
      miniflare: {
        ratelimits: {
          CONN_LIMITER: { namespace_id: "1001", simple: { period: 60, limit: 10_000 } },
          ROOM_LIMITER: { namespace_id: "1002", simple: { period: 60, limit: 10_000 } },
        },
      },
    }),
  ],
})
