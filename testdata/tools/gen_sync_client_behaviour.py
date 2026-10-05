#!/usr/bin/env python3
# builds testdata/sync_client_behaviour.json from the contract numbers in
# plans/04-sync-client-contract.md. reads relayLimits live so a relay change
# shows up here, and simulates the pacer / budgets so the scenario
# expectations are computed, not hand typed.
#
#   python3 testdata/tools/gen_sync_client_behaviour.py          rewrite the fixture
#   python3 testdata/tools/gen_sync_client_behaviour.py --check  exit 1 if it would change
import json
import math
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECK = "--check" in sys.argv[1:]
relay = json.loads((ROOT / "testdata/sync_protocol_v3.json").read_text())["relayLimits"]
RV = relay["values"]
PACING = relay["clientPacing"]

MiB = 1_048_576

# ---- pacer ---------------------------------------------------------------
PACER = {
    "windowMs": PACING["windowMs"],
    "frameBucket": {"capacity": 30, "refillPerWindow": 120},
    "byteBucket": {"capacity": MiB, "refillPerWindow": 2 * MiB},
    "interactiveReserve": {"frames": 10, "bytes": 65_536},
    "inFlight": {"maxMutations": 32, "maxMutationBytes": MiB,
                 "singleOversizeFrameAllowedWhenEmpty": True},
    "priorities": ["control", "presence", "chat", "retry", "mutation"],
    "reserveAppliesTo": ["retry", "mutation"],
    "presenceQueueDepth": 1,
    "bucketsResetToFullOnNewSocket": True,
    "after4008": {"startFrameTokens": 15, "startByteTokens": MiB // 2},
    "countedFrames": ["hello", "leave", "chat-key", "chat", "loc", "put", "del",
                      "retransmissions of any of these"],
}
assert PACER["frameBucket"]["capacity"] + PACER["frameBucket"]["refillPerWindow"] <= PACING["maxFramesPerWindow"]
assert PACER["byteBucket"]["capacity"] + PACER["byteBucket"]["refillPerWindow"] <= PACING["maxBytesPerWindow"]
assert PACER["byteBucket"]["capacity"] >= RV["MAX_FRAME_BYTES"]
# worst case bunching at the relay: one full sliding window plus everything in flight
assert PACING["maxFramesPerWindow"] + PACER["inFlight"]["maxMutations"] + 4 < RV["RATE_MAX_MSGS"]
assert PACER["inFlight"]["maxMutationBytes"] * 4 <= RV["ROOM_PENDING_MAX_BYTES"]


def frame_rate_per_ms():
    return PACER["frameBucket"]["refillPerWindow"] / PACER["windowMs"]


def byte_rate_per_ms():
    return PACER["byteBucket"]["refillPerWindow"] / PACER["windowMs"]


def simulate_mutations(n, size, ack_after_write_ms):
    """greedy pacer, writes complete instantly, every frame acked ack_after ms later."""
    fcap = PACER["frameBucket"]["capacity"]
    bcap = PACER["byteBucket"]["capacity"]
    fres = PACER["interactiveReserve"]["frames"]
    bres = PACER["interactiveReserve"]["bytes"]
    maxf = PACER["inFlight"]["maxMutations"]
    maxb = PACER["inFlight"]["maxMutationBytes"]
    ft, bt, now = float(fcap), float(bcap), 0.0
    sends, acks = [], []  # acks: (time, size)
    queued = n
    eps = 1e-9
    while queued:
        # retire acks that happened by now
        inflight = [(t, s) for (t, s) in acks if t > now + eps]
        acks = inflight
        inf_count = len(inflight)
        inf_bytes = sum(s for _, s in inflight)
        in_flight_ok = inf_count < maxf and (inf_bytes + size <= maxb or inf_count == 0)
        if ft >= 1 + fres - eps and bt >= size + bres - eps and in_flight_ok:
            ft -= 1
            bt -= size
            sends.append(now)
            acks.append((now + ack_after_write_ms, size))
            queued -= 1
            continue
        # next event: token availability or an ack
        waits = []
        if ft < 1 + fres:
            waits.append((1 + fres - ft) / frame_rate_per_ms())
        if bt < size + bres:
            waits.append((size + bres - bt) / byte_rate_per_ms())
        if not in_flight_ok:
            waits.append(min(t for t, _ in inflight) - now)
        dt = max(waits)
        if not in_flight_ok and (ft >= 1 + fres and bt >= size + bres):
            dt = min(t for t, _ in inflight) - now
        dt = max(dt, 0.0)
        now += dt
        ft = min(fcap, ft + dt * frame_rate_per_ms())
        bt = min(bcap, bt + dt * byte_rate_per_ms())
    return sends


def max_in_window(times, sizes, window):
    best_f, best_b = 0, 0
    for i, t in enumerate(times):
        f = b = 0
        for j in range(i, len(times)):
            if times[j] < t + window:
                f += 1
                b += sizes[j]
        best_f, best_b = max(best_f, f), max(best_b, b)
    return best_f, best_b


p1 = simulate_mutations(500, 2_000, 200)
p1_burst = sum(1 for t in p1 if t == 0.0)
p1_win = max_in_window(p1, [2_000] * len(p1), PACER["windowMs"])
assert p1_burst == 20 and p1_win[0] <= PACING["maxFramesPerWindow"]
p2 = simulate_mutations(10, 700_000, 200)
p2_win = max_in_window(p2, [700_000] * len(p2), PACER["windowMs"])
assert p2_win[1] <= PACING["maxBytesPerWindow"]

# ---- retransmit ------------------------------------------------------------
RETRANSMIT = {
    "timerStartsAt": "transport write completion of that exact copy",
    "ackTimeoutBaseMs": 5_000,
    "ackTimeoutPerAheadMs": 200,
    "ackTimeoutCapMs": 30_000,
    "maxAttempts": 3,
    "androidWritePollMs": 250,
    "formula": "min(capMs, (baseMs + perAheadMs * aheadInFlight) * 2^(attempt-1))",
    "neverReenqueueWhileACopyIsUnwritten": True,
    "onExhausted": {"issue": "UNCONFIRMED_RECONNECT", "action": "cancelSocket", "backoffClass": "transient",
                    "opBecomes": "reconciliation (forcedLocalDiff)"},
}


def ack_timeout(attempt, ahead):
    return min(RETRANSMIT["ackTimeoutCapMs"],
               (RETRANSMIT["ackTimeoutBaseMs"] + RETRANSMIT["ackTimeoutPerAheadMs"] * ahead) * 2 ** (attempt - 1))


# ---- receive budget --------------------------------------------------------
RECEIVE = {
    "windowMs": 10_000,
    "windowKind": "tumbling, same as today",
    "androidTransportQueue": {"maxBytes": 16 * MiB, "maxFrames": 8_192},
    "room": {"baseFrames": 600, "perSessionFrames": 25, "maxFrames": 4_000,
             "baseBytes": 12 * MiB, "perSessionBytes": 262_144, "maxBytes": 48 * MiB,
             "sessionsCounted": "authenticated remote sessions in activeSessions at admit time"},
    "selfResponse": {"types": ["op-ack", "op-nack", "chat-ack", "chat-nack", "chat-key-ack",
                               "chat-key-nack", "hello-ack"],
                     "maxFrames": 400, "maxBytes": MiB,
                     "classification": "by the t field only; still matched exactly against pending requests afterwards"},
    "initial": {"maxFrames": 10_000, "maxBytes": 54_525_952, "appliesUntil": "hello-ack"},
    "background": {"maxFrames": 4_000, "maxBytes": 48 * MiB,
                   "note": "frames discarded unparsed in background presence mode count here only"},
    "onExceed": {"localClose": 1008, "backoffClass": "transient"},
}
assert RECEIVE["room"]["baseFrames"] >= 3 * RV["RATE_MAX_MSGS"]
assert RECEIVE["room"]["baseBytes"] >= 3 * RV["RATE_MAX_BYTES"]
assert RECEIVE["selfResponse"]["maxFrames"] >= 2 * PACING["maxFramesPerWindow"]


def room_budget(sessions):
    r = RECEIVE["room"]
    return (min(r["maxFrames"], r["baseFrames"] + r["perSessionFrames"] * sessions),
            min(r["maxBytes"], r["baseBytes"] + r["perSessionBytes"] * sessions))


# ---- watchdogs / heartbeat ---------------------------------------------------
WATCHDOGS = {
    "connectOpenTimeoutMs": 30_000,
    "connectOpenSatisfiedBy": ["transport open (101)", "first inbound message"],
    "handshakeStallTimeoutMs": 60_000,
    "handshakeProgressIs": ["any inbound transport bytes where the transport exposes them (Android BoundedDraft.translateFrame)",
                            "any complete inbound message (iOS)",
                            "any locally completed snapshot validation step"],
    "suspendedBetween": ["snapshot-end received", "hello written"],
    "helloAckTimeoutMs": 30_000,
    "handshakeAbsoluteMaxMs": 900_000,
    "androidTcpConnectTimeoutMs": 10_000,
    "inboundQueueSpaceTimeoutMs": 60_000,
    "onFire": {"backoffClass": "transient", "issueKind": "CONNECTION"},
}
HEARTBEAT = {
    "foreground": {"pingIntervalMs": 20_000, "deadAfterMsWithoutPongOrFrame": 30_000},
    "background": {"pingIntervalMs": 60_000, "deadAfterMsWithoutPongOrFrame": 30_000},
    "backgroundSendProbe": {"livenessMaxAgeMs": 75_000, "probePongTimeoutMs": 10_000,
                            "androidWakeLockMaxMs": 20_000},
    "pathChangeProbeTimeoutMs": 5_000,
}

# ---- backoff -------------------------------------------------------------------
BACKOFF = {
    "formula": "delayMs = floorMs + random * min(capMs - floorMs, spreadMs * 2^attempt); random uniform in [0,1]; attempt starts at 0, +1 after every scheduled reconnect, capped at attemptCap",
    "attemptCap": 20,
    "sharedAttemptCounterAcrossClasses": True,
    "classes": {
        "transient": {"floorMs": 250, "spreadMs": 750, "capMs": 30_000},
        "slow_busy": {"floorMs": 15_000, "spreadMs": 15_000, "capMs": 300_000},
        "slow_rate": {"floorMs": 60_000, "spreadMs": 30_000, "capMs": 300_000},
        "slow_storage": {"floorMs": 30_000, "spreadMs": 30_000, "capMs": 300_000},
        "slow_unknown": {"floorMs": 30_000, "spreadMs": 30_000, "capMs": 300_000},
    },
    "resetWhen": ["first op-ack received in the current session",
                  "the session has been CONNECTED continuously for stableSessionMs"],
    "neverResetAt": ["transport open", "snapshot-end", "hello-ack"],
    "stableSessionMs": 30_000,
}


def backoff_delay(cls, attempt, rnd):
    c = BACKOFF["classes"][cls]
    return c["floorMs"] + rnd * min(c["capMs"] - c["floorMs"], c["spreadMs"] * 2 ** attempt)


REACHABILITY = {
    "triggers": {"android": ["ConnectivityManager default NetworkCallback onAvailable",
                             "onCapabilitiesChanged gaining NET_CAPABILITY_VALIDATED"],
                 "ios": ["NWPathMonitor status unsatisfied -> satisfied",
                         "NWPathMonitor interface type change while satisfied"]},
    "minSpacingFromLastAttemptMs": 2_000,
    "appliesToBackoffClasses": ["transient"],
    "resetsAttemptCounter": False,
    "whileConnected": "network lost or default network changed -> liveness probe (ping) with pathChangeProbeTimeoutMs; failure = transport loss",
    "neverOverrides": ["PAUSED_ACTION_REQUIRED", "slow_* floors", "background mode (see background.reconnect)"],
}

STOP = {
    "structuralSnapshotFailuresBeforeStop": 3,
    "structuralCounterResetBy": "hello-ack",
    "sessionConflictsBeforeStop": 3,
    "sessionConflictWindowMs": 600_000,
    "staleEpochEscalationsBeforeStop": 3,
    "busyFailuresBeforeSurface": 2,
    "pausedState": "PAUSED_ACTION_REQUIRED: socket closed, wantConnected false, room/keys/replay kept, issue shown with Retry; Retry or (if retryOnForeground) the next foreground return resets the counters and connects",
}

CLOSE_CODES = {
    "1000": {"action": "none_if_we_sent_leave_else_reconnect", "backoffClass": "transient"},
    "1001": {"action": "reconnect", "backoffClass": "transient"},
    "1005": {"action": "reconnect", "backoffClass": "transient"},
    "1006": {"action": "reconnect", "backoffClass": "transient"},
    "1007": {"action": "reconnect", "backoffClass": "transient"},
    "1011": {"action": "reconnect", "backoffClass": "slow_storage", "surfaceAfterConsecutive": 2, "issue": "RELAY_BUSY"},
    "1012": {"action": "reconnect", "backoffClass": "transient"},
    "1013": {"action": "reconnect", "backoffClass": "slow_busy", "surfaceAfterConsecutive": 2, "issue": "RELAY_BUSY"},
    "4008": {"action": "reconnect", "backoffClass": "transient", "pacer": "after4008"},
    "4009": {"action": "reconnect", "backoffClass": "slow_unknown"},
    "4010": {"action": "stop", "issue": "IDENTITY_REJECTED", "retryOnForeground": False},
    "4011": {"action": "stop", "issue": "IDENTITY_REJECTED", "retryOnForeground": False},
    "4012": {"action": "reconnect", "backoffClass": "transient"},
    "4013": {"action": "stop", "issue": "ROOM_FULL_CANNOT_JOIN", "retryOnForeground": True},
    "4014": {"action": "escalate_epoch_then_reconnect", "backoffClass": "transient",
             "stopAfter": "staleEpochEscalationsBeforeStop", "stopIssue": "SESSION_COUNTER_BEHIND"},
    "4015": {"action": "reconnect", "backoffClass": "transient", "countsAs": "sessionConflict",
             "stopAfter": "sessionConflictsBeforeStop", "stopIssue": "SESSION_CONFLICT", "retryOnForeground": True},
    "other_1xxx": {"action": "reconnect", "backoffClass": "transient"},
    "other_4xxx": {"action": "reconnect", "backoffClass": "slow_unknown"},
}
# every relay close code the fixture documents must have an explicit row
for code in relay["closeCodes"]:
    assert code in CLOSE_CODES, code

HTTP = {
    "401": {"action": "stop", "issue": "RELAY_REFUSED_ROOM", "retryOnForeground": False},
    "403": {"action": "stop", "issue": "RELAY_REFUSED_ROOM", "retryOnForeground": False},
    "404": {"action": "stop", "issue": "RELAY_REFUSED_ROOM", "retryOnForeground": False},
    "426": {"action": "stop", "issue": "RELAY_REFUSED_ROOM", "retryOnForeground": False},
    "429": {"action": "reconnect", "backoffClass": "slow_rate", "surfaceAfterConsecutive": 1, "issue": "RELAY_RATE_LIMITED"},
    "503": {"action": "reconnect", "backoffClass": "slow_busy", "surfaceAfterConsecutive": 2, "issue": "RELAY_BUSY"},
    "other_5xx": {"action": "reconnect", "backoffClass": "transient"},
    "other_4xx": {"action": "reconnect", "backoffClass": "slow_unknown"},
    "none": {"action": "reconnect", "backoffClass": "transient",
             "covers": "DNS, TCP, TLS, proxy failures and timeouts with no HTTP status"},
    "statusSource": {"android": "Java-WebSocket handshake failure reason /Invalid status code received: (\\d{3})/ when the socket never opened",
                     "ios": "(task.response as? HTTPURLResponse)?.statusCode when the task never opened"},
}
for status in relay["upgradeStatus"]:
    assert status in HTTP, status

LOCAL_CLOSE = {
    "leave": {"action": "none"},
    "lifecyclePause": {"action": "none"},
    "receiveBudgetExceeded": {"closeCode": 1008, "action": "reconnect", "backoffClass": "transient"},
    "oversizedInbound": {"closeCode": 1009, "action": "reconnect", "backoffClass": "transient"},
    "binaryInbound": {"closeCode": 1003, "action": "reconnect", "backoffClass": "transient"},
    "connectOpenTimeout": {"action": "reconnect", "backoffClass": "transient"},
    "handshakeStall": {"action": "reconnect", "backoffClass": "transient"},
    "helloAckTimeout": {"action": "reconnect", "backoffClass": "transient"},
    "handshakeAbsolute": {"action": "reconnect", "backoffClass": "transient"},
    "livenessTimeout": {"action": "reconnect", "backoffClass": "transient"},
    "ackExhausted": {"action": "reconnect", "backoffClass": "transient", "issue": "UNCONFIRMED_RECONNECT"},
    "structuralSnapshot": {"action": "reconnect", "backoffClass": "transient", "issue": "SNAPSHOT_STRUCTURAL",
                           "stopAfter": "structuralSnapshotFailuresBeforeStop", "stopIssue": "SNAPSHOT_MALFORMED_STOPPED"},
    "liveWindowResync": {"action": "reconnect_now", "countsAsFailure": False},
    "sessionNack": {"action": "reconnect", "backoffClass": "transient", "countsAs": "sessionConflict",
                    "stopAfter": "sessionConflictsBeforeStop", "stopIssue": "SESSION_CONFLICT"},
    "helloRequiredNack": {"action": "reconnect", "backoffClass": "transient"},
}

OP_NACK = {
    "stale": {"resolveOp": True, "reconnect": False, "issue": None,
              "ownPersistedStampAndNotSkipped": "markConfirmed",
              "otherwise": "suppressUntilLocalEdit"},
    "not-found": {"sameAs": "stale"},
    "counter-window": {"resolveOp": True, "reconnect": False, "pauseMutationsForJoin": True,
                       "issue": "ROOM_RESET_CHANGES_PAUSED"},
    "quota": {"resolveOp": True, "reconnect": False, "suppressUntilLocalEdit": True, "issue": "ROOM_QUOTA_NACK"},
    "invalid": {"resolveOp": True, "reconnect": False, "suppressUntilLocalEdit": True, "issue": "RELAY_INVALID_NACK"},
    "storage": {"resolveOp": False, "retry": "through pacer on the ack timeout schedule, consumes an attempt",
                "reconnect": False, "issue": None},
    "hello-required": {"resolveOp": False, "reconnect": True, "localClose": "helloRequiredNack"},
    "session-mismatch": {"resolveOp": False, "reconnect": True, "localClose": "sessionNack"},
    "session-replaced": {"resolveOp": False, "reconnect": True, "localClose": "sessionNack"},
    "unknown_retry_true": {"sameAs": "storage"},
    "unknown_retry_false": {"sameAs": "invalid"},
    "neverSecurityKind": True,
    "unparseableNack": "ignored (as today), the op stays pending on its ack timer",
}
for code in relay["opNackCodes"]:
    assert code in OP_NACK, code

SNAPSHOT = {
    "fatalStructural": [
        "snapshot_begin_when_not_connecting", "second_snapshot_begin", "seq_not_nonnegative_integer",
        "page_before_begin", "page_after_final_page", "more_not_boolean", "items_not_array",
        "item_not_object", "item_id_missing_or_not_canonical_32_byte_base64url", "duplicate_wire_id",
        "end_before_final_page", "end_seq_mismatch", "aggregate_bytes_over_54525952",
        "item_count_over_10000"],
    "skipUnverified": [
        "vs_unparseable", "by_not_equal_vs_actor", "pub_or_sd_not_canonical", "actor_binding_mismatch",
        "pub_differs_from_pinned_key", "kind_syntax_invalid", "type_and_deleted_inconsistent",
        "ct_not_canonical_or_too_short_or_too_long", "aead_open_failed", "inner_json_invalid",
        "signature_missing_or_invalid", "tombstone_inner_has_extra_keys"],
    "skipUnsupported": [
        "kind_unknown_but_authentic", "put_content_missing_or_empty", "importer_failed",
        "importer_invalid_skipped_nonzero", "object_count_not_exactly_one", "kind_content_mismatch",
        "embedded_uuid_does_not_match_wire_id", "identity_collision_with_other_object_kind",
        "expected_model_hash_unavailable"],
    "ignoreNotNewer": "authenticated record that does not beat local replay state (unchanged behaviour, not counted)",
    "skippedRecordsAre": ["not committed to replay state", "not applied to the model",
                          "added to the join's skippedWireIds", "counted per category for the notice"],
    "commitSeqWithSkips": True,
    "verifiedCleanSnapshotRequires": ["zero skipUnverified records", "no seq regression"],
    "liveRecordsUseSameClassification": True,
}

# ---- 3.0.1 (sync-android-2): the embedded object id must be a canonical UUID before it's hashed -----------
CANONICAL_UUID = r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"


def canonical_uuid(s):
    """whole string, ASCII hex only, no trimming, no trailing newline (re.fullmatch, not $)"""
    return len(s) == 36 and re.fullmatch(CANONICAL_UUID[1:-1], s, flags=re.ASCII) is not None


def _java_digit(ch):
    """Character.digit(ch, 16) for the characters these rows use (ASCII, fullwidth digits / letters)"""
    o = ord(ch)
    if "0" <= ch <= "9":
        return o - 48
    if "a" <= ch <= "f":
        return o - 87
    if "A" <= ch <= "F":
        return o - 55
    if 0xFF10 <= o <= 0xFF19:
        return o - 0xFF10
    assert ch.isascii(), "model only knows ASCII + fullwidth digits: %r" % ch
    return -1


def lenient_uuid_bytes(s):
    """what 3.0.0 Android's SyncIdentity.uuidToBytes hashed: strip '-', need 32 chars, Character.digit per
    nibble with no validation. None where it threw (the record then never matched its wire id)"""
    stripped = s.replace("-", "")
    if len(stripped) != 32:
        return None
    return bytes(((_java_digit(stripped[i]) << 4) + _java_digit(stripped[i + 1])) & 0xFF
                 for i in range(0, 32, 2)).hex()


def embedded_id_cases():
    canon = "3f2a1b4c-0d5e-4f60-8a7b-9c8d7e6f5a4b"
    canon_bytes = canon.replace("-", "")
    rows = [
        ("lowercase_canonical", canon, "what both apps send in v3 today (iOS GeoJSONExporter.wire, Android UUID.toString)"),
        ("uppercase_canonical", canon.upper(), "RFC 9562: case-insensitive on input. Accepted on both; Android folds the "
                                               "local id to lowercase so one object never gets two local ids"),
        ("mixed_case_canonical", "3F2a1B4c-0D5e-4F60-8a7B-9c8D7e6F5a4B", "same as uppercase"),
        ("hex32_no_dashes", canon_bytes, "sync-android-2: 3.0.0 Android hashed it to the canonical wire id, then "
                                          "validRemote refused it and sync stopped for the room"),
        ("nonhex32", "zyxwvutsrqponmlkjihgzyxwvutsrqpo", "32 non-hex chars: Character.digit gave -1 per nibble, "
                                                          "a wire id an attacker can still compute"),
        ("braces", "{" + canon + "}", "Microsoft registry form"),
        ("leading_space", " " + canon, "no trimming"),
        ("trailing_newline", canon + "\n", "a regex $ that matches before a final newline must not let this through"),
        ("fullwidth_digit", "３" + canon[1:], "Character.digit and Long.parseLong accept Unicode digits; ASCII only"),
        ("short_groups", "0-0-0-0-0", "java.util.UUID.fromString accepts this, the canonical form doesn't"),
        ("nil_uuid_canonical", "00000000-0000-0000-0000-000000000000", "canonical and accepted (a UUID like any other)"),
    ]
    out = []
    for cid, embedded, note in rows:
        ok = canonical_uuid(embedded)
        lenient = lenient_uuid_bytes(embedded)
        if ok:
            outer, outer_from = embedded.replace("-", "").lower(), "canonicalBytes"
        elif lenient is not None:
            outer, outer_from = lenient, "lenientBytes"
        else:
            outer, outer_from = canon_bytes, "nearestCanonicalBytes"
        expect = ({"category": "valid", "localId": embedded.lower()} if ok else
                  {"category": "skipUnsupported", "reason": "embedded_uuid_does_not_match_wire_id"})
        expect.update({"paths": ["snapshot", "live"], "stopsSync": False, "persistenceFailure": False,
                       "issueIfSkipped": None if ok else "SKIPPED_UNSUPPORTED"})
        out.append({"id": cid, "embeddedId": embedded, "outerWireIdFromBytesHex": outer, "outerFrom": outer_from,
                    "lenient300Bytes": lenient, "expect": expect, "note": note})
    assert {r["id"] for r in out if r["expect"]["category"] == "valid"} == {
        "lowercase_canonical", "uppercase_canonical", "mixed_case_canonical", "nil_uuid_canonical"}
    # the poison rows: 3.0.0 Android's hasher matched them, so they reached validRemote
    assert lenient_uuid_bytes(canon_bytes) == canon_bytes and lenient_uuid_bytes("３" + canon[1:]) == canon_bytes
    return out


SNAPSHOT["embeddedIdPattern"] = CANONICAL_UUID
SNAPSHOT["embeddedIdRule"] = (
    "3.0.1: before the wire id is computed, the single object's embedded id must be a canonical 8-4-4-4-12 UUID: "
    "whole-string match of embeddedIdPattern, ASCII hex in either case, exactly 36 characters, no trimming. "
    "Anything else is skipUnsupported embedded_uuid_does_not_match_wire_id on both the snapshot and the live "
    "path, never a sync stop. The wire-id hasher refuses non-canonical input too (null, never lenient bytes). "
    "An accepted id is the local model id in lowercase (Android folds it; iOS keeps a UUID value). Every "
    "record the validator accepts must also pass the replay commit's own checks, so a validated record can never "
    "end in persistenceFailure. v3 senders keep sending lowercase")
SNAPSHOT["embeddedIdCases"] = embedded_id_cases()
SNAPSHOT["embeddedIdCasesRule"] = (
    "build a validly sealed and signed v3 waypoint put whose GeoJSON feature id is embeddedId and whose outer id "
    "is the wire id of the 16 bytes outerWireIdFromBytesHex (HMAC(metadataKey, 'tacmap-wire-obj-v3\\0' || bytes)); "
    "run it through the classifier and through the real manager on the snapshot path and as a live put")

LIVE_RESYNC = {
    "trigger": "authenticated live put/del whose stamp beats local state but counter - roomHighWater > ADVANCE_WINDOW",
    "cooldownMs": 60_000,
    "maxPerHour": 6,
    "whileCoolingDown": "drop the frame (today's behaviour) and remember one pending resync",
    "action": "clean close + immediate reconnect, not counted as a failure, backoff untouched",
    "neverTrust": "snapshot-begin highWater",
}

ROOM_RESET = {
    "seqRegression": {"detect": "snapshot seq < lastSnapshotSeq", "issue": "ROOM_RESET_SUSPECTED",
                      "surface": "once per join", "continueSyncing": True,
                      "lastSnapshotSeq": "max(previous, seq) as today"},
    "counterWindowNack": {"issue": "ROOM_RESET_CHANGES_PAUSED", "surface": "once per join",
                          "pauseMutationsUntil": "leave or join", "keep": ["socket", "presence", "chat", "inbound apply"]},
}

HELLO_EPOCH = {
    "timeFloorUnit": "unix minutes = floor(unixMillis / 60000)",
    "timeFloorAppliesWhen": ["no persisted epoch for the local actor", "previous connection closed 4014"],
    "escalationFactor": 2,
    "escalationAppliesWhen": "previous connection closed 4014; multiplies the epoch that was rejected",
    "maxEscalationsPerJoin": 3,
    "backgroundSpareBlock": 64,
    "backgroundSpareBlockAppliesWhen": "background presence is opted in for this v3 room at hello reservation time",
    "max": "ffffffffffffffff",
    "formula": "next = max(persisted+1 (or 1), timeFloor if applicable, 2*rejected if 4014); persist next (+spare block if bg) before signing",
    "vectors": [
        {"name": "plain_increment", "persisted": "0000000000000005", "nowMs": 1790812800000,
         "after4014": False, "bgOptIn": False, "expectNext": "0000000000000006", "expectPersisted": "0000000000000006",
         "expectSpares": []},
        {"name": "bg_spare_block", "persisted": "0000000000000005", "nowMs": 1790812800000,
         "after4014": False, "bgOptIn": True, "expectNext": "0000000000000006", "expectPersisted": "0000000000000046",
         "expectSpares": {"first": "0000000000000007", "last": "0000000000000046", "count": 64}},
        {"name": "lost_state_time_floor", "persisted": None, "nowMs": 1790812800000,
         "after4014": False, "bgOptIn": False, "expectNext": "0000000001c76d60", "expectPersisted": "0000000001c76d60",
         "expectSpares": []},
        {"name": "stale_epoch_doubles", "persisted": "0000000001c76d60", "rejected": "0000000001c76d60",
         "nowMs": 1790812800000, "after4014": True, "bgOptIn": False,
         "expectNext": "00000000038edac0", "expectPersisted": "00000000038edac0", "expectSpares": []},
        {"name": "exhausted", "persisted": "ffffffffffffffff", "nowMs": 1790812800000,
         "after4014": False, "bgOptIn": False, "expectError": "exhausted -> stop SESSION_COUNTER_BEHIND"},
    ],
}
assert int("0000000001c76d60", 16) == 1790812800000 // 60000
assert int("00000000038edac0", 16) == 2 * (1790812800000 // 60000)

CHAT = {
    "replayFences": {
        "maxFences": 256,
        "pruneRule": "drop fence (actor, sd, kid) when the durable replay state has a session domain for that actor and it differs from sd",
        "pruneWhen": ["before admitting a new identity", "on room open", "on any chat-store write"],
        "atCapWithNothingPrunable": {"result": "reject", "issue": "CHAT_REPLAY_FULL", "surface": "once per join"},
        "fingerprintsKeptOnWrite": 16,
        "fingerprintsAcceptedOnLoad": 64,
    },
    "history": {"maxMessages": 500, "maxEncodedBytes": 2_097_152, "pruneTargetBytes": 1_572_864,
                "pruneOrder": "oldest by local acceptance order",
                "pruneWhen": "a candidate document would exceed maxEncodedBytes",
                "unreadIdsFollowRetention": True},
    "backgroundRecipient": {
        "rule": "a remote session whose latest accepted presence advertises retention > 45 s is not chat-receiving",
        "directSend": "blocked locally with CHAT_RECIPIENT_IN_BACKGROUND",
        "roomSend": "allowed, state stays Routed / Sent to room as today",
        "foregroundRetentionSeconds": 45,
    },
}

PRESENCE = {
    "foreground": {
        "minIntervalMs": 5_000,
        "stationaryHeartbeatMs": 20_000,
        "moveThreshold": "distance from last SENT fix > max(moveMinMetres, current horizontalAccuracy)",
        "moveMinMetres": 10.0,
        "courseChangeDegrees": 20.0,
        "courseCheckMinSpeedMps": 1.5,
        "speedChangeMps": 1.5,
        "immediateTriggers": ["first frame of an authenticated session", "presence config change (callsign, affiliation, echelon, function, isHQ)"],
        "distance": "haversine, R = 6371008.8 m",
    },
    "background": {"cadence": "unchanged: selected interval", "stationarySuppression": False,
                   "bridgeFrameAtEntry": "send one background-retention frame using the newest fix <= bridgeFixMaxAgeMs old",
                   "bridgeFixMaxAgeMs": 120_000},
    "fencePersistence": {
        "stride": 16,
        "flushMs": 60_000,
        "crashFloorAdd": 15,
        "flag": "presenceFenceExact",
        "rule": "persist (all actors, one write, flag false) before exposing a counter >= persisted+stride; flush every flushMs while any counter differs; clean points write flag true; before the first acceptance after a flag-true write, write flag false",
        "cleanPoints": ["leave", "background entry", "dispose"],
        "loadFloorWhenNotExact": "persisted + crashFloorAdd",
    },
}

BATCHING = {
    "inboundBatchMaxFrames": 64,
    "splitBeforeRepeatedMutationWireId": True,
    "inboundQueueMaxFrames": 1_024,
    "inboundQueueMaxBytes": 16 * MiB,
    "replayWritesMax": {"perSnapshot": 2, "perHello": 1, "perLiveBatch": 2, "perOutboundDiffPass": 1,
                        "presence": "at most one per flushMs in steady state"},
    "storeWritesMax": {"perSnapshotPerStore": 1, "perLiveBatchPerStore": 1},
    "journalWritesMax": "one per local store mutation event (all ids in that event)",
    "diffDebounceMs": 250,
    "acksTriggerSyncDiff": False,
    "runningHighWater": True,
    "noDeepCopyPerTransaction": True,
}

WIRE_ID_INDEX = {
    "build": "once per room session after keys and stores are attached, one reusable HMAC instance",
    "maintain": ["local store mutation events", "remote apply", "remote delete"],
    "invalidate": ["leave", "join", "store detach / key lock"],
    "lookups": "O(1) forward (localId -> wireId) and reverse (wireId -> localId); a reverse miss means no local object, never a scan",
    "diffChecksContentBeforeHashing": True,
}

LIFECYCLE = {
    "sessionEndsIff": "the transition locks the mission-data key, engages App Lock, or detaches the mission stores",
    "ios": {"inactiveIsTransientWhen": ["DataKey stays unlocked (device-bound mode)", "App Lock overlay not shown"],
            "onTransientInactive": ["keep socket", "keep chat key", "keep processing inbound", "no reconnect on return"],
            "authBoundInactive": "DataKey locks -> same as background"},
    "android": {"onPauseWithoutBackgroundPresence": ["close socket (no explicit leave)", "detach stores", "keep manager, room, keys, replay state, chat binding", "reconnect after stores attach on resume"],
                "protocols": ["v2", "v3"],
                "disposeOnlyOn": ["explicit leave", "join-code change", "process death"]},
    "foregroundReturn": "fresh connection and verified snapshot (unchanged)",
}

BACKGROUND = {
    "onEntry": ["cancel all delivery retry timers", "move pending ops to reconciliation (forcedLocalDiff)",
                "release chat secrets (pending chats fail)", "send bridge presence frame",
                "switch heartbeat to background interval", "discarded frames count only against receiveBudget.background"],
    "never": ["delivery retries", "unconfirmed-change errors", "socket cancel caused by deliveries"],
    "acks": "dropped with all other inbound frames; the foreground snapshot re-derives which ops landed",
    "reconnect": {
        "enabledPendingDocChange": "THREAT_MODEL section 7 background paragraph (contract D1)",
        "attemptAt": ["next presence send opportunity after a detected drop", "reachability regained"],
        "minSpacingMs": 60_000,
        "maxConsecutiveFailures": 3,
        "maxSnapshotDrainBytes": 4 * MiB,
        "epoch": "next unused spare from helloEpoch.backgroundSpareBlock, never a durable write",
        "session": ["fresh session domain", "drain snapshot frames unparsed (fence + byte ceilings only)",
                    "send hello", "await hello-ack (helloAckTimeoutMs)", "send presence only"],
        "neverSend": ["chat-key", "chat", "put", "del"],
        "pauseWhen": ["no spare epoch left", "maxConsecutiveFailures reached", "snapshot larger than maxSnapshotDrainBytes", "location eligibility lost"],
    },
    "pauseSurface": {"issue": "BACKGROUND_PAUSED", "android": "replace the FGS notification with a non-ongoing paused notification", "ios": "issue shown on next foreground"},
}

V2_ID = "3f2a1b4c-0d5e-4f60-8a7b-9c8d7e6f5a4b"
V2 = {
    "outboundId": "3.0.1 (owner decision, gap-v2-room-2x-interop-1/2): iOS sends uuidString (uppercase), exactly as "
                  "shipped 2.x iOS did. Android sends the raw id it remembered for that state key (sticky once any "
                  "accepted inbound record used a raw id with an uppercase letter), else its lowercase local id",
    "outboundIdRules": {
        "ios": "frame id, AAD and signature message = uuid.uuidString (uppercase). Every map (lastContent, "
               "versions, versionsBy, kindById, forcedLocalDiff, forcedLegacyDeletes, deliveries' localId, join "
               "suppression) stays keyed by the lowercase state key; only the wire id is uppercase",
        "android": "frame id, AAD and signature message = rememberedRawId[stateKey] ?: localId (lowercase). The "
                   "local object keeps the lowercase id (unchanged)",
        "embeddedId": "unchanged: lowercase on both (GeoJSONExporter.wire / UUID.toString)",
        "remember": "Android only: every inbound put or del that passes stateKey, beats, AEAD and signature (and, "
                    "for a put, the embedded check), applied or not, records its raw id for the state key when the "
                    "raw id contains an uppercase letter. A later lowercase record never replaces it. Kept for "
                    "deleted objects too, so an undo re-creates under the same casing",
        "persist": "Android only: sealed per-room store (DEK-bound opaque filename like the replay and chat stores), "
                   "loaded at v2 join before the first diff, written at most once per snapshot and once per live "
                   "batch, capped at 10,000 entries (then it stops learning), removed with the room's other local "
                   "stores. A lost or unreadable store only means lowercase sends until the next snapshot re-teaches it",
        "upgradeFrom300": "iOS 3.0.0 kept every v2 per-id map in memory (cleared on each v2 connect), so nothing "
                          "on the device is keyed lowercase across the update. Relay records 3.0.0 iOS wrote under "
                          "lowercase ids stay; 3.0.1 never deletes or rewrites a record because of its casing "
                          "(a del under the other casing deletes the object on 2.x iOS). The next edit supersedes "
                          "them under the uppercase id at a higher v",
        "v3": "unaffected: v3 wire ids are HMACs of the UUID bytes, embedded ids follow snapshot.embeddedIdRule",
    },
    "inboundIdPattern": "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
    "verifyWith": "the raw id string exactly as received (AAD and signature)",
    "stateKey": "lowercase(raw id)",
    "embeddedIdCheck": "lowercase(embedded feature id) == lowercase(record id)",
    "tieBreak": "apply iff (v, by) > (lastV, lastBy) with by compared as ASCII byte strings",
    "vectors": {
        "casing": [
            {"raw": "3F2A1B4C-0D5E-4F60-8A7B-9C8D7E6F5A4B", "accept": True, "key": "3f2a1b4c-0d5e-4f60-8a7b-9c8d7e6f5a4b"},
            {"raw": "3f2a1b4c-0d5e-4f60-8a7b-9c8d7e6f5a4b", "accept": True, "key": "3f2a1b4c-0d5e-4f60-8a7b-9c8d7e6f5a4b"},
            {"raw": "3f2a1b4c0d5e4f608a7b9c8d7e6f5a4b", "accept": False},
            {"raw": "{3f2a1b4c-0d5e-4f60-8a7b-9c8d7e6f5a4b}", "accept": False},
        ],
        "outbound": [
            {"id": "ios_own_object", "platform": "ios", "localId": V2_ID.upper(), "rememberedRawId": None,
             "expectFrameId": V2_ID.upper(), "expectStateKey": V2_ID, "expectEmbeddedId": V2_ID},
            {"id": "ios_android_created_object", "platform": "ios", "localId": V2_ID.upper(),
             "lastInboundRawId": V2_ID, "rememberedRawId": None,
             "expectFrameId": V2_ID.upper(), "expectStateKey": V2_ID, "expectEmbeddedId": V2_ID,
             "note": "iOS always sends uppercase, whoever created the object (2.x iOS did the same)"},
            {"id": "android_own_object", "platform": "android", "localId": V2_ID, "rememberedRawId": None,
             "expectFrameId": V2_ID, "expectStateKey": V2_ID, "expectEmbeddedId": V2_ID,
             "note": "shipped 2.x Android drops uppercase, so Android-created objects stay lowercase (S3-01) until an uppercase id for them is accepted, e.g. after a 3.0.1 iOS edit"},
            {"id": "android_edits_ios_object", "platform": "android", "localId": V2_ID,
             "rememberedRawId": V2_ID.upper(), "expectFrameId": V2_ID.upper(), "expectStateKey": V2_ID,
             "expectEmbeddedId": V2_ID,
             "note": "gap-v2-room-2x-interop-2: the edit goes out under the id 2.x iOS uses, so it doesn't echo-delete"},
            {"id": "android_delete_ios_object", "platform": "android", "kind": "del", "localId": V2_ID,
             "rememberedRawId": V2_ID.upper(), "expectFrameId": V2_ID.upper(), "expectStateKey": V2_ID},
        ],
        "remember": [
            {"id": "upper_put_learned", "events": [{"raw": V2_ID.upper(), "t": "put", "accepted": True}],
             "expectRemembered": V2_ID.upper()},
            {"id": "sticky_over_later_lower", "events": [{"raw": V2_ID.upper(), "t": "put", "accepted": True},
                                                        {"raw": V2_ID, "t": "put", "accepted": True}],
             "expectRemembered": V2_ID.upper()},
            {"id": "lower_only", "events": [{"raw": V2_ID, "t": "put", "accepted": True}], "expectRemembered": None},
            {"id": "not_accepted_not_learned", "events": [{"raw": V2_ID.upper(), "t": "put", "accepted": False}],
             "expectRemembered": None},
            {"id": "upper_del_learned", "events": [{"raw": V2_ID.upper(), "t": "del", "accepted": True}],
             "expectRemembered": V2_ID.upper()},
            {"id": "mixed_case_kept_verbatim", "events": [{"raw": "3F2a1b4c-0d5e-4f60-8a7b-9c8d7e6f5a4b", "t": "put",
                                                         "accepted": True}],
             "expectRemembered": "3F2a1b4c-0d5e-4f60-8a7b-9c8d7e6f5a4b"},
            {"id": "non_canonical_never_learned", "events": [{"raw": "3F2A1B4C0D5E4F608A7B9C8D7E6F5A4B", "t": "put",
                                                            "accepted": False}], "expectRemembered": None},
        ],
        "tie": [
            {"last": {"v": 42, "by": "a1b2c3d4-0000-4000-8000-000000000001"},
             "incoming": {"v": 42, "by": "b1b2c3d4-0000-4000-8000-000000000001"}, "apply": True},
            {"last": {"v": 42, "by": "b1b2c3d4-0000-4000-8000-000000000001"},
             "incoming": {"v": 42, "by": "A1B2C3D4-0000-4000-8000-000000000001"}, "apply": False},
            {"last": {"v": 42, "by": "b1b2c3d4-0000-4000-8000-000000000001"},
             "incoming": {"v": 43, "by": "01b2c3d4-0000-4000-8000-000000000001"}, "apply": True},
            {"last": {"v": 42, "by": "b1b2c3d4-0000-4000-8000-000000000001"},
             "incoming": {"v": 42, "by": "b1b2c3d4-0000-4000-8000-000000000001"}, "apply": False},
        ],
    },
}
assert "A1" < "b1"  # ASCII order: uppercase sorts below lowercase
for _row in V2["vectors"]["outbound"]:
    # every frame id we send is canonical and folds back to the same state key
    assert canonical_uuid(_row["expectFrameId"]) and _row["expectFrameId"].lower() == _row["expectStateKey"], _row
    if _row["platform"] == "ios":
        assert _row["expectFrameId"] == _row["expectFrameId"].upper(), _row
for _row in V2["vectors"]["remember"]:
    _seen = None
    for _ev in _row["events"]:
        if _ev["accepted"]:
            assert canonical_uuid(_ev["raw"]), _row
            if _ev["raw"] != _ev["raw"].lower() and _seen is None:
                _seen = _ev["raw"]
    assert _seen == _row["expectRemembered"], _row

SIZE = {
    "objectCtMaxChars": RV["CT_MAX"],
    "maxFrameBytes": RV["MAX_FRAME_BYTES"],
    "checkBeforeStampReservation": True,
    "how": "build the exact inner JSON with a signature placeholder of the real length (86 base64url chars), ctChars = 4*ceil((innerUtf8Bytes+28)/3), frame = exact outer JSON bytes",
    "onTooLarge": {"reserveStamp": False, "send": False, "suppressUntilLocalEdit": True, "issue": "OBJECT_TOO_LARGE", "surface": "once per object per join"},
}

ISSUES = {
    "SKIPPED_UNSUPPORTED": {"kind": "CONNECTION", "scope": "join", "string": "sync_records_skipped_unsupported"},
    "SKIPPED_UNVERIFIED": {"kind": "SECURITY", "scope": "join", "string": "sync_records_skipped_unverified"},
    "ROOM_RESET_SUSPECTED": {"kind": "SECURITY", "scope": "join", "string": "sync_room_reset_suspected"},
    "ROOM_RESET_CHANGES_PAUSED": {"kind": "CONNECTION", "scope": "join", "persistent": True, "string": "sync_room_reset_changes_paused"},
    "OBJECT_TOO_LARGE": {"kind": "CONNECTION", "scope": "join per object", "string": "sync_object_too_large"},
    "ROOM_QUOTA_NACK": {"kind": "CONNECTION", "scope": "session", "string": "existing ui_the_unit_sync_room_is_full_so_this_saved_local_c_4e353477"},
    "RELAY_INVALID_NACK": {"kind": "CONNECTION", "scope": "session", "string": "existing Messages.syncTheUnitSyncRelayRejectedAChangeAsInvalidMessage"},
    "UNCONFIRMED_RECONNECT": {"kind": "CONNECTION", "scope": "session", "string": "existing ui_a_unit_sync_change_is_still_unconfirmed_after_bo_3d3fc607"},
    "SNAPSHOT_STRUCTURAL": {"kind": "SECURITY", "scope": "session", "string": "existing snapshot authentication failure message"},
    "RELAY_BUSY": {"kind": "CONNECTION", "scope": "failure chain", "string": "sync_relay_busy"},
    "RELAY_RATE_LIMITED": {"kind": "CONNECTION", "scope": "failure chain", "string": "sync_relay_rate_limited"},
    "ROOM_FULL_CANNOT_JOIN": {"kind": "CONNECTION", "scope": "stop", "string": "sync_room_full_cannot_join"},
    "RELAY_REFUSED_ROOM": {"kind": "CONNECTION", "scope": "stop", "string": "sync_relay_refused_room"},
    "IDENTITY_REJECTED": {"kind": "SECURITY", "scope": "stop", "string": "sync_identity_rejected"},
    "SESSION_CONFLICT": {"kind": "CONNECTION", "scope": "stop", "string": "sync_session_conflict"},
    "SESSION_COUNTER_BEHIND": {"kind": "CONNECTION", "scope": "stop", "string": "sync_session_counter_behind"},
    "SNAPSHOT_MALFORMED_STOPPED": {"kind": "SECURITY", "scope": "stop", "string": "sync_snapshot_malformed_stopped"},
    "BACKGROUND_PAUSED": {"kind": "CONNECTION", "scope": "background period", "string": "sync_background_paused"},
    "CHAT_REPLAY_FULL": {"kind": "chat availability", "scope": "join", "string": "chat_replay_table_full"},
    "CHAT_RECIPIENT_IN_BACKGROUND": {"kind": "send block reason", "scope": "per send", "string": "chat_recipient_in_background"},
}

STRINGS = {
    "sync_records_skipped_unsupported": {
        "platforms": ["ios", "android"], "parameters": ["count"],
        "en": "Some synced objects use a format this version of TacMap can't display, so they were skipped (count: {1}). Update TacMap to see them.",
        "de": "Einige synchronisierte Objekte verwenden ein Format, das diese TacMap-Version nicht anzeigen kann, und wurden übersprungen (Anzahl: {1}). Aktualisiere TacMap, um sie zu sehen."},
    "sync_records_skipped_unverified": {
        "platforms": ["ios", "android"], "parameters": ["count"],
        "en": "Some synced objects failed verification and were ignored (count: {1}). Your other data is unaffected. If this keeps happening, create a new join code.",
        "de": "Einige synchronisierte Objekte haben die Prüfung nicht bestanden und wurden ignoriert (Anzahl: {1}). Deine übrigen Daten sind nicht betroffen. Wenn das weiterhin passiert, erstelle einen neuen Beitrittscode."},
    "sync_room_reset_suspected": {
        "platforms": ["ios", "android"], "parameters": [],
        "en": "The relay sent an older copy of this room than this device has already seen. The room may have been reset after a long idle period, or the relay may be misbehaving. If this keeps happening, create a new join code.",
        "de": "Das Relay hat einen älteren Stand dieses Raums gesendet, als dieses Gerät bereits kennt. Der Raum wurde möglicherweise nach langer Inaktivität zurückgesetzt, oder das Relay verhält sich fehlerhaft. Wenn das weiterhin passiert, erstelle einen neuen Beitrittscode."},
    "sync_room_reset_changes_paused": {
        "platforms": ["ios", "android"], "parameters": [],
        "en": "This Unit Sync room was reset by the relay and can no longer accept changes from this device. Your map stays saved here, and positions and chat still work. Create a new join code and share it with your unit.",
        "de": "Dieser Unit-Sync-Raum wurde vom Relay zurückgesetzt und kann keine Änderungen von diesem Gerät mehr annehmen. Deine Karte bleibt hier gespeichert, Positionen und Chat funktionieren weiterhin. Erstelle einen neuen Beitrittscode und teile ihn mit deiner Einheit."},
    "sync_object_too_large": {
        "platforms": ["ios", "android"], "parameters": [],
        "en": "An object is too large to sync and stays on this device only. Simplify it or split it into smaller parts to share it.",
        "de": "Ein Objekt ist zu groß für die Synchronisierung und bleibt nur auf diesem Gerät. Vereinfache es oder zerlege es in kleinere Teile, um es zu teilen."},
    "sync_relay_busy": {
        "platforms": ["ios", "android"], "parameters": [],
        "en": "The Unit Sync room or relay is busy. Retrying automatically in a few minutes.",
        "de": "Der Unit-Sync-Raum oder das Relay ist ausgelastet. Neuer Versuch automatisch in einigen Minuten."},
    "sync_relay_rate_limited": {
        "platforms": ["ios", "android"], "parameters": [],
        "en": "The relay is limiting new connections from this network. Retrying automatically in about a minute.",
        "de": "Das Relay begrenzt neue Verbindungen aus diesem Netzwerk. Neuer Versuch automatisch in etwa einer Minute."},
    "sync_room_full_cannot_join": {
        "platforms": ["ios", "android"], "parameters": [],
        "en": "This Unit Sync room is full and can't add this device. Ask your unit to delete unused objects, or create a new join code. Tap Retry to try again.",
        "de": "Dieser Unit-Sync-Raum ist voll und kann dieses Gerät nicht aufnehmen. Bitte deine Einheit, nicht benötigte Objekte zu löschen, oder erstelle einen neuen Beitrittscode. Tippe auf „Erneut versuchen“, um es noch einmal zu versuchen."},
    "sync_relay_refused_room": {
        "platforms": ["ios", "android"], "parameters": [],
        "en": "The relay refused this room. Check that TacMap is up to date and the join code is correct, then tap Retry.",
        "de": "Das Relay hat diesen Raum abgelehnt. Prüfe, ob TacMap aktuell und der Beitrittscode korrekt ist, und tippe dann auf „Erneut versuchen“."},
    "sync_identity_rejected": {
        "platforms": ["ios", "android"], "parameters": [],
        "en": "The relay rejected this device's signed Unit Sync identity, so sync stopped. Leave the room and join again. If it keeps happening, create a new join code.",
        "de": "Das Relay hat die signierte Unit-Sync-Identität dieses Geräts abgelehnt, daher wurde die Synchronisierung beendet. Verlasse den Raum und tritt erneut bei. Wenn das weiterhin passiert, erstelle einen neuen Beitrittscode."},
    "sync_session_conflict": {
        "platforms": ["ios", "android"], "parameters": [],
        "en": "Another session with this device's Unit Sync identity keeps replacing this one. Close TacMap on any other copy of this device, then tap Retry.",
        "de": "Eine andere Sitzung mit der Unit-Sync-Identität dieses Geräts ersetzt diese immer wieder. Schließe TacMap auf allen anderen Kopien dieses Geräts und tippe dann auf „Erneut versuchen“."},
    "sync_session_counter_behind": {
        "platforms": ["ios", "android"], "parameters": [],
        "en": "The relay keeps rejecting this device's session counter after a storage reset, so sync stopped. Create a new join code for your unit.",
        "de": "Das Relay lehnt den Sitzungszähler dieses Geräts nach einem Speicher-Reset weiterhin ab, daher wurde die Synchronisierung beendet. Erstelle einen neuen Beitrittscode für deine Einheit."},
    "sync_snapshot_malformed_stopped": {
        "platforms": ["ios", "android"], "parameters": [],
        "en": "The relay keeps sending a malformed room snapshot, so sync stopped. No unverified data was used. Check the relay, then tap Retry.",
        "de": "Das Relay sendet wiederholt einen fehlerhaften Raum-Datenstand, daher wurde die Synchronisierung beendet. Ungeprüfte Daten wurden nicht verwendet. Prüfe das Relay und tippe dann auf „Erneut versuchen“."},
    "sync_background_paused": {
        "platforms": ["ios", "android"], "parameters": ["time"],
        "en": "Background location sharing paused at {1} because the connection was lost. Open TacMap to resume.",
        "de": "Die Standortfreigabe im Hintergrund wurde um {1} pausiert, weil die Verbindung verloren ging. Öffne TacMap, um fortzufahren."},
    "sync_background_paused_title": {
        "platforms": ["android"], "parameters": [],
        "en": "Background Unit Sync paused",
        "de": "Unit Sync im Hintergrund pausiert"},
    "chat_recipient_in_background": {
        "platforms": ["ios", "android"], "parameters": [],
        "en": "That unit's TacMap is in the background and can't receive chat until it is opened again.",
        "de": "TacMap dieser Einheit läuft im Hintergrund und kann erst wieder Chatnachrichten empfangen, wenn die App geöffnet wird."},
    "chat_replay_table_full": {
        "platforms": ["ios", "android"], "parameters": [],
        "en": "A message from a new unit session was blocked because this room's chat replay protection is full. Create a new join code to keep chatting with new sessions.",
        "de": "Eine Nachricht aus einer neuen Sitzung einer Einheit wurde blockiert, weil der Wiedergabeschutz des Chats in diesem Raum voll ist. Erstelle einen neuen Beitrittscode, um weiter mit neuen Sitzungen zu chatten."},
}
for key, issue in ISSUES.items():
    s = issue["string"]
    if not s.startswith("existing"):
        assert s in STRINGS, (key, s)

SP4 = [
    "resume from seq / delta snapshots (S1-07, S5-06b, S6-12)",
    "compact presence frame pv:2 (S5-11)",
    "egress-only / quiet subscription for background sockets (S2-08, S5-07, S6-05)",
    "chat-key revoke frame so a backgrounded peer stops being a chat route (S6-04 relay half)",
    "presence-only join that skips the snapshot (S2-07 relay half)",
    "relay reporting its stored hello epoch on 4014 (S2-11, S3-11 relay half)",
    "relay acking same-stamp resend as duplicate (S3-15 relay half)",
    "not resending own tombstones older than TOMBSTONE_TTL_MS / treating seq as non-rolling (needs a retention capability)",
    "relay batch-put frame or retryable rate nack (S6-07 relay half)",
]

# Count-before-bytes would retain 500 messages and never trigger hysteresis.
# Compute the independent budget result for the full 501-message candidate.
_count_boundary_sizes = [4190] * 501
_count_boundary_drop = 0
while sum(_count_boundary_sizes[_count_boundary_drop:]) + max(0, len(_count_boundary_sizes) - _count_boundary_drop - 1) > CHAT["history"]["pruneTargetBytes"]:
    _count_boundary_drop += 1
assert sum(_count_boundary_sizes) + 500 > CHAT["history"]["maxEncodedBytes"]
assert sum(_count_boundary_sizes[1:]) + 499 <= CHAT["history"]["maxEncodedBytes"]

# ---- scenarios ------------------------------------------------------------------
fd, bd = room_budget(5)
SCEN = [
    {"id": "backoff_full_jitter_transient", "requiredBy": "SP2", "findings": ["S2-14"], "unit": "SyncBackoffPolicy",
     "steps": [{"event": "failure", "class": "transient", "random": 1.0, "expectDelayMs": backoff_delay("transient", n, 1.0)} for n in range(7)]
     + [{"event": "failure", "class": "transient", "random": 0.0, "expectDelayMs": 250}]},
    {"id": "backoff_not_reset_at_hello_ack", "requiredBy": "SP2", "findings": ["S3 verifier note 2", "S3-02"], "unit": "SyncBackoffPolicy",
     "steps": [
         {"atMs": 0, "event": "failure", "class": "transient", "random": 1.0, "expectDelayMs": backoff_delay("transient", 0, 1.0)},
         {"atMs": 1000, "event": "failure", "class": "transient", "random": 1.0, "expectDelayMs": backoff_delay("transient", 1, 1.0)},
         {"atMs": 3000, "event": "helloAck"},
         {"atMs": 4000, "event": "failure", "class": "transient", "random": 1.0, "expectDelayMs": backoff_delay("transient", 2, 1.0)},
         {"atMs": 8000, "event": "helloAck"},
         {"atMs": 8500, "event": "opAck"},
         {"atMs": 9000, "event": "failure", "class": "transient", "random": 1.0, "expectDelayMs": backoff_delay("transient", 0, 1.0)}]},
    {"id": "backoff_reset_after_stable_30s", "requiredBy": "SP2", "findings": ["S3 verifier note 2"], "unit": "SyncBackoffPolicy",
     "steps": [
         {"atMs": 0, "event": "failure", "class": "transient", "random": 1.0, "expectDelayMs": 1000},
         {"atMs": 1000, "event": "failure", "class": "transient", "random": 1.0, "expectDelayMs": 1750},
         {"atMs": 5000, "event": "helloAck"},
         {"atMs": 34999, "event": "tick", "expectAttempt": 2},
         {"atMs": 35000, "event": "tick", "expectAttempt": 0}]},
    {"id": "backoff_slow_busy_503", "requiredBy": "SP2", "findings": ["S2-11"], "unit": "SyncBackoffPolicy",
     "steps": [{"event": "failure", "class": "slow_busy", "random": 0.5, "expectDelayMs": backoff_delay("slow_busy", 0, 0.5)},
               {"event": "failure", "class": "slow_busy", "random": 1.0, "expectDelayMs": backoff_delay("slow_busy", 1, 1.0)},
               {"event": "failure", "class": "slow_rate", "random": 0.0, "expectDelayMs": 60000}]},
    {"id": "close_and_status_table", "requiredBy": "SP2", "findings": ["S2-11", "S3-11", "S3-13"], "unit": "SyncCloseClassifier",
     "cases": [
         {"opened": False, "httpStatus": 503, "expect": {"action": "reconnect", "backoffClass": "slow_busy"}},
         {"opened": False, "httpStatus": 429, "expect": {"action": "reconnect", "backoffClass": "slow_rate", "issue": "RELAY_RATE_LIMITED"}},
         {"opened": False, "httpStatus": 403, "expect": {"action": "stop", "issue": "RELAY_REFUSED_ROOM"}},
         {"opened": False, "httpStatus": 502, "expect": {"action": "reconnect", "backoffClass": "transient"}},
         {"opened": False, "httpStatus": 400, "expect": {"action": "reconnect", "backoffClass": "slow_unknown"}},
         {"opened": False, "httpStatus": None, "expect": {"action": "reconnect", "backoffClass": "transient"}},
         {"opened": True, "closeCode": 1006, "expect": {"action": "reconnect", "backoffClass": "transient"}},
         {"opened": True, "closeCode": 1013, "expect": {"action": "reconnect", "backoffClass": "slow_busy"}},
         {"opened": True, "closeCode": 4008, "expect": {"action": "reconnect", "backoffClass": "transient", "pacer": "after4008"}},
         {"opened": True, "closeCode": 4013, "expect": {"action": "stop", "issue": "ROOM_FULL_CANNOT_JOIN"}},
         {"opened": True, "closeCode": 4014, "expect": {"action": "escalate_epoch_then_reconnect"}},
         {"opened": True, "closeCode": 4011, "expect": {"action": "stop", "issue": "IDENTITY_REJECTED"}},
         {"opened": True, "closeCode": 4099, "expect": {"action": "reconnect", "backoffClass": "slow_unknown"}},
         {"opened": True, "closeCode": 1000, "afterOwnLeave": True, "expect": {"action": "none"}}]},
    {"id": "session_conflict_stops_after_three", "requiredBy": "SP2", "findings": ["S2-11"], "unit": "SyncCloseClassifier",
     "steps": [{"atMs": 0, "closeCode": 4015, "expect": {"action": "reconnect"}},
               {"atMs": 60000, "nack": "session-replaced", "expect": {"action": "reconnect"}},
               {"atMs": 120000, "closeCode": 4015, "expect": {"action": "stop", "issue": "SESSION_CONFLICT"}}]},
    {"id": "stale_nack_no_reconnect", "requiredBy": "SP2", "findings": ["S6-03", "S6-02", "S3-15"], "unit": "SyncNackPolicy",
     "steps": [
         {"event": "connected"},
         {"event": "localEdit", "localId": "X", "sends": "put 0000000000000005:A"},
         {"event": "inbound", "frame": "put X 0000000000000005:B (B > A, authenticated)", "expect": {"modelEquals": "B content"}},
         {"event": "inbound", "frame": "op-nack stale retry:false for A's rid",
          "expect": {"status": "CONNECTED", "newSockets": 0, "issue": None, "opResolved": True,
                     "republishesOfX": 0}}]},
    {"id": "stale_on_own_persisted_stamp_is_confirmed", "requiredBy": "SP2", "findings": ["S3-15"], "unit": "SyncNackPolicy",
     "cases": [{"code": "stale", "rejectedStampEqualsOwnPersisted": True, "wireIdSkippedThisJoin": False,
                "expect": {"markConfirmed": True, "suppress": False}},
               {"code": "stale", "rejectedStampEqualsOwnPersisted": True, "wireIdSkippedThisJoin": True,
                "expect": {"markConfirmed": False, "suppress": True}},
               {"code": "stale", "rejectedStampEqualsOwnPersisted": False, "wireIdSkippedThisJoin": False,
                "expect": {"markConfirmed": False, "suppress": True}}]},
    {"id": "counter_window_pauses_mutations_once", "requiredBy": "SP2", "findings": ["S3-02", "S6-01"], "unit": "SyncNackPolicy",
     "steps": [{"event": "inbound", "frame": "op-nack counter-window retry:false",
                "expect": {"reconnect": False, "pauseMutationsForJoin": True, "issue": "ROOM_RESET_CHANGES_PAUSED", "surfacedCount": 1}},
               {"event": "localEdit", "expect": {"putsSent": 0}},
               {"event": "presenceDue", "expect": {"locSent": 1}},
               {"event": "reconnect", "expect": {"pauseMutationsForJoin": True, "surfacedCount": 1}}]},
    {"id": "seq_regression_surfaced_once_per_join", "requiredBy": "SP2", "findings": ["S6-01", "S3-02"], "unit": "SnapshotFence",
     "steps": [{"event": "snapshotBegin", "seq": 4, "lastSnapshotSeq": 90, "expect": {"issue": "ROOM_RESET_SUSPECTED", "surfacedCount": 1, "continue": True}},
               {"event": "reconnect"},
               {"event": "snapshotBegin", "seq": 5, "lastSnapshotSeq": 90, "expect": {"surfacedCount": 1, "continue": True}}]},
    {"id": "poison_record_skipped", "requiredBy": "SP2", "findings": ["S2-10", "S3-03", "S4-01"], "unit": "SnapshotRecordClassifier",
     "steps": [
         {"event": "snapshot", "items": ["valid waypoint W1", "W2 with ct replaced by base64(64 random bytes)", "W3 kind 'route' validly sealed and signed"],
          "expect": {"status": "CONNECTED_after_hello_ack", "applied": ["W1"], "skippedUnverified": ["W2"], "skippedUnsupported": ["W3"],
                     "replayCommitted": ["W1"], "issues": ["SKIPPED_UNVERIFIED", "SKIPPED_UNSUPPORTED"], "verifiedCleanSnapshot": False}},
         {"event": "reconnect_same_snapshot", "expect": {"newIssueToasts": 0, "reconnectsCausedBySkip": 0}},
         {"event": "localObjectExistsFor", "wireId": "W2", "localGenerationAdvanced": False, "expect": {"published": False}},
         {"event": "userEdits", "wireId": "W2", "expect": {"published": True}}]},
    {"id": "structural_snapshot_stops_after_three", "requiredBy": "SP2", "findings": ["S2-10"], "unit": "SnapshotRecordClassifier",
     "steps": [{"event": "snapshot", "duplicateWireId": True, "expect": {"committed": False, "action": "reconnect", "backoffClass": "transient"}},
               {"event": "snapshot", "duplicateWireId": True, "expect": {"action": "reconnect"}},
               {"event": "snapshot", "duplicateWireId": True, "expect": {"action": "stop", "issue": "SNAPSHOT_MALFORMED_STOPPED"}}]},
    {"id": "layer_metadata_staged_in_item_order", "requiredBy": "SP2", "findings": ["S3-08"], "unit": "SnapshotValidator",
     "given": {"localLayers": [], "items": [{"wire": "D1", "layerId": "L", "layerName": "Recon"}, {"wire": "D2", "layerId": "L", "layerName": "Scouts"}]},
     "expect": {"D1": {"newLayers": ["L/Recon"]}, "D2": {"newLayers": [], "exportsWithLayerName": "Recon"},
                "status": "CONNECTED_after_hello_ack", "persistenceFailure": False}},
    {"id": "live_window_resync_cooldown", "requiredBy": "SP2", "findings": ["SP1 review", "ADR-001 section 9"], "unit": "LiveWindowResyncPolicy",
     "steps": [{"atMs": 0, "event": "windowRejection", "expect": {"resyncNow": True}},
               {"atMs": 10000, "event": "windowRejection", "expect": {"resyncNow": False, "pending": True}},
               {"atMs": 60000, "event": "tick", "expect": {"resyncNow": True}},
               {"atMs": 61000, "event": "windowRejection", "expect": {"resyncNow": False, "pending": True}}]},
    {"id": "pacer_bulk_500_small", "requiredBy": "SP2", "findings": ["S2-04", "S3-07", "S5-08", "S6-07"], "unit": "SyncOutboundPacer",
     "given": {"mutations": 500, "frameBytes": 2000, "writeCompletesImmediately": True, "ackAfterWriteMs": 200},
     "expect": {"sendsAtZero": p1_burst, "maxFramesAnyWindow": p1_win[0], "maxFramesAnyWindowLimit": PACING["maxFramesPerWindow"],
                "lastSendAtMs": round(p1[-1], 3), "toleranceMs": 1, "maxInFlightObserved": "<= 32"}},
    {"id": "pacer_large_frames_bytes", "requiredBy": "SP2", "findings": ["S5-08", "S5-09"], "unit": "SyncOutboundPacer",
     "given": {"mutations": 10, "frameBytes": 700000, "writeCompletesImmediately": True, "ackAfterWriteMs": 200},
     "expect": {"sendTimesMs": [round(t, 3) for t in p2], "toleranceMs": 1, "maxBytesAnyWindow": p2_win[1],
                "maxBytesAnyWindowLimit": PACING["maxBytesPerWindow"]}},
    {"id": "pacer_interactive_reserve", "requiredBy": "SP2", "findings": ["S5-08"], "unit": "SyncOutboundPacer",
     "given": {"mutations": 1000, "frameBytes": 2000, "ackAfterWriteMs": 200},
     "steps": [{"atMs": 5000, "event": "offer", "class": "presence", "bytes": 1500, "expect": {"sentAtMs": 5000}},
               {"atMs": 5000, "event": "offer", "class": "chat", "bytes": 12000, "expect": {"sentAtMs": 5000}}]},
    {"id": "retransmit_starts_at_write", "requiredBy": "SP2", "findings": ["S3-06", "S5-09"], "unit": "SyncAckTimer",
     "steps": [{"atMs": 0, "event": "enqueue", "rid": "r1"},
               {"atMs": 12000, "event": "writeComplete", "rid": "r1", "aheadInFlight": 0, "expect": {"ackDeadlineMs": 12000 + ack_timeout(1, 0)}},
               {"atMs": 15000, "event": "ack", "rid": "r1", "expect": {"retransmits": 0}}],
     "timeouts": [{"attempt": a, "ahead": k, "expectMs": ack_timeout(a, k)} for a in (1, 2, 3) for k in (0, 10, 31)]},
    {"id": "no_retransmit_while_unwritten", "requiredBy": "SP2", "findings": ["S3-06"], "unit": "SyncAckTimer",
     "steps": [{"atMs": 0, "event": "enqueue", "rid": "r1"},
               {"atMs": 100, "event": "writeComplete", "rid": "r1", "aheadInFlight": 0},
               {"atMs": 5100, "event": "ackTimeout", "rid": "r1", "expect": {"retransmitEnqueued": True}},
               {"atMs": 20000, "event": "tick", "copyStillUnwritten": True, "expect": {"retransmitEnqueued": False, "attempt": 2}},
               {"atMs": 21000, "event": "writeComplete", "rid": "r1", "aheadInFlight": 0, "expect": {"ackDeadlineMs": 21000 + ack_timeout(2, 0)}}]},
    {"id": "receive_budget_bulk_import_no_close", "requiredBy": "SP2", "findings": ["S2-04", "S4-02"], "unit": "SyncReceiveBudget",
     "given": {"activeRemoteSessions": 5, "phase": "live", "frameBytes": {"put": 2000, "loc": 1500, "op-ack": 300}},
     "steps": [{"withinMs": 1000, "room": {"put": 199, "loc": 40}, "selfResponse": {"op-ack": 199}, "expect": {"allAdmitted": True}},
               {"withinMs": 1000, "room": {"loc": fd - 239}, "expect": {"allAdmitted": True}},
               {"withinMs": 1000, "room": {"loc": 1}, "expect": {"admitted": False, "localClose": 1008}}],
     "expect": {"roomFrameLimit": fd, "roomByteLimit": bd}},
    {"id": "background_discard_uses_background_budget", "requiredBy": "SP3", "findings": ["S5-07", "S6-05"], "unit": "SyncReceiveBudget",
     "given": {"phase": "background"},
     "steps": [{"withinMs": 5000, "room": {"loc": 1000}, "expect": {"allAdmitted": True, "liveBudgetCharged": 0}},
               {"withinMs": 5000, "room": {"loc": 3001}, "expect": {"lastAdmitted": False}}]},
    {"id": "handshake_progress_watchdog", "requiredBy": "SP2", "findings": ["S2-03", "S3-05"], "unit": "SyncHandshakeWatchdog",
     "steps": [{"atMs": 0, "event": "socketCreated"},
               {"atMs": 500, "event": "open"},
               {"atMs": 1000, "event": "snapshotBegin"},
               {"atMs": 50000, "event": "snapshotPage"},
               {"atMs": 105000, "event": "snapshotPage"},
               {"atMs": 160000, "event": "snapshotPage", "more": False},
               {"atMs": 200000, "event": "snapshotEnd"},
               {"atMs": 240000, "event": "helloWritten"},
               {"atMs": 250000, "event": "helloAck", "expect": {"timedOut": False, "status": "CONNECTED"}}]},
    {"id": "handshake_stall_fires", "requiredBy": "SP2", "findings": ["S2-03"], "unit": "SyncHandshakeWatchdog",
     "steps": [{"atMs": 0, "event": "socketCreated"}, {"atMs": 500, "event": "open"},
               {"atMs": 1000, "event": "snapshotBegin"},
               {"atMs": 60999, "event": "tick", "expect": {"timedOut": False}},
               {"atMs": 61000, "event": "tick", "expect": {"timedOut": True, "localClose": "handshakeStall"}}]},
    {"id": "android_connect_open_timeout", "requiredBy": "SP2", "findings": ["S2-02"], "unit": "SyncHandshakeWatchdog",
     "steps": [{"atMs": 0, "event": "socketCreated"},
               {"atMs": 29999, "event": "tick", "expect": {"timedOut": False}},
               {"atMs": 30000, "event": "tick", "expect": {"timedOut": True, "localClose": "connectOpenTimeout"}}]},
    {"id": "hello_ack_timeout", "requiredBy": "SP2", "findings": ["S3-05"], "unit": "SyncHandshakeWatchdog",
     "steps": [{"atMs": 0, "event": "socketCreated"}, {"atMs": 500, "event": "open"}, {"atMs": 600, "event": "snapshotBegin"},
               {"atMs": 700, "event": "snapshotPage", "more": False}, {"atMs": 800, "event": "snapshotEnd"},
               {"atMs": 900, "event": "helloWritten"},
               {"atMs": 30899, "event": "tick", "expect": {"timedOut": False}},
               {"atMs": 30900, "event": "tick", "expect": {"timedOut": True, "localClose": "helloAckTimeout"}}]},
    {"id": "reachability_shortcuts_transient_only", "requiredBy": "SP2", "findings": ["S2-13"], "unit": "SyncBackoffPolicy",
     "steps": [{"atMs": 0, "event": "attemptStarted"},
               {"atMs": 100, "event": "failure", "class": "transient", "random": 1.0, "attemptBefore": 6, "expectDelayMs": 30000},
               {"atMs": 1000, "event": "networkAvailable", "expect": {"connectAtMs": 2000}},
               {"atMs": 5000, "event": "failure", "class": "slow_rate", "random": 0.0, "expectDelayMs": 60000},
               {"atMs": 6000, "event": "networkAvailable", "expect": {"connectAtMs": 65000}}]},
    {"id": "hello_epoch_vectors", "requiredBy": "SP2", "findings": ["S3-11"], "unit": "HelloEpochPolicy",
     "vectorsRef": "helloEpoch.vectors"},
    {"id": "chat_fence_pruning", "requiredBy": "SP2", "findings": ["S3-04", "S4-06"], "unit": "ChatReplayPruner",
     "cases": [
         {"fences": [["A", "sd1", "k1"], ["A", "sd2", "k2"], ["B", "sd3", "k3"], ["C", "sd4", "k4"]],
          "durableSessions": {"A": "sd2", "B": "sd3"},
          "expectRetained": [["A", "sd2", "k2"], ["B", "sd3", "k3"], ["C", "sd4", "k4"]]},
         {"fenceCount": 256, "superseded": 0, "newIdentity": True, "expect": {"result": "rejected", "issue": "CHAT_REPLAY_FULL"}},
         {"fenceCount": 256, "superseded": 1, "newIdentity": True, "expect": {"result": "accepted", "fenceCountAfter": 256}}]},
    {"id": "chat_history_byte_prune", "requiredBy": "SP2", "findings": ["S4-07"], "unit": "ChatHistoryBudget",
     "given": {"fixedOverheadBytes": 300000, "separatorBytes": 1, "messageEncodedBytes": [8000] * 250},
     "expect": {"dropOldest": 91, "keep": 159, "resultBytesAtMost": 1_572_864},
     "countBoundary": {"given": {"fixedOverheadBytes": 0, "separatorBytes": 1, "messageEncodedBytes": _count_boundary_sizes},
                       "expect": {"dropOldest": _count_boundary_drop, "keep": len(_count_boundary_sizes) - _count_boundary_drop, "resultBytesAtMost": 1_572_864}}},
    {"id": "presence_stationary_heartbeat", "requiredBy": "SP3", "findings": ["S5-10"], "unit": "PresenceSendPolicy",
     "given": {"fixes": {"startMs": 0, "endMs": 59000, "everyMs": 1000, "lat": -33.8688, "lon": 151.2093,
                         "speedMps": 0, "courseDeg": 0, "horizontalAccuracyM": 5}},
     "expect": {"sendAtMs": [0, 20000, 40000]}},
    {"id": "presence_moving_keeps_5s", "requiredBy": "SP3", "findings": ["S5-10"], "unit": "PresenceSendPolicy",
     "given": {"fixes": {"startMs": 0, "endMs": 59000, "everyMs": 1000, "lat": -33.8688, "lon": 151.2093,
                         "speedMps": 3, "courseDeg": 90, "horizontalAccuracyM": 5,
                         "position": "east of start by speed*t metres, lon offset = metres / (111320 * cos(lat))"}},
     "expect": {"sendAtMs": list(range(0, 60000, 5000))}},
    {"id": "presence_config_change_sends", "requiredBy": "SP3", "findings": ["S5-10"], "unit": "PresenceSendPolicy",
     "given": {"fixes": {"startMs": 0, "endMs": 29000, "everyMs": 1000, "lat": -33.8688, "lon": 151.2093,
                         "speedMps": 0, "courseDeg": 0, "horizontalAccuracyM": 5},
               "configChangeAtMs": 7000},
     "expect": {"sendAtMs": [0, 7000, 27000]}},
    {"id": "presence_fence_stride_and_crash_floor", "requiredBy": "SP3", "findings": ["S5-01", "S3-12", "S4-04"], "unit": "PresenceFencePersistence",
     "steps": [{"event": "load", "persisted": 0, "exact": True},
               {"event": "acceptCounters", "from": 1, "to": 40, "expectPersistBeforeExposing": [1, 17, 33]},
               {"event": "crashAndLoad", "persisted": 33, "exact": False, "expectFloor": 48},
               {"event": "offer", "counter": 48, "expect": {"accepted": False}},
               {"event": "offer", "counter": 49, "expect": {"accepted": True}},
               {"event": "cleanPoint", "lastAccepted": 49, "expectPersist": {"counter": 49, "exact": True}},
               {"event": "load", "persisted": 49, "exact": True, "expectFloor": 49}]},
    {"id": "ios_transient_inactive_keeps_session", "requiredBy": "SP2", "findings": ["S2-12"], "unit": "SyncLifecyclePolicy",
     "cases": [{"platform": "ios", "keyMode": "device", "appLock": False, "phases": ["active", "inactive", "active"],
                "expect": {"socketClosed": False, "newGeneration": False, "chatKeyKept": True}},
               {"platform": "ios", "keyMode": "auth", "appLock": False, "phases": ["active", "inactive", "active"],
                "expect": {"socketClosed": True, "newGeneration": True}},
               {"platform": "ios", "keyMode": "device", "appLock": True, "phases": ["active", "background", "active"],
                "expect": {"socketClosed": True, "newGeneration": True}}]},
    {"id": "android_pause_keeps_room", "requiredBy": "SP2", "findings": ["S2-01"], "unit": "SyncLifecyclePolicy",
     "cases": [{"platform": "android", "room": "3:", "shareLocation": False, "bgOptIn": False,
                "events": ["onPause", "onResume", "storesAttached"],
                "expect": {"roomRetained": True, "sameManager": True, "connectAttempts": 1, "userInputNeeded": False}},
               {"platform": "android", "room": "2:", "shareLocation": False, "bgOptIn": False,
                "events": ["onPause", "onResume", "storesAttached"],
                "expect": {"roomRetained": True, "sameManager": True, "connectAttempts": 1}}]},
    {"id": "background_entry_with_pending_delivery", "requiredBy": "SP3", "findings": ["S2-05"], "unit": "SyncLifecyclePolicy",
     "steps": [{"atMs": 0, "event": "putWritten", "rid": "r1"},
               {"atMs": 100, "event": "enterBackgroundPresence"},
               {"atMs": 30000, "event": "tick", "expect": {"retransmits": 0, "socketCancelled": False, "issue": None}},
               {"atMs": 600000, "event": "foreground", "expect": {"reconciliationContains": "r1's localId"}}]},
    {"id": "background_reconnect_uses_spare_epoch", "requiredBy": "SP3", "findings": ["S2-06", "S2-07"], "unit": "BackgroundPresencePolicy",
     "given": {"spares": 64, "snapshotBytes": 300000},
     "steps": [{"atMs": 0, "event": "socketClosed"},
               {"atMs": 900000, "event": "fixDue", "expect": {"connect": True, "durableWrites": 0, "helloEpoch": "next spare",
                                                              "framesSent": ["hello", "loc"], "chatKeySent": False}}]},
    {"id": "background_pauses_without_spare", "requiredBy": "SP3", "findings": ["S2-07"], "unit": "BackgroundPresencePolicy",
     "given": {"spares": 0},
     "steps": [{"atMs": 0, "event": "socketClosed"},
               {"atMs": 900000, "event": "fixDue", "expect": {"connect": False, "issue": "BACKGROUND_PAUSED"}}]},
    {"id": "chat_blocked_to_background_peer", "requiredBy": "SP3", "findings": ["S6-04"], "unit": "ChatSendGate",
     "cases": [{"recipientLatestRetentionSeconds": 1200, "scope": "direct", "expect": {"blocked": True, "reason": "CHAT_RECIPIENT_IN_BACKGROUND"}},
               {"recipientLatestRetentionSeconds": 45, "scope": "direct", "expect": {"blocked": False}},
               {"recipientLatestRetentionSeconds": 1200, "scope": "room", "expect": {"blocked": False}}]},
    {"id": "object_too_large_not_reserved", "requiredBy": "SP2", "findings": ["S1-09"], "unit": "OutboundSizeCheck",
     "cases": [{"innerUtf8Bytes": 524_970, "expect": {"ctChars": 4 * math.ceil((524_970 + 28) / 3), "send": True}},
               {"innerUtf8Bytes": 524_980, "expect": {"ctChars": 4 * math.ceil((524_980 + 28) / 3), "send": False, "stampReserved": False, "issue": "OBJECT_TOO_LARGE"}}]},
    {"id": "v2_casing_and_tie", "requiredBy": "SP2", "findings": ["S3-01", "S3-14", "gap-v2-room-2x-interop-1", "gap-v2-room-2x-interop-2"], "unit": "LegacyV2Ids", "vectorsRef": "v2.vectors"},
    {"id": "poison_embedded_id_skipped", "requiredBy": "SP2", "findings": ["sync-android-2"], "unit": "SnapshotValidator", "vectorsRef": "snapshot.embeddedIdCases"},
]
# chat prune vector: total = overhead + sum + separators, drop oldest until <= target
_sizes = [8000] * 250
_keep = len(_sizes)
while 300000 + 8000 * _keep + max(0, _keep - 1) > 1_572_864:
    _keep -= 1
assert (_keep, 250 - _keep) == (159, 91)
# sanity on the size vector boundaries
assert 4 * math.ceil((524_970 + 28) / 3) <= RV["CT_MAX"] < 4 * math.ceil((524_980 + 28) / 3)

doc = {
    "_comment": "Unit Sync client behaviour contract for SP2/SP3 (plans/04-sync-client-contract.md). Android and iOS tests load this file and must agree with it. relayLimitsDependencies must equal testdata/sync_protocol_v3.json relayLimits; a test on each platform checks that and the invariants below. Generated by testdata/tools/gen_sync_client_behaviour.py (run with --check in CI); change constants there and regenerate, never edit this file by hand.",
    "_contract": "plans/04-sync-client-contract.md",
    "_version": 1,
    "relayLimitsDependencies": {
        "source": "testdata/sync_protocol_v3.json#/relayLimits",
        "values": {k: RV[k] for k in ["RATE_WINDOW_MS", "RATE_MAX_MSGS", "RATE_MAX_BYTES", "CT_MAX", "MAX_FRAME_BYTES",
                                       "ADVANCE_WINDOW", "SNAPSHOT_FRAME_BYTES", "HELLO_DEADLINE_MS",
                                       "ROOM_PENDING_MAX_MSGS", "ROOM_PENDING_MAX_BYTES"]},
        "clientPacing": {k: PACING[k] for k in ["maxFramesPerWindow", "maxBytesPerWindow", "windowMs"]},
        "closeCodes": sorted(relay["closeCodes"]),
        "upgradeStatus": sorted(relay["upgradeStatus"]),
        "opNackCodes": sorted(relay["opNackCodes"]),
    },
    "invariants": [
        {"name": "frameBucketWithinClientPacing", "check": "pacer.frameBucket.capacity + pacer.frameBucket.refillPerWindow <= clientPacing.maxFramesPerWindow"},
        {"name": "byteBucketWithinClientPacing", "check": "pacer.byteBucket.capacity + pacer.byteBucket.refillPerWindow <= clientPacing.maxBytesPerWindow"},
        {"name": "byteBucketFitsOneFrame", "check": "pacer.byteBucket.capacity >= MAX_FRAME_BYTES"},
        {"name": "bunchingBelowRelayWindow", "check": "clientPacing.maxFramesPerWindow + pacer.inFlight.maxMutations + 4 < RATE_MAX_MSGS"},
        {"name": "inFlightBytesWellBelowRoomBacklog", "check": "pacer.inFlight.maxMutationBytes * 4 <= ROOM_PENDING_MAX_BYTES"},
        {"name": "roomReceiveAbovePerSenderAllowance", "check": "receiveBudget.room.baseFrames >= 3 * RATE_MAX_MSGS and receiveBudget.room.baseBytes >= 3 * RATE_MAX_BYTES"},
        {"name": "selfResponseCoversPacing", "check": "receiveBudget.selfResponse.maxFrames >= 2 * clientPacing.maxFramesPerWindow"},
        {"name": "everyRelayCloseCodeHasARow", "check": "every relayLimits.closeCodes key is in closeCodeActions"},
        {"name": "everyUpgradeStatusHasARow", "check": "every relayLimits.upgradeStatus key is in httpStatusActions"},
        {"name": "everyNackCodeHasARow", "check": "every relayLimits.opNackCodes key is in opNackActions"},
        {"name": "presenceHeartbeatUnderRetention", "check": "2 * presence.foreground.stationaryHeartbeatMs < 45000"},
    ],
    "pacer": PACER,
    "outboundSize": SIZE,
    "retransmit": RETRANSMIT,
    "receiveBudget": RECEIVE,
    "watchdogs": WATCHDOGS,
    "heartbeat": HEARTBEAT,
    "backoff": BACKOFF,
    "reachability": REACHABILITY,
    "stopThresholds": STOP,
    "closeCodeActions": CLOSE_CODES,
    "httpStatusActions": HTTP,
    "localCloseActions": LOCAL_CLOSE,
    "opNackActions": OP_NACK,
    "snapshot": SNAPSHOT,
    "liveWindowResync": LIVE_RESYNC,
    "roomReset": ROOM_RESET,
    "helloEpoch": HELLO_EPOCH,
    "chat": CHAT,
    "presence": PRESENCE,
    "persistenceBatching": BATCHING,
    "wireIdIndex": WIRE_ID_INDEX,
    "lifecycle": LIFECYCLE,
    "background": BACKGROUND,
    "v2": V2,
    "issues": ISSUES,
    "strings": STRINGS,
    "sp4Deferred": SP4,
    "scenarios": SCEN,
}
assert 2 * PRESENCE["foreground"]["stationaryHeartbeatMs"] < 45000

out = ROOT / "testdata/sync_client_behaviour.json"
rendered = json.dumps(doc, indent=2, ensure_ascii=False) + "\n"
if CHECK:
    if not out.exists() or out.read_text() != rendered:
        print("sync_client_behaviour.json is stale, rerun without --check", file=sys.stderr)
        sys.exit(1)
    print("sync_client_behaviour.json is up to date")
    sys.exit(0)
out.write_text(rendered)
print("wrote", out, "scenarios", len(SCEN))
print("p1 burst", p1_burst, "max window", p1_win, "last", p1[-1])
print("p2 times", [round(t, 3) for t in p2], "max window bytes", p2_win)
print("room budget(5)", fd, bd)
