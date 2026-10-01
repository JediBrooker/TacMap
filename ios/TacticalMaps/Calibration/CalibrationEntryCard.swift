import SwiftUI
import CoreLocation

/// The coordinate entry card (s10), docked at the TOP so on the smallest
/// phones the crosshair stays visible between the card and the keyboard. The
/// map behind it stays pannable.
struct CalibrationEntryCard: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @ObservedObject var session: CalibrationSession
    @ObservedObject var mapVM: MapViewModel
    var location: CLLocation?

    @FocusState private var fieldFocused: Bool
    @State private var showLabel = false

    private let amber = Color(red: 1, green: 0.65, blue: 0.18)

    private var pending: CalibrationPending? { session.pendingEntry }

    private var number: Int {
        if let id = pending?.editingId, let p = session.state.point(id) { return p.number }
        return session.state.nextNumber
    }

    private var datumName: String { CalibrationDatumChoice.displayName(session.state.datumID ?? "WGS84") }

    /// how far (screen pt) the crosshair is from the pending marker
    private var crosshairOffsetPt: Double? {
        guard let pending, let d = session.display,
              let p = d.georef.toPage(lat: mapVM.cameraCentre.latitude, lon: mapVM.cameraCentre.longitude) else { return nil }
        let zoom = WebMercator.zoom(latitude: mapVM.cameraCentre.latitude, groundResolution: max(mapVM.currentMetresPerPoint, 1e-9))
        guard let spp = CalibrationCameraAnchor.screenPointsPerPagePoint(d.georef, at: pending.page, zoom: zoom) else { return nil }
        return hypot(p.x - pending.page.x, p.y - pending.page.y) * spp
    }

    private var interpretation: String? {
        guard case .success(let r)? = session.entryParse else { return nil }
        return (r.messages.map(\.text) + [datumName]).joined(separator: " · ")
    }

    private var errorText: String? {
        guard case .failure(let e)? = session.entryParse else { return nil }
        return e.text
    }

    private var showKindSegment: Bool {
        if case .success(let r)? = session.entryParse { return r.kindSegment }
        return false
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(Messages.calibrationEntryTitle(DisplayFormat.number(Double(number), decimals: 0)))
                .font(.headline)
                .foregroundStyle(.white)

            TextField(Messages.calibrationEntryPlaceholder(), text: $session.entryText)
                .font(.system(.title3, design: .monospaced).weight(.semibold))
                .keyboardType(.asciiCapable)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .submitLabel(.done)
                .focused($fieldFocused)
                .padding(10)
                .background(.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
                .foregroundStyle(.white)
                .accessibilityIdentifier("calibration.entry.field")
                .onSubmit { fieldFocused = false }

            if let interpretation {
                Label(interpretation, systemImage: "checkmark.circle")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(Color(red: 0.45, green: 0.89, blue: 0.54))
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("calibration.entry.readsAs")
            } else if let errorText {
                Label(errorText, systemImage: "xmark.octagon")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(Color(red: 1, green: 0.45, blue: 0.42))
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("calibration.entry.error")
            }

            if showKindSegment {
                Picker("", selection: $session.entryKind) {
                    Text(Messages.calibrationKindIntersection()).tag(CalibrationPointKind.intersection)
                    Text(Messages.calibrationKindFeature()).tag(CalibrationPointKind.feature)
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("calibration.entry.kind")
            }

            if let warn = session.entryCheck?.message {
                Label(warn.text, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(amber)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("calibration.entry.warnFar")
            }

            HStack(spacing: 8) {
                gpsButton
                if (crosshairOffsetPt ?? 0) > CalibrationLimits.moveToCrosshairMinScreenPt {
                    Button(Messages.calibrationMoveToCrosshair()) {
                        session.moveEntryToCrosshair(capture: mapVM.calibrationCapture?())
                    }
                    .accessibilityIdentifier("calibration.entry.moveToCrosshair")
                }
                if fieldFocused {
                    Button(Messages.calibrationHideKeyboard()) { fieldFocused = false }
                }
            }
            .font(.caption.weight(.semibold))
            .buttonStyle(CalibrationChipStyle(minHeight: 44))

            DisclosureGroup(isExpanded: $showLabel) {
                TextField(Messages.calibrationLabelPlaceholder(), text: $session.entryLabel)
                    .textInputAutocapitalization(.sentences)
                    .padding(8)
                    .background(.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
                    .foregroundStyle(.white)
            } label: {
                Text(Messages.calibrationLabelPlaceholder())
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.75))
            }
            .tint(.white)

            HStack(spacing: 8) {
                Button(L10n.text("Cancel")) {
                    fieldFocused = false
                    session.cancelEntry()
                }
                .buttonStyle(CalibrationChipStyle(minHeight: 56, fill: true))
                .accessibilityIdentifier("calibration.entry.cancel")
                Button(session.entryCheck?.warn == true ? Messages.calibrationSaveAnyway() : Messages.calibrationSave()) {
                    fieldFocused = false
                    session.commitEntry()
                }
                .buttonStyle(CalibrationPrimaryStyle(tint: session.entryCheck?.warn == true ? amber : Color(red: 0.45, green: 0.89, blue: 0.54)))
                .disabled(!session.canSaveEntry)
                .opacity(session.canSaveEntry ? 1 : 0.45)
                .accessibilityIdentifier("calibration.entry.save")
            }
            .font(.headline)
        }
        .padding(12)
        .background(.black.opacity(0.92), in: RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(.white.opacity(0.14)))
        .onAppear { fieldFocused = true }
    }

    @ViewBuilder
    private var gpsButton: some View {
        if let location, location.horizontalAccuracy >= 0 {
            let acc = DisplayFormat.distance(location.horizontalAccuracy)
            if location.horizontalAccuracy <= CalibrationLimits.gpsMaxAccuracyM {
                Button(Messages.calibrationUseGps(acc)) { session.useGPS(location) }
                    .accessibilityIdentifier("calibration.entry.gps")
            } else {
                Text(Messages.calibrationGpsTooCoarse(acc))
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.7))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}
