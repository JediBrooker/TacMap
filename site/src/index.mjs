import { CONTENT_SECURITY_POLICY } from "./security-policy.mjs";

const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);

export const SECURITY_HEADERS = Object.freeze({
  "Content-Security-Policy": CONTENT_SECURITY_POLICY,
  "Cross-Origin-Opener-Policy": "same-origin",
  "Cross-Origin-Resource-Policy": "same-origin",
  "Permissions-Policy": [
    "accelerometer=()",
    "autoplay=()",
    "camera=()",
    "display-capture=()",
    "encrypted-media=()",
    "fullscreen=()",
    "geolocation=()",
    "gyroscope=()",
    "magnetometer=()",
    "microphone=()",
    "payment=()",
    "publickey-credentials-get=()",
    "screen-wake-lock=()",
    "usb=()",
  ].join(", "),
  "Referrer-Policy": "no-referrer",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
});

export function shouldRedirectToHttps(url) {
  return url.protocol === "http:" && !LOOPBACK_HOSTS.has(url.hostname);
}

export function withSecurityHeaders(response, requestUrl) {
  const headers = new Headers(response.headers);
  for (const [name, value] of Object.entries(SECURITY_HEADERS)) {
    headers.set(name, value);
  }
  // The canonical domain has a currently valid Cloudflare edge certificate.
  // Do not extend HSTS to unverified subdomains or preview hosts.
  if (requestUrl.protocol === "https:" && requestUrl.hostname === "tacmap.app") {
    headers.set("Strict-Transport-Security", "max-age=31536000");
  } else {
    headers.delete("Strict-Transport-Security");
  }
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
}

export default {
  async fetch(request, env) {
    const requestUrl = new URL(request.url);
    if (shouldRedirectToHttps(requestUrl)) {
      const destination = new URL(requestUrl);
      destination.protocol = "https:";
      const redirect = new Response(null, {
        status: 308,
        headers: {
          "Cache-Control": "no-store",
          Location: destination.toString(),
        },
      });
      return withSecurityHeaders(redirect, requestUrl);
    }

    return withSecurityHeaders(await env.ASSETS.fetch(request), requestUrl);
  },
};
