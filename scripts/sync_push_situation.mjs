// Push a constructed NATO APP-6 tactical situation + mock presence peers
// into a TacMap Unit Sync room. Updated for v2 crypto (PBKDF2 + AAD +
// writer-auth):
//   master     = PBKDF2-HMAC-SHA256(code, "tacmap-sync-salt-v2", 210000)
//   roomId     = base64url(HMAC(master, "tacmap-roomid-v2"))
//   roomKey    = HMAC(master, "tacmap-roomkey-v2")
//   authToken  = base64url(HMAC(master, "tacmap-auth-v2"))
//   aad        = "$id|$v|$kind" (put/del) or "loc|$clientId" (presence)
//   ct         = base64( iv(12) || AES-256-GCM(key, plaintext, aad) || tag(16) )
//
//   node scripts/sync_push_situation.mjs WOLFPACK-6
import crypto from "node:crypto";
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
const WebSocket = require("../sync/node_modules/ws");

const code = process.argv[2] || "WOLFPACK-6";
const SALT = "tacmap-sync-salt-v2";
const PBKDF2_ITERATIONS = 210_000;

const master = crypto.pbkdf2Sync(code, SALT, PBKDF2_ITERATIONS, 32, "sha256");
function subKey(label) {
  return crypto.createHmac("sha256", master).update(label, "utf8").digest();
}
const roomId = subKey("tacmap-roomid-v2").toString("base64url");
const KEY = subKey("tacmap-roomkey-v2");
const authToken = subKey("tacmap-auth-v2").toString("base64url");
const url = "wss://tacmap-sync.christianbrooker.workers.dev/room/" + roomId;

function seal(plaintext, aad) {
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv("aes-256-gcm", KEY, iv, { authTagLength: 16 });
  if (aad) c.setAAD(Buffer.from(aad, "utf8"));
  const ct = Buffer.concat([c.update(Buffer.from(plaintext, "utf8")), c.final()]);
  return Buffer.concat([iv, ct, c.getAuthTag()]).toString("base64");
}
const uuid = () => crypto.randomUUID();

// --- per-device Ed25519 identity (unit sync now signs every object write AND
// presence). The app pins a key per clientId (TOFU) and REJECTS any object or
// presence that isn't signed by it, so this push has to present a stable device
// key and sign exactly like a real client would. base64url(no pad), byte-for-
// byte matching SyncSigning on iOS + Android. ---
const { publicKey: edPub, privateKey: edPriv } = crypto.generateKeyPairSync("ed25519");
const PUB = edPub.export({ format: "jwk" }).x;   // base64url(no pad) of the raw 32-byte key
const US = "\u001f";
const edSign = (msg) => crypto.sign(null, Buffer.from(msg, "utf8"), edPriv).toString("base64url");
const f6 = (x) => Number(x).toFixed(6);
// Canonical signed messages - MUST match SyncSigning.objectMessage /
// presenceMessage exactly (U+001F join, %.6f coords, ts in epoch-ms).
const objectMessage = (id, v, kind, by, content) => [id, String(v), kind, by, content].join(US);
const presenceMessage = (clientId, ts, lat, lon, heading, speed, callsign, aff, ech, fn, isHQ) =>
  [clientId, String(ts), f6(lat), f6(lon), f6(heading), f6(speed),
   callsign, aff, ech, fn, isHQ ? "1" : "0"].join(US);

// --- situation builders, centre = device location (Blue Mountains) ---
const C = { lon: 150.305, lat: -33.700 };
const DLON = (process.env.CENTER_LON ? parseFloat(process.env.CENTER_LON) : C.lon) - C.lon;
const DLAT = (process.env.CENTER_LAT ? parseFloat(process.env.CENTER_LAT) : C.lat) - C.lat;
const ox = (v) => +(v + DLON).toFixed(6), oy = (v) => +(v + DLAT).toFixed(6);
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
      geometry: { type: "Point", coordinates: [ox(lon), oy(lat)] }, properties: props } };
}
function line(name, coords, stroke, width = 3, graphic = null) {
  const props = { name, source: "drawing", kind: "polyline",
    "tacticalmaps:category": "drawing", "tacticalmaps:kind": "polyline",
    stroke, "stroke-width": width };
  if (graphic) props["tacticalmaps:line_graphic"] = graphic;
  return { kind: "drawing",
    feature: { type: "Feature", id: uuid(),
      geometry: { type: "LineString", coordinates: coords.map(([x, y]) => [ox(x), oy(y)]) }, properties: props } };
}
const TASK_SCALE = parseFloat(process.env.TASK_SCALE || "1");
function task(name, asset, lon, lat, rotation = 0, scale = 1.0) {
  scale = scale * TASK_SCALE;
  return { kind: "waypoint",
    feature: { type: "Feature", id: uuid(),
      geometry: { type: "Point", coordinates: [ox(lon), oy(lat)] },
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
      geometry: { type: "Polygon", coordinates: [ring.map(([x, y]) => [ox(x), oy(y)])] },
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
  area("OBJ FALCON", ellipse(150.3018, -33.6948, 0.0016, 0.0010, 16), "#E2A400", "#E2A400", 0.16, 3),
  area("OBJ HILL 223", ellipse(150.3086, -33.6948, 0.0016, 0.0010, 16), "#E2A400", "#E2A400", 0.16, 3),
  line("BDRY", [[150.3050, -33.7058], [150.3050, -33.7008]], "#E9F0E8", 3, "boundary"),
  line("LD / LC", [[150.2998, -33.7028], [150.3102, -33.7028]], "#E2A400", 3, "phaseLine"),
  line("FEBA", [[150.2996, -33.7006], [150.3020, -33.7000], [150.3052, -33.7010],
                [150.3082, -33.7000], [150.3104, -33.7006]], "#5AA0FF", 4, "forwardEdge"),
  line("EN FLOT", [[150.3104, -33.6970], [150.3082, -33.6964], [150.3050, -33.6974],
                   [150.3020, -33.6964], [150.2996, -33.6970]], "#FF5A4A", 4, "forwardEdge"),
  task("AXIS DAGGER", "axisOfMainAttack",       150.3024, -33.6992, 270, 11.0),
  task("AXIS SABRE",  "axisOfSupportingAttack", 150.3078, -33.6992, 270, 10.0),
  task("SBF 1", "supportByFire", 150.2996, -33.6946, 0, 3.0),
  task("SEIZE", "seize", 150.3090, -33.6948, 0, 8.0),
  task("GUARD", "guard", 150.2992, -33.7008, 90, 9.0),
  unit("EN", "hostile", "company", "infantry", 150.3018, -33.6946),
  unit("EN", "hostile", "company", "infantry", 150.3086, -33.6946),
  unit("BN HQ", "friend", "battalionRegiment", "infantry", 150.3050, -33.7056, true),
  unit("A COY", "friend", "company", "infantry", 150.3024, -33.7040),
  unit("B COY", "friend", "company", "infantry", 150.3076, -33.7040),
  unit("C SQN", "friend", "company", "armour", 150.3098, -33.7048),
];

// Mock presence peers, positioned near the friendly force.
const PRESENCE_PEERS = [
  { clientId: uuid(), callsign: "ALPHA-1", affiliation: "friend", echelon: "team",
    function: "infantry", isHQ: false, lat: oy(-33.7035), lon: ox(150.3015), heading: 0, speed: 1.2 },
  { clientId: uuid(), callsign: "BRAVO-2", affiliation: "friend", echelon: "squad",
    function: "armour", isHQ: false, lat: oy(-33.7032), lon: ox(150.3082), heading: 350, speed: 3.4 },
  { clientId: uuid(), callsign: "CHARLIE-3", affiliation: "friend", echelon: "team",
    function: "reconnaissance", isHQ: false, lat: oy(-33.7018), lon: ox(150.3050), heading: 10, speed: 0.8 },
  { clientId: uuid(), callsign: "HQ ACTUAL", affiliation: "friend", echelon: "battalion",
    function: "infantry", isHQ: true, lat: oy(-33.7060), lon: ox(150.3048), heading: 0, speed: 0 },
];

const ws = new WebSocket(url, { headers: { Authorization: `Bearer ${authToken}` } });
let v = 0;
ws.on("open", () => {
  console.log("connected:", url);
  for (const obj of SITUATION) {
    v += 1;
    const id = obj.feature.id;
    const aad = `${id}|${v}|${obj.kind}`;
    const content = JSON.stringify({ type: "FeatureCollection", features: [obj.feature] });
    // Sign the write, then seal {c, pub, sig} - the client verifies the sig
    // against the key it pins for by="ops-hq" before it'll show the object.
    const sig = edSign(objectMessage(id, v, obj.kind, "ops-hq", content));
    const inner = JSON.stringify({ c: content, pub: PUB, sig });
    ws.send(JSON.stringify({ t: "put", id, v, by: "ops-hq", kind: obj.kind, ct: seal(inner, aad) }));
    console.log("pushed", obj.kind, obj.feature.properties.name);
  }
  console.log(`pushed ${SITUATION.length} objects.`);

  // fire off presence for each mock peer, then keep broadcasting every 5s.
  // ts is epoch-ms (Int64) to match the client's signed presence + freshness
  // check; each peer's presence is signed so the client will render it.
  function sendPresence() {
    const ts = Date.now();
    for (const peer of PRESENCE_PEERS) {
      const sig = edSign(presenceMessage(peer.clientId, ts, peer.lat, peer.lon,
        peer.heading, peer.speed, peer.callsign, peer.affiliation, peer.echelon,
        peer.function, peer.isHQ));
      const payload = JSON.stringify({ ...peer, ts, pub: PUB, sig });
      const aad = `loc|${peer.clientId}`;
      ws.send(JSON.stringify({ t: "loc", clientId: peer.clientId, ct: seal(payload, aad) }));
    }
  }
  sendPresence();
  setInterval(sendPresence, 5000);
  console.log(`broadcasting ${PRESENCE_PEERS.length} presence peers every 5s.`);
  console.log("Peers:", PRESENCE_PEERS.map(p => `${p.callsign} (${p.function})`).join(", "));
  console.log("Hold Ctrl+C to stop. Devices joining room will see the situation + peers.");
});
ws.on("error", (e) => console.error("ws error:", e.message || e));
ws.on("close", () => console.log("closed"));
