// Renders THREAT_MODEL.md into site/public/threat-model.html.
// Deploy is a plain static asset push, so we generate the html here and commit
// it rather than doing anything clever at request time.
//
// usage: node site/build-docs.mjs   (from repo root)

import { readFileSync, writeFileSync } from "node:fs";
import { marked } from "marked";

const SRC = "THREAT_MODEL.md";
const OUT = "site/public/threat-model.html";

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
    font-family:"IBM Plex Sans",system-ui,sans-serif;line-height:1.7;
    -webkit-font-smoothing:antialiased}
  a{color:var(--signal);text-decoration:none}
  a:hover{text-decoration:underline}

  header.nav{position:sticky;top:0;z-index:20;
    background:rgba(11,16,19,.72);backdrop-filter:blur(10px);
    border-bottom:1px solid var(--line-2)}
  .nav-in{max-width:var(--maxw);margin:0 auto;padding:14px 28px;
    display:flex;align-items:center;justify-content:space-between;gap:20px}
  .brand{display:flex;align-items:center;gap:11px;font-family:"Saira",sans-serif;
    font-weight:800;font-size:19px;letter-spacing:.02em;color:var(--ink)}
  .brand:hover{text-decoration:none}
  .brand .glyph{width:26px;height:26px;flex:none;display:block;border-radius:4px}
  .nav-in .back{font-family:"IBM Plex Mono",monospace;font-size:13px;color:var(--ink-dim)}
  .nav-in .back:hover{color:var(--ink);text-decoration:none}

  main{max-width:var(--maxw);margin:0 auto;padding:56px 28px 96px}
  h1,h2,h3,h4{font-family:"Saira",sans-serif;line-height:1.15;letter-spacing:-.01em;
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

  code{font-family:"IBM Plex Mono",monospace;font-size:.9em;color:var(--ink);
    background:var(--panel);border:1px solid var(--line-2);
    border-radius:3px;padding:1px 5px}
  pre{background:var(--void-2);border:1px solid var(--line);border-radius:5px;
    padding:16px 18px;overflow-x:auto;margin:18px 0}
  pre code{background:none;border:none;padding:0;font-size:13px;line-height:1.6}

  .tablewrap{overflow-x:auto;margin:20px 0;border:1px solid var(--line);border-radius:5px}
  table{width:100%;border-collapse:collapse;font-size:14.5px}
  th,td{text-align:left;padding:12px 16px;border-bottom:1px solid var(--line-2);
    vertical-align:top}
  thead th{font-family:"Saira",sans-serif;font-weight:700;background:var(--void-2);
    color:var(--ink);white-space:nowrap}
  td{color:var(--ink-dim)}
  tbody tr:last-child td{border-bottom:none}

  footer{border-top:1px solid var(--line-2);background:var(--void-2);padding:32px 0}
  .foot-in{max-width:var(--maxw);margin:0 auto;padding:0 28px;
    display:flex;justify-content:space-between;gap:20px;flex-wrap:wrap;
    font-family:"IBM Plex Mono",monospace;font-size:12px;color:var(--ink-faint);
    letter-spacing:.06em}
  .foot-in a{color:var(--ink-dim)}
`;

const slug = (s) =>
  s.toLowerCase().replace(/<[^>]+>/g, "").replace(/[^\w\s-]/g, "")
    .trim().replace(/\s+/g, "-");

const md = readFileSync(SRC, "utf8");

const renderer = new marked.Renderer();
// give every heading a stable id so the landing page can deep-link into it
renderer.heading = ({ tokens, depth }) => {
  const text = marked.parseInline(tokens.map((t) => t.raw).join(""));
  const raw = tokens.map((t) => t.raw).join("");
  let id = slug(raw);
  // the landing page links "How sync encryption works" straight at section 4
  if (/^4\./.test(raw.trim())) id = "relay";
  return `<h${depth} id="${id}">${text}</h${depth}>\n`;
};
// tables need their own scroll container or the page scrolls sideways on mobile
renderer.table = (token) => {
  const head = token.header
    .map((c) => `<th>${marked.parseInline(c.text)}</th>`).join("");
  const body = token.rows
    .map((r) => `<tr>${r.map((c) => `<td>${marked.parseInline(c.text)}</td>`).join("")}</tr>`)
    .join("\n");
  return `<div class="tablewrap"><table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>\n`;
};

const body = marked.parse(md, { renderer, gfm: true });

const html = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Threat model — TacMap</title>
<meta name="description" content="What TacMap exposes, to whom, and where its guarantees stop. Written for users, unit security staff, and code auditors.">
<link rel="canonical" href="https://tacmap.app/threat-model">
<meta name="theme-color" content="#0E1519">
<link rel="icon" type="image/png" href="assets/brand/play-icon-512.png">
<meta property="og:title" content="TacMap threat model">
<meta property="og:description" content="What TacMap exposes, to whom, and where its guarantees stop.">
<meta property="og:type" content="article">
<meta property="og:url" content="https://tacmap.app/threat-model">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Saira:wght@600;700;800&family=IBM+Plex+Sans:wght@400;500;600&family=IBM+Plex+Mono:wght@400;500&display=swap" rel="stylesheet">
<style>${CSS}</style>
</head>
<body>
<header class="nav">
  <div class="nav-in">
    <a class="brand" href="/">
      <img class="glyph" src="assets/brand/play-icon-512.png" width="26" height="26" alt="">
      TACMAP
    </a>
    <a class="back" href="/">← Back to site</a>
  </div>
</header>
<main>
${body}
</main>
<footer>
  <div class="foot-in">
    <span>TACMAP · THREAT MODEL</span>
    <span><a href="https://github.com/JediBrooker/TacMap">Source on GitHub</a></span>
  </div>
</footer>
</body>
</html>
`;

writeFileSync(OUT, html);
console.log(`wrote ${OUT} (${html.length} bytes)`);
