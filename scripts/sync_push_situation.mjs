// Push a constructed NATO APP-6 tactical situation into a TacMap Unit Sync
// room, so a device joining that room receives it as the shared picture
// (store-and-forward snapshot). Replicates the app's E2E client:
//   roomId = base64url(SHA-256("tacmap-room|"+code))
//   roomKey = HKDF-SHA256(code, salt="tacmap-sync-salt-v1", info="tacmap-e2e", 32)
//   ct = base64( iv(12) ‖ AES-256-GCM ciphertext ‖ tag(16) )
//   message = {"t":"put","id","v","by","kind","ct"}
//
//   node scripts/sync_push_situation.mjs WOLFPACK-6
import crypto from "node:crypto";

const code = process.argv[2] || "WOLFPACK-6";
const SALT = Buffer.from("tacmap-sync-salt-v1", "utf8");
const INFO = Buffer.from("tacmap-e2e", "utf8");
const KEY = Buffer.from(crypto.hkdfSync("sha256", Buffer.from(code, "utf8"), SALT, INFO, 32));
const roomId = crypto.createHash("sha256").update("tacmap-room|" + code, "utf8").digest("base64url");
const url = "wss://tacmap-sync.christianbrooker.workers.dev/room/" + roomId;

function seal(plaintext) {
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv("aes-256-gcm", KEY, iv);
  const ct = Buffer.concat([c.update(Buffer.from(plaintext, "utf8")), c.final()]);
  return Buffer.concat([iv, ct, c.getAuthTag()]).toString("base64");
}
const uuid = () => crypto.randomUUID();

// --- situation builders (centre = device location, Blue Mountains) ---
const C = { lon: 150.305, lat: -33.700 };
function unit(name, aff, ech, fn, lon, lat, hq = false) {
  const props = {
    name, source: "symbol", kind: "military",
    "tacticalmaps:category": "military",
    "tacticalmaps:affiliation": aff,
    "tacticalmaps:echelon": ech,
    "tacticalmaps:function": fn,
  };
  if (hq) props["tacticalmaps:is_hq"] = true;
  return { kind: "waypoint",
    feature: { type: "Feature", id: uuid(),
      geometry: { type: "Point", coordinates: [lon, lat] }, properties: props } };
}
function line(name, coords, stroke, width = 3, graphic = null) {
  const props = { name, source: "drawing", kind: "polyline",
    "tacticalmaps:category": "drawing", "tacticalmaps:kind": "polyline",
    stroke, "stroke-width": width };
  if (graphic) props["tacticalmaps:line_graphic"] = graphic;
  return { kind: "drawing",
    feature: { type: "Feature", id: uuid(),
      geometry: { type: "LineString", coordinates: coords }, properties: props } };
}
// Tactical task graphic (control measure) — a point symbol from the app's
// AppSymbols assets (asset = the rawValue). rotation is clockwise degrees
// (0 = canonical; the axis/SBF arrows point EAST at 0, so 270 points north).
function task(name, asset, lon, lat, rotation = 0, scale = 1.0) {
  return { kind: "waypoint",
    feature: { type: "Feature", id: uuid(),
      geometry: { type: "Point", coordinates: [lon, lat] },
      properties: { name, source: "symbol", kind: "control_measure",
        "tacticalmaps:category": "controlMeasure",
        "tacticalmaps:tcm_asset": asset, "tacticalmaps:tcm_name": name,
        rotation, scale_x: scale, scale_y: scale,
        "tacticalmaps:rotation_deg": rotation,
        "tacticalmaps:scale_x": scale, "tacticalmaps:scale_y": scale } } };
}

function area(name, coords, stroke, fill, fillOpacity = 0.16, width = 3) {
  const ring = coords.slice(); ring.push(coords[0]);
  return { kind: "drawing",
    feature: { type: "Feature", id: uuid(),
      geometry: { type: "Polygon", coordinates: [ring] },
      properties: { name, source: "drawing", kind: "polygon",
        "tacticalmaps:category": "drawing", "tacticalmaps:kind": "polygon",
        stroke, "stroke-width": width, fill, "fill-opacity": fillOpacity } } };
}
function ellipse(cx, cy, rx, ry, n = 14) {
  const pts = [];
  for (let i = 0; i < n; i++) {
    const a = (i / n) * 2 * Math.PI;
    pts.push([+(cx + rx * Math.cos(a)).toFixed(6), +(cy + ry * Math.sin(a)).toFixed(6)]);
  }
  return pts;
}

const SITUATION = [
  // Objectives (in enemy depth, north)
  area("OBJ FALCON", ellipse(150.3018, -33.6948, 0.0016, 0.0010, 16), "#E2A400", "#E2A400", 0.16, 3),
  area("OBJ HILL 223", ellipse(150.3086, -33.6948, 0.0016, 0.0010, 16), "#E2A400", "#E2A400", 0.16, 3),
  // Bright colours so they read on dark satellite: friendly blue/white, enemy red.
  // Boundary (between the two assault companies) — tick-marked line graphic
  line("BDRY", [[150.3050, -33.7058], [150.3050, -33.7008]], "#E9F0E8", 3, "boundary"),
  // Line of departure (phase line, dashed)
  line("LD / LC", [[150.2998, -33.7028], [150.3102, -33.7028]], "#E2A400", 3, "phaseLine"),
  // Friendly FEBA — crenellated forward line, teeth toward enemy (W->E => left-normal north)
  line("FEBA", [[150.2996, -33.7006], [150.3020, -33.7000], [150.3052, -33.7010],
                [150.3082, -33.7000], [150.3104, -33.7006]], "#5AA0FF", 4, "forwardEdge"),
  // Enemy forward line — crenellated, teeth toward friendly (E->W => left-normal south)
  line("EN FLOT", [[150.3104, -33.6970], [150.3082, -33.6964], [150.3050, -33.6974],
                   [150.3020, -33.6964], [150.2996, -33.6970]], "#FF5A4A", 4, "forwardEdge"),
  // ---- TASK GRAPHICS (control measures) — size = 64 * scale * zoomScale,
  //      and the hero zoomScale is ~0.3, so use big scales to read well ----
  // Axes of advance (big arrows) — rotate 270 so the east-facing asset points north
  task("AXIS DAGGER", "axisOfMainAttack",       150.3024, -33.6992, 270, 11.0),
  task("AXIS SABRE",  "axisOfSupportingAttack", 150.3078, -33.6992, 270, 10.0),
  // Support by fire — WEST of the westernmost enemy unit (OBJ FALCON), at 90 deg
  // to the enemy front, firing east onto it (rotation 0 = arrows point east)
  task("SBF 1", "supportByFire", 150.2996, -33.6946, 0, 3.0),
  // Seize the high ground (on OBJ HILL 223)
  task("SEIZE", "seize", 150.3090, -33.6948, 0, 8.0),
  // Guard the open western flank (rotate 90 -> oriented N-S)
  task("GUARD", "guard", 150.2992, -33.7008, 90, 9.0),
  // Enemy formations (on the objectives)
  unit("EN", "hostile", "company", "infantry", 150.3018, -33.6946),
  unit("EN", "hostile", "company", "infantry", 150.3086, -33.6946),
  // Friendly assault (south of the FEBA), advancing north
  unit("BN HQ", "friend", "battalionRegiment", "infantry", 150.3050, -33.7056, true),
  unit("A COY", "friend", "company", "infantry", 150.3024, -33.7040),
  unit("B COY", "friend", "company", "infantry", 150.3076, -33.7040),
  unit("C SQN", "friend", "company", "armour", 150.3098, -33.7048),
];

const ws = new WebSocket(url);
let v = 0;
ws.addEventListener("open", () => {
  console.log("connected:", url);
  for (const obj of SITUATION) {
    v += 1;
    const content = JSON.stringify({ type: "FeatureCollection", features: [obj.feature] });
    ws.send(JSON.stringify({ t: "put", id: obj.feature.id, v, by: "ops-hq", kind: obj.kind, ct: seal(content) }));
    console.log("pushed", obj.kind, obj.feature.properties.name);
  }
  console.log(`pushed ${SITUATION.length} objects to room ${code}. holding connection…`);
  setInterval(() => { try { ws.send(JSON.stringify({ t: "ping" })); } catch {} }, 5000);
});
ws.addEventListener("error", (e) => console.error("ws error:", e.message || e));
ws.addEventListener("close", () => console.log("closed"));
