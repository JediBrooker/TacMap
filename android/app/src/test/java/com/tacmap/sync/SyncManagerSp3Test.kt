package com.tacmap.sync

import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingLayer
import com.tacmap.drawings.DrawingPoint
import com.tacmap.export.GeoJsonExporter
import com.tacmap.settings.BackgroundUnitSyncInterval
import com.tacmap.util.DataKey
import com.tacmap.util.SafeStore
import com.tacmap.waypoints.Waypoint
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * SP3 manager scripts (plans/04 sections 1, 17-21) through the S6-02 seam.
 * Each one is the regression for one audited behaviour.
 */
class SyncManagerSp3Test {
    private var harness: SyncHarness? = null
    private val toasts = ArrayList<String>()

    private fun start(separateWorkers: Boolean = false): SyncHarness {
        SyncHarness.installStoreKey()
        val h = SyncHarness(separateWorkers = separateWorkers)
        harness = h
        h.scope.launch { h.manager.remoteUpdates.collect { toasts += it } }
        h.runCurrent()
        return h
    }

    @After fun tearDown() {
        harness?.close()
        SyncHarness.restoreStoreKey()
    }

    private fun waypoint(name: String, id: String = UUID.randomUUID().toString()) =
        Waypoint(id = id, name = name, latitude = -35.0, longitude = 149.0, createdAt = 1_700_000_000_000L)

    private fun SyncHarness.shareLocation() {
        assertTrue(manager.updatePresenceConfig(PresenceConfig(shareLocation = true, callsign = "Alpha")))
    }

    private fun SyncHarness.fix(ageMs: Long = 0, lat: Double = -35.0, lon: Double = 149.0, speed: Double? = 0.0) =
        PresenceFixSample(
            provider = android.location.LocationManager.GPS_PROVIDER,
            latitude = lat,
            longitude = lon,
            accuracyMetres = 5.0,
            bearingDegrees = 0.0,
            speedMps = speed,
            elapsedRealtimeNanos = clock.elapsedRealtimeNanos() - ageMs * 1_000_000L,
        )

    private fun FakeSocket.locs() = sentOfType("loc")

    // ---- S2-05 / fixture background_entry_with_pending_delivery ---------------

    @Test
    fun backgroundEntryWithAPendingDeliveryNeverRetriesAndReconcilesOnReturn() {
        val h = start()
        h.shareLocation()
        h.join()
        h.completeHandshake()
        val wp = h.addWaypoint("in flight")
        h.advance(300)
        val first = h.socket.sentOfType("put").single()
        h.advance(100)
        assertTrue(h.manager.enterBackgroundPresenceOnly(BackgroundUnitSyncInterval.DEFAULT))
        val background = h.socket
        h.advance(30_000)
        assertEquals("no retransmits in background", 1, background.sentOfType("put").size)
        assertFalse("socket not cancelled by deliveries", background.terminal)
        assertNull(h.manager.lastError.value)

        h.advance(570_000)
        h.manager.prepareForForegroundUnlock()
        h.runCurrent()
        assertTrue(h.manager.attachForegroundStores(h.waypointStore, h.drawingStore) { null })
        h.runCurrent()
        h.completeHandshake()
        h.advance(300)
        val resent = h.socket.sentOfType("put").single { it.getString("id") == first.getString("id") }
        assertEquals("reconciled at its reserved stamp", first.getString("vs"), resent.getString("vs"))
        assertNotNull(wp)
    }

    // ---- S2-06 / S2-07: wake-safe keepalive and a loud pause ------------------

    @Test
    fun quietBackgroundSocketIsProbedBeforeSendingAndPausesLoudlyWhenDead() {
        val h = start()
        h.shareLocation()
        h.join()
        h.completeHandshake()
        val pausedAt = ArrayList<Long>()
        var transportEnded = 0
        h.manager.backgroundPresencePaused = { pausedAt += it }
        h.manager.backgroundTransportEnded = { transportEnded += 1 }
        h.socket.progress()
        h.runCurrent()
        assertTrue(h.manager.enterBackgroundPresenceOnly(BackgroundUnitSyncInterval.DEFAULT))
        val socket = h.socket

        // a fresh socket sends straight away
        assertTrue(h.manager.sendBackgroundPresence(h.fix(), BackgroundUnitSyncInterval.DEFAULT))
        h.runCurrent()
        assertEquals(1, socket.locs().size)
        assertEquals(0, socket.pings)

        // quiet for 80 s: wake lock, ping, and only send once the pong shows up
        h.advance(BackgroundUnitSyncInterval.DEFAULT.minutes * 60_000L)
        assertTrue(h.manager.sendBackgroundPresence(h.fix(), BackgroundUnitSyncInterval.DEFAULT))
        h.runCurrent()
        assertEquals(1, socket.pings)
        assertTrue(h.wakeLock.held)
        assertEquals(BackgroundPresencePolicy.WAKE_LOCK_MAX_MS, h.wakeLock.lastTimeoutMs)
        assertEquals("nothing sent into a socket that might be dead", 1, socket.locs().size)
        h.advance(1_000)
        socket.progress()
        h.advance(300)
        assertEquals(2, socket.locs().size)
        assertFalse(h.wakeLock.held)

        // next time nothing answers: the socket is dead, background sharing pauses and says so
        h.advance(BackgroundUnitSyncInterval.DEFAULT.minutes * 60_000L)
        assertTrue(h.manager.sendBackgroundPresence(h.fix(), BackgroundUnitSyncInterval.DEFAULT))
        h.advance(BackgroundPresencePolicy.PROBE_PONG_TIMEOUT_MS + 500)
        assertTrue(socket.terminal)
        assertEquals(2, socket.locs().size)
        assertFalse(h.wakeLock.held)
        assertEquals(1, pausedAt.size)
        assertEquals(1, transportEnded)
        assertEquals("no reconnect while screen-off", 1, h.transport.sockets.size)

        // foreground return says it once, with the time
        h.manager.prepareForForegroundUnlock()
        h.runCurrent()
        assertTrue(h.manager.attachForegroundStores(h.waypointStore, h.drawingStore) { null })
        h.runCurrent()
        assertTrue(toasts.any { it.contains("paused") })
        assertEquals(SyncIssueKind.CONNECTION, h.manager.currentIssueKind)
    }

    @Test
    fun bridgeFrameTakesAFixUpToTwoMinutesOld() {
        val h = start()
        h.shareLocation()
        h.join()
        h.completeHandshake()
        h.manager.locationSampleProvider = { h.fix(ageMs = 90_000) }
        assertTrue(h.manager.enterBackgroundPresenceOnly(BackgroundUnitSyncInterval.DEFAULT))
        h.runCurrent()
        assertEquals(1, h.socket.locs().size)
    }

    @Test
    fun bridgeFrameNeverUsesAFixOlderThanTwoMinutes() {
        val h = start()
        h.shareLocation()
        h.join()
        h.completeHandshake()
        h.manager.locationSampleProvider = { h.fix(ageMs = 121_000) }
        assertTrue(h.manager.enterBackgroundPresenceOnly(BackgroundUnitSyncInterval.DEFAULT))
        h.runCurrent()
        assertTrue(h.socket.locs().isEmpty())
    }

    // ---- S5-10: stationary suppression ----------------------------------------

    @Test
    fun stationaryForegroundPresenceSendsAHeartbeatNotEveryFiveSeconds() {
        val h = start()
        h.shareLocation()
        h.manager.locationSampleProvider = { h.fix() }
        h.join()
        h.completeHandshake()
        h.advance(60_000)
        val sent = h.socket.locs().size
        println("SP3 stationary presence over 60 s: locFrames=$sent")
        // hello-ack seed, then 20 s heartbeats
        assertTrue("$sent frames", sent in 3..4)
    }

    @Test
    fun movingForegroundPresenceKeepsTheFiveSecondCadence() {
        val h = start()
        h.shareLocation()
        h.join()
        val startMs = h.now
        // 3 m/s east
        h.manager.locationSampleProvider = {
            val metres = 3.0 * (h.now - startMs) / 1_000.0
            h.fix(lon = 149.0 + metres / (111_320.0 * Math.cos(Math.toRadians(-35.0))), speed = 3.0)
        }
        h.completeHandshake()
        h.advance(60_000)
        // this is also what a parked unit sent before S5-10, one frame per 5 s tick
        println("SP3 moving presence over 60 s: locFrames=${h.socket.locs().size}")
        assertTrue("${h.socket.locs().size} frames", h.socket.locs().size >= 12)
    }

    // ---- S6-04: chat to a background peer ---------------------------------------

    private fun chatKeyFrame(h: SyncHarness, peer: FakeV3Peer): JSONObject {
        val keys = h.keys()
        val kx = TacMapChatEphemeralKey.generate().publicKeyRaw
        val kid = TacMapChatCrypto.chatKeyId(keys.roomIdRaw, peer.actor, peer.sd, kx)
        val preimage = TacMapChatCrypto.chatKeyPreimage(keys.roomIdRaw, peer.actor, peer.sd, kx, kid)
        return JSONObject().put("t", "chat-key").put("cv", 1).put("by", peer.actor).put("sd", peer.sdText)
            .put("kx", SyncIdentity.urlB64(kx)).put("kid", kid).put("sig", SyncSigning.sign(peer.seed, preimage))
    }

    private fun locWithRetention(h: SyncHarness, peer: FakeV3Peer, counter: Long, retentionSeconds: Int): JSONObject {
        val keys = h.keys()
        val payload = PresencePayloadV3("peer", "FRIEND", "TEAM", "INFANTRY", false, -35.0, 149.0, 0.0, 0.0)
        val exact = PresencePayloadV3.encode(payload)
        val vs = VersionStamp(counter, peer.actor).encode()
        fun preimage(kind: String, hash: ByteArray) = SyncIdentity.buildPreimage(
            SyncIdentity.DOMAIN_PRESENCE, keys.roomIdRaw, peer.actor, peer.sd, VersionStamp.counterHex16(counter), "", kind, hash,
        )
        val envelope = JSONObject()
        payload.putFlatFields(envelope)
        envelope.put("pv", PresencePayloadV3.ENVELOPE_VERSION).put("p", exact.standardBase64).put("pub", peer.pub)
            .put("sig", SyncSigning.sign(peer.seed, preimage("loc", SyncIdentity.sha256(exact.bytes))))
        val retention = PresenceRetentionV3.encodePayload(retentionSeconds)!!
        envelope.put(PresenceRetentionV3.VERSION_FIELD, PresenceRetentionV3.ENVELOPE_VERSION)
            .put(PresenceRetentionV3.PAYLOAD_FIELD, SyncCrypto.encodeBase64(retention))
            .put(PresenceRetentionV3.SIGNATURE_FIELD,
                SyncSigning.sign(peer.seed, preimage(PresenceRetentionV3.SIGNATURE_KIND, SyncIdentity.sha256(retention))))
        val ct = SyncCrypto.encodeBase64(SyncCrypto.seal(
            keys.roomKey, envelope.toString().toByteArray(Charsets.UTF_8), SyncCrypto.aadPresenceV3(peer.actor, vs),
        ))
        return JSONObject().put("t", "loc").put("by", peer.actor).put("ct", ct)
            .put("pub", peer.pub).put("sd", peer.sdText).put("vs", vs)
    }

    @Test
    fun directChatToABackgroundPeerIsBlockedInsteadOfRouted() {
        val h = start()
        h.join()
        h.completeHandshake()
        val ownKey = h.socket.sentOfType("chat-key").single()
        h.deliver(JSONObject().put("t", "chat-key-ack").put("cv", 1).put("by", ownKey.getString("by"))
            .put("sd", ownKey.getString("sd")).put("kid", ownKey.getString("kid")))
        val peer = FakeV3Peer(h.keys())
        h.deliver(peer.hello())
        h.deliver(chatKeyFrame(h, peer))
        val target = checkNotNull(h.manager.chatTargetFor(peer.actor))

        h.deliver(locWithRetention(h, peer, 1, 45))
        assertNull("foreground peer still gets direct chat", h.manager.chatSendBlockReason(target))
        assertNull(h.manager.chatSendBlockReason(TacMapChatTarget.EntireRoom))

        h.deliver(locWithRetention(h, peer, 2, 1_200))
        val reason = h.manager.chatSendBlockReason(target)
        assertEquals(com.tacmap.localization.Messages.chatRecipientInBackgroundMessage().text, reason?.text)
        assertTrue(h.manager.sendChat(target, TacMapChatContentKind.TEXT, "RTB now") is TacMapChatSendResult.Blocked)
        assertTrue(h.socket.sentOfType("chat").isEmpty())
        // the room send is unchanged, Routed never claimed delivery there
        assertNull(h.manager.chatSendBlockReason(TacMapChatTarget.EntireRoom))
    }

    // ---- S3 verifier note 4: validation off the main thread -------------------

    @Test
    fun snapshotRecordsAreCheckedOnTheWorkerAndCommittedOnlyAtTheEnd() {
        val h = start(separateWorkers = true)
        val peer = FakeV3Peer(h.keys())
        val records = (1..20).map { peer.waypointRecord(waypoint("w$it"), it + 2L) }
        h.join()
        h.deriveDispatcher.runCurrent(); h.runCurrent()
        h.beginSnapshot()
        h.snapshotPage(records.take(10), more = true)
        h.snapshotPage(records.drop(10), more = false)
        h.endSnapshot()
        assertTrue("nothing committed before the worker is done", h.waypointStore.committedWaypoints.value.isEmpty())
        assertEquals(-1L, h.manager.replayStateForTests!!.lastSnapshotSeq)
        assertTrue(h.socket.sentOfType("hello").isEmpty())

        h.validationDispatcher.runCurrent()
        h.runCurrent()
        // the commit's sealed write is on the persistence worker too, nothing applied before it lands
        assertTrue(h.waypointStore.committedWaypoints.value.isEmpty())
        assertTrue(h.socket.sentOfType("hello").isEmpty())
        repeat(4) {
            h.persistenceDispatcher.runCurrent()
            h.runCurrent()
        }
        assertEquals(1L, h.manager.replayStateForTests!!.lastSnapshotSeq)
        assertEquals(20, h.waypointStore.committedWaypoints.value.size)
        assertFalse(h.manager.replayStateForTests!!.hasPendingModelApplications())
        assertEquals(1, h.socket.sentOfType("hello").size)
    }

    @Test
    fun oppositeKindCreatedDuringValidationIsSkippedBeforeReplayCommit() {
        val h = start(separateWorkers = true)
        val peer = FakeV3Peer(h.keys())
        val remote = waypoint("remote")
        h.join()
        h.deriveDispatcher.runCurrent(); h.runCurrent()
        h.beginSnapshot()
        h.snapshotPage(listOf(peer.waypointRecord(remote, 5)))
        h.validationDispatcher.runCurrent()
        val local = DrawingFeature(
            id = remote.id, name = "local", geometry = DrawingGeometry.LINE,
            points = listOf(DrawingPoint(-35.0, 149.0), DrawingPoint(-35.1, 149.1)),
        )
        assertTrue(h.drawingStore.addFeature(local))
        h.endSnapshot()
        repeat(6) {
            h.validationDispatcher.runCurrent(); h.runCurrent()
            h.persistenceDispatcher.runCurrent(); h.runCurrent()
        }
        val replay = h.manager.replayStateForTests!!
        assertNull(replay.getStamp(peer.wireId(remote.id)))
        assertNull(replay.getPinnedPubkey(peer.actor))
        assertEquals(0L, replay.localCounter)
        assertFalse(replay.hasPendingModelApplications())
        assertEquals(local, h.drawingStore.committedDocument.value.features.single())
        assertTrue(h.waypointStore.committedWaypoints.value.isEmpty())
        assertEquals(SnapshotRecordCategory.SKIP_UNSUPPORTED, h.manager.skippedCategoryForTests(peer.wireId(remote.id)))
        assertEquals(1, h.socket.sentOfType("hello").size)
    }

    @Test
    fun newlyCollidingRecordCannotHideLayersNeededByTheNextRecord() {
        val h = start(separateWorkers = true)
        val peer = FakeV3Peer(h.keys())
        val layer = DrawingLayer(id = "shared-new-layer", name = "Recon", createdAt = 1)
        fun drawing(name: String) = DrawingFeature(
            name = name, geometry = DrawingGeometry.LINE, layerId = layer.id,
            points = listOf(DrawingPoint(-35.0, 149.0), DrawingPoint(-35.1, 149.1)),
        )
        val colliding = drawing("colliding")
        val fresh = drawing("fresh")
        fun content(d: DrawingFeature) = GeoJsonExporter.export(emptyList(), listOf(d), listOf(layer), 1f)
        h.join()
        h.deriveDispatcher.runCurrent(); h.runCurrent()
        h.beginSnapshot()
        h.snapshotPage(listOf(
            peer.record(peer.wireId(colliding.id), 5, "drawing", content(colliding)),
            peer.record(peer.wireId(fresh.id), 6, "drawing", content(fresh)),
        ))
        h.validationDispatcher.runCurrent()
        assertTrue(h.waypointStore.add(waypoint("local", colliding.id)))
        h.endSnapshot()
        // Restaging itself waits for the separate validation worker, never the UI.
        h.runCurrent()
        assertEquals(-1L, h.manager.replayStateForTests!!.lastSnapshotSeq)
        repeat(8) {
            h.validationDispatcher.runCurrent(); h.runCurrent()
            h.persistenceDispatcher.runCurrent(); h.runCurrent()
        }
        val replay = h.manager.replayStateForTests!!
        assertNull(replay.getStamp(peer.wireId(colliding.id)))
        assertEquals(6L, replay.getStamp(peer.wireId(fresh.id))!!.counter)
        assertEquals(listOf(fresh.id), h.drawingStore.committedDocument.value.features.map { it.id })
        assertEquals(layer.name, h.drawingStore.committedDocument.value.layers.single { it.id == layer.id }.name)
        assertFalse(replay.hasPendingModelApplications())
        assertEquals(1, h.socket.sentOfType("hello").size)
        assertNotEqualsSecurity(h)
    }

    @Test
    fun staleSnapshotRecordCannotStageLayersForAFreshRecord() {
        val h = start(separateWorkers = true)
        val peer = FakeV3Peer(h.keys())
        val layer = DrawingLayer(id = "new-layer", name = "Recon", createdAt = 1)
        fun drawing(name: String) = DrawingFeature(
            name = name, geometry = DrawingGeometry.LINE, layerId = layer.id,
            points = listOf(DrawingPoint(-35.0, 149.0), DrawingPoint(-35.1, 149.1)),
        )
        val stale = drawing("stale")
        val fresh = drawing("fresh")
        fun content(d: DrawingFeature) = GeoJsonExporter.export(emptyList(), listOf(d), listOf(layer), 1f)
        h.join()
        h.deriveDispatcher.runCurrent(); h.runCurrent()
        val replay = h.manager.replayStateForTests!!
        assertTrue(replay.commitSnapshot(listOf(SyncReplayState.AuthenticatedMutation(
            peer.wireId(stale.id), VersionStamp(10, peer.actor), peer.pub,
            SyncIdentity.bytesToHex(SyncIdentity.sha256("newer saved content".toByteArray())), false,
        )), 0))
        h.beginSnapshot()
        h.snapshotPage(listOf(
            peer.record(peer.wireId(stale.id), 5, "drawing", content(stale)),
            peer.record(peer.wireId(fresh.id), 11, "drawing", content(fresh)),
        ))
        h.endSnapshot()
        repeat(6) {
            h.validationDispatcher.runCurrent(); h.runCurrent()
            h.persistenceDispatcher.runCurrent(); h.runCurrent()
        }
        assertEquals(listOf(fresh.id), h.drawingStore.committedDocument.value.features.map { it.id })
        val adopted = h.drawingStore.committedDocument.value.layers.single { it.id == layer.id }
        assertEquals(layer.name, adopted.name)
        assertEquals(layer.color, adopted.color)
        assertEquals(10L, replay.getStamp(peer.wireId(stale.id))!!.counter)
        assertEquals(11L, replay.getStamp(peer.wireId(fresh.id))!!.counter)
        assertFalse(replay.hasPendingModelApplications())
        assertEquals(1, h.socket.sentOfType("hello").size)
        assertNotEqualsSecurity(h)
    }

    @Test
    fun exactResolvedSnapshotRecordCannotStageUnappliedLayers() {
        exactRecordCannotStageUnappliedLayers(localDiverged = false)
    }

    @Test
    fun locallyDivergedPendingSnapshotRecordCannotStageUnappliedLayers() {
        exactRecordCannotStageUnappliedLayers(localDiverged = true)
    }

    private fun exactRecordCannotStageUnappliedLayers(localDiverged: Boolean) {
        val h = start(separateWorkers = true)
        val peer = FakeV3Peer(h.keys())
        val layer = DrawingLayer(id = "unapplied-layer", name = "Recon", createdAt = 1)
        fun drawing(name: String) = DrawingFeature(
            name = name, geometry = DrawingGeometry.LINE, layerId = layer.id,
            points = listOf(DrawingPoint(-35.0, 149.0), DrawingPoint(-35.1, 149.1)),
        )
        val exact = drawing("exact")
        val fresh = drawing("fresh")
        fun content(d: DrawingFeature) = GeoJsonExporter.export(emptyList(), listOf(d), listOf(layer), 1f)
        h.join()
        h.deriveDispatcher.runCurrent(); h.runCurrent()
        val replay = h.manager.replayStateForTests!!
        val mutation = SyncReplayState.AuthenticatedMutation(
            peer.wireId(exact.id), VersionStamp(5, peer.actor), peer.pub,
            SyncIdentity.bytesToHex(SyncIdentity.sha256(content(exact).toByteArray())), false,
        )
        if (localDiverged) {
            assertTrue(replay.commitRemoteAuthenticated(SyncReplayState.RemoteMutation(
                mutation, priorModelHash = null, localModelId = exact.id, acceptedGeneration = 0,
                expectedModelHash = mutation.contentHash,
            )))
            assertTrue(h.drawingStore.addFeature(exact.copy(name = "local", layerId = "default")))
            h.runCurrent()
        } else {
            assertTrue(replay.commitSnapshot(listOf(mutation), 0))
        }
        h.beginSnapshot()
        h.snapshotPage(listOf(
            peer.record(peer.wireId(exact.id), 5, "drawing", content(exact)),
            peer.record(peer.wireId(fresh.id), 6, "drawing", content(fresh)),
        ))
        h.endSnapshot()
        repeat(8) {
            h.validationDispatcher.runCurrent(); h.runCurrent()
            h.persistenceDispatcher.runCurrent(); h.runCurrent()
        }
        assertTrue(h.drawingStore.committedDocument.value.features.any { it.id == fresh.id })
        assertEquals(layer.name, h.drawingStore.committedDocument.value.layers.single { it.id == layer.id }.name)
        if (localDiverged) {
            assertEquals("local", h.drawingStore.committedDocument.value.features.single { it.id == exact.id }.name)
        } else {
            assertTrue(h.drawingStore.committedDocument.value.features.none { it.id == exact.id })
        }
        assertFalse(replay.hasPendingModelApplications())
        assertEquals(1, h.socket.sentOfType("hello").size)
        assertNotEqualsSecurity(h)
    }

    @Test
    fun repeatedLiveWireIdEndsTheLayerStagingSubgroup() {
        val h = start()
        h.join(); h.completeHandshake()
        val peer = FakeV3Peer(h.keys())
        h.deliver(peer.hello())
        val layer = DrawingLayer(id = "first-version-layer", name = "Recon", createdAt = 1)
        val first = DrawingFeature(
            name = "first", geometry = DrawingGeometry.LINE, layerId = layer.id,
            points = listOf(DrawingPoint(-35.0, 149.0), DrawingPoint(-35.1, 149.1)),
        )
        val second = first.copy(name = "second", layerId = "default")
        val other = first.copy(id = UUID.randomUUID().toString(), name = "other")
        fun record(feature: DrawingFeature, counter: Long, layers: List<DrawingLayer>) = peer.record(
            peer.wireId(feature.id), counter, "drawing",
            GeoJsonExporter.export(emptyList(), listOf(feature), layers, 1f), t = "put",
        )
        h.socket.deliver(record(first, 5, listOf(layer)))
        h.socket.deliver(record(second, 6, emptyList()))
        h.socket.deliver(record(other, 7, listOf(layer)))
        h.runCurrent()
        assertEquals("second", h.drawingStore.committedDocument.value.features.single { it.id == first.id }.name)
        assertEquals(layer.name, h.drawingStore.committedDocument.value.layers.single { it.id == layer.id }.name)
        assertTrue(h.drawingStore.committedDocument.value.features.any { it.id == other.id })
        assertFalse(h.manager.replayStateForTests!!.hasPendingModelApplications())
        assertNotEqualsSecurity(h)
    }

    @Test
    fun layerRenamedWhileTheSnapshotValidatesIsRestaged() {
        val h = start(separateWorkers = true)
        val peer = FakeV3Peer(h.keys())
        assertTrue(h.drawingStore.addLayerVerbatim(DrawingLayer(id = "L", name = "Recon", createdAt = 1)))
        val drawing = DrawingFeature(
            name = "d", geometry = DrawingGeometry.LINE,
            points = listOf(DrawingPoint(-35.0, 149.0), DrawingPoint(-35.1, 149.1)), layerId = "L",
        )
        val content = GeoJsonExporter.export(emptyList(), listOf(drawing), listOf(DrawingLayer(id = "L", name = "Recon", createdAt = 1)), 1f)
        h.join()
        h.deriveDispatcher.runCurrent(); h.runCurrent()
        h.beginSnapshot()
        h.snapshotPage(listOf(peer.record(peer.wireId(drawing.id), 3, "drawing", content)))
        h.validationDispatcher.runCurrent()
        assertTrue(h.drawingStore.renameLayer("L", "Alpha"))
        h.endSnapshot()
        repeat(6) {
            h.validationDispatcher.runCurrent()
            h.persistenceDispatcher.runCurrent()
            h.runCurrent()
        }
        assertEquals(1, h.socket.sentOfType("hello").size)
        assertNotEqualsSecurity(h)
        assertEquals(setOf(drawing.id), h.drawingStore.committedDocument.value.features.map { it.id }.toSet())
    }

    @Test
    fun anEditWhileTheSnapshotCommitSealsIsNotOverwritten() {
        val h = start(separateWorkers = true)
        val peer = FakeV3Peer(h.keys())
        val mine = h.addWaypoint("local")
        h.join()
        h.deriveDispatcher.runCurrent(); h.runCurrent()
        h.beginSnapshot()
        h.snapshotPage(listOf(peer.waypointRecord(mine.copy(name = "remote"), 5)))
        h.endSnapshot()
        h.validationDispatcher.runCurrent()
        h.runCurrent()
        // the commit is sealing on the persistence worker, the user renames it meanwhile
        assertTrue(h.waypointStore.update(mine.copy(name = "edited")))
        h.runCurrent()
        repeat(4) {
            h.persistenceDispatcher.runCurrent()
            h.runCurrent()
        }
        assertEquals("edited", h.waypointStore.committedWaypoints.value.single().name)
        assertFalse(h.manager.replayStateForTests!!.hasPendingModelApplications())
        assertNotEqualsSecurity(h)
        val hello = h.socket.sentOfType("hello").single()
        h.deliver(JSONObject().put("t", "hello-ack").put("by", hello.getString("by"))
            .put("sd", hello.getString("sd")).put("vs", hello.getString("vs")))
        repeat(3) {
            h.advance(300)
            h.persistenceDispatcher.runCurrent()
            h.runCurrent()
        }
        // the local edit wins at a new stamp above the remote one
        val put = h.socket.sentOfType("put").single { it.getString("id") == peer.wireId(mine.id) }
        assertTrue(VersionStamp.parse(put.getString("vs"))!!.counter > 5)
    }

    private fun assertNotEqualsSecurity(h: SyncHarness) {
        assertFalse("no persistence failure", h.manager.currentIssueKind == SyncIssueKind.SECURITY)
    }

    @Test
    fun framesAfterSnapshotEndWaitForTheCommit() {
        val h = start(separateWorkers = true)
        val peer = FakeV3Peer(h.keys())
        h.join()
        h.deriveDispatcher.runCurrent(); h.runCurrent()
        h.beginSnapshot()
        h.snapshotPage(listOf(peer.waypointRecord(waypoint("x"), 3)))
        h.endSnapshot()
        // the relay follows snapshot-end with live hellos; they queue behind the commit
        h.deliver(peer.hello())
        assertTrue(h.manager.onlineMembers.value.isEmpty())
        // Snapshot commit, pending cleanup, hello epoch and the queued peer hello
        // each have their own durable turn; none may publish ahead of its writer.
        repeat(12) {
            h.validationDispatcher.runCurrent()
            h.persistenceDispatcher.runCurrent()
            h.runCurrent()
        }
        assertEquals(1, h.socket.sentOfType("hello").size)
        assertNotNull(h.manager.onlineMembers.value[peer.actor])
    }

    // ---- section 1.1: the reader is released on enqueue ------------------------

    @Test
    fun readerIsReleasedWhenQueuedAndWaitsOnlyForAFullQueue() {
        val h = start()
        h.join()
        h.socket.open()
        h.runCurrent()
        val socket = h.socket
        var released = 0
        val frame = """{"t":"pong"}"""
        repeat(SyncManager.INBOUND_QUEUE_MAX_FRAMES + 1) {
            socket.listener.onTextMessage(socket, frame) { released += 1 }
        }
        assertEquals("released as soon as each one is queued", SyncManager.INBOUND_QUEUE_MAX_FRAMES, released)
        h.runCurrent()
        assertEquals("the waiting reader goes once the worker drains", SyncManager.INBOUND_QUEUE_MAX_FRAMES + 1, released)
    }

    // ---- S5-13: PBKDF2 off the UI thread --------------------------------------

    @Test
    fun joinShowsConnectingAndDerivesOffTheCallingThread() {
        val h = start(separateWorkers = true)
        h.manager.join(SyncHarness.CODE)
        h.runCurrent()
        assertEquals(SyncManager.Status.CONNECTING, h.manager.status.value)
        assertTrue("no socket before the keys exist", h.transport.sockets.isEmpty())
        assertNull(h.manager.room.value)
        h.deriveDispatcher.runCurrent()
        h.runCurrent()
        assertEquals(1, h.transport.sockets.size)
        assertEquals(SyncHarness.CODE, h.manager.room.value)
    }

    @Test
    fun leaveDuringDerivationDropsTheResult() {
        val h = start(separateWorkers = true)
        h.manager.join(SyncHarness.CODE)
        h.runCurrent()
        h.manager.leave()
        h.deriveDispatcher.runCurrent()
        h.runCurrent()
        assertTrue(h.transport.sockets.isEmpty())
        assertNull(h.manager.room.value)
        assertEquals(SyncManager.Status.OFFLINE, h.manager.status.value)
    }

    // ---- section 1.3: nothing visible before the batch is durable ---------------

    @Test
    fun presenceIsNotExposedWhenTheBatchWriteFails() {
        val h = start()
        h.join()
        h.completeHandshake()
        val peer = FakeV3Peer(h.keys())
        h.deliver(peer.hello())
        val replay = h.manager.replayStateForTests!!
        assertEquals(0L, replay.getPresenceCounter(peer.actor))
        SafeStore.keyProvider = SafeStore.KeyProvider { throw DataKey.LockedException() }
        try {
            h.deliver(locWithRetention(h, peer, 1, 45))
        } finally {
            SyncHarness.installStoreKey()
        }
        assertTrue("peer not exposed", h.manager.peers.value.isEmpty())
        assertEquals(0L, replay.getPresenceCounter(peer.actor))
        assertEquals(SyncIssueKind.SECURITY, h.manager.currentIssueKind)
    }

    @Test
    fun aFailedLiveBatchWriteRollsBackEveryRecordInIt() {
        val h = start()
        h.join()
        h.completeHandshake()
        val peer = FakeV3Peer(h.keys())
        h.deliver(peer.hello())
        val wps = (1..5).map { waypoint("q$it") }
        SafeStore.keyProvider = SafeStore.KeyProvider { throw DataKey.LockedException() }
        try {
            wps.forEachIndexed { i, wp -> h.socket.deliver(peer.waypointRecord(wp, 50L + i, t = "put")) }
            h.runCurrent()
        } finally {
            SyncHarness.installStoreKey()
        }
        val replay = h.manager.replayStateForTests!!
        for (wp in wps) assertNull(replay.getStamp(peer.wireId(wp.id)))
        assertFalse(replay.hasPendingModelApplications())
        assertTrue(h.waypointStore.committedWaypoints.value.isEmpty())
        assertEquals(SyncIssueKind.SECURITY, h.manager.currentIssueKind)
    }

    @Test
    fun helloAndPresenceInOneBatchShareOneWrite() {
        val h = start()
        h.join()
        h.completeHandshake()
        h.advance(300)
        val peer = FakeV3Peer(h.keys())
        val socket = h.socket
        val replay = h.manager.replayStateForTests!!
        socket.deliver(peer.hello())
        for (c in 1L..10L) socket.deliver(locWithRetention(h, peer, c, 45))
        h.runCurrent()
        assertNotNull(h.manager.peers.value[peer.actor])
        // one batch, one write, and that write carries all ten counters
        assertEquals(10L, replay.getPresenceCounter(peer.actor))
        assertFalse(replay.hasUnflushedPresence)
        // inside the stride: exposed without a write, the 60 s flush picks it up
        for (c in 11L..15L) h.deliver(locWithRetention(h, peer, c, 45))
        assertEquals(15L, replay.getPresenceCounter(peer.actor))
        assertTrue(replay.hasUnflushedPresence)
        h.advance(PresenceFencePersistence.FLUSH_MS + 100)
        assertFalse("60 s flush", replay.hasUnflushedPresence)
    }

    // ---- section 18: the index follows the stores -----------------------------

    @Test
    fun remoteDeleteFindsAnObjectAddedAfterTheIndexWasBuilt() {
        val h = start()
        h.join()
        h.completeHandshake()
        val peer = FakeV3Peer(h.keys())
        h.deliver(peer.hello())
        // added after the index was built, deleted remotely before our diff even ran
        val wp = h.addWaypoint("late")
        h.deliver(peer.record(peer.wireId(wp.id), 5, "del", null, deleted = true, t = "del"))
        assertTrue(h.waypointStore.committedWaypoints.value.none { it.id == wp.id })
        h.advance(500)
        assertTrue(h.socket.sentOfType("put").none { it.getString("id") == peer.wireId(wp.id) })
    }
}
