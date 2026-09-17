import SwiftUI
import MapKit
import CoreLocation

struct OfflineSearchRecord {
    enum Target {
        case waypoint(UUID)
        case drawing(UUID)
    }

    let id: String
    let target: Target
    let name: String
    let notes: String?
    let typeTerms: [String]
    let layerName: String
    let coordinate: CLLocationCoordinate2D
    let createdOrder: Int64
}

struct SearchResult: Identifiable {
    enum Kind {
        case mgrs
        case partialMGRS
        case latitudeLongitude
        case waypoint
        case drawing
        case place
    }

    enum Target {
        case coordinate
        case waypoint(UUID)
        case drawing(UUID)
    }

    let id: String
    let title: String
    let subtitle: String
    let coordinate: CLLocationCoordinate2D
    let kind: Kind
    let target: Target
}

struct OfflineSearchOutput {
    let results: [SearchResult]
    let pendingStatusMessage: LocalizedMessage?
    var statusMessage: String? { pendingStatusMessage?.text }
    /// Coordinate-shaped input is fully handled on-device, including range
    /// errors. The online place-search coordinator uses this production value
    /// to guarantee that coordinate text never reaches MapKit's provider.
    let recognizedCoordinateInput: Bool

    init(results: [SearchResult],
         statusMessage: LocalizedMessage?,
         recognizedCoordinateInput: Bool = false) {
        self.results = results
        self.pendingStatusMessage = statusMessage
        self.recognizedCoordinateInput = recognizedCoordinateInput
    }
}

enum OnlinePlaceLookupDecision: Equatable {
    case skipShortOrBlank
    case skipCoordinate
    case disabled
    case requestProvider
}

struct OnlinePlaceLookupOutcome {
    let results: [SearchResult]
    let pendingStatusMessage: LocalizedMessage?
    var statusMessage: String? { pendingStatusMessage?.text }
    init(results: [SearchResult], statusMessage: LocalizedMessage?) {
        self.results = results
        self.pendingStatusMessage = statusMessage
    }

}

/// The one production decision point between offline search and MapKit. Tests
/// inject a provider spy through `perform`, so the no-egress guarantee covers
/// the same orchestration path the SwiftUI sheet uses rather than fixture data.
enum OnlinePlaceLookup {
    static var disabledStatus: String { disabledStatusMessage.text }
    static var disabledStatusMessage: LocalizedMessage {
        Messages.displayPlaceNameSearchIsOffEnableOnlineLookupsInMessage()
    }
    static var unavailableStatus: String { unavailableStatusMessage.text }
    static var unavailableStatusMessage: LocalizedMessage {
        Messages.displayPlaceSearchUnavailableOfflineMgrsGridAndLatLonMessage()
    }

    static func decision(rawQuery: String,
                         offlineOutput: OfflineSearchOutput,
                         onlineLookups: Bool) -> OnlinePlaceLookupDecision {
        let query = rawQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard query.count >= 2 else { return .skipShortOrBlank }
        guard !offlineOutput.recognizedCoordinateInput else { return .skipCoordinate }
        guard onlineLookups else { return .disabled }
        return .requestProvider
    }

    static func perform(
        rawQuery: String,
        offlineOutput: OfflineSearchOutput,
        onlineLookups: Bool,
        provider: () async throws -> [SearchResult]
    ) async throws -> OnlinePlaceLookupOutcome {
        switch decision(rawQuery: rawQuery,
                        offlineOutput: offlineOutput,
                        onlineLookups: onlineLookups) {
        case .skipShortOrBlank, .skipCoordinate:
            return OnlinePlaceLookupOutcome(results: [], statusMessage: nil)
        case .disabled:
            return OnlinePlaceLookupOutcome(results: [], statusMessage: disabledStatusMessage)
        case .requestProvider:
            do {
                let results = try await provider()
                let trimmed = rawQuery.trimmingCharacters(in: .whitespacesAndNewlines)
                let status = results.isEmpty && offlineOutput.results.isEmpty
                    ? Messages.displayNoMatchesForMessage(trimmed)
                    : nil
                return OnlinePlaceLookupOutcome(results: results, statusMessage: status)
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                return OnlinePlaceLookupOutcome(results: [], statusMessage: unavailableStatusMessage)
            }
        }
    }
}

/// Pure, deterministic, offline-only mission search. Online providers are
/// deliberately absent from this type and are appended by SearchSheet.
enum OfflineSearchEngine {
    static var coordinateRangeMessage: String { coordinateRangeMessageMessage.text }
    static var coordinateRangeMessageMessage: LocalizedMessage {
        Messages.displayLatitudeMustBeBetweenAndAndLongitudeBetweenAndMessage()
    }

    static func records(waypoints: [Waypoint],
                        drawings: [DrawingShape],
                        layers: [DrawingLayer]) -> [OfflineSearchRecord] {
        let layerNames = Dictionary(uniqueKeysWithValues: layers.map { ($0.id, $0.displayName) })
        let waypointRecords = waypoints.map { waypoint in
            OfflineSearchRecord(
                id: "waypoint:\(waypoint.id.uuidString.lowercased())",
                target: .waypoint(waypoint.id),
                name: waypoint.name,
                notes: waypoint.notes,
                typeTerms: [waypoint.kind.displayName, waypoint.kind.categoryDisplayName],
                layerName: layerNames[waypoint.layerID] ?? "",
                coordinate: waypoint.coordinate,
                createdOrder: order(for: waypoint.createdAt)
            )
        }
        let drawingRecords = drawings.compactMap { drawing -> OfflineSearchRecord? in
            guard let coordinate = drawing.labelAnchor else { return nil }
            return OfflineSearchRecord(
                id: "drawing:\(drawing.id.uuidString.lowercased())",
                target: .drawing(drawing.id),
                name: drawing.name ?? drawing.kind.displayName,
                notes: drawing.notes,
                typeTerms: [drawing.kind.displayName],
                layerName: layerNames[drawing.layerID] ?? "",
                coordinate: coordinate,
                createdOrder: order(for: drawing.createdAt)
            )
        }
        return waypointRecords + drawingRecords
    }

    static func search(_ rawQuery: String,
                       anchor: CLLocationCoordinate2D,
                       records: [OfflineSearchRecord]) -> OfflineSearchOutput {
        let query = rawQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else {
            let recent = records.sorted {
                if $0.createdOrder != $1.createdOrder { return $0.createdOrder > $1.createdOrder }
                return $0.id < $1.id
            }.prefix(8).map { result(for: $0) }
            return OfflineSearchOutput(results: recent, statusMessage: nil)
        }

        let compact = query.uppercased().filter { !$0.isWhitespace }

        if compact.rangeOfCharacter(from: .letters) != nil,
           let resolved = try? MGRSFormatter.resolveGridReference(compact, relativeTo: anchor) {
            let result = SearchResult(
                id: "coordinate:mgrs",
                title: resolved.formattedReference,
                subtitle: latLonString(resolved.coordinate),
                coordinate: resolved.coordinate,
                kind: .mgrs,
                target: .coordinate
            )
            return OfflineSearchOutput(
                results: [result], statusMessage: nil, recognizedCoordinateInput: true)
        } else if compact.rangeOfCharacter(from: .letters) != nil,
                  let coordinate = MGRSFormatter.coordinate(from: compact) {
            let result = SearchResult(
                id: "coordinate:mgrs",
                title: MGRSFormatter.formatted(compact),
                subtitle: latLonString(coordinate),
                coordinate: coordinate,
                kind: .mgrs,
                target: .coordinate
            )
            return OfflineSearchOutput(
                results: [result], statusMessage: nil, recognizedCoordinateInput: true)
        } else if let partial = partialMGRSResult(query, anchor: anchor) {
            return OfflineSearchOutput(
                results: [partial], statusMessage: nil, recognizedCoordinateInput: true)
        } else {
            switch decimalCoordinate(query) {
            case .valid(let coordinate):
                let result = SearchResult(
                    id: "coordinate:lat-lon",
                    title: L10n.text("Latitude / Longitude"),
                    subtitle: String(format: "%.5f, %.5f", coordinate.latitude, coordinate.longitude),
                    coordinate: coordinate,
                    kind: .latitudeLongitude,
                    target: .coordinate
                )
                return OfflineSearchOutput(
                    results: [result], statusMessage: nil, recognizedCoordinateInput: true)
            case .outOfRange:
                return OfflineSearchOutput(
                    results: [], statusMessage: coordinateRangeMessageMessage,
                    recognizedCoordinateInput: true)
            case .notCoordinate:
                break
            }
        }

        let needle = normalized(query)
        let ranked = records.compactMap { record -> (Int, OfflineSearchRecord)? in
            guard let rank = rank(record, needle: needle) else { return nil }
            return (rank, record)
        }.sorted {
            if $0.0 != $1.0 { return $0.0 < $1.0 }
            if $0.1.createdOrder != $1.1.createdOrder {
                return $0.1.createdOrder < $1.1.createdOrder
            }
            return $0.1.id < $1.1.id
        }

        let localResults = ranked.map { _, record in result(for: record) }

        return OfflineSearchOutput(
            results: Array(localResults.prefix(20)),
            statusMessage: nil,
            recognizedCoordinateInput: looksCoordinateShaped(query)
        )
    }

    private static func order(for date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 * 1_000).rounded())
    }

    private static func result(for record: OfflineSearchRecord) -> SearchResult {
        switch record.target {
        case .waypoint(let id):
            return SearchResult(
                id: record.id,
                title: record.name,
                subtitle: record.typeTerms.first ?? L10n.text("Waypoint"),
                coordinate: record.coordinate,
                kind: .waypoint,
                target: .waypoint(id)
            )
        case .drawing(let id):
            return SearchResult(
                id: record.id,
                title: record.name,
                subtitle: record.typeTerms.first ?? L10n.text("Drawing"),
                coordinate: record.coordinate,
                kind: .drawing,
                target: .drawing(id)
            )
        }
    }

    private static func normalized(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines)
            .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
            .lowercased()
    }

    private static func rank(_ record: OfflineSearchRecord, needle: String) -> Int? {
        let name = normalized(record.name)
        if name == needle { return 0 }
        if name.hasPrefix(needle) { return 1 }
        if name.contains(needle) { return 2 }
        if normalized(record.notes ?? "").contains(needle) { return 3 }
        if record.typeTerms.contains(where: { normalized($0).contains(needle) }) { return 4 }
        if normalized(record.layerName).contains(needle) { return 5 }
        return nil
    }

    private enum DecimalCoordinate {
        case valid(CLLocationCoordinate2D)
        case outOfRange
        case notCoordinate
    }

    private static func decimalCoordinate(_ raw: String) -> DecimalCoordinate {
        let pattern = #"^\s*([-+]?\d+(?:\.\d+)?)\s*(?:,|\s)\s*([-+]?\d+(?:\.\d+)?)\s*$"#
        guard let expression = try? NSRegularExpression(pattern: pattern),
              let match = expression.firstMatch(
                in: raw,
                range: NSRange(raw.startIndex..., in: raw)
              ),
              let latitudeRange = Range(match.range(at: 1), in: raw),
              let longitudeRange = Range(match.range(at: 2), in: raw),
              let latitude = Double(raw[latitudeRange]),
              let longitude = Double(raw[longitudeRange]),
              latitude.isFinite, longitude.isFinite else {
            return .notCoordinate
        }
        guard (-90...90).contains(latitude), (-180...180).contains(longitude) else {
            return .outOfRange
        }
        return .valid(CLLocationCoordinate2D(latitude: latitude, longitude: longitude))
    }

    private static func partialMGRSResult(_ raw: String,
                                          anchor: CLLocationCoordinate2D) -> SearchResult? {
        let components = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .split(whereSeparator: { $0.isWhitespace })
        guard (1...2).contains(components.count),
              components.allSatisfy({ part in
                  part.unicodeScalars.allSatisfy { $0.value >= 48 && $0.value <= 57 }
              }),
              components.count != 2 || components[0].count == components[1].count else { return nil }
        let digits = components.joined()
        guard let resolved = try? MGRSFormatter.resolveGridReference(
            digits, relativeTo: anchor) else { return nil }
        let size = resolved.squareSizeMetres >= 1_000
            ? "\(resolved.squareSizeMetres / 1_000) km"
            : "\(resolved.squareSizeMetres) m"
        return SearchResult(
            id: "coordinate:partial-mgrs",
            title: resolved.formattedReference,
            subtitle: L10n.text("Centre of %1$@ grid square (relative to local grid)", size),
            coordinate: resolved.coordinate,
            kind: .partialMGRS,
            target: .coordinate
        )
    }

    /// Conservative privacy classifier for malformed coordinate input. Valid
    /// coordinates return earlier; this catches numeric/grid-shaped typos so
    /// they still cannot fall through to an online place provider. Requiring a
    /// coordinate-shaped prefix avoids the former `Route 1885` false positive.
    private static func looksCoordinateShaped(_ raw: String) -> Bool {
        let query = raw.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !query.isEmpty else { return false }

        // Once a query starts with a complete MGRS/UPS grid-zone + square
        // family, keep even malformed trailing tokens offline. For example,
        // `56HLH NORTH` is clearly intended as a coordinate correction, not a
        // place-name request. Spacing within the prefix is accepted so pasted
        // radio traffic such as `56 H L H NORTH` receives the same treatment.
        // UPS has no numeric zone, so require a boundary after its band +
        // two-letter square before accepting a malformed tail. Without that
        // boundary, ordinary words such as `ALPHA` look like `ALP` + `HA`.
        let mgrsPattern = #"^(?:\d{1,2}\s*[C-HJ-NP-X]\s*[A-HJ-NP-Z]\s*[A-HJ-NP-Z][\sA-Z0-9+\-]*|[ABYZ]\s*[A-HJ-NP-Z]\s*[A-HJ-NP-Z](?:[\s0-9+\-][\sA-Z0-9+\-]*)?)$"#
        if query.range(of: mgrsPattern, options: .regularExpression) != nil { return true }

        // Direction-suffixed latitude/longitude pairs are coordinate-shaped
        // even when the strict decimal parser cannot accept them. Anchoring
        // the whole query avoids suppressing prose such as "Route 33S to 151E".
        let number = #"[+\-]?(?:\d+(?:\.\d+)?|\.\d+)"#
        let latitudeDirection = #"(?:NORTH|SOUTH|N|S)"#
        let longitudeDirection = #"(?:EAST|WEST|E|W)"#
        let separator = #"\s*(?:,|;|/)?\s*"#
        let hemispherePatterns = [
            #"^\#(number)\s*°?\s*\#(latitudeDirection)\#(separator)\#(number)\s*°?\s*\#(longitudeDirection)$"#,
            #"^\#(number)\s*°?\s*\#(longitudeDirection)\#(separator)\#(number)\s*°?\s*\#(latitudeDirection)$"#,
            #"^\#(latitudeDirection)\s*\#(number)\#(separator)\#(longitudeDirection)\s*\#(number)$"#,
            #"^\#(longitudeDirection)\s*\#(number)\#(separator)\#(latitudeDirection)\s*\#(number)$"#,
        ]
        if hemispherePatterns.contains(where: {
            query.range(of: $0, options: .regularExpression) != nil
        }) { return true }

        let numericCharacters = CharacterSet(charactersIn: "+-.,0123456789")
            .union(.whitespacesAndNewlines)
        if query.unicodeScalars.allSatisfy({ numericCharacters.contains($0) }),
           query.unicodeScalars.contains(where: CharacterSet.decimalDigits.contains) {
            return true
        }

        if query.contains(",") {
            let first = query.split(separator: ",", omittingEmptySubsequences: false).first?
                .trimmingCharacters(in: .whitespaces) ?? ""
            if first.first?.isNumber == true || first.first == "+" || first.first == "-" {
                return true
            }
        }
        return false
    }

    private static func latLonString(_ coordinate: CLLocationCoordinate2D) -> String {
        String(format: "%.5f° %@, %.5f° %@",
               abs(coordinate.latitude), coordinate.latitude >= 0 ? "N" : "S",
               abs(coordinate.longitude), coordinate.longitude >= 0 ? "E" : "W")
    }
}

struct SearchSheet: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @ObservedObject var mapVM: MapViewModel
    @ObservedObject var waypointStore: WaypointStore
    @ObservedObject var drawingStore: DrawingStore
    @ObservedObject private var opsec = OpsecSettings.shared
    @Environment(\.dismiss) private var dismiss

    @State private var query = ""
    @State private var places: [SearchResult] = []
    @State private var isSearching = false
    @State private var placeStatus: LocalizedMessage?

    private var offlineOutput: OfflineSearchOutput {
        OfflineSearchEngine.search(
            query,
            anchor: mapVM.cameraCentre,
            records: OfflineSearchEngine.records(
                waypoints: waypointStore.waypoints,
                drawings: drawingStore.shapes,
                layers: drawingStore.layers
            )
        )
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                searchField
                    .padding(.horizontal)
                    .padding(.top, 6)
                    .padding(.bottom, 4)
                Text(L10n.text("Mission objects, MGRS, grid, lat/lon, or place name"))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal)
                    .padding(.bottom, 6)
                resultsList
            }
            .navigationTitle(L10n.text("Search"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button(L10n.text("Done")) { dismiss() } }
            }
            .task(id: SearchTaskKey(query: query, onlinePlaces: opsec.onlineLookups)) {
                await updatePlaces(for: query)
            }
        }
    }

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
            TextField(L10n.text("Search"), text: $query)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
            if !query.isEmpty {
                Button {
                    query = ""
                    places = []
                    placeStatus = nil
                } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L10n.text("Clear search"))
            }
        }
        .padding(8)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 10))
    }

    @ViewBuilder
    private var resultsList: some View {
        List {
            if !offlineOutput.results.isEmpty {
                Section(L10n.text("Mission & Coordinates")) {
                    ForEach(offlineOutput.results) { row($0) }
                }
            }
            if isSearching {
                Section(L10n.text("Places")) {
                    HStack { ProgressView(); Text(L10n.text("Searching…")).foregroundStyle(.secondary) }
                }
            } else if !places.isEmpty {
                Section(L10n.text("Places")) { ForEach(places) { row($0) } }
            }
            if let message = offlineOutput.statusMessage ?? placeStatus?.text,
               !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Section { Text(message).foregroundStyle(.secondary) }
            }
        }
        .listStyle(.insetGrouped)
    }

    private func row(_ result: SearchResult) -> some View {
        Button {
            select(result)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: icon(for: result.kind))
                    .foregroundStyle(colour(for: result.kind))
                    .font(.title3)
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text(result.title)
                        .foregroundStyle(.primary)
                        .font(.callout.weight(.semibold))
                    if !result.subtitle.isEmpty {
                        Text(result.subtitle).foregroundStyle(.secondary).font(.caption)
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

    private func updatePlaces(for raw: String) async {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        await MainActor.run {
            places = []
            placeStatus = nil
            isSearching = false
        }
        let offline = OfflineSearchEngine.search(
            trimmed,
            anchor: mapVM.cameraCentre,
            records: OfflineSearchEngine.records(
                waypoints: waypointStore.waypoints,
                drawings: drawingStore.shapes,
                layers: drawingStore.layers
            )
        )
        let decision = OnlinePlaceLookup.decision(
            rawQuery: trimmed,
            offlineOutput: offline,
            onlineLookups: opsec.onlineLookups
        )
        if decision == .requestProvider {
            do { try await Task.sleep(nanoseconds: 350_000_000) } catch { return }
            guard !Task.isCancelled else { return }
            await MainActor.run { isSearching = true }
        }
        let centre = mapVM.cameraCentre
        do {
            let outcome = try await OnlinePlaceLookup.perform(
                rawQuery: trimmed,
                offlineOutput: offline,
                onlineLookups: opsec.onlineLookups,
                provider: { try await searchPlaces(query: trimmed, centre: centre) }
            )
            guard !Task.isCancelled else { return }
            await MainActor.run {
                places = outcome.results
                placeStatus = outcome.pendingStatusMessage
                isSearching = false
            }
        } catch is CancellationError {
            await MainActor.run { isSearching = false }
        } catch {
            // Provider failures are mapped to an actionable status by the
            // coordinator; cancellation is the only expected thrown error.
            await MainActor.run { isSearching = false }
        }
    }

    private func searchPlaces(query: String,
                              centre: CLLocationCoordinate2D) async throws -> [SearchResult] {
        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = query
        if centre.latitude != 0 || centre.longitude != 0 {
            request.region = MKCoordinateRegion(
                center: centre,
                latitudinalMeters: 200_000,
                longitudinalMeters: 200_000
            )
        }
        request.resultTypes = [.pointOfInterest, .address]
        let response = try await MKLocalSearch(request: request).start()
        return response.mapItems.prefix(20).enumerated().map { index, item in
            SearchResult(
                id: "place:\(index):\(item.placemark.coordinate.latitude),\(item.placemark.coordinate.longitude)",
                title: item.name ?? L10n.text("Unknown"),
                subtitle: addressLine(item),
                coordinate: item.placemark.coordinate,
                kind: .place,
                target: .coordinate
            )
        }
    }

    private func select(_ result: SearchResult) {
        mapVM.cameraRequests.send(MKCoordinateRegion(
            center: result.coordinate,
            latitudinalMeters: 2_500,
            longitudinalMeters: 2_500
        ))
        switch result.target {
        case .coordinate:
            break
        case .waypoint(let id):
            mapVM.selectedDrawingID = nil
            mapVM.selectedWaypointID = id
        case .drawing(let id):
            mapVM.selectedWaypointID = nil
            mapVM.selectedDrawingID = id
        }
        dismiss()
    }

    private func icon(for kind: SearchResult.Kind) -> String {
        switch kind {
        case .mgrs, .partialMGRS, .latitudeLongitude: return "scope"
        case .waypoint: return "mappin.circle.fill"
        case .drawing: return "scribble.variable"
        case .place: return "map.fill"
        }
    }

    private func colour(for kind: SearchResult.Kind) -> Color {
        switch kind {
        case .mgrs, .partialMGRS, .latitudeLongitude: return .green
        case .waypoint: return .orange
        case .drawing: return .purple
        case .place: return .blue
        }
    }

    private func addressLine(_ item: MKMapItem) -> String {
        let placemark = item.placemark
        return [placemark.thoroughfare, placemark.locality,
                placemark.administrativeArea, placemark.country]
            .compactMap { $0 }
            .joined(separator: ", ")
    }

    private struct SearchTaskKey: Hashable {
        let query: String
        let onlinePlaces: Bool
    }
}
