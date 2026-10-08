import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import test from "node:test";

import worker, {
  SECURITY_HEADERS,
  shouldRedirectToHttps,
  withSecurityHeaders,
} from "../src/index.mjs";
import { CONTENT_SECURITY_POLICY } from "../src/security-policy.mjs";

const sha256Source = (value) =>
  `'sha256-${createHash("sha256").update(value, "utf8").digest("base64")}'`;
const inlineBlocks = (document, tag) => {
  const pattern = new RegExp(`<${tag}(?:\\s[^>]*)?>([\\s\\S]*?)</${tag}>`, "gi");
  return [...document.matchAll(pattern)].map((match) => match[1]);
};

test("redirect policy preserves the full URL and excludes local development", async () => {
  assert.equal(shouldRedirectToHttps(new URL("http://tacmap.app/a?q=1")), true);
  assert.equal(shouldRedirectToHttps(new URL("https://tacmap.app/a?q=1")), false);
  assert.equal(shouldRedirectToHttps(new URL("http://127.0.0.1:8788/a")), false);

  let assetFetches = 0;
  const response = await worker.fetch(
    new Request("http://tacmap.app/field/map?grid=55HFA"),
    { ASSETS: { fetch: async () => { assetFetches += 1; return new Response("unused"); } } },
  );
  assert.equal(response.status, 308);
  assert.equal(response.headers.get("location"), "https://tacmap.app/field/map?grid=55HFA");
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.equal(response.headers.has("strict-transport-security"), false);
  assert.equal(assetFetches, 0);
});

test("asset responses receive strict headers and scoped HSTS", async () => {
  const canonical = withSecurityHeaders(
    new Response("ok", { headers: { "Content-Type": "text/plain" } }),
    new URL("https://tacmap.app/"),
  );
  assert.equal(canonical.headers.get("strict-transport-security"), "max-age=31536000");
  assert.equal(canonical.headers.get("x-frame-options"), "DENY");
  assert.equal(canonical.headers.get("x-content-type-options"), "nosniff");
  assert.equal(canonical.headers.get("referrer-policy"), "no-referrer");
  assert.equal(canonical.headers.get("content-security-policy"), CONTENT_SECURITY_POLICY);
  assert.equal(canonical.headers.get("content-type"), "text/plain");

  const preview = withSecurityHeaders(new Response("ok"), new URL("https://preview.workers.dev/"));
  assert.equal(preview.headers.has("strict-transport-security"), false);
});

test("CSP pins every inline block and permits no unsafe or third-party source", async () => {
  const documents = await Promise.all([
    readFile(new URL("../public/index.html", import.meta.url), "utf8"),
    readFile(new URL("../public/threat-model.html", import.meta.url), "utf8"),
    readFile(new URL("../public/privacy.html", import.meta.url), "utf8"),
    ...["de/privacy.html", "de/support.html", "de.html", "support.html", "custom-symbols.html", "de/custom-symbols.html"].map(path => readFile(new URL("../public/" + path, import.meta.url), "utf8")),
  ]);
  const styleBlocks = documents.flatMap((document) => inlineBlocks(document, "style"));
  const scriptBlocks = documents.flatMap((document) => inlineBlocks(document, "script"));
  // index.html and de.html each carry 2 style blocks (main + noscript) and the
  // nav script, the 7 generated doc pages carry one style block each
  assert.equal(styleBlocks.length, 11);
  assert.equal(scriptBlocks.length, 2);
  for (const block of [...styleBlocks, ...scriptBlocks]) {
    assert.match(CONTENT_SECURITY_POLICY, new RegExp(sha256Source(block).replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  }
  assert.doesNotMatch(CONTENT_SECURITY_POLICY, /unsafe-inline|unsafe-eval|https?:/);
  assert.match(CONTENT_SECURITY_POLICY, /default-src 'none'/);
  assert.match(CONTENT_SECURITY_POLICY, /connect-src 'none'/);
  assert.match(CONTENT_SECURITY_POLICY, /frame-ancestors 'none'/);
  assert.match(CONTENT_SECURITY_POLICY, /style-src-attr 'none'/);
  assert.match(CONTENT_SECURITY_POLICY, /script-src-attr 'none'/);
  for (const document of documents) {
    assert.doesNotMatch(document, /fonts\.(?:googleapis|gstatic)\.com/i);
    assert.doesNotMatch(document, /\sstyle=/i);
  }
  assert.equal(SECURITY_HEADERS["Content-Security-Policy"], CONTENT_SECURITY_POLICY);
});

test("homepage gallery shows the 2.2 store set", async () => {
  const homepage = await readFile(new URL("../public/index.html", import.meta.url), "utf8");

  assert.match(homepage, /8 store screens · iPhone and Android/);
  // odd slides from the iPhone set, even ones from Android, see build_site_gallery.py
  for (const stem of ["01-hero", "android-02-line-of-sight", "03-night-mode", "android-04-unit-sync",
                      "05-sun-moon", "android-06-pdfmap", "07-symbol-builder", "android-08-export"]) {
    assert.match(homepage, new RegExp(`assets/store/${stem}\\.webp`));
  }
  // nothing from the retired 2.0 set
  assert.doesNotMatch(homepage, /assets\/store\/(?:02-unit-sync|04-recording|05-weather|06-basemaps|09-search|android-01-field-tools)/);
  assert.doesNotMatch(homepage, /release candidate/);
});

test("english and german homepages stay in step", async () => {
  const [en, de] = await Promise.all([
    readFile(new URL("../public/index.html", import.meta.url), "utf8"),
    readFile(new URL("../public/de.html", import.meta.url), "utf8"),
  ]);
  // same css + js byte for byte, so one CSP hash covers both
  assert.deepEqual(inlineBlocks(de, "style"), inlineBlocks(en, "style"));
  assert.deepEqual(inlineBlocks(de, "script"), inlineBlocks(en, "script"));

  // same sections in the same order, same number of cards / faq entries
  const ids = (doc) => [...doc.matchAll(/\sid="([^"]+)"/g)].map((m) => m[1]);
  const count = (doc, re) => (doc.match(re) ?? []).length;
  assert.deepEqual(ids(de), ids(en));
  for (const re of [/class="feat"/g, /class="qa"/g, /class="shot"/g, /class="who-card"/g, /class="vlink"/g, /<tr>/g]) {
    assert.equal(count(de, re), count(en, re), `count mismatch for ${re}`);
  }
  // gallery shows the same approved files (de uses absolute paths)
  const shots = (doc) => [...doc.matchAll(/(?:src|srcset)="\/?(assets\/store\/[^"]+)"/g)].map((m) => m[1]);
  assert.deepEqual(shots(de), shots(en));

  // the feature loop is live on both, muted so it can autoplay
  for (const doc of [en, de]) {
    assert.match(doc, /<video autoplay muted loop playsinline/);
    assert.match(doc, /assets\/video\/feature\.webm/);
    assert.doesNotMatch(doc, /<!-- MEDIA:/);
  }

  // the profile is the one field tool that goes online, keep saying so
  assert.match(en, /online lookups are on, and each one sends up to 200\s+points/);
  assert.match(de, /eingeschalteten Online-Abfragen\s+abgerufen, und jedes sendet bis zu 200 Punkte/);
  assert.match(de, /<html lang="de">/);
});

test("sitemap lists every canonical page and en/de pages point at each other", async () => {
  const pages = ["index.html", "de.html", "custom-symbols.html", "de/custom-symbols.html", "threat-model.html",
                 "privacy.html", "de/privacy.html", "de/support.html", "support.html"];
  const [sitemap, robots, ...documents] = await Promise.all([
    readFile(new URL("../public/sitemap.xml", import.meta.url), "utf8"),
    readFile(new URL("../public/robots.txt", import.meta.url), "utf8"),
    ...pages.map((path) => readFile(new URL("../public/" + path, import.meta.url), "utf8")),
  ]);
  assert.match(robots, /^Sitemap: https:\/\/tacmap\.app\/sitemap\.xml$/m);

  const locs = [...sitemap.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1]);
  const canonicals = documents.map((doc) => doc.match(/<link rel="canonical" href="([^"]+)">/)[1]);
  assert.deepEqual([...locs].sort(), [...canonicals].sort());

  const alternates = (doc) => Object.fromEntries(
    [...doc.matchAll(/<link rel="alternate" hreflang="([^"]+)" href="([^"]+)">/g)].map((m) => [m[1], m[2]]));
  for (const [en, de] of [["index.html", "de.html"], ["custom-symbols.html", "de/custom-symbols.html"],
                          ["privacy.html", "de/privacy.html"], ["support.html", "de/support.html"]]) {
    const enDoc = documents[pages.indexOf(en)];
    const deDoc = documents[pages.indexOf(de)];
    const expected = { en: canonicals[pages.indexOf(en)], de: canonicals[pages.indexOf(de)], "x-default": canonicals[pages.indexOf(en)] };
    // hreflang has to be reciprocal or google ignores it
    assert.deepEqual(alternates(enDoc), expected, en);
    assert.deepEqual(alternates(deDoc), expected, de);
    assert.equal((sitemap.match(new RegExp(`hreflang="de" href="${expected.de}"`, "g")) ?? []).length, 2);
  }
  assert.deepEqual(alternates(documents[pages.indexOf("threat-model.html")]), {});
});

test("public disclosures cover screen-off Unit Sync on both platforms", async () => {
  const [threatModel, privacyPolicy, generatedThreatModel, generatedPrivacyPolicy] = await Promise.all([
    readFile(new URL("../../docs/THREAT_MODEL.md", import.meta.url), "utf8"),
    readFile(new URL("../../docs/PRIVACY_POLICY.md", import.meta.url), "utf8"),
    readFile(new URL("../public/threat-model.html", import.meta.url), "utf8"),
    readFile(new URL("../public/privacy.html", import.meta.url), "utf8"),
  ]);

  for (const disclosure of [threatModel, generatedThreatModel]) {
    assert.match(disclosure, /screen-off presence \(iOS and Android\)/i);
    assert.match(disclosure, /Android[\s\S]{0,260}location\s+foreground\s+service/i);
    assert.match(disclosure, /does not request[\s\S]{0,120}ACCESS_BACKGROUND_LOCATION/i);
  }
  assert.match(privacyPolicy, /On iOS and Android,[^\n]*Background Unit Sync location/i);
  assert.match(privacyPolicy, /Foreground service \(location\)[\s\S]{0,220}Background Unit Sync presence/i);
  assert.match(privacyPolicy, /Notifications[\s\S]{0,240}foreground\s+service\s+notification/i);
  assert.doesNotMatch(threatModel, /Opted-in iOS screen-off presence/i);
  assert.doesNotMatch(privacyPolicy, /On iOS, \*\*Background Unit Sync location/i);
  assert.match(generatedPrivacyPolicy, /On iOS and Android,[\s\S]{0,120}Background Unit Sync location/i);
  assert.match(generatedPrivacyPolicy, /location\s+foreground\s+service with an ongoing/i);
});

test("public documentation keeps optional network features default-off", async () => {
  const [readme, threatModel, privacyPolicy] = await Promise.all([
    readFile(new URL("../../README.md", import.meta.url), "utf8"),
    readFile(new URL("../../docs/THREAT_MODEL.md", import.meta.url), "utf8"),
    readFile(new URL("../../docs/PRIVACY_POLICY.md", import.meta.url), "utf8"),
  ]);

  assert.match(readme, /online\s+lookups are off on a fresh install/i);
  assert.match(readme, /online basemaps are\s+off on a fresh install/i);
  assert.doesNotMatch(readme, /online (?:lookups|basemaps) are\s+enabled on a fresh install/i);
  assert.match(threatModel, /online-map and lookup gates start off for a\s+fresh install/i);
  assert.match(privacyPolicy, /online lookups — disabled by default/i);
});
