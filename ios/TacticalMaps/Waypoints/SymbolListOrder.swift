import Foundation
import CoreLocation

/// Orders offered by the Symbology list. Raw values are persisted in
/// UserDefaults, so keep them stable. Android mirrors these in
/// `SymbolListOrder.kt`; `testdata/symbol_list_order.json` pins both.
enum SymbolListOrder: String, CaseIterable, Identifiable {
    case newest
    case name
    case affiliation
    case layer
    case distance

    static let defaultsKey = "symbolList.order"
    static let defaultValue: Self = .newest

    var id: String { rawValue }

    var label: String {
        switch self {
        case .newest: Messages.symbolsSortNewest()
        case .name: Messages.symbolsSortName()
        case .affiliation: Messages.symbolsSortAffiliation()
        case .layer: Messages.symbolsSortLayer()
        case .distance: Messages.symbolsSortDistance()
        }
    }

    static func stored(_ rawValue: String) -> Self {
        Self(rawValue: rawValue) ?? defaultValue
    }
}

/// Affiliation bucket for grouping. Units use their frame affiliation and task
/// graphics their affiliation colour; black tasks, markers and generic
/// waypoints carry no affiliation and land in `other`.
enum SymbolListAffiliation: String, CaseIterable {
    case friend
    case hostile
    case neutral
    case unknown
    case other

    init(_ waypoint: Waypoint) {
        if let spec = waypoint.kind.militarySpec {
            switch spec.affiliation {
            case .friend: self = .friend
            case .hostile: self = .hostile
            case .neutral: self = .neutral
            case .unknown: self = .unknown
            }
        } else if waypoint.kind.controlMeasure != nil {
            switch waypoint.taskColor {
            case .blue: self = .friend
            case .red: self = .hostile
            case .green: self = .neutral
            case .yellow: self = .unknown
            case .black: self = .other
            }
        } else {
            self = .other
        }
    }

    var title: String {
        switch self {
        case .friend: SymbolAffiliation.friend.displayName
        case .hostile: SymbolAffiliation.hostile.displayName
        case .neutral: SymbolAffiliation.neutral.displayName
        case .unknown: SymbolAffiliation.unknown.displayName
        case .other: Messages.symbolsGroupOther()
        }
    }
}

enum SymbolListGroup: Hashable {
    /// Ungrouped orders produce one untitled section.
    case all
    case affiliation(SymbolListAffiliation)
    case layer(UUID)
    /// Symbols whose layer is not in the current layer list.
    case otherLayer
}

struct SymbolListSection: Identifiable {
    let group: SymbolListGroup
    let waypoints: [Waypoint]

    var id: SymbolListGroup { group }
}

/// Pure ordering for the Symbology list so both platforms group and sort the
/// same mission identically.
enum SymbolListSorter {
    /// IUGG mean Earth radius. List distances are for ordering and a compact
    /// readout; the sphere keeps them identical on iOS and Android.
    static let earthRadiusMetres = 6_371_008.8

    static func sections(_ waypoints: [Waypoint],
                         order: SymbolListOrder,
                         layerOrder: [UUID],
                         reference: CLLocationCoordinate2D) -> [SymbolListSection] {
        switch order {
        case .newest:
            return [SymbolListSection(group: .all, waypoints: waypoints.sorted(by: newestFirst))]
        case .name:
            return [SymbolListSection(group: .all, waypoints: waypoints.sorted(by: byName))]
        case .distance:
            let distances = Dictionary(waypoints.map {
                ($0.id, distanceMetres(from: reference, to: $0.coordinate))
            }, uniquingKeysWith: { first, _ in first })
            let sorted = waypoints.sorted { lhs, rhs in
                let l = distances[lhs.id] ?? 0, r = distances[rhs.id] ?? 0
                return l != r ? l < r : byName(lhs, rhs)
            }
            return [SymbolListSection(group: .all, waypoints: sorted)]
        case .affiliation:
            let buckets = Dictionary(grouping: waypoints) { SymbolListAffiliation($0) }
            return SymbolListAffiliation.allCases.compactMap { affiliation in
                guard let members = buckets[affiliation], !members.isEmpty else { return nil }
                return SymbolListSection(group: .affiliation(affiliation),
                                         waypoints: members.sorted(by: byName))
            }
        case .layer:
            let known = Set(layerOrder)
            let buckets: [UUID?: [Waypoint]] = Dictionary(grouping: waypoints) {
                known.contains($0.layerID) ? $0.layerID : nil
            }
            var sections: [SymbolListSection] = layerOrder.compactMap { layerID in
                guard let members = buckets[layerID], !members.isEmpty else { return nil }
                return SymbolListSection(group: .layer(layerID), waypoints: members.sorted(by: byName))
            }
            if let orphans = buckets[UUID?.none], !orphans.isEmpty {
                sections.append(SymbolListSection(group: .otherLayer, waypoints: orphans.sorted(by: byName)))
            }
            return sections
        }
    }

    /// Great-circle (haversine) distance in metres.
    static func distanceMetres(from a: CLLocationCoordinate2D, to b: CLLocationCoordinate2D) -> Double {
        let φ1: Double = a.latitude * .pi / 180
        let φ2: Double = b.latitude * .pi / 180
        let sinHalfΔφ: Double = sin((φ2 - φ1) / 2)
        let sinHalfΔλ: Double = sin((b.longitude - a.longitude) * .pi / 360)
        let h: Double = sinHalfΔφ * sinHalfΔφ + cos(φ1) * cos(φ2) * sinHalfΔλ * sinHalfΔλ
        return 2 * earthRadiusMetres * asin(min(1, h.squareRoot()))
    }

    /// Natural name order: ASCII digit runs compare by value, everything else
    /// case- and accent-insensitively by Unicode scalar, so "2 PL" sorts
    /// before "10 PL" in every language.
    static func naturalOrder(_ lhs: String, _ rhs: String) -> ComparisonResult {
        let a = chunks(of: fold(lhs)), b = chunks(of: fold(rhs))
        for (x, y) in zip(a, b) {
            let result: ComparisonResult
            if x.isNumber && y.isNumber {
                result = compareDigits(x.scalars, y.scalars)
            } else {
                result = compareScalars(x.scalars, y.scalars)
            }
            if result != .orderedSame { return result }
        }
        if a.count == b.count { return .orderedSame }
        return a.count < b.count ? .orderedAscending : .orderedDescending
    }

    private static func newestFirst(_ lhs: Waypoint, _ rhs: Waypoint) -> Bool {
        if lhs.createdAt != rhs.createdAt { return lhs.createdAt > rhs.createdAt }
        switch naturalOrder(lhs.name, rhs.name) {
        case .orderedAscending: return true
        case .orderedDescending: return false
        case .orderedSame: return lhs.id.uuidString.lowercased() < rhs.id.uuidString.lowercased()
        }
    }

    private static func byName(_ lhs: Waypoint, _ rhs: Waypoint) -> Bool {
        switch naturalOrder(lhs.name, rhs.name) {
        case .orderedAscending: return true
        case .orderedDescending: return false
        case .orderedSame:
            if lhs.createdAt != rhs.createdAt { return lhs.createdAt > rhs.createdAt }
            return lhs.id.uuidString.lowercased() < rhs.id.uuidString.lowercased()
        }
    }

    private struct Chunk {
        let isNumber: Bool
        let scalars: [UInt32]
    }

    private static func fold(_ value: String) -> String {
        // Decompose, drop combining marks, then lower-case: the same steps the
        // Android comparator takes, so accents never change the order.
        let decomposed = value.decomposedStringWithCanonicalMapping
        let stripped = String(String.UnicodeScalarView(
            decomposed.unicodeScalars.filter { $0.properties.generalCategory != .nonspacingMark }
        ))
        return stripped.lowercased()
    }

    private static func chunks(of value: String) -> [Chunk] {
        var result: [Chunk] = []
        var current: [UInt32] = []
        var currentIsNumber = false
        for scalar in value.unicodeScalars {
            let isDigit = scalar.value >= 0x30 && scalar.value <= 0x39
            if !current.isEmpty && isDigit != currentIsNumber {
                result.append(Chunk(isNumber: currentIsNumber, scalars: current))
                current = []
            }
            currentIsNumber = isDigit
            current.append(scalar.value)
        }
        if !current.isEmpty { result.append(Chunk(isNumber: currentIsNumber, scalars: current)) }
        return result
    }

    private static func compareDigits(_ lhs: [UInt32], _ rhs: [UInt32]) -> ComparisonResult {
        let a = Array(lhs.drop(while: { $0 == 0x30 }))
        let b = Array(rhs.drop(while: { $0 == 0x30 }))
        if a.count != b.count { return a.count < b.count ? .orderedAscending : .orderedDescending }
        return compareScalars(a, b)
    }

    private static func compareScalars(_ lhs: [UInt32], _ rhs: [UInt32]) -> ComparisonResult {
        for (x, y) in zip(lhs, rhs) where x != y {
            return x < y ? .orderedAscending : .orderedDescending
        }
        if lhs.count == rhs.count { return .orderedSame }
        return lhs.count < rhs.count ? .orderedAscending : .orderedDescending
    }
}
