// Renders the canonical security/privacy Markdown into public HTML.
// Deploy is a plain static asset push, so we generate the html here and commit
// it rather than doing anything clever at request time.
//
// usage: node site/build-docs.mjs   (from repo root)

import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { marked } from "marked";

const INDEX = "site/public/index.html";
const POLICY_OUT = "site/src/security-policy.mjs";
const DOCUMENTS = [
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
  {"src": "docs/de/OVERVIEW.md", "out": "site/public/de.html", "language": "de", "title": "Offline-Karten für unterwegs — TacMap", "description": "Offline-Karten für unterwegs", "canonical": "https://tacmap.app/de", "ogTitle": "TacMap — Offline-Karten für unterwegs", "ogDescription": "Offline-Karten für unterwegs", "footerLabel": "TACMAP", "sourceUrl": "https://github.com/JediBrooker/TacMap/blob/main/docs/de/OVERVIEW.md", "relayHeading": false},
  {"src": "docs/SUPPORT.md", "out": "site/public/support.html", "language": "en", "title": "Help and contact — TacMap", "description": "Help and contact", "canonical": "https://tacmap.app/support", "ogTitle": "TacMap — Help and contact", "ogDescription": "Help and contact", "footerLabel": "TACMAP", "sourceUrl": "https://github.com/JediBrooker/TacMap/blob/main/docs/SUPPORT.md", "relayHeading": false},
];

// keep these in sync with the :root block in site/public/index.html
const CSS = `
  :root{
    --void:#0E1519; --void-2:#0B1013; --panel:#141E24;
    --line:#243440; --line-2:#1B272E;
    --ink:#E7EDEC; --ink-dim:#8DA2AA; --ink-faint:#5E727B;
    --signal:#FF5A24; --maxw:820px;
  }
  *{box-sizing:border-box;margin:0;padding:0}
  html{scroll-behavior:smooth}
  body{background:var(--void);color:var(--ink);
    font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;line-height:1.7;
    -webkit-font-smoothing:antialiased}
  a{color:var(--signal);text-decoration:none}
  a:hover{text-decoration:underline}
  main a{overflow-wrap:anywhere}

  header.nav{position:sticky;top:0;z-index:20;
    background:rgba(11,16,19,.72);backdrop-filter:blur(10px);
    border-bottom:1px solid var(--line-2)}
  .nav-in{max-width:var(--maxw);margin:0 auto;padding:14px 28px;
    display:flex;align-items:center;justify-content:space-between;gap:20px}
  .brand{display:flex;align-items:center;gap:11px;font-family:system-ui,sans-serif;
    font-weight:800;font-size:19px;letter-spacing:.02em;color:var(--ink)}
  .brand:hover{text-decoration:none}
  .brand .glyph{width:26px;height:26px;flex:none;display:block;border-radius:6px}
  .nav-in .back{font-family:ui-monospace,"SF Mono",Menlo,monospace;font-size:13px;color:var(--ink-dim)}
  .nav-in .back:hover{color:var(--ink);text-decoration:none}

  main{max-width:var(--maxw);margin:0 auto;padding:56px 28px 96px}
  h1,h2,h3,h4{font-family:system-ui,sans-serif;line-height:1.15;letter-spacing:-.01em;
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

  code{font-family:ui-monospace,"SF Mono",Menlo,monospace;font-size:.9em;color:var(--ink);
    background:var(--panel);border:1px solid var(--line-2);
    border-radius:3px;padding:1px 5px}
  pre{background:var(--void-2);border:1px solid var(--line);border-radius:5px;
    padding:16px 18px;overflow-x:auto;margin:18px 0}
  pre code{background:none;border:none;padding:0;font-size:13px;line-height:1.6}

  .tablewrap{overflow-x:auto;margin:20px 0;border:1px solid var(--line);border-radius:5px}
  table{width:100%;border-collapse:collapse;font-size:14.5px}
  th,td{text-align:left;padding:12px 16px;border-bottom:1px solid var(--line-2);
    vertical-align:top}
  thead th{font-family:system-ui,sans-serif;font-weight:700;background:var(--void-2);
    color:var(--ink);white-space:nowrap}
  td{color:var(--ink-dim)}
  tbody tr:last-child td{border-bottom:none}

  footer{border-top:1px solid var(--line-2);background:var(--void-2);padding:32px 0}
  .foot-in{max-width:var(--maxw);margin:0 auto;padding:0 28px;
    display:flex;justify-content:space-between;gap:20px;flex-wrap:wrap;
    font-family:ui-monospace,"SF Mono",Menlo,monospace;font-size:12px;color:var(--ink-faint);
    letter-spacing:.06em}
  .foot-in a{color:var(--ink-dim)}
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
<style>${CSS}</style>
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
    <span>${config.footerLabel}</span>
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

const index = readFileSync(INDEX, "utf8");
for (const [name, document] of [[INDEX, index], ...generatedDocuments]) {
  if (/\\sstyle=/.test(document)) {
    throw new Error(`${name} contains an inline style attribute; CSP requires CSS classes`);
  }
  if (/fonts\\.(?:googleapis|gstatic)\\.com/i.test(document)) {
    throw new Error(`${name} contains a third-party Google Fonts request`);
  }
}

const styleBlocks = [
  ...inlineBlocks(index, "style"),
  ...generatedDocuments.flatMap(([, document]) => inlineBlocks(document, "style")),
];
const scriptBlocks = [
  ...inlineBlocks(index, "script"),
  ...generatedDocuments.flatMap(([, document]) => inlineBlocks(document, "script")),
];
if (styleBlocks.length !== DOCUMENTS.length + 2 || scriptBlocks.length !== 1) {
  throw new Error(
    `Expected one style block per document and two homepage style blocks and one inline script; found ${styleBlocks.length} and ${scriptBlocks.length}`,
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

for (const [out, document] of generatedDocuments) {
  mkdirSync(out.slice(0, out.lastIndexOf("/")), { recursive: true });
  writeFileSync(out, document);
  console.log(`wrote ${out} (${document.length} bytes)`);
}
writeFileSync(POLICY_OUT, policyModule);
console.log(`wrote ${POLICY_OUT} (${policyModule.length} bytes)`);
