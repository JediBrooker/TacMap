import Combine
import Foundation

/// Process wide "is the full version unlocked" flag. RootGate owns the truth
/// (purchase, or the free trial still running) and pushes it in here so view
/// models that never see the StoreManager (MapViewModel picking a basemap) can
/// still ask. Defaults to true so unit tests get today's behaviour unless they
/// flip it on purpose.
///
/// 3.1: the app never hard locks anymore. When this is false we're the free
/// viewer: open maps, see where you are, everything else opens the paywall.
final class FeatureAccess: ObservableObject {
    static let shared = FeatureAccess()
    @Published var isFullVersion = true
}

/// Pure decisions about what the free viewer gets. No state in here on
/// purpose, the call sites hand in the flags so this stays trivially testable.
enum FeatureGate {
    /// Everything the viewer can be asked for. Free ones listed first.
    enum Feature: CaseIterable {
        // free for good
        case importMaps            // PDF Map… / Offline Tiles…, multi page, bake, calibration
        case importedMapLibrary    // the Layers sheet library + management
        case layerVisibility
        case openTopoMap
        case liveLocation
        case heading               // compass chip, North Up / Heading Up
        case grid                  // MGRS / lat-long grid + crosshair readout
        case copyCoordinates
        case elevationHeader
        case search
        case nightMode
        case unitLabels
        case undoRedo
        case appLock
        case settings
        case about
        case stopTrackRecording    // a recording already running can always be stopped
        // part of the one-time unlock
        case symbology
        case drawings
        case measure
        case weather
        case importGeoJSON
        case importKML
        case exportGeoJSON
        case exportKML
        case exportKMZ
        case exportGPX
        case exportAll
        case startTrackRecording
        case chat
        case unitSync
        case esriBasemaps
    }

    /// The features the viewer does NOT get. Kept as one explicit set rather
    /// than a default-locked switch so adding a feature means deciding here.
    static let locked: Set<Feature> = [
        .symbology, .drawings, .measure, .weather,
        .importGeoJSON, .importKML,
        .exportGeoJSON, .exportKML, .exportKMZ, .exportGPX, .exportAll,
        .startTrackRecording, .chat, .unitSync, .esriBasemaps,
    ]

    static func allows(_ feature: Feature, fullVersion: Bool) -> Bool {
        fullVersion || !locked.contains(feature)
    }

    /// Which online style actually gets drawn for a pick. The viewer and a
    /// keyless dev build both fall back to OpenTopoMap, the only style that
    /// needs no Esri token. The pick itself is never rewritten by this, that
    /// is what lets the Esri map come straight back on unlock.
    static func displayStyle(for style: BasemapStyle, fullVersion: Bool, esriKeyAvailable: Bool) -> BasemapStyle {
        guard style.requiresEsriKey else { return style }
        return fullVersion && esriKeyAvailable ? style : .osmTopo
    }

    /// Convenience for the live call sites: current entitlement + build key.
    static func displayStyle(for style: BasemapStyle) -> BasemapStyle {
        displayStyle(for: style,
                     fullVersion: FeatureAccess.shared.isFullVersion,
                     esriKeyAvailable: EsriKey.isAvailable)
    }

    /// The track recording row is the one menu entry whose lock depends on
    /// state: starting (idle / retry after an interruption) is part of the
    /// unlock, stopping or cancelling a start that is already underway is not.
    static func trackRecordingRowLocked(state: RecordingCoordinator.State, fullVersion: Bool) -> Bool {
        switch state {
        case .idle, .interrupted:
            return !allows(.startTrackRecording, fullVersion: fullVersion)
        case .awaitingPermission, .starting, .recording:
            return !allows(.stopTrackRecording, fullVersion: fullVersion)
        }
    }
}
