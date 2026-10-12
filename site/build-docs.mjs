// Renders the canonical security/privacy Markdown into public HTML.
// Deploy is a plain static asset push, so we generate the html here and commit
// it rather than doing anything clever at request time.
//
// usage: node site/build-docs.mjs   (from repo root)

import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { marked } from "marked";

// hand written landing pages, english + german. they share one inline style
// and script (byte for byte, the test checks) so the CSP hashes dont double up.
// de.html used to be rendered from docs/de/OVERVIEW.md, its hand written now
// so it can mirror the english page section for section.
const HOMEPAGES = ["site/public/index.html", "site/public/de.html"];
const POLICY_OUT = "site/src/security-policy.mjs";
const SITEMAP_OUT = "site/public/sitemap.xml";
const ORIGIN = "https://tacmap.app";
// en/de translations of the same page. drives the hreflang links on the
// generated docs and the alternates in sitemap.xml. the two homepages carry
// their hreflang links by hand, keep them matching the first row.
// threat-model is english only so its not in here.
const TRANSLATIONS = [
  { en: `${ORIGIN}/`, de: `${ORIGIN}/de` },
  { en: `${ORIGIN}/custom-symbols`, de: `${ORIGIN}/de/custom-symbols` },
  { en: `${ORIGIN}/privacy`, de: `${ORIGIN}/de/privacy` },
  { en: `${ORIGIN}/support`, de: `${ORIGIN}/de/support` },
];
const translationsOf = (url) => TRANSLATIONS.find((pair) => pair.en === url || pair.de === url);
const hreflangLinks = (url) => {
  const pair = translationsOf(url);
  if (!pair) return "";
  return `<link rel="alternate" hreflang="en" href="${pair.en}">\n`
    + `<link rel="alternate" hreflang="de" href="${pair.de}">\n`
    + `<link rel="alternate" hreflang="x-default" href="${pair.en}">\n`;
};
const DOCUMENTS = [
  {
    src: "docs/SYMBOL_PACK_GUIDE.md", out: "site/public/custom-symbols.html",
    language: "en", title: "Import custom symbol packs — TacMap",
    description: "Create a layer, import a custom symbol pack on iOS or Android, and search offline. Includes 894 German emergency-services symbols.",
    canonical: "https://tacmap.app/custom-symbols", ogTitle: "Bring your own symbols — TacMap",
    ogDescription: "A step-by-step guide for iOS and Android, with a ready-to-import German emergency-services pack.",
    footerLabel: "TACMAP · CUSTOM SYMBOLS",
    sourceUrl: "https://github.com/JediBrooker/TacMap/blob/main/docs/SYMBOL_PACK_GUIDE.md", relayHeading: false,
  },
  {
    src: "docs/de/SYMBOL_PACK_GUIDE.md", out: "site/public/de/custom-symbols.html",
    language: "de", title: "Eigene Symbolpakete importieren — TacMap",
    description: "Eigene Ebenen erstellen, Symbolpakete auf iOS und Android importieren und offline suchen. Mit 894 deutschen BOS-Symbolen.",
    canonical: "https://tacmap.app/de/custom-symbols", ogTitle: "Eigene Symbole verwenden — TacMap",
    ogDescription: "Schritt für Schritt auf iOS und Android, mit einem fertigen deutschen BOS-Symbolpaket.",
    footerLabel: "TACMAP · EIGENE SYMBOLE",
    sourceUrl: "https://github.com/JediBrooker/TacMap/blob/main/docs/de/SYMBOL_PACK_GUIDE.md", relayHeading: false,
  },
  {
    src: "docs/THREAT_MODEL.md",
    out: "site/public/threat-model.html",
    title: "Threat model — TacMap",
    description: "What TacMap exposes, to whom, and where its guarantees stop. Written for users, unit security staff, and code auditors.",
    canonical: "https://tacmap.app/threat-model",
    ogTitle: "TacMap threat model",
    ogDescription: "What TacMap exposes, to whom, and where its guarantees stop.",
    footerLabel: "TACMAP · THREAT MODEL",
    sourceUrl: "https://github.com/JediBrooker/TacMap/blob/main/docs/THREAT_MODEL.md",
    relayHeading: true,
  },
  {
    src: "docs/PRIVACY_POLICY.md",
    out: "site/public/privacy.html",
    title: "Privacy policy — TacMap",
    description: "How TacMap stores data, when optional services make network requests, and what service providers can observe.",
    canonical: "https://tacmap.app/privacy",
    ogTitle: "TacMap privacy policy",
    ogDescription: "How TacMap handles on-device data and optional network services.",
    footerLabel: "TACMAP · PRIVACY POLICY",
    sourceUrl: "https://github.com/JediBrooker/TacMap/blob/main/docs/PRIVACY_POLICY.md",
    relayHeading: false,
  },
  {"src": "docs/de/PRIVACY_POLICY.md", "out": "site/public/de/privacy.html", "language": "de", "title": "Datenschutzerklärung — TacMap", "description": "Datenschutzerklärung", "canonical": "https://tacmap.app/de/privacy", "ogTitle": "TacMap — Datenschutzerklärung", "ogDescription": "Datenschutzerklärung", "footerLabel": "TACMAP", "sourceUrl": "https://github.com/JediBrooker/TacMap/blob/main/docs/de/PRIVACY_POLICY.md", "relayHeading": false},
  {"src": "docs/de/HELP.md", "out": "site/public/de/support.html", "language": "de", "title": "Hilfe und Kontakt — TacMap", "description": "Hilfe und Kontakt", "canonical": "https://tacmap.app/de/support", "ogTitle": "TacMap — Hilfe und Kontakt", "ogDescription": "Hilfe und Kontakt", "footerLabel": "TACMAP", "sourceUrl": "https://github.com/JediBrooker/TacMap/blob/main/docs/de/HELP.md", "relayHeading": false},
  {"src": "docs/SUPPORT.md", "out": "site/public/support.html", "language": "en", "title": "Help and contact — TacMap", "description": "Help and contact", "canonical": "https://tacmap.app/support", "ogTitle": "TacMap — Help and contact", "ogDescription": "Help and contact", "footerLabel": "TACMAP", "sourceUrl": "https://github.com/JediBrooker/TacMap/blob/main/docs/SUPPORT.md", "relayHeading": false},
];

// Inline (not <img>) so it needs no extra request and always renders; keep in
// sync with the copy in the site/public/index.html footer.
const FLAG_AU = '<svg class="flag-au" role="img" aria-label="Australian flag" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1260 630"><path fill="#012169" d="M0 0 1260 0 1260 630 0 630Z"/><path fill="#FFF" d="M0 0 70.4 0 630 279.8 630 315 559.6 315 0 35.2ZM630 0 630 35.2 70.4 315 0 315 0 279.8 559.6 0Z"/><path fill="#C8102E" d="M0 0 315 157.5 268 157.5 0 23.5ZM630 315 315 157.5 362 157.5 630 291.5ZM630 0 315 157.5 315 134 583 0ZM0 315 315 157.5 315 181 47 315Z"/><path fill="#FFF" d="M262.5 0 367.5 0 367.5 105 630 105 630 210 367.5 210 367.5 315 262.5 315 262.5 210 0 210 0 105 262.5 105Z"/><path fill="#C8102E" d="M283.5 0 346.5 0 346.5 126 630 126 630 189 346.5 189 346.5 315 283.5 315 283.5 189 0 189 0 126 283.5 126Z"/><path fill="#FFF" d="M315 378 333.2 434.7 388.9 413.6 355.9 463.2 407.1 493.5 347.8 498.7 356 557.6 315 514.5 274 557.6 282.2 498.7 222.9 493.5 274.1 463.2 241.1 413.6 296.8 434.7ZM945 480 953.7 507 980.2 496.9 964.5 520.5 988.9 535 960.6 537.5 964.5 565.5 945 545 925.5 565.5 929.4 537.5 901.1 535 925.5 520.5 909.8 496.9 936.3 507ZM787.5 230.6 796.2 257.6 822.7 247.5 807 271.1 831.4 285.6 803.1 288.1 807 316.1 787.5 295.6 768 316.1 771.9 288.1 743.6 285.6 768 271.1 752.3 247.5 778.8 257.6ZM945 60 953.7 87 980.2 76.9 964.5 100.5 988.9 115 960.6 117.5 964.5 145.5 945 125 925.5 145.5 929.4 117.5 901.1 115 925.5 100.5 909.8 76.9 936.3 87ZM1091.3 195.6 1100 222.6 1126.5 212.5 1110.8 236.1 1135.2 250.6 1106.9 253.1 1110.8 281.1 1091.3 260.6 1071.8 281.1 1075.7 253.1 1047.4 250.6 1071.8 236.1 1056.1 212.5 1082.6 222.6ZM1008 315 1014.2 332.8 1033 333.2 1018 344.6 1023.5 362.6 1008 351.8 992.5 362.6 998 344.6 983 333.2 1001.8 332.8Z"/></svg>';


// keep these in sync with the :root block in site/public/index.html (and de.html)
const CSS = `
  :root{
    --font-sans:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;
    --font-mono:ui-monospace,"SFMono-Regular",Menlo,Consolas,monospace;
    --void:#0E1519; --void-2:#0B1013; --panel:#141E24;
    --line:#243440; --line-2:#1B272E;
    --ink:#E7EDEC; --ink-dim:#8DA2AA; --ink-faint:#5E727B;
    --signal:#FF5A24; --maxw:820px;
  }
  *{box-sizing:border-box;margin:0;padding:0}
  html{scroll-behavior:smooth}
  body{background:var(--void);color:var(--ink);
    font-family:var(--font-sans);line-height:1.7;
    -webkit-font-smoothing:antialiased}
  a{color:var(--signal);text-decoration:none}
  a:hover{text-decoration:underline}
  main a{overflow-wrap:anywhere}

  header.nav{position:sticky;top:0;z-index:20;
    background:rgba(11,16,19,.72);backdrop-filter:blur(10px);
    border-bottom:1px solid var(--line-2)}
  .nav-in{max-width:var(--maxw);margin:0 auto;padding:14px 28px;
    display:flex;align-items:center;justify-content:space-between;gap:20px}
  .brand{display:flex;align-items:center;gap:11px;font-family:var(--font-sans);
    font-weight:800;font-size:19px;letter-spacing:.02em;color:var(--ink)}
  .brand:hover{text-decoration:none}
  .brand .glyph{width:26px;height:26px;flex:none;display:block;border-radius:6px}
  .nav-in .back{font-family:var(--font-mono);font-size:13px;color:var(--ink-dim)}
  .nav-in .back:hover{color:var(--ink);text-decoration:none}

  main{max-width:var(--maxw);margin:0 auto;padding:56px 28px 96px}
  h1,h2,h3,h4{font-family:var(--font-sans);line-height:1.15;letter-spacing:-.01em;
    color:var(--ink);scroll-margin-top:80px}
  h1{font-size:clamp(32px,5vw,46px);font-weight:800;margin-bottom:8px}
  h2{font-size:clamp(22px,3vw,30px);font-weight:700;margin:52px 0 16px;
    padding-top:24px;border-top:1px solid var(--line-2)}
  h3{font-size:19px;font-weight:600;margin:32px 0 12px;color:var(--ink)}
  h4{font-size:16px;font-weight:600;margin:24px 0 8px}
  p{margin:14px 0;color:var(--ink-dim)}
  strong{color:var(--ink);font-weight:600}
  ul,ol{margin:14px 0 14px 22px;color:var(--ink-dim)}
  li{margin:7px 0}
  hr{border:none;border-top:1px solid var(--line-2);margin:40px 0}
  blockquote{border-left:2px solid var(--signal);padding-left:18px;margin:18px 0;
    color:var(--ink-dim)}

  code{font-family:var(--font-mono);font-size:.9em;color:var(--ink);
    background:var(--panel);border:1px solid var(--line-2);
    border-radius:3px;padding:1px 5px}
  pre{background:var(--void-2);border:1px solid var(--line);border-radius:5px;
    padding:16px 18px;overflow-x:auto;margin:18px 0}
  pre code{background:none;border:none;padding:0;font-size:13px;line-height:1.6}

  .tablewrap{overflow-x:auto;margin:20px 0;border:1px solid var(--line);border-radius:5px}
  table{width:100%;border-collapse:collapse;font-size:14.5px}
  th,td{text-align:left;padding:12px 16px;border-bottom:1px solid var(--line-2);
    vertical-align:top}
  thead th{font-family:var(--font-sans);font-weight:700;background:var(--void-2);
    color:var(--ink);white-space:nowrap}
  td{color:var(--ink-dim)}
  tbody tr:last-child td{border-bottom:none}

  footer{border-top:1px solid var(--line-2);background:var(--void-2);padding:32px 0}
  .foot-in{max-width:var(--maxw);margin:0 auto;padding:0 28px;
    display:flex;justify-content:space-between;gap:20px;flex-wrap:wrap;
    font-family:var(--font-mono);font-size:12px;color:var(--ink-faint);
    letter-spacing:.06em}
  .foot-in a{color:var(--ink-dim)}
  .flag-au{width:18px;height:9px;margin-left:7px;vertical-align:-1px}
`;

const slug = (s) =>
  s.toLowerCase().replace(/<[^>]+>/g, "").replace(/[^\w\s-]/g, "")
    .trim().replace(/\s+/g, "-");

const renderDocument = (config) => {
  const renderer = new marked.Renderer();
  // Give every heading a stable id so the landing page can deep-link into it.
  renderer.heading = ({ tokens, depth }) => {
    const text = marked.parseInline(tokens.map((t) => t.raw).join(""));
    const raw = tokens.map((t) => t.raw).join("");
    let id = slug(raw);
    if (config.relayHeading && /^4\./.test(raw.trim())) id = "relay";
    return `<h${depth} id="${id}">${text}</h${depth}>\n`;
  };
  // Tables need their own scroll container or the page scrolls sideways on mobile.
  renderer.table = (token) => {
    const head = token.header
      .map((c) => `<th>${marked.parseInline(c.text)}</th>`).join("");
    const body = token.rows
      .map((r) => `<tr>${r.map((c) => `<td>${marked.parseInline(c.text)}</td>`).join("")}</tr>`)
      .join("\n");
    return `<div class="tablewrap"><table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>\n`;
  };

  const markdown = readFileSync(config.src, "utf8");
  const body = marked.parse(markdown, { renderer, gfm: true });
  return `<!DOCTYPE html>
<html lang="${config.language ?? "en"}">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${config.title}</title>
<meta name="description" content="${config.description}">
<link rel="canonical" href="${config.canonical}">
<meta name="theme-color" content="#0E1519">
<link rel="icon" type="image/png" href="/assets/brand/app-icon-512.png">
<meta property="og:title" content="${config.ogTitle}">
<meta property="og:description" content="${config.ogDescription}">
<meta property="og:type" content="article">
<meta property="og:url" content="${config.canonical}">
${hreflangLinks(config.canonical)}<style>${CSS}</style>
</head>
<body>
<header class="nav">
  <div class="nav-in">
    <a class="brand" href="/">
      <img class="glyph" src="/assets/brand/app-icon-512.png" width="26" height="26" alt="">
      TACMAP
    </a>
    <a class="back" href="${config.language === "de" ? "/de" : "/"}">${config.language === "de" ? "← Zur Website" : "← Back to site"}</a>
  </div>
</header>
<main>
${body}
</main>
<footer>
  <div class="foot-in">
    <span>${config.footerLabel} · ${config.language === "de" ? "HERGESTELLT IN AUSTRALIEN" : "MADE IN AUSTRALIA"}${FLAG_AU}</span>
    <span><a href="${config.sourceUrl}">${config.language === "de" ? "Quelltext auf GitHub" : "Source on GitHub"}</a></span>
  </div>
</footer>
</body>
</html>
`;
};

const generatedDocuments = DOCUMENTS.map((config) => [config.out, renderDocument(config)]);

const inlineBlocks = (document, tag) => {
  const pattern = new RegExp(`<${tag}(?:\\s[^>]*)?>([\\s\\S]*?)</${tag}>`, "gi");
  return [...document.matchAll(pattern)].map((match) => match[1]);
};
const hashSource = (value) =>
  `'sha256-${createHash("sha256").update(value, "utf8").digest("base64")}'`;

const homepages = HOMEPAGES.map((path) => [path, readFileSync(path, "utf8")]);
for (const [name, document] of [...homepages, ...generatedDocuments]) {
  if (/\\sstyle=/.test(document)) {
    throw new Error(`${name} contains an inline style attribute; CSP requires CSS classes`);
  }
  if (/fonts\\.(?:googleapis|gstatic)\\.com/i.test(document)) {
    throw new Error(`${name} contains a third-party Google Fonts request`);
  }
}

const styleBlocks = [
  ...homepages.flatMap(([, document]) => inlineBlocks(document, "style")),
  ...generatedDocuments.flatMap(([, document]) => inlineBlocks(document, "style")),
];
const scriptBlocks = [
  ...homepages.flatMap(([, document]) => inlineBlocks(document, "script")),
  ...generatedDocuments.flatMap(([, document]) => inlineBlocks(document, "script")),
];
if (styleBlocks.length !== DOCUMENTS.length + 2 * HOMEPAGES.length
    || scriptBlocks.length !== HOMEPAGES.length) {
  throw new Error(
    `Expected one style block per document, two style blocks and one inline script per homepage; found ${styleBlocks.length} and ${scriptBlocks.length}`,
  );
}
// Both generated document pages intentionally share byte-identical CSS. One CSP
// source hash authorizes that content on either page, so omit duplicate tokens.
const styleHashes = [...new Set(styleBlocks.map(hashSource))];
const scriptHashes = [...new Set(scriptBlocks.map(hashSource))];

const contentSecurityPolicy = [
  "default-src 'none'",
  "base-uri 'none'",
  "connect-src 'none'",
  "font-src 'self'",
  "form-action 'none'",
  "frame-ancestors 'none'",
  "frame-src 'none'",
  "img-src 'self'",
  "manifest-src 'self'",
  "media-src 'self'",
  "object-src 'none'",
  `script-src ${scriptHashes.join(" ")}`,
  "script-src-attr 'none'",
  `style-src ${styleHashes.join(" ")}`,
  "style-src-attr 'none'",
  "worker-src 'none'",
  "upgrade-insecure-requests",
].join("; ");
const policyModule = `// Generated by site/build-docs.mjs; do not edit by hand.\n`
  + `export const CONTENT_SECURITY_POLICY = ${JSON.stringify(contentSecurityPolicy)};\n`;

// sitemap is just every canonical url, homepages first. pulled straight off the
// pages so a new doc cant be missed. no lastmod, google ignores it unless its
// accurate and we dont track that.
const canonicalOf = (name, document) => {
  const match = document.match(/<link rel="canonical" href="([^"]+)">/);
  if (!match) throw new Error(`${name} has no canonical link`);
  return match[1];
};
const sitemapUrls = [...homepages, ...generatedDocuments].map(([name, document]) => canonicalOf(name, document));
for (const pair of TRANSLATIONS) {
  if (!sitemapUrls.includes(pair.en) || !sitemapUrls.includes(pair.de)) {
    throw new Error(`translation pair ${pair.en} / ${pair.de} points at a page we dont publish`);
  }
}
const sitemapEntry = (url) => {
  const pair = translationsOf(url);
  const alternates = pair
    ? [["en", pair.en], ["de", pair.de], ["x-default", pair.en]]
      .map(([lang, href]) => `\n    <xhtml:link rel="alternate" hreflang="${lang}" href="${href}"/>`).join("")
    : "";
  return `  <url>\n    <loc>${url}</loc>${alternates}\n  </url>`;
};
const sitemap = `<?xml version="1.0" encoding="UTF-8"?>\n`
  + `<!-- Generated by site/build-docs.mjs; do not edit by hand. -->\n`
  + `<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">\n`
  + sitemapUrls.map(sitemapEntry).join("\n")
  + `\n</urlset>\n`;

for (const [out, document] of generatedDocuments) {
  mkdirSync(out.slice(0, out.lastIndexOf("/")), { recursive: true });
  writeFileSync(out, document);
  console.log(`wrote ${out} (${document.length} bytes)`);
}
writeFileSync(SITEMAP_OUT, sitemap);
console.log(`wrote ${SITEMAP_OUT} (${sitemap.length} bytes)`);
writeFileSync(POLICY_OUT, policyModule);
console.log(`wrote ${POLICY_OUT} (${policyModule.length} bytes)`);
