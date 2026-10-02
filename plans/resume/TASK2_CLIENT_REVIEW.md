# Task 2 independent client review

Reviewed locally on 2026-10-02 against `1e21684`, `plans/03-unit-sync-revision.md`,
the complete binding `plans/04-sync-client-contract.md`, ADR-001 through ADR-004,
`docs/THREAT_MODEL.md`, the shared fixtures, and the previous platform reports.

This is the initial independent review and repair handoff, **not final acceptance**.
Android and iOS repair agents were editing during the review. A fresh reviewer must
inspect the final diff and the completed test evidence before accepting Task 2.
No emulator, simulator, parallel build, commit, push or deployment was run here.

## Scope and independence

Read the complete sync policy implementations, snapshot classifiers/validators,
replay persistence, manager inbound/outbound/lifecycle flows, transport seams,
chat retention, store batching, and presence rendering/lifecycle wiring.
The requested diff also contains inherited PDF/grid changes; their detailed
verification belongs to the completed WP reviews and later integration/device
tasks. This report does not claim a second exhaustive PDF review.

The parent explicitly authorized this reviewer to repair finding CR-1 after the
Android implementation agent requested assistance. This reviewer authored only
`SyncWebSocketTransport.kt` and the new `SyncTransportWriteCompletionTest.kt`.
Consequently the new transport implementation needs **fresh independent review**;
the remainder of this review was read-only except this report.

## Findings routed to the implementation owners

| ID | Priority | Finding and required evidence | Status at handoff |
|---|---|---|---|
| CR-1 | P1 | Android treated `outQueue` removal as completed network write. The installed Java-WebSocket 1.6.0 writer calls `outQueue.take()` **before** `OutputStream.write` and `flush` (verified by inspecting the installed library bytecode). A large frame on a slow uplink could start its 5-second ack timer, retransmit and exhaust before its first write completed. | Reviewer repair implemented; Android agent owns compilation and regression/full-suite runs; fresh review required. |
| CR-2 | P2 | Both snapshot validators captured object kinds, but completion rechecked only layers. A local opposite-kind object created during validation could pass the old collision check, enter replay state, then be refused on iOS or collide/fail on Android. A skipped unsupported record must change no stamp, actor pin, counter, hash or marker (§2.2). | Both owners notified; held-worker precommit collision regressions being added. |
| CR-3 | P2 | Android staged layers from every authentic valid record, including stale/conflicting records that would not apply. A later accepted record then omitted `newLayers` for metadata introduced only by the ignored item, producing missing metadata/hash mismatch. iOS was better but also staged exact resolved records that may have no pending application and no longer install their layers. | Both owners notified; Android reported stale-layer regression passing; final exact-resolved and divergent-layer cases need inspection. |
| CR-4 | P2 | iOS classifier checked only the caller's `deleted` argument versus kind. It did not inspect contradictory `t`/`deleted` fields, unlike Android. A signed live `put` with `deleted:true`, or snapshot `t:del/deleted:false/kind:waypoint`, could apply on iOS and skip on Android. | iOS owner notified; require handler-level zero-side-effect tests. |
| CR-5 | P2 | iOS hello/chat-key/presence handlers published observable membership/recipients/peers before the deferred replay batch was durable. Synchronous publisher subscribers can observe this even if main-runloop rendering waits. Android stages these publications until commit success. | iOS owner notified; require failed-write observer regression. |
| CR-6 | P2 | iOS production replay and journal writes were direct synchronous `SafeStore.write` calls from the main actor. Snapshot validation was off-main, but durable persistence was not on the serial background executor required by §19. | iOS owner confirmed, adding executor and thread/order regressions. A synchronous wait still blocks the main actor; assess this limitation explicitly in final review. |
| CR-7 | P3 | iOS presence crash-floor addition and `existing + advanceWindow` could overflow before the bound check for valid near-maximum counters. Android admission uses safe subtraction. | iOS owner notified; require max-counter boundary tests. |
| CR-8 | Docs | `sync/README.md` still described a 512-frame initial-snapshot budget, while code and contract use 10,000 frames / 54,525,952 bytes. | Parent notified alongside the handoff's stale SP2 sentence. |

## CR-1 repair design and validation obligations

The bounded draft registers each exact encoded array through the library's public
`createBinaryFrame` hook, shared through draft clones. The socket output stream
retires registered arrays only after successful write **and flush**. Pending bytes
include both queued arrays and the currently dequeued write. Application TEXT bytes
are counted separately from control-frame bytes so automatic ping/pong traffic
cannot distort the manager's application write position. The outbound queue bound
uses all registered frame bytes, including the active writer. Terminal cleanup
clears the registry and ignores late flushes.

The ws wrapper delegates to the actual socket. The wss wrapper remains an
`SSLSocket`, forwards SSL parameters/ciphers/protocols, and uses the default trusted
TLS factory with the original URI hostname supplied for SNI and certificate
identity. Java-WebSocket's existing HTTPS endpoint-identification setup therefore
still reaches the real TLS socket. No reflection, private library modification,
wire-format change, custom frame parser or trust-all TLS context was introduced.

New tests run the **actual Java-WebSocket writer against a local server**, with an
injected socket stream held during write and separately during flush. They require
the full 50,008 framed bytes to remain pending and the ack timer to remain unwritten
at virtual 20 seconds, then require pending bytes to drain after release. Additional
tests cover failing flushes, control-frame accounting, late terminal completions,
and SSL parameter delegation. At report creation these tests had been handed to
the Android owner for execution; this report does not invent passing results.

Fresh reviewer focus: TLS peer/hostname verification, every factory overload,
array registry lifetime on cancellation/error/draft reset, blocked writer timeout
and close behavior, and actual manager timer integration. A TLS loopback smoke
test would strengthen the parameter-delegation test.

## Contract section disposition

| Section | Review disposition |
|---|---|
| 0 Ground rules | No intended wire additions; both relay versions remain required. Background reconnect flags remain false. SECURITY notice semantics preserved. |
| 1 Inbound batching | Bounded queues and serial arrival handling present. CR-5 must close iOS publish-before-durability gap. Repeated same-ID live updates may flush a subgroup; final reviewer should assess write-count bounds. |
| 2 Snapshot classification | Binding, canonical encoding, AEAD, signature, tombstone strictness and skip suppression checked. CR-2/CR-4 remain acceptance gates. Structural errors reject before snapshot commit. |
| 3 Layer ordering | Accepted-item staging implemented; CR-3 addresses ignored/exact-resolved metadata affecting subsequent items. |
| 4 Live window resync | Authentication precedes window resync; cooldown/hour cap present; relay highWater ignored. |
| 5 Reset/rollback | Seq regression remains SECURITY and the durable seq stays maximum. Counter-window only pauses local mutations. No benign-expiry downgrade observed. |
| 6 Nacks | Exact pending correlation; resolving/suppression/own-stamp shortcut checked. Nacks do not update durable auth state; skipped IDs disable confirmation shortcut. |
| 7 Close/HTTP | Policy tables, local-close provenance, stop thresholds, identity SECURITY exception and foreground retry inspected. Existing manager table tests cover rows. |
| 8 Backoff | Shared exponential full jitter, stable-session reset and slow classes match fixture. |
| 9 Watchdogs | Connect/stall/absolute/hello-ack deadlines and local-validation stall suspension present. Transport progress integration checked. |
| 10 Reachability | Transient-only shortcut and 2-second attempt spacing; connected path-change probe present. |
| 11 Pacer/retries/size | Shared budgets/reserve/window and pre-reservation size checks present. Known iOS superseded-rid leak and signed-leave bypass assigned to owners. CR-1 is an additional actual-transport acceptance gate. |
| 12 Receive budgets | Room/self-response/background partitions and initial budget checked. Known aggregate-limit admission precedence needs final explicit disposition. |
| 13 Lifecycle | Android pause retains joined manager; iOS inactive device-bound policy inspected through ContentView and RootGate. No signed leave on ordinary lifecycle pause. |
| 14 Hello epochs | Persist-before-sign, recovery time floor/doubling/spare-block/overflow policies present. Flags keep background reconnect disabled. |
| 15 Chat replay/history | Superseded session fences depend on authenticated durable hello state. 16 fingerprints/legacy 64 load, byte cap and unread retention inspected. Owners fixing bytes-before-count order with real sealed-store tests. |
| 16 v2 | Raw ID retained for crypto, lowercase canonical state keys and byte-order tie breaking checked. No v3 crypto relaxation observed. |
| 17 Persistence | Replay undo logs, running high water, batch reservations, journal batching, presence stride/crash floor present. CR-5/CR-6/CR-7 require final evidence. |
| 18 Wire index | Forward/reverse O(1) lookup and reusable HMAC present. Historical local IDs intentionally retained within a join for owed tombstones; store detach/session rebuild checked. |
| 19 Off-main | PBKDF2 and snapshot verification executor present. CR-2/CR-3 revalidation must preserve off-main importer work; CR-6 persistence executor gate. |
| 20 Presence efficiency | Stationary send policy matches fixture; separate iOS presence observable/overlay subscription and shape-fingerprint redraw guard inspected. Rendering/device evidence remains owner task. |
| 21 Background | Entry cancels delivery retries and chat secrets; bridge/freshness, background discard budget, paused notices and direct-chat gate inspected. Both reconnect switches stay false. Disabled-path residual parity issues must stay documented until enabling decision. |
| 22 Strings | Generated shared localized strings present. Parent owns final catalog/review/inventory check after all repairs. |
| 23 Seams/tests | Manager transport/clock/random/path/validation seams present; shared scenario mappings inspected. Generator freshness verified. Full suites and fresh final review still required. |
| 24 Deferred scope | No negotiated SP4 feature should be enabled; existing false background switch respected. |
| 25 Required docs | D4 stride language and D9 background-chat language present; THREAT_MODEL still describes pause after background socket loss. README stale client limits routed. |
| 26 Traceability | Findings tied to contract and regression requirements above; final owner evidence should close each listed gate. |

## Security and code quality assessment

No signature/AEAD bypass, relay high-water trust, rollback acceptance, nack-driven
durable auth mutation or lowering of persisted snapshot sequence was found in the
reviewed flows. This is a source review conclusion, not an assurance that all
runtime/device cases passed. CR-2/CR-4/CR-5 are specifically needed to satisfy the
strict zero-side-effect/durable-publication contract before acceptance.

The pure policy units and shared fixtures make large parts of the behavior easy to
audit, and transaction undo logs/cache/indexes address the prior full-state scans.
The manager files remain very large and contain several intertwined transaction,
lifecycle and publication boundaries. The concrete bugs here arise at those
boundaries: asynchronous snapshot inputs becoming stale, queue removal being
mistaken for write completion, and staged authentication becoming visible early.
Broad refactoring is not required to fix this package, but final tests must prove
these boundaries with held executors/streams and failing persistence, rather than
only asserting pure policy constants or fake successful transport callbacks.

`python3 testdata/tools/gen_sync_client_behaviour.py --check` passed. Existing
protocol/chat/malicious-frame expected fixtures were unchanged relative to
`1e21684`; no expected values were weakened here. `git diff --check` passed before
this report. Final build/test/localization/interop evidence belongs to the parent
and platform owners, and must be linked before marking Task 2 accepted.
