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
function line(name, coords, stroke, width = 3) {
  return { kind: "drawing",
    feature: { type: "Feature", id: uuid(),
      geometry: { type: "LineString", coordinates: coords },
      properties: { name, source: "drawing", kind: "polyline",
        "tacticalmaps:category": "drawing", "tacticalmaps:kind": "polyline",
        stroke, "stroke-width": width } } };
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

const objCx = C.lon, objCy = -33.6960;
const SITUATION = [
  // Control measures / graphics (drawn first so units sit on top)
  area("AA EAGLE", [
    [150.3018, -33.7044], [150.3044, -33.7044],
    [150.3046, -33.7060], [150.3016, -33.7060],
  ], "#1E8A34", "#1E8A34", 0.10, 2),
  line("AXIS DAGGER", [
    [150.3050, -33.7044], [150.3048, -33.7016],
    [150.3046, -33.6986], [150.3050, -33.6968],
  ], "#0E5FD8", 4),
  line("LD / LC", [[150.3003, -33.7015], [150.3097, -33.7015]], "#E2A400", 3),
  line("PL BLUE", [[150.3003, -33.6986], [150.3097, -33.6986]], "#E2A400", 3),
  area("OBJ FALCON", ellipse(objCx, objCy, 0.0017, 0.0011, 16), "#D8281F", "#D8281F", 0.20, 3),
  // Enemy on the objective
  unit("EN", "hostile", "platoon", "infantry", 150.3046, -33.6960),
  unit("EN", "hostile", "section", "infantry", 150.3066, -33.6970),
  // Friendly assault (south of the LD), advancing north
  unit("CO HQ", "friend", "company", "infantry", 150.3050, -33.7048, true),
  unit("1 PL", "friend", "platoon", "infantry", 150.3022, -33.7032),
  unit("2 PL", "friend", "platoon", "infantry", 150.3078, -33.7032),
  unit("SBF", "friend", "platoon", "antiTank", 150.3092, -33.7040),
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
