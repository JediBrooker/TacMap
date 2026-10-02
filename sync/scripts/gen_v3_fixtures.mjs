#!/usr/bin/env node
// Regenerates every derivable vector in testdata/sync_protocol_v3.json from its
// inputs (join code, seeds, uuids, plaintexts, stamps) with a third, node-only
// implementation, and diffs against the committed file (S6-13).
//
//   node scripts/gen_v3_fixtures.mjs           check, exit 1 on any drift
//   node scripts/gen_v3_fixtures.mjs --print   print the regenerated sections
//   --fixture <path>                            check some other copy
//
// The original generator never made it into the repo. This one reproduces the
// committed vectors byte for byte, so new normative cases (relay reset markers,
// new signed frames) can be added here instead of hand-edited on one platform.
// Policy sections (constants, relayLimits) aren't derivable and are skipped;
// sync/test/contract.test.ts pins relayLimits to the relay source.
import {
  actorId, deriveRoom, hex16, keyPairFromSeed, loadFixture, preimage, sha256, sign, wireObjectId, compareStamps,
} from "./v3-wire.mjs"

const fixtureFlag = process.argv.indexOf("--fixture")
const fixture = loadFixture(fixtureFlag > 0 ? process.argv[fixtureFlag + 1] : undefined)
const kd = fixture.key_derivation
const room = deriveRoom(kd.join_code, kd.salt, kd.iterations)
const b64u = bytes => Buffer.from(bytes).toString("base64url")

function identity(entry) {
  const keys = keyPairFromSeed(entry.seed_hex)
  return {
    seed_hex: entry.seed_hex,
    pubkey_raw_hex: keys.pubRaw.toString("hex"),
    pubkey_base64url: b64u(keys.pubRaw),
    actor_id: actorId(room.roomIdRaw, keys.pubRaw),
  }
}

const deviceA = keyPairFromSeed(fixture.identity.device_a.seed_hex)
const alt = deriveRoom(fixture.identity.cross_room_correlation.alt_join_code, kd.salt, kd.iterations)
const altActor = actorId(alt.roomIdRaw, deviceA.pubRaw)

const session = Buffer.from(fixture.signed_preimage.session_domain_hex, "hex")
function signedCase(entry) {
  const payload = entry.kind === "hello" ? Buffer.from(entry.plaintext, "hex") : Buffer.from(entry.plaintext, "utf8")
  const payloadHash = sha256(payload)
  const message = preimage(Number(entry.domain_byte), room.roomIdRaw, entry.actor_id, session,
    hex16(entry.counter), entry.object_id, entry.kind, payloadHash)
  return {
    ...entry,
    payload_hash_hex: payloadHash.toString("hex"),
    preimage_hex: message.toString("hex"),
    signature_base64url: sign(deviceA.privateKey, message),
  }
}

function helloEpochCase(entry) {
  const sessionDomain = Buffer.from(entry.session_domain_hex, "hex")
  const message = preimage(0x04, room.roomIdRaw, fixture.identity.device_a.actor_id, sessionDomain,
    entry.epoch_hex, "", "hello", sha256(deviceA.pubRaw))
  return { ...entry, preimage_hex: message.toString("hex"), signature_base64url: sign(deviceA.privateKey, message) }
}

const window = BigInt(fixture.constants.ADVANCE_WINDOW)
function replayCase(entry) {
  if (entry.pinnedPubkey) return { ...entry, accepted: entry.pinnedPubkey === entry.incomingPubkey }
  if (entry.roomHighWater) {
    const counter = BigInt("0x" + entry.incoming.slice(0, 16))
    return { ...entry, accepted: counter <= BigInt("0x" + entry.roomHighWater) + window }
  }
  const existing = entry.existing ?? entry.tombstone
  return { ...entry, accepted: compareStamps(entry.incoming, existing) > 0 }
}

const regenerated = {
  key_derivation: {
    ...kd,
    master_hex: room.master.toString("hex"),
    auth_token_hex: room.authTokenRaw.toString("hex"),
    auth_token_base64url: room.authToken,
    room_id_raw_hex: room.roomIdRaw.toString("hex"),
    room_id: room.roomId,
    room_key_hex: room.roomKey.toString("hex"),
    metadata_key_hex: room.metadataKey.toString("hex"),
  },
  identity: {
    device_a: identity(fixture.identity.device_a),
    device_b: identity(fixture.identity.device_b),
    cross_room_correlation: {
      ...fixture.identity.cross_room_correlation,
      alt_room_id: alt.roomId,
      device_a_actor_id_in_alt_room: altActor,
      same_device_different_actor: altActor !== actorId(room.roomIdRaw, deviceA.pubRaw),
    },
  },
  wire_object_ids: {
    metadata_key_hex: room.metadataKey.toString("hex"),
    cases: fixture.wire_object_ids.cases.map(entry => ({
      ...entry, wire_object_id: wireObjectId(room.metadataKey, Buffer.from(entry.local_uuid_hex, "hex")),
    })),
  },
  version_stamp: {
    ...fixture.version_stamp,
    comparison_cases: fixture.version_stamp.comparison_cases.map(entry => ({
      ...entry, winner: compareStamps(entry.a, entry.b) > 0 ? "a" : "b",
    })),
  },
  signed_preimage: {
    session_domain_hex: fixture.signed_preimage.session_domain_hex,
    cases: fixture.signed_preimage.cases.map(signedCase),
  },
  hello_epoch_cases: fixture.hello_epoch_cases.map(helloEpochCase),
  auth_verification: {
    ...fixture.auth_verification,
    authTokenBase64url: room.authToken,
    roomId: room.roomId,
    roomIdRawHex: room.roomIdRaw.toString("hex"),
  },
  replay_cases: fixture.replay_cases.map(replayCase),
}

if (process.argv.includes("--print")) {
  console.log(JSON.stringify(regenerated, null, 2))
  process.exit(0)
}

const drift = []
function compare(path, expected, actual) {
  if (typeof expected !== typeof actual || Array.isArray(expected) !== Array.isArray(actual)) {
    drift.push(`${path}: type differs`)
    return
  }
  if (expected && typeof expected === "object") {
    const keys = new Set([...Object.keys(expected), ...Object.keys(actual)])
    for (const key of keys) compare(`${path}.${key}`, expected[key], actual[key])
    return
  }
  if (expected !== actual) drift.push(`${path}: committed ${JSON.stringify(expected)} regenerated ${JSON.stringify(actual)}`)
}
for (const [section, value] of Object.entries(regenerated)) compare(section, fixture[section], value)

if (drift.length > 0) {
  console.error(`sync_protocol_v3.json drifted in ${drift.length} place(s):`)
  for (const line of drift) console.error(`  ${line}`)
  process.exit(1)
}
console.log(`sync_protocol_v3.json: ${Object.keys(regenerated).length} derivable sections regenerate byte-exact`)
