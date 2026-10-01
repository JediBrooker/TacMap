// Sync protocol v3 wire primitives in plain node:crypto. Shared by the fixture
// checker (gen_v3_fixtures.mjs) and the soak harness (soak.mjs), and pinned to
// testdata/sync_protocol_v3.json by selfTest() so neither can drift quietly.
// Mirrors ADR-001 and the Android/iOS SyncCrypto + SyncIdentity code.
import crypto from "node:crypto"
import { readFileSync } from "node:fs"
import { fileURLToPath } from "node:url"

export const FIXTURE_PATH = fileURLToPath(new URL("../../testdata/sync_protocol_v3.json", import.meta.url))

export const DOMAIN = { put: 0x01, del: 0x02, loc: 0x03, hello: 0x04 }

export const b64u = bytes => Buffer.from(bytes).toString("base64url")
export const fromB64u = text => Buffer.from(text, "base64url")
export const sha256 = (...parts) => crypto.createHash("sha256").update(Buffer.concat(parts.map(part => Buffer.from(part)))).digest()
const hmac = (key, label) => crypto.createHmac("sha256", key).update(label, "utf8").digest()
export const hex16 = n => BigInt(n).toString(16).padStart(16, "0")

export function loadFixture(path = FIXTURE_PATH) {
  return JSON.parse(readFileSync(path, "utf8"))
}

export function deriveRoom(joinCode, salt = "tacmap-sync-salt-v3", iterations = 210_000) {
  const master = crypto.pbkdf2Sync(joinCode, salt, iterations, 32, "sha256")
  const authToken = hmac(master, "tacmap-auth-v3")
  const roomIdRaw = sha256(Buffer.from("tacmap-room-id-v3\0"), authToken)
  return {
    master,
    authTokenRaw: authToken,
    authToken: b64u(authToken),
    roomIdRaw,
    roomId: b64u(roomIdRaw),
    roomKey: hmac(master, "tacmap-roomkey-v3"),
    metadataKey: hmac(master, "tacmap-metadata-v3"),
  }
}

export function actorId(roomIdRaw, pubRaw) {
  return b64u(sha256(Buffer.from("tacmap-actor-v3\0"), roomIdRaw, pubRaw))
}

export function wireObjectId(metadataKey, uuidBytes) {
  return b64u(crypto.createHmac("sha256", metadataKey).update(Buffer.concat([Buffer.from("tacmap-wire-obj-v3\0"), uuidBytes])).digest())
}

function le16(n) {
  const out = Buffer.alloc(2)
  out.writeUInt16LE(n)
  return out
}

// ADR-001 buildPreimage: domain, version, room, len16 actor, session, counter
// hex, len16 object id, len8 kind, payload hash
export function preimage(domain, roomIdRaw, actor, sessionDomain, counterHex, objectId, kind, payloadHash) {
  const actorBytes = Buffer.from(actor)
  const objectBytes = Buffer.from(objectId)
  const kindBytes = Buffer.from(kind)
  return Buffer.concat([
    Buffer.from([domain, 0x03]), roomIdRaw, le16(actorBytes.length), actorBytes, sessionDomain,
    Buffer.from(counterHex, "ascii"), le16(objectBytes.length), objectBytes,
    Buffer.from([kindBytes.length]), kindBytes, payloadHash,
  ])
}

export function keyPairFromSeed(seedHex) {
  const pkcs8 = Buffer.concat([Buffer.from("302e020100300506032b657004220420", "hex"), Buffer.from(seedHex, "hex")])
  const privateKey = crypto.createPrivateKey({ key: pkcs8, format: "der", type: "pkcs8" })
  const publicKey = crypto.createPublicKey(privateKey)
  return { privateKey, publicKey, pubRaw: Buffer.from(publicKey.export({ format: "jwk" }).x, "base64url") }
}

export function generateKeyPair() {
  const { privateKey, publicKey } = crypto.generateKeyPairSync("ed25519")
  return { privateKey, publicKey, pubRaw: Buffer.from(publicKey.export({ format: "jwk" }).x, "base64url") }
}

export const sign = (privateKey, message) => b64u(crypto.sign(null, message, privateKey))

export function verify(pubRaw, message, signature) {
  try {
    const key = crypto.createPublicKey({ key: { kty: "OKP", crv: "Ed25519", x: b64u(pubRaw) }, format: "jwk" })
    return crypto.verify(null, message, key, fromB64u(signature))
  } catch {
    return false
  }
}

// AES-256-GCM combined form nonce(12) || ciphertext || tag(16), standard base64
export function seal(key, plaintext, aad) {
  const iv = crypto.randomBytes(12)
  const cipher = crypto.createCipheriv("aes-256-gcm", key, iv)
  cipher.setAAD(Buffer.from(aad))
  return Buffer.concat([iv, cipher.update(plaintext), cipher.final(), cipher.getAuthTag()]).toString("base64")
}

export function open(key, ciphertext, aad) {
  try {
    const bytes = Buffer.from(ciphertext, "base64")
    if (bytes.length < 28) return null
    const decipher = crypto.createDecipheriv("aes-256-gcm", key, bytes.subarray(0, 12))
    decipher.setAAD(Buffer.from(aad))
    decipher.setAuthTag(bytes.subarray(bytes.length - 16))
    return Buffer.concat([decipher.update(bytes.subarray(12, bytes.length - 16)), decipher.final()])
  } catch {
    return null
  }
}

export const objectAad = (wireId, vs, kind) => `${wireId}:${vs}:${kind}`
export const presenceAad = (actor, vs) => `loc:${actor}:${vs}`

export function compareStamps(a, b) {
  const ca = BigInt("0x" + a.slice(0, 16))
  const cb = BigInt("0x" + b.slice(0, 16))
  if (ca !== cb) return ca > cb ? 1 : -1
  const aa = a.slice(17)
  const ab = b.slice(17)
  return aa === ab ? 0 : aa > ab ? 1 : -1
}

// sealed, signed put/del frame exactly as SyncManager sendPutV3/sendDelV3 build it
export function objectFrame({ room, actor, pubRaw, privateKey, sessionDomain, wireId, vs, kind, content, rid }) {
  const deleted = kind === "del"
  const payload = deleted ? Buffer.alloc(0) : Buffer.from(content, "utf8")
  const sig = sign(privateKey, preimage(deleted ? DOMAIN.del : DOMAIN.put, room.roomIdRaw, actor, sessionDomain,
    vs.slice(0, 16), wireId, kind, sha256(payload)))
  const inner = JSON.stringify(deleted ? { sig } : { c: content, sig })
  return {
    t: deleted ? "del" : "put", id: wireId, vs, by: actor, kind,
    ct: seal(room.roomKey, Buffer.from(inner, "utf8"), objectAad(wireId, vs, kind)),
    pub: b64u(pubRaw), sd: b64u(sessionDomain), rid,
  }
}

// peer-side check of a stored/broadcast record: AEAD open + signature over the
// record's own actor/session context. returns the content (or "" for a delete)
export function verifyRecord(room, record) {
  const kind = record.kind
  const plain = open(room.roomKey, record.ct, objectAad(record.id, record.vs, kind))
  if (!plain) return null
  let inner
  try { inner = JSON.parse(plain.toString("utf8")) } catch { return null }
  const deleted = record.deleted === true || record.t === "del"
  const pubRaw = fromB64u(record.pub)
  if (actorId(room.roomIdRaw, pubRaw) !== record.by || record.vs.slice(17) !== record.by) return null
  const payload = deleted ? Buffer.alloc(0) : Buffer.from(String(inner.c ?? ""), "utf8")
  const message = preimage(deleted ? DOMAIN.del : DOMAIN.put, room.roomIdRaw, record.by, fromB64u(record.sd),
    record.vs.slice(0, 16), record.id, kind, sha256(payload))
  if (!verify(pubRaw, message, inner.sig)) return null
  return deleted ? "" : inner.c
}

export function helloFrame({ room, actor, pubRaw, privateKey, sessionDomain, epoch }) {
  const epochHex = hex16(epoch)
  const sig = sign(privateKey, preimage(DOMAIN.hello, room.roomIdRaw, actor, sessionDomain, epochHex, "", "hello", sha256(pubRaw)))
  return { t: "hello", by: actor, pub: b64u(pubRaw), sd: b64u(sessionDomain), vs: `${epochHex}:${actor}`, sig }
}

export function presenceFrame({ room, actor, pubRaw, privateKey, sessionDomain, counter, fields }) {
  const vs = `${hex16(counter)}:${actor}`
  const payload = Buffer.from(JSON.stringify(fields), "utf8")
  const sig = sign(privateKey, preimage(DOMAIN.loc, room.roomIdRaw, actor, sessionDomain, hex16(counter), "", "loc", sha256(payload)))
  const inner = JSON.stringify({ pv: 1, p: payload.toString("base64"), ...fields, pub: b64u(pubRaw), sig })
  return {
    t: "loc", by: actor, pub: b64u(pubRaw), sd: b64u(sessionDomain), vs,
    ct: seal(room.roomKey, Buffer.from(inner, "utf8"), presenceAad(actor, vs)),
  }
}

export function verifyPresence(room, frame) {
  const plain = open(room.roomKey, frame.ct, presenceAad(frame.by, frame.vs))
  if (!plain) return false
  let inner
  try { inner = JSON.parse(plain.toString("utf8")) } catch { return false }
  const payload = Buffer.from(String(inner.p ?? ""), "base64")
  const message = preimage(DOMAIN.loc, room.roomIdRaw, frame.by, fromB64u(frame.sd), frame.vs.slice(0, 16), "", "loc", sha256(payload))
  return verify(fromB64u(frame.pub), message, inner.sig)
}

// Throws unless derivation, actor ids, wire ids and every signed preimage and
// signature reproduce the shared fixture byte for byte.
export function selfTest(fixture = loadFixture()) {
  const kd = fixture.key_derivation
  const room = deriveRoom(kd.join_code, kd.salt, kd.iterations)
  const expectEqual = (label, actual, expected) => {
    if (actual !== expected) throw new Error(`v3 wire self-test: ${label} drifted from the fixture`)
  }
  expectEqual("room id", room.roomId, kd.room_id)
  expectEqual("auth token", room.authToken, kd.auth_token_base64url)
  expectEqual("room key", room.roomKey.toString("hex"), kd.room_key_hex)
  expectEqual("metadata key", room.metadataKey.toString("hex"), kd.metadata_key_hex)
  const device = fixture.identity.device_a
  const keys = keyPairFromSeed(device.seed_hex)
  expectEqual("device pubkey", keys.pubRaw.toString("hex"), device.pubkey_raw_hex)
  expectEqual("actor id", actorId(room.roomIdRaw, keys.pubRaw), device.actor_id)
  for (const entry of fixture.wire_object_ids.cases) {
    expectEqual("wire id", wireObjectId(room.metadataKey, Buffer.from(entry.local_uuid_hex, "hex")), entry.wire_object_id)
  }
  const session = Buffer.from(fixture.signed_preimage.session_domain_hex, "hex")
  for (const entry of fixture.signed_preimage.cases) {
    const payload = entry.kind === "hello" ? Buffer.from(entry.plaintext, "hex") : Buffer.from(entry.plaintext, "utf8")
    const message = preimage(Number(entry.domain_byte), room.roomIdRaw, entry.actor_id, session,
      hex16(entry.counter), entry.object_id, entry.kind, sha256(payload))
    expectEqual(`${entry.name} preimage`, message.toString("hex"), entry.preimage_hex)
    expectEqual(`${entry.name} signature`, sign(keys.privateKey, message), entry.signature_base64url)
  }
  return room
}
