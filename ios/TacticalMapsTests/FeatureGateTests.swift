import XCTest
@testable import TacticalMaps

/// 3.1 free viewer: what the viewer gets, what opens the paywall, and the
/// basemap substitution that keeps the Esri pick without drawing it.
final class FeatureGateTests: XCTestCase {

    override func tearDown() {
        // shared flag, put it back so the rest of the suite sees today's default
        FeatureAccess.shared.isFullVersion = true
        super.tearDown()
    }

    // MARK: - feature list

    private let freeFeatures: [FeatureGate.Feature] = [
        .importMaps, .importedMapLibrary, .layerVisibility, .openTopoMap, .liveLocation,
        .heading, .grid, .copyCoordinates, .elevationHeader, .search, .nightMode,
        .unitLabels, .undoRedo, .appLock, .settings, .about, .stopTrackRecording,
    ]

    private let lockedFeatures: [FeatureGate.Feature] = [
        .symbology, .drawings, .measure, .weather, .importGeoJSON, .importKML,
        .exportGeoJSON, .exportKML, .exportKMZ, .exportGPX, .exportAll,
        .startTrackRecording, .chat, .unitSync, .esriBasemaps,
    ]

    func testFullVersionAllowsEverything() {
        for feature in FeatureGate.Feature.allCases {
            XCTAssertTrue(FeatureGate.allows(feature, fullVersion: true), "\(feature) must be open in the full version")
        }
    }

    func testViewerKeepsTheFreeFeatures() {
        for feature in freeFeatures {
            XCTAssertTrue(FeatureGate.allows(feature, fullVersion: false), "\(feature) is free for good")
        }
    }

    func testViewerLocksTheUnlockFeatures() {
        for feature in lockedFeatures {
            XCTAssertFalse(FeatureGate.allows(feature, fullVersion: false), "\(feature) is part of the unlock")
        }
    }

    func testEveryFeatureIsClassifiedExactlyOnce() {
        // a new case has to be decided, not silently fall into one bucket
        let classified = Set(freeFeatures).union(lockedFeatures)
        XCTAssertEqual(classified, Set(FeatureGate.Feature.allCases))
        XCTAssertEqual(freeFeatures.count + lockedFeatures.count, FeatureGate.Feature.allCases.count)
        XCTAssertEqual(FeatureGate.locked, Set(lockedFeatures))
    }

    func testEveryTestStartsAsFullVersion() {
        // TestBootstrap (the bundle's principal class) pins this before each case,
        // whatever the host app's RootGate decided from the sim's trial
        XCTAssertTrue(FeatureAccess.shared.isFullVersion)
    }

    // MARK: - track recording row

    private var interruptedMessage: LocalizedMessage { Messages.trialExpiredDetailsMessage("3") }

    func testStartingARecordingIsLockedStoppingIsNot() {
        XCTAssertTrue(FeatureGate.trackRecordingRowLocked(state: .idle, fullVersion: false))
        XCTAssertTrue(FeatureGate.trackRecordingRowLocked(state: .interrupted(interruptedMessage), fullVersion: false), "retry is a start")
        XCTAssertFalse(FeatureGate.trackRecordingRowLocked(state: .recording, fullVersion: false))
        XCTAssertFalse(FeatureGate.trackRecordingRowLocked(state: .starting, fullVersion: false))
        XCTAssertFalse(FeatureGate.trackRecordingRowLocked(state: .awaitingPermission, fullVersion: false))
        for state in [RecordingCoordinator.State.idle, .interrupted(interruptedMessage), .recording, .starting, .awaitingPermission] {
            XCTAssertFalse(FeatureGate.trackRecordingRowLocked(state: state, fullVersion: true))
        }
    }

    // MARK: - basemap substitution

    func testOpenTopoMapIsNeverSubstituted() {
        for full in [true, false] {
            for keyed in [true, false] {
                XCTAssertEqual(FeatureGate.displayStyle(for: .osmTopo, fullVersion: full, esriKeyAvailable: keyed), .osmTopo)
            }
        }
    }

    func testFullVersionWithKeyDrawsThePick() {
        for style in BasemapStyle.allCases {
            XCTAssertEqual(FeatureGate.displayStyle(for: style, fullVersion: true, esriKeyAvailable: true), style)
        }
    }

    func testViewerDrawsOpenTopoMapOverEveryEsriPick() {
        for style in BasemapStyle.allCases where style.requiresEsriKey {
            XCTAssertEqual(FeatureGate.displayStyle(for: style, fullVersion: false, esriKeyAvailable: true), .osmTopo, "\(style)")
            XCTAssertEqual(FeatureGate.displayStyle(for: style, fullVersion: false, esriKeyAvailable: false), .osmTopo, "\(style)")
        }
    }

    func testKeylessBuildFallsBackEvenWhenFull() {
        for style in BasemapStyle.allCases where style.requiresEsriKey {
            XCTAssertEqual(FeatureGate.displayStyle(for: style, fullVersion: true, esriKeyAvailable: false), .osmTopo, "\(style)")
        }
    }

    // MARK: - MapViewModel keeps the pick, draws the fallback

    /// what the full version would draw for an Esri pick on this build
    private var esriOrFallback: BasemapStyle { EsriKey.isAvailable ? .esriSatellite : .osmTopo }

    func testViewerSelectionStoresEsriButDrawsOpenTopoMap() {
        FeatureAccess.shared.isFullVersion = false
        let vm = MapViewModel(libraryDependencies: PDFRenderGuardStoreTests.inMemoryLibrary(LibraryState(), fileURL: { _ in nil }),
                              initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        _ = vm.restoreActiveMapSelection()
        XCTAssertTrue(vm.selectOnlineBasemap(.esriSatellite))
        // the durable pick is the user's, untouched by the viewer
        XCTAssertEqual(vm.library?.preferredStyle, BasemapStyle.esriSatellite)
        XCTAssertEqual(vm.library?.active, MapSelection.online(.esriSatellite))
        // the tiles on screen are the free ones
        XCTAssertEqual((vm.mapSource as? OnlineRasterBasemapSource)?.style, BasemapStyle.osmTopo)
        XCTAssertEqual(vm.preferredOnlineBasemap().style, .osmTopo)

        // unlock lands: the pick comes straight back, no durable write needed
        FeatureAccess.shared.isFullVersion = true
        vm.refreshOnlineBasemapForEntitlement()
        XCTAssertEqual((vm.mapSource as? OnlineRasterBasemapSource)?.style, esriOrFallback)
        XCTAssertEqual(vm.library?.preferredStyle, BasemapStyle.esriSatellite)

        // and the trial lapsing takes it away again
        FeatureAccess.shared.isFullVersion = false
        vm.refreshOnlineBasemapForEntitlement()
        XCTAssertEqual((vm.mapSource as? OnlineRasterBasemapSource)?.style, BasemapStyle.osmTopo)
        XCTAssertEqual(vm.library?.preferredStyle, BasemapStyle.esriSatellite)
    }

    func testViewerRestoreOfAStoredEsriPickDrawsOpenTopoMap() {
        FeatureAccess.shared.isFullVersion = false
        var state = LibraryState()
        state.active = .online(.esriTopo)
        state.preferredOnlineStyle = BasemapStyle.esriTopo.rawValue
        let vm = MapViewModel(libraryDependencies: PDFRenderGuardStoreTests.inMemoryLibrary(state, fileURL: { _ in nil }),
                              initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        XCTAssertEqual(vm.restoreActiveMapSelection(), .restored)
        XCTAssertEqual((vm.mapSource as? OnlineRasterBasemapSource)?.style, BasemapStyle.osmTopo)
        XCTAssertEqual(vm.library?.active, MapSelection.online(.esriTopo), "restore never rewrites the pick")

        FeatureAccess.shared.isFullVersion = true
        vm.refreshOnlineBasemapForEntitlement()
        XCTAssertEqual((vm.mapSource as? OnlineRasterBasemapSource)?.style, EsriKey.isAvailable ? BasemapStyle.esriTopo : .osmTopo)
    }

    func testViewerInitialDefaultIsDrawnAsOpenTopoMap() {
        FeatureAccess.shared.isFullVersion = false
        let vm = MapViewModel(libraryDependencies: PDFRenderGuardStoreTests.noLibrary())
        XCTAssertEqual((vm.mapSource as? OnlineRasterBasemapSource)?.style, BasemapStyle.osmTopo)
        // unlock: back to the product default for this build
        FeatureAccess.shared.isFullVersion = true
        vm.refreshOnlineBasemapForEntitlement()
        XCTAssertEqual((vm.mapSource as? OnlineRasterBasemapSource)?.style, OnlineRasterBasemapSource.defaultStyle)
    }

    func testEntitlementRefreshLeavesAnImportedMapAlone() throws {
        let src = try XCTUnwrap(PDFTileRenderFixtureTests.testdataURL("geopdf/tacmap_grid_sf_iso.pdf"))
        let g = try XCTUnwrap(GeoPDFReader.read(url: src)?.georef)
        let vm = MapViewModel(libraryDependencies: PDFRenderGuardStoreTests.noLibrary(),
                              initialMapSource: PDFMapSource(url: src, georef: g))
        FeatureAccess.shared.isFullVersion = false
        vm.refreshOnlineBasemapForEntitlement()
        XCTAssertTrue(vm.mapSource is PDFMapSource)
    }

    func testFullVersionBehaviourIsUnchanged() {
        // today's path: select Esri, see Esri (or the keyless fallback)
        let vm = MapViewModel(libraryDependencies: PDFRenderGuardStoreTests.inMemoryLibrary(LibraryState(), fileURL: { _ in nil }),
                              initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        _ = vm.restoreActiveMapSelection()
        XCTAssertTrue(vm.selectOnlineBasemap(.esriSatellite))
        XCTAssertEqual((vm.mapSource as? OnlineRasterBasemapSource)?.style, esriOrFallback)
        XCTAssertEqual(vm.library?.preferredStyle, BasemapStyle.esriSatellite)
        vm.refreshOnlineBasemapForEntitlement()
        XCTAssertEqual((vm.mapSource as? OnlineRasterBasemapSource)?.style, esriOrFallback, "no-op when nothing changed")
    }
}
