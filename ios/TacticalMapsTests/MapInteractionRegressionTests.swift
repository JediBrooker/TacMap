import XCTest
import CoreLocation
import CoreGraphics
import UIKit
import Grid
@testable import TacticalMaps

final class MapInteractionRegressionTests: XCTestCase {
    private enum MutationProbeError: Error { case failed }

    private final class MutationWriteProbe {
        var attempts = 0
        var shouldFail = false

        func write(_ data: Data, _ url: URL, _ label: String) throws {
            attempts += 1
            if shouldFail { throw MutationProbeError.failed }
        }

        func reset() { attempts = 0 }
    }

    private func temporaryStoreURL(_ name: String) -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("RendererGestureTests-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent(name)
    }

    func testCoordinateDisplayFormatResolvesSelectedPrimaryCoordinate() {
        let mgrs = "56HLH 13225 37516"
        let wgs84 = "33.86880° S, 151.20930° E"
        let utm = "56S 334369mE 6250948mN"

        XCTAssertEqual(
            CoordinateDisplayFormat.mgrs.resolve(mgrs: mgrs, wgs84: wgs84, utm: utm),
            .init(format: .mgrs, text: mgrs)
        )
        XCTAssertEqual(
            CoordinateDisplayFormat.wgs84.resolve(mgrs: mgrs, wgs84: wgs84, utm: utm),
            .init(format: .wgs84, text: wgs84)
        )
        XCTAssertEqual(
            CoordinateDisplayFormat.utm.resolve(mgrs: mgrs, wgs84: wgs84, utm: utm),
            .init(format: .utm, text: utm)
        )
    }

    func testUnavailableUTMPrimaryFallsBackToWGS84() {
        let wgs84 = "85.00000° N, 0.00000° E"
        for unavailable in [nil, "", "   ", "N/A (>84°N)"] as [String?] {
            XCTAssertEqual(
                CoordinateDisplayFormat.utm.resolve(
                    mgrs: "N/A (>84°N)",
                    wgs84: wgs84,
                    utm: unavailable
                ),
                .init(format: .wgs84, text: wgs84)
            )
        }
    }

    func testCoordinateDisplayPreferenceDefaultsToMGRSAndPersists() throws {
        let suiteName = "CoordinateDisplayPreferenceTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }

        XCTAssertEqual(CoordinateDisplayFormat.stored(in: defaults), .mgrs)
        CoordinateDisplayFormat.utm.persist(in: defaults)
        XCTAssertEqual(CoordinateDisplayFormat.stored(in: defaults), .utm)

        defaults.set("unknown-future-value", forKey: CoordinateDisplayFormat.defaultsKey)
        XCTAssertEqual(CoordinateDisplayFormat.stored(in: defaults), .mgrs)
    }

    func testBackgroundUnitSyncIntervalDefaultsToFifteenMinutesAndPersists() throws {
        let suiteName = "BackgroundUnitSyncIntervalTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }

        XCTAssertEqual(BackgroundUnitSyncInterval.stored(in: defaults), .fifteenMinutes)
        XCTAssertEqual(BackgroundUnitSyncInterval.defaultValue.seconds, 15 * 60)
        XCTAssertEqual(
            BackgroundUnitSyncInterval.allCases.map(\.rawValue),
            [1, 5, 15, 30, 60]
        )

        BackgroundUnitSyncInterval.fiveMinutes.persist(in: defaults)
        XCTAssertEqual(BackgroundUnitSyncInterval.stored(in: defaults), .fiveMinutes)
        BackgroundUnitSyncInterval.thirtyMinutes.persist(in: defaults)
        XCTAssertEqual(BackgroundUnitSyncInterval.stored(in: defaults), .thirtyMinutes)
        BackgroundUnitSyncInterval.sixtyMinutes.persist(in: defaults)
        XCTAssertEqual(BackgroundUnitSyncInterval.stored(in: defaults), .sixtyMinutes)

        defaults.set(999, forKey: BackgroundUnitSyncInterval.defaultsKey)
        XCTAssertEqual(BackgroundUnitSyncInterval.stored(in: defaults), .fifteenMinutes)
    }

    func testStationaryLocationRefreshIsEarlyAndDebounced() {
        var policy = LocationFixRefreshPolicy()
        let now = Date(timeIntervalSince1970: 10_000)

        XCTAssertFalse(policy.shouldRestart(
            fixTimestamp: now.addingTimeInterval(-20),
            maximumAge: 20,
            maximumFutureSkew: 30,
            now: now,
            uptime: 100
        ))
        XCTAssertTrue(policy.shouldRestart(
            fixTimestamp: now.addingTimeInterval(-21),
            maximumAge: 20,
            maximumFutureSkew: 30,
            now: now,
            uptime: 100
        ))
        XCTAssertFalse(policy.shouldRestart(
            fixTimestamp: nil,
            maximumAge: 20,
            maximumFutureSkew: 30,
            now: now,
            uptime: 114
        ), "a missing fix must not restart Core Location every presence tick")
        XCTAssertTrue(policy.shouldRestart(
            fixTimestamp: nil,
            maximumAge: 20,
            maximumFutureSkew: 30,
            now: now,
            uptime: 115
        ))

        XCTAssertFalse(policy.shouldRestart(
            fixTimestamp: now,
            maximumAge: 20,
            maximumFutureSkew: 30,
            now: now,
            uptime: 116
        ))
        XCTAssertFalse(policy.shouldRestart(
            fixTimestamp: now.addingTimeInterval(31),
            maximumAge: 20,
            maximumFutureSkew: 30,
            now: now,
            uptime: 117
        ), "even an invalid callback must not defeat the refresh debounce")
        XCTAssertTrue(policy.shouldRestart(
            fixTimestamp: now.addingTimeInterval(31),
            maximumAge: 20,
            maximumFutureSkew: 30,
            now: now,
            uptime: 130
        ), "a badly future-dated fix must be replaced")
    }

    func testUnitSyncJoinGateRequiresConsentUntilBothLocationSettingsAreEnabled() {
        XCTAssertTrue(UnitSyncJoinGate.requiresConsent(
            roomCode: "3:strong-room-code",
            shareLocation: false,
            backgroundLocation: false
        ))
        XCTAssertTrue(UnitSyncJoinGate.requiresConsent(
            roomCode: "3:strong-room-code",
            shareLocation: true,
            backgroundLocation: false
        ))
        XCTAssertTrue(UnitSyncJoinGate.requiresConsent(
            roomCode: "3:strong-room-code",
            shareLocation: false,
            backgroundLocation: true
        ))
        XCTAssertFalse(UnitSyncJoinGate.requiresConsent(
            roomCode: "3:strong-room-code",
            shareLocation: true,
            backgroundLocation: true
        ))
        XCTAssertFalse(UnitSyncJoinGate.requiresConsent(
            roomCode: "2:legacy-room-code",
            shareLocation: false,
            backgroundLocation: false
        ), "legacy rooms cannot use the v3 background-location feature")
    }

    func testHeadingNormalizesAcrossNorthAndConvertsToMils() {
        XCTAssertEqual(MapHeading.normalized(361), 1, accuracy: 1e-12)
        XCTAssertEqual(MapHeading.normalized(-90), 270, accuracy: 1e-12)
        XCTAssertEqual(MapHeading.mils(for: 0), 0)
        XCTAssertEqual(MapHeading.mils(for: 90), 1600)
        XCTAssertEqual(MapHeading.mils(for: 180), 3200)
        XCTAssertEqual(MapHeading.mils(for: 270), 4800)
        XCTAssertEqual(MapHeading.milsString(for: 359.99), "0000")
    }

    func testCompassTapResetsBeforeEnteringHeadingUp() {
        XCTAssertEqual(
            MapHeading.compassTapAction(
                headingUpEnabled: false,
                currentHeading: 25,
                headingAvailable: true
            ),
            .resetNorth
        )
        XCTAssertEqual(
            MapHeading.compassTapAction(
                headingUpEnabled: false,
                currentHeading: 359.5,
                headingAvailable: true
            ),
            .enableHeadingUp
        )
        XCTAssertEqual(
            MapHeading.compassTapAction(
                headingUpEnabled: true,
                currentHeading: 140,
                headingAvailable: true
            ),
            .disableHeadingUp
        )
        XCTAssertEqual(
            MapHeading.compassTapAction(
                headingUpEnabled: false,
                currentHeading: 0,
                headingAvailable: false
            ),
            .headingUnavailable
        )
    }

    func testHeadingSmoothingCrossesNorthByTheShortPath() {
        XCTAssertEqual(
            MapHeading.smoothed(previous: 359, measured: 1, factor: 0.5),
            0,
            accuracy: 1e-12
        )
        XCTAssertEqual(MapHeading.shortestDelta(from: 1, to: 0), -1, accuracy: 1e-12)
    }

    func testCompassNorthReferenceSuffixesAreUnambiguous() {
        XCTAssertEqual(HeadingNorthReference.trueNorth.displaySuffix, "T")
        XCTAssertEqual(HeadingNorthReference.magneticNorth.displaySuffix, "M")
        XCTAssertEqual(HeadingNorthReference.trueNorth.accessibilityLabel, "true north")
        XCTAssertEqual(HeadingNorthReference.magneticNorth.accessibilityLabel, "magnetic north")
    }

    func testHeadingOrientationTracksTheInterfaceTopEdge() {
        XCTAssertEqual(LocationService.headingOrientation(for: .portrait), .portrait)
        XCTAssertEqual(
            LocationService.headingOrientation(for: .portraitUpsideDown),
            .portraitUpsideDown
        )
        XCTAssertEqual(LocationService.headingOrientation(for: .landscapeLeft), .landscapeRight)
        XCTAssertEqual(LocationService.headingOrientation(for: .landscapeRight), .landscapeLeft)
    }

    func testHeadingOrientationPrefersActiveThenInactiveScene() {
        XCTAssertEqual(
            LocationService.preferredInterfaceOrientation(from: [
                (state: .background, orientation: .portraitUpsideDown),
                (state: .foregroundInactive, orientation: .landscapeLeft),
                (state: .foregroundActive, orientation: .landscapeRight),
            ]),
            .landscapeRight
        )
        XCTAssertEqual(
            LocationService.preferredInterfaceOrientation(from: [
                (state: .background, orientation: .portraitUpsideDown),
                (state: .foregroundInactive, orientation: .landscapeLeft),
            ]),
            .landscapeLeft
        )
        XCTAssertNil(LocationService.preferredInterfaceOrientation(from: [
            (state: .background, orientation: .portrait),
            (state: .unattached, orientation: .landscapeRight),
        ]))
    }

    func testHeadingOrientationChangesOnlyWhenReferenceEdgeChanges() {
        XCTAssertNil(LocationService.nextHeadingOrientation(
            current: .portrait,
            interfaceOrientation: .portrait
        ))
        XCTAssertEqual(
            LocationService.nextHeadingOrientation(
                current: .portrait,
                interfaceOrientation: .landscapeLeft
            ),
            .landscapeRight
        )
        XCTAssertNil(LocationService.nextHeadingOrientation(
            current: .landscapeRight,
            interfaceOrientation: .landscapeLeft
        ))
    }

    func testMapOrientationPreferenceDefaultsAndRoundTrips() {
        let suiteName = "MapOrientationModeTests-\(UUID().uuidString)"
        guard let defaults = UserDefaults(suiteName: suiteName) else {
            return XCTFail("Could not create isolated defaults suite")
        }
        defer { defaults.removePersistentDomain(forName: suiteName) }

        XCTAssertEqual(MapOrientationMode.stored(in: defaults), .northUp)
        defaults.set("unknown", forKey: MapOrientationMode.defaultsKey)
        XCTAssertEqual(MapOrientationMode.stored(in: defaults), .northUp)
        for mode in MapOrientationMode.allCases {
            mode.persist(in: defaults)
            XCTAssertEqual(MapOrientationMode.stored(in: defaults), mode)
        }
    }

    func testRotationDeltaPublishesNormalizedCameraHeading() {
        let camera = MapCamera(
            center: CLLocationCoordinate2D(latitude: -33.86, longitude: 151.21),
            zoom: 12,
            headingDegrees: 350,
            viewportSize: CGSize(width: 390, height: 844)
        )
        let view = TileMapView(camera: camera)
        var published: MapCamera?
        view.onCameraChange = { published = $0 }
        let gesture = UIRotationGestureRecognizer()
        gesture.rotation = 20 * .pi / 180

        view.consumeRotationGestureDelta(gesture)

        XCTAssertEqual(view.camera.headingDegrees, 10, accuracy: 1e-9)
        XCTAssertEqual(published?.headingDegrees ?? -1, 10, accuracy: 1e-9)
        XCTAssertEqual(gesture.rotation, 0, accuracy: 1e-12)
    }

    func testHeadingUpIgnoresManualRotation() {
        let camera = MapCamera(
            center: CLLocationCoordinate2D(latitude: -33.86, longitude: 151.21),
            zoom: 12,
            headingDegrees: 90,
            viewportSize: CGSize(width: 390, height: 844)
        )
        let view = TileMapView(camera: camera)
        view.isRotationGestureEnabled = false

        view.applyRotationGestureDelta(20 * .pi / 180)

        XCTAssertEqual(view.camera.headingDegrees, 90, accuracy: 1e-12)
    }

    func testHeatmapCoverageIsInvariantAcrossMapHeading() {
        let camera = MapCamera(
            center: CLLocationCoordinate2D(latitude: -33.86, longitude: 151.21),
            zoom: 12,
            headingDegrees: 0,
            viewportSize: CGSize(width: 390, height: 844)
        )
        var turned = camera
        turned.headingDegrees = 90

        let north = MapProjectionMath.orientationInvariantRegion(camera)
        let east = MapProjectionMath.orientationInvariantRegion(turned)

        XCTAssertEqual(north.center.latitude, east.center.latitude, accuracy: 1e-12)
        XCTAssertEqual(north.center.longitude, east.center.longitude, accuracy: 1e-12)
        XCTAssertEqual(north.span.latitudeDelta, east.span.latitudeDelta, accuracy: 1e-12)
        XCTAssertEqual(north.span.longitudeDelta, east.span.longitudeDelta, accuracy: 1e-12)
    }

    func testHeatmapCoverageChangesWhenViewportDiagonalChanges() {
        let camera = MapCamera(
            center: CLLocationCoordinate2D(latitude: -33.86, longitude: 151.21),
            zoom: 12,
            headingDegrees: 0,
            viewportSize: CGSize(width: 390, height: 844)
        )
        var resized = camera
        resized.viewportSize = CGSize(width: 844, height: 844)

        let originalRegion = MapProjectionMath.orientationInvariantRegion(camera)
        let resizedRegion = MapProjectionMath.orientationInvariantRegion(resized)

        XCTAssertGreaterThan(resizedRegion.span.latitudeDelta, originalRegion.span.latitudeDelta)
        XCTAssertGreaterThan(resizedRegion.span.longitudeDelta, originalRegion.span.longitudeDelta)
    }

    func testCoordinateOnlyWaypointMoveRepublishesScreenPosition() {
        let camera = MapCamera(
            center: CLLocationCoordinate2D(latitude: 0, longitude: 0),
            zoom: 10,
            headingDegrees: 0,
            viewportSize: CGSize(width: 400, height: 600)
        )
        let view = TileMapView(camera: camera)
        let mapVM = MapViewModel()
        let coordinator = TileMapContainer.Coordinator()
        coordinator.attach(view: view, mapVM: mapVM)

        var waypoint = Waypoint(
            name: "Unit",
            coordinate: CLLocationCoordinate2D(latitude: 0, longitude: 0)
        )
        coordinator.syncWaypoints([waypoint], view: view)
        let first = expectation(description: "initial position published")
        DispatchQueue.main.async {
            XCTAssertEqual(mapVM.waypointScreenPositions[waypoint.id]?.x ?? -1, 200, accuracy: 1e-6)
            first.fulfill()
        }
        wait(for: [first], timeout: 1)

        waypoint.longitude = 0.5
        coordinator.syncWaypoints([waypoint], view: view)
        let moved = expectation(description: "coordinate change published")
        DispatchQueue.main.async {
            XCTAssertGreaterThan(mapVM.waypointScreenPositions[waypoint.id]?.x ?? 0, 200)
            moved.fulfill()
        }
        wait(for: [moved], timeout: 1)
    }

    func testAppliedMGRSGridAddsExactlyOnePhysicalPixel() {
        let base = MGRSGridRenderer.lineWidth(for: .HUNDRED_KILOMETER)
        XCTAssertEqual(
            MGRSGridRenderer.appliedLineWidth(
                for: .HUNDRED_KILOMETER,
                screenScale: 2
            ),
            base + 0.5,
            accuracy: 1e-12
        )
        XCTAssertEqual(
            MGRSGridRenderer.appliedLineWidth(
                for: .HUNDRED_KILOMETER,
                screenScale: 3
            ),
            base + 1.0 / 3.0,
            accuracy: 1e-12
        )
    }

    func testDistanceFormattingUsedByFromMeReadout() {
        XCTAssertEqual(MeasureFormat.distance(428), "428 m")
        XCTAssertEqual(MeasureFormat.distance(1_500), "1.50 km")
    }

    func testWaypointDragKeepsChangedEventsInMemoryAndCommitsExactlyOnce() throws {
        let probe = MutationWriteProbe()
        let store = WaypointStore(
            storageURL: temporaryStoreURL("waypoints.json"),
            persistenceWriter: probe.write
        )
        let original = Waypoint(name: "Unit", latitude: -33.0, longitude: 151.0)
        _ = try store.addDurably(original)
        probe.reset()

        var preview = WaypointGesturePreview(original)
        preview.move(to: .init(latitude: -33.1, longitude: 151.1))
        preview.move(to: .init(latitude: -33.2, longitude: 151.2))
        preview.move(to: .init(latitude: -33.3, longitude: 151.3))

        XCTAssertEqual(probe.attempts, 0, "changed events must never write mission data")
        XCTAssertEqual(store.waypoints, [original], "changed events must not publish mission data")
        XCTAssertEqual(preview.candidate.latitude, -33.3, accuracy: 1e-12)
        XCTAssertEqual(preview.candidate.longitude, 151.3, accuracy: 1e-12)

        XCTAssertTrue(try preview.commit(to: store))
        XCTAssertEqual(probe.attempts, 1, "the ended event commits one candidate document")
        XCTAssertEqual(store.waypoints, [preview.candidate])
    }

    func testDrawingDragFailureRetainsKnownGoodStoreAfterOneAttempt() throws {
        let probe = MutationWriteProbe()
        let store = DrawingStore(
            storageURL: temporaryStoreURL("drawings.json"),
            persistenceWriter: probe.write
        )
        let original = DrawingShape(
            kind: .polyline,
            coordinates: [
                .init(latitude: -33.0, longitude: 151.0),
                .init(latitude: -33.1, longitude: 151.1)
            ],
            layerID: DrawingLayer.legacyFallbackID
        )
        _ = try store.addDurably(original)
        probe.reset()

        var preview = DrawingGesturePreview(original)
        preview.translate(latitudeDelta: 0.1, longitudeDelta: 0.2)
        preview.translate(latitudeDelta: 0.3, longitudeDelta: 0.4)
        XCTAssertEqual(probe.attempts, 0)
        XCTAssertEqual(store.shapes, [original])

        probe.shouldFail = true
        XCTAssertThrowsError(try preview.commit(to: store))
        XCTAssertEqual(probe.attempts, 1)
        XCTAssertEqual(store.shapes, [original],
                       "a failed end-gesture write must retain the last durable drawing")
    }

    func testDrawingControlSlidersPreviewInMemoryAndCommitOnceAtGestureEnd() throws {
        let probe = MutationWriteProbe()
        let store = DrawingStore(
            storageURL: temporaryStoreURL("drawing-slider-controls.json"),
            persistenceWriter: probe.write
        )
        let original = DrawingShape(
            kind: .polygon,
            coordinates: [
                .init(latitude: -33.0, longitude: 151.0),
                .init(latitude: -33.0, longitude: 151.1),
                .init(latitude: -33.1, longitude: 151.1)
            ],
            layerID: DrawingLayer.legacyFallbackID
        )
        _ = try store.addDurably(original)
        probe.reset()

        var transaction = DrawingSliderTransaction()
        for tick in 1...20 {
            let value = Double(tick)
            transaction.update(from: original) { candidate in
                candidate.style.strokeWidth = 1 + value / 2
                candidate.style.setFillOpacity(value / 20)
                candidate.rotation = value
                candidate.scaleX = 1 + value / 20
                candidate.scaleY = 1 + value / 10
            }
        }

        let preview = try XCTUnwrap(transaction.candidate)
        XCTAssertEqual(probe.attempts, 0, "slider ticks must not write mission data")
        XCTAssertEqual(store.shapes, [original], "slider ticks must not publish mission data")
        XCTAssertEqual(preview.style.strokeWidth, 11, accuracy: 1e-12)
        XCTAssertEqual(preview.style.fillOpacity, 1, accuracy: 1e-12)
        XCTAssertEqual(preview.rotation, 20, accuracy: 1e-12)
        XCTAssertEqual(preview.scaleX, 2, accuracy: 1e-12)
        XCTAssertEqual(preview.scaleY, 3, accuracy: 1e-12)

        XCTAssertTrue(try transaction.finish {
            try store.commitEdit($0, actionName: "Adjust Drawing")
        })
        XCTAssertEqual(probe.attempts, 1, "one ended gesture performs one durable write")
        XCTAssertEqual(store.shapes, [preview])
        XCTAssertNil(transaction.candidate)

        probe.reset()
        probe.shouldFail = true
        var failed = DrawingSliderTransaction()
        failed.update(from: preview) { $0.rotation = 90 }
        failed.update(from: preview) { $0.scaleX = 4 }
        XCTAssertThrowsError(try failed.finish {
            try store.commitEdit($0, actionName: "Adjust Drawing")
        })
        XCTAssertEqual(probe.attempts, 1)
        XCTAssertEqual(store.shapes, [preview],
                       "failed end commit must retain the last known-good drawing")
        XCTAssertNil(failed.candidate, "failed commit must discard the transient candidate")
    }

    func testDrawingRendererPreviewReplacesOnlyCandidateWithoutStorePublication() {
        let first = DrawingShape(
            kind: .polyline,
            coordinates: [
                .init(latitude: -33.0, longitude: 151.0),
                .init(latitude: -33.1, longitude: 151.1)
            ]
        )
        let second = DrawingShape(
            kind: .polyline,
            coordinates: [
                .init(latitude: -34.0, longitude: 150.0),
                .init(latitude: -34.1, longitude: 150.1)
            ]
        )
        let vectors = [first, second].map {
            PDFVectorShape(sourceID: $0.id,
                           coords: $0.clEffectiveCoordinates,
                           isPolygon: false,
                           style: $0.style,
                           isSelected: false,
                           inProgress: false)
        }
        var preview = DrawingGesturePreview(first)
        preview.translate(latitudeDelta: 0.5, longitudeDelta: 0.25)

        let rendered = DrawingVectorShapes.replacingDrawingPreview(preview.candidate, in: vectors)
        XCTAssertEqual(rendered.count, 2)
        XCTAssertEqual(rendered[0].sourceID, first.id)
        XCTAssertEqual(rendered[0].coords[0].latitude, -32.5, accuracy: 1e-12)
        XCTAssertEqual(rendered[0].coords[0].longitude, 151.25, accuracy: 1e-12)
        XCTAssertEqual(rendered[1].sourceID, second.id)
        XCTAssertEqual(rendered[1].coords[0].latitude, -34.0, accuracy: 1e-12)
        XCTAssertEqual(rendered[1].coords[0].longitude, 150.0, accuracy: 1e-12)
    }

    func testPointDrawingPreviewMovesPinAndLabelInMemory() {
        let point = DrawingShape(
            name: "Observation post",
            kind: .point,
            coordinates: [.init(latitude: -33.0, longitude: 151.0)]
        )
        let durable = DrawingDecorationsOverlayView.Model(
            dots: [],
            labels: [.init(sourceID: point.id,
                           lat: -33.0, lon: 151.0, text: "Observation post")],
            pins: [.init(sourceID: point.id,
                         lat: -33.0, lon: 151.0, colorHex: point.style.strokeColorHex)]
        )
        var preview = DrawingGesturePreview(point)
        preview.translate(latitudeDelta: 0.25, longitudeDelta: -0.5)

        let rendered = durable.replacingDrawingPreview(preview.candidate)
        XCTAssertEqual(rendered.pins.first?.lat ?? 0, -32.75, accuracy: 1e-12)
        XCTAssertEqual(rendered.pins.first?.lon ?? 0, 150.5, accuracy: 1e-12)
        XCTAssertEqual(rendered.labels.first?.lat ?? 0, -32.75, accuracy: 1e-12)
        XCTAssertEqual(rendered.labels.first?.lon ?? 0, 150.5, accuracy: 1e-12)

        XCTAssertEqual(durable.pins.first?.lat ?? 0, -33.0, accuracy: 1e-12)
        XCTAssertEqual(durable.labels.first?.lon ?? 0, 151.0, accuracy: 1e-12)
    }

    func testEveryDrawingKindMovesRenderedAnchorToCrosshairWithoutChangingShape() throws {
        let target = CLLocationCoordinate2D(latitude: -33.86, longitude: 151.21)
        let fixtures = [
            DrawingShape(kind: .point,
                         coordinates: [.init(latitude: -34.0, longitude: 150.0)]),
            DrawingShape(kind: .polyline,
                         coordinates: [.init(latitude: -34.1, longitude: 150.0),
                                       .init(latitude: -33.9, longitude: 150.4)],
                         rotation: 35, scaleX: 1.5, scaleY: 0.75),
            DrawingShape(kind: .freedraw,
                         coordinates: [.init(latitude: -34.2, longitude: 150.0),
                                       .init(latitude: -34.0, longitude: 150.2),
                                       .init(latitude: -33.8, longitude: 150.5)]),
            DrawingShape(kind: .polygon,
                         coordinates: [.init(latitude: -34.1, longitude: 150.0),
                                       .init(latitude: -34.0, longitude: 150.4),
                                       .init(latitude: -33.8, longitude: 150.2)])
        ]

        for original in fixtures {
            let moved = original.moved(to: target)
            let anchor = try XCTUnwrap(moved.labelAnchor)
            XCTAssertEqual(anchor.latitude, target.latitude, accuracy: 1e-9)
            XCTAssertEqual(anchor.longitude, target.longitude, accuracy: 1e-9)
            XCTAssertEqual(moved.coordinates.count, original.coordinates.count)
            XCTAssertEqual(moved.rotation, original.rotation)
            XCTAssertEqual(moved.scaleX, original.scaleX)
            XCTAssertEqual(moved.scaleY, original.scaleY)
            if original.coordinates.count > 1 {
                XCTAssertEqual(
                    moved.coordinates[1].latitude - moved.coordinates[0].latitude,
                    original.coordinates[1].latitude - original.coordinates[0].latitude,
                    accuracy: 1e-9
                )
            }
        }
    }
}
