import SwiftUI
import MapKit

struct ContentView: View {
    @StateObject private var locationService = LocationService()
    @StateObject private var waypointStore   = WaypointStore()
    @StateObject private var drawingStore    = DrawingStore()
    @StateObject private var drawingSession  = DrawingSessionViewModel()
    @StateObject private var visibility      = LayerVisibility()
    @StateObject private var mapVM           = MapViewModel()
    @StateObject private var calibration     = CalibrationSession()

    @State private var showImporter      = false
    @State private var showWaypointSheet = false
    @State private var showDrawingsSheet = false   // "All Drawings" list
    @State private var showLayersSheet   = false
    @State private var showExportSheet   = false
    @State private var showSearchSheet   = false
    @State private var showAboutSheet    = false
    @State private var drawingsPanelOpen = false   // inline panel below hamburger

    var body: some View {
        GeometryReader { geo in
            ZStack {
                MapContainerView(
                    mapVM: mapVM,
                    locationService: locationService,
                    waypointStore: waypointStore,
                    drawingStore: drawingStore,
                    drawingSession: drawingSession,
                    visibility: visibility,
                    calibration: calibration
                )
                .ignoresSafeArea()

                // Tap-anywhere-else dismisses the drawings panel. Layered between
                // the map and the HUD so taps on HUD controls still work.
                if drawingsPanelOpen {
                    Color.black.opacity(0.001)
                        .ignoresSafeArea()
                        .contentShape(Rectangle())
                        .onTapGesture { drawingsPanelOpen = false }
                }

                // Crosshair: always visible (except while drawing — taps go
                // to vertex placement and the crosshair would compete with
                // the tap-target markers).
                if !drawingSession.isDrawing {
                    CrosshairOverlay().allowsHitTesting(false)
                }

                VStack(spacing: 0) {
                    MGRSHeaderView(
                        mgrs: mapVM.headerMGRS,
                        wgs84: mapVM.headerWGS84,
                        isBrowsing: mapVM.isBrowsing,
                        accuracy: locationService.lastAccuracy,
                        // Crosshair elevation: prefer the DEM lookup so panning
                        // around shows real terrain heights; fall back to the
                        // GPS-reported altitude only if the DEM hasn't replied yet.
                        elevation: mapVM.centreElevation ?? locationService.lastAltitude
                    )
                    .padding(.horizontal, 12)

                    // Hamburger (left) + compass (right).
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 8) {
                            HamburgerMenu(
                                onSearch:    {
                                    drawingsPanelOpen = false
                                    showSearchSheet = true
                                },
                                onWaypoints: {
                                    drawingsPanelOpen = false
                                    showWaypointSheet = true
                                },
                                onDrawings:  {
                                    showDrawingsSheet = false
                                    drawingsPanelOpen.toggle()
                                },
                                onLayers:    {
                                    drawingsPanelOpen = false
                                    showLayersSheet = true
                                },
                                onImport:    {
                                    drawingsPanelOpen = false
                                    showImporter = true
                                },
                                onExport:    {
                                    drawingsPanelOpen = false
                                    showExportSheet = true
                                },
                                onAbout:     {
                                    drawingsPanelOpen = false
                                    showAboutSheet = true
                                }
                            )

                            if drawingsPanelOpen {
                                DrawingsPanel(
                                    drawingStore: drawingStore,
                                    session: drawingSession,
                                    onShowAll: {
                                        drawingsPanelOpen = false
                                        showDrawingsSheet = true
                                    },
                                    onDismiss: { drawingsPanelOpen = false }
                                )
                                .transition(.move(edge: .top).combined(with: .opacity))
                            }
                        }
                        Spacer()
                        CompassChip(heading: mapVM.heading) { mapVM.resetNorth() }
                    }
                    .padding(.horizontal, 12)
                    .padding(.top, 10)
                    .animation(.easeInOut(duration: 0.18), value: drawingsPanelOpen)

                    Spacer(minLength: 0)

                    if calibration.isCalibrating {
                        CalibrationOverlay(
                            session: calibration,
                            onFinish: finishCalibration,
                            onCancel: { calibration.cancel() }
                        )
                        .padding(.horizontal, 12)
                        .padding(.bottom, max(geo.safeAreaInsets.bottom, 8) + 6)
                    } else if drawingSession.isDrawing {
                        DrawToolbar(session: drawingSession) {
                            if let shape = drawingSession.finish() {
                                drawingStore.add(shape)
                            }
                        }
                        .padding(.horizontal, 12)
                        .padding(.bottom, max(geo.safeAreaInsets.bottom, 8) + 6)
                    } else {
                        // Anchor the centre-on-location pill to the very
                        // bottom edge of the screen, sitting beside the
                        // "Legal" / Apple-Maps attribution logo. We offset
                        // past the safe area (the home-indicator gesture
                        // zone is still respected because the pill clears
                        // it horizontally — it's a tap target, not a swipe).
                        CentreButton {
                            mapVM.centreOnUser(locationService.lastLocation)
                        }
                        .offset(y: geo.safeAreaInsets.bottom - 4)
                    }
                }
                // HUD sits flush below the dynamic island. We let it bleed slightly
                // into the safe-area region by using a small negative-ish padding,
                // ignoring the safe area entirely on the top edge — the box's
                // rounded corners tuck under the island shape naturally.
                // Bottom inset is applied per-control above so the centre-on-
                // location pill can sit closer to the screen edge than the
                // calibration / draw toolbars (which want a full safe-area gap).
                .padding(.top, 4)
            }
        }
        .onAppear {
            locationService.requestAuthorisation()
            locationService.start()
        }
        .onReceive(locationService.$lastLocation.compactMap { $0 }) { loc in
            mapVM.userLocationDidUpdate(loc)
        }
        .sheet(isPresented: $showWaypointSheet) {
            WaypointListSheet(waypointStore: waypointStore, mapVM: mapVM)
        }
        .sheet(isPresented: $showDrawingsSheet) {
            DrawingsSheet(drawingStore: drawingStore, session: drawingSession)
        }
        .sheet(isPresented: $showLayersSheet) {
            LayersSheet(visibility: visibility, mapVM: mapVM, onCalibrate: startCalibration)
        }
        .sheet(isPresented: Binding(
            get: { calibration.pendingTap != nil },
            set: { if !$0 { calibration.clearPendingTap() } }
        )) {
            CalibrationInputSheet(
                session: calibration,
                onCancel: { calibration.clearPendingTap() },
                currentLocation: locationService.lastLocation?.coordinate
            )
        }
        .sheet(isPresented: $showExportSheet) {
            ExportSheet(waypointStore: waypointStore, drawingStore: drawingStore)
        }
        .sheet(isPresented: $showSearchSheet) {
            SearchSheet(mapVM: mapVM)
        }
        .sheet(isPresented: $showAboutSheet) {
            AcknowledgementsView()
        }
        .fileImporter(
            isPresented: $showImporter,
            allowedContentTypes: [.pdf],
            allowsMultipleSelection: false
        ) { result in
            handleImport(result)
        }
    }

    private func startCalibration() {
        guard let pdfSource = mapVM.mapSource as? PDFMapSource else { return }
        calibration.start(for: pdfSource)
    }

    private func finishCalibration() {
        guard let result = calibration.finish(),
              let source = calibration.source else { return }
        // Build a fresh source so MapContainerView rebuilds the overlay
        // (its sync logic keys on source.id).
        let newSource = PDFMapSource(url: source.url, bounds: nil, fromGeoPDF: false)
        newSource.applyCalibration(
            transform: result.transform,
            fiduciaries: calibration.fiduciaries
        )
        let bounds = newSource.bounds
        calibration.cancel()
        mapVM.mapSource = newSource
        if let b = bounds {
            let span = MKCoordinateSpan(
                latitudeDelta:  abs(b.northEast.latitude  - b.southWest.latitude)  * 1.2,
                longitudeDelta: abs(b.northEast.longitude - b.southWest.longitude) * 1.2
            )
            mapVM.cameraRequests.send(MKCoordinateRegion(center: b.centre, span: span))
        }
    }

    private func handleImport(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result, let url = urls.first else { return }

        // The file picker may hand us a security-scoped URL (file came from
        // outside the sandbox). Copy into Documents so we have stable access.
        let scoped = url.startAccessingSecurityScopedResource()
        let docsDir = FileManager.default.urls(
            for: .documentDirectory, in: .userDomainMask
        ).first!
        let dest = docsDir.appendingPathComponent(url.lastPathComponent)
        if !FileManager.default.fileExists(atPath: dest.path) {
            try? FileManager.default.copyItem(at: url, to: dest)
        }
        if scoped { url.stopAccessingSecurityScopedResource() }

        NSLog("[Import] picked \(url.lastPathComponent) -> dest=\(dest.path)")
        let cameraAtImport = mapVM.cameraCentre
        Task.detached(priority: .userInitiated) { [mapVM] in
            let parsed = GeoPDFReader.bounds(from: dest)
            NSLog("[Import] LGIDict/known-sheet parse: \(String(describing: parsed))")
            let resolvedBounds = parsed ?? GeoPDFReader.fallbackBounds(centeredOn: cameraAtImport)
            NSLog("[Import] resolved bounds SW=\(resolvedBounds.southWest.latitude),\(resolvedBounds.southWest.longitude) NE=\(resolvedBounds.northEast.latitude),\(resolvedBounds.northEast.longitude)")
            let fromGeoPDF = (parsed != nil)

            await MainActor.run {
                NSLog("[Import] installing PDFMapSource on MainActor")
                mapVM.mapSource = PDFMapSource(
                    url: dest,
                    bounds: resolvedBounds,
                    fromGeoPDF: fromGeoPDF
                )
                let span = MKCoordinateSpan(
                    latitudeDelta:  abs(resolvedBounds.northEast.latitude  - resolvedBounds.southWest.latitude)  * 1.25,
                    longitudeDelta: abs(resolvedBounds.northEast.longitude - resolvedBounds.southWest.longitude) * 1.25
                )
                mapVM.cameraRequests.send(
                    MKCoordinateRegion(center: resolvedBounds.centre, span: span)
                )
            }
        }
    }
}

#Preview {
    ContentView()
        .preferredColorScheme(.dark)
}
