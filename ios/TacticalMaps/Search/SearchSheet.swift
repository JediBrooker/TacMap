import SwiftUI
import MapKit
import CoreLocation

/// Search by:
///   • Place name / address / POI — MKLocalSearch
///   • Full MGRS grid reference  — e.g. `56HLH 13225 37516` (spaces optional)
///   • **Partial** grid reference — 4, 6, 8, or 10 digits. Resolved against
///     the user’s current GZD + 100km-square prefix (whatever the map centre
///     is sitting in). E.g. at Holsworthy, typing `1885` lands on the centre
///     of grid square 56HLH 18 85 (≈1 km square).
struct SearchSheet: View {
    @ObservedObject var mapVM: MapViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var query: String = ""
    @State private var places: [SearchResult] = []
    @State private var inferredCoordinates: [SearchResult] = []
    @State private var isSearching: Bool = false
    @State private var statusMessage: String? = nil

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                searchField
                    .padding(.horizontal)
                    .padding(.top, 6)
                    .padding(.bottom, 4)

                Text("Place name, address, full MGRS, or 4/6/8/10-figure grid")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal)
                    .padding(.bottom, 6)

                resultsList
            }
            .navigationTitle("Search")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
            // SwiftUI built-in debouncer: re-runs the task only when `query`
            // changes, auto-cancels the previous fire. No timers, no Tasks,
            // no @State race conditions — fixes the v10 crash.
            .task(id: query) {
                await runSearch(for: query)
            }
        }
    }

    // MARK: - Sub-views

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
            TextField("Search", text: $query)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
            if !query.isEmpty {
                Button {
                    query = ""
                    places = []
                    inferredCoordinates = []
                    statusMessage = nil
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(8)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 10))
    }

    @ViewBuilder
    private var resultsList: some View {
        List {
            if !inferredCoordinates.isEmpty {
                Section("Grid Reference") {
                    ForEach(inferredCoordinates) { row($0) }
                }
            }
            if isSearching {
                Section { HStack { ProgressView(); Text("Searching…").foregroundStyle(.secondary) } }
            }
            if !places.isEmpty {
                Section("Places") {
                    ForEach(places) { row($0) }
                }
            }
            if let msg = statusMessage,
               places.isEmpty,
               inferredCoordinates.isEmpty,
               !isSearching {
                Section { Text(msg).foregroundStyle(.secondary) }
            }
        }
        .listStyle(.insetGrouped)
    }

    @ViewBuilder
    private func row(_ result: SearchResult) -> some View {
        Button {
            flyTo(result.coordinate)
            dismiss()
        } label: {
            HStack(spacing: 12) {
                Image(systemName: result.kind == .mgrs ? "scope" : "mappin.circle.fill")
                    .foregroundStyle(result.kind == .mgrs ? Color.green : Color.blue)
                    .font(.title3)
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text(result.title)
                        .foregroundStyle(.primary)
                        .font(.callout.weight(.semibold))
                    if !result.subtitle.isEmpty {
                        Text(result.subtitle)
                            .foregroundStyle(.secondary)
                            .font(.caption)
                    }
                }
                Spacer()
                Text(MGRSFormatter.string(from: result.coordinate))
                    .font(.caption2.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
            }
        }
    }

    // MARK: - Search pipeline

    /// Single entry point invoked by `.task(id: query)`. Runs MGRS detection
    /// synchronously then a debounced MKLocalSearch.
    private func runSearch(for raw: String) async {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)

        // Recompute MGRS interpretations immediately on every keystroke.
        let coordResults = inferCoordinateResults(from: trimmed)
        await MainActor.run {
            inferredCoordinates = coordResults
            statusMessage = nil
            if trimmed.isEmpty { places = [] }
        }

        guard trimmed.count >= 2 else {
            await MainActor.run { isSearching = false }
            return
        }

        // Debounce 350ms before hitting the network. .task(id:) cancels this
        // automatically when the user keeps typing.
        do {
            try await Task.sleep(nanoseconds: 350_000_000)
        } catch {
            return  // cancelled
        }
        if Task.isCancelled { return }

        await runPlaceSearch(for: trimmed)
    }

    private func runPlaceSearch(for trimmed: String) async {
        await MainActor.run { isSearching = true }

        let req = MKLocalSearch.Request()
        req.naturalLanguageQuery = trimmed
        let centre = mapVM.cameraCentre
        if centre.latitude != 0 || centre.longitude != 0 {
            req.region = MKCoordinateRegion(
                center: centre,
                latitudinalMeters: 200_000,
                longitudinalMeters: 200_000
            )
        }
        req.resultTypes = [.pointOfInterest, .address]

        do {
            let response = try await MKLocalSearch(request: req).start()
            if Task.isCancelled { return }
            let mapped = response.mapItems.prefix(20).map { item -> SearchResult in
                SearchResult(
                    title: item.name ?? "Unknown",
                    subtitle: addressLine(item),
                    coordinate: item.placemark.coordinate,
                    kind: .place
                )
            }
            await MainActor.run {
                places = Array(mapped)
                isSearching = false
                if places.isEmpty && inferredCoordinates.isEmpty {
                    statusMessage = "No matches for \u{201C}\(trimmed)\u{201D}."
                }
            }
        } catch {
            await MainActor.run {
                isSearching = false
                places = []
                let ns = error as NSError
                // MKError.unknown (-1) for cancelled or no-results — stay silent.
                if ns.domain != MKError.errorDomain || ns.code > 0 {
                    statusMessage = "Search failed: \(error.localizedDescription)"
                }
            }
        }
    }

    // MARK: - MGRS interpretation

    /// Return ordered candidate coordinates for the query: a full-MGRS parse
    /// plus any partial-grid interpretation. Most-specific results first.
    private func inferCoordinateResults(from raw: String) -> [SearchResult] {
        guard !raw.isEmpty else { return [] }
        var out: [SearchResult] = []

        // 1) Full MGRS — needs the GZD prefix to parse.
        let compact = raw.uppercased().filter { !$0.isWhitespace }
        if let coord = MGRSFormatter.coordinate(from: compact) {
            out.append(SearchResult(
                title:    MGRSFormatter.formatted(compact),
                subtitle: latLonString(coord),
                coordinate: coord,
                kind: .mgrs
            ))
        }

        // 2) Partial grid: 4 / 6 / 8 / 10 digits, resolved against the camera centre.
        if let partial = partialGridResult(raw) {
            out.append(partial)
        }

        return out
    }

    /// Type just the digits (e.g. "1885" or "188 850") and we synthesize a
    /// full MGRS by prefixing the current camera’s GZD + 100km square ID,
    /// then return the *centre* of the implied square (1km/100m/10m/1m).
    private func partialGridResult(_ raw: String) -> SearchResult? {
        let digits = raw.filter { $0.isNumber }
        guard [4, 6, 8, 10].contains(digits.count) else { return nil }

        let anchor = mapVM.cameraCentre
        guard anchor.latitude != 0 || anchor.longitude != 0 else { return nil }

        // "56HLH" / "9VCD" — the GZD letters + 100km square.
        let fullMGRS = MGRSFormatter.string(from: anchor, spaced: false)
        guard let prefix = extractGZDPrefix(fullMGRS) else { return nil }

        let half = digits.count / 2
        let easting  = String(digits.prefix(half))
        let northing = String(digits.suffix(half))
        let synthesized = "\(prefix)\(easting)\(northing)"

        guard let sw = MGRSFormatter.coordinate(from: synthesized) else { return nil }
        let centre = centreOfSquare(sw: sw, eastNorthDigits: half)

        let squareSize: String = {
            switch half {
            case 2: return "1 km"
            case 3: return "100 m"
            case 4: return "10 m"
            case 5: return "1 m"
            default: return ""
            }
        }()
        return SearchResult(
            title:    "\(prefix) \(easting) \(northing)",
            subtitle: "Centre of \(squareSize) grid square (relative to \(prefix))",
            coordinate: centre,
            kind: .mgrs
        )
    }

    private func extractGZDPrefix(_ mgrs: String) -> String? {
        // UTM zones: <1–2 digits><band letter><2 square letters>
        let utm = #"^(\d{1,2}[A-Z][A-Z]{2})"#
        // UPS polar: <A|B|Y|Z><2 square letters>
        let ups = #"^([ABYZ][A-Z]{2})"#
        for p in [utm, ups] {
            if let rx = try? NSRegularExpression(pattern: p),
               let m = rx.firstMatch(in: mgrs, range: NSRange(mgrs.startIndex..., in: mgrs)),
               let r = Range(m.range(at: 1), in: mgrs) {
                return String(mgrs[r])
            }
        }
        return nil
    }

    /// MGRS coords decode to the south-west corner of their precision square.
    /// Bump by half a square in the local metric frame so the result lands at
    /// the visual middle of the grid square — that’s the conventional
    /// “grid reference points to here” behaviour for navigation.
    private func centreOfSquare(sw: CLLocationCoordinate2D, eastNorthDigits half: Int) -> CLLocationCoordinate2D {
        let halfMetres: Double = {
            switch half {
            case 2: return 500    // half of 1 km
            case 3: return 50     // half of 100 m
            case 4: return 5      // half of 10 m
            case 5: return 0.5    // half of 1 m
            default: return 0
            }
        }()
        let dLat = halfMetres / 111_320.0
        let dLon = halfMetres / (111_320.0 * max(0.01, cos(sw.latitude * .pi / 180)))
        return CLLocationCoordinate2D(
            latitude:  sw.latitude  + dLat,
            longitude: sw.longitude + dLon
        )
    }

    private func addressLine(_ item: MKMapItem) -> String {
        let p = item.placemark
        let parts = [p.thoroughfare, p.locality, p.administrativeArea, p.country].compactMap { $0 }
        return parts.joined(separator: ", ")
    }

    private func latLonString(_ c: CLLocationCoordinate2D) -> String {
        String(format: "%.5f° %@, %.5f° %@",
               abs(c.latitude),  c.latitude  >= 0 ? "N" : "S",
               abs(c.longitude), c.longitude >= 0 ? "E" : "W")
    }

    private func flyTo(_ coord: CLLocationCoordinate2D) {
        let region = MKCoordinateRegion(
            center: coord,
            latitudinalMeters: 2500,
            longitudinalMeters: 2500
        )
        mapVM.cameraRequests.send(region)
    }
}

/// Search result row model.
struct SearchResult: Identifiable, Hashable {
    enum Kind: Hashable { case mgrs, place }
    let id: UUID = UUID()
    let title: String
    let subtitle: String
    let coordinate: CLLocationCoordinate2D
    let kind: Kind

    static func == (l: SearchResult, r: SearchResult) -> Bool { l.id == r.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}
