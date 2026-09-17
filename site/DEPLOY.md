# tacmap.app deployment gates

The website is a Cloudflare Worker with Static Assets. Before any deployment:

```sh
npm ci
npm run check:site
```

`site/src/index.mjs` runs before the asset binding. It preserves the full path
and query while returning `308` for non-loopback HTTP requests, adds the site
security headers to asset responses, and sends HSTS only from HTTPS responses
on the exact canonical host `tacmap.app`. It deliberately omits
`includeSubDomains` and `preload`: `www.tacmap.app` and every possible subdomain
have not been validated as HTTPS-only.

## Required Cloudflare account gate

Repository code cannot change zone settings. The release owner must first
confirm an active edge certificate for `tacmap.app`, then enable **SSL/TLS →
Edge Certificates → Always Use HTTPS** for the production zone. Cloudflare
recommends this edge redirect for an entirely HTTPS-capable application; the
Worker redirect remains a tested defence in depth. Static Assets `_redirects`
is not a substitute because Cloudflare does not support domain-level or
scheme-conditional redirects in that file.

Also confirm that the custom domain `tacmap.app` is attached to the
`tacmapapp` Worker and not to a legacy Pages project. `site/wrangler.jsonc`
deliberately does not declare or take ownership of an account-level route, so a
successful Worker upload alone does not prove that production serves it.

After those account checks, deploy from the repository root with:

```sh
npm exec --no -- wrangler deploy --config site/wrangler.jsonc
```

After deployment, verify both the external gate and the Worker response:

```sh
curl -sS -D - -o /dev/null 'http://tacmap.app/threat-model.html?check=1'
curl -sS -D - -o /dev/null 'https://tacmap.app/threat-model?check=1'
curl -sS -D - -o /dev/null 'https://tacmap.app/privacy?check=1'
```

The first command must return a permanent redirect whose `Location` is the
same URL under `https://`. Both HTTPS document responses must include the generated CSP,
`X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, the restrictive
Permissions Policy, both frame controls, and
`Strict-Transport-Security: max-age=31536000`.

Primary references: Cloudflare's Workers Static Assets documentation for
[_headers](https://developers.cloudflare.com/workers/static-assets/headers/),
[_redirects limitations](https://developers.cloudflare.com/workers/static-assets/redirects/),
and [HTTPS enforcement](https://developers.cloudflare.com/ssl/edge-certificates/encrypt-visitor-traffic/).
