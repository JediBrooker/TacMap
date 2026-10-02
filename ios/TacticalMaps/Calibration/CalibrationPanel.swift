import SwiftUI
import CoreLocation

/// State glyph + word, never colour alone (night mode turns colour into luminance).
enum CalibrationStatusGlyph {
    case ok, warning, error, info

    var symbol: String {
        switch self {
        case .ok: return "checkmark.circle.fill"
        case .warning: return "exclamationmark.triangle.fill"
        case .error: return "xmark.octagon.fill"
        case .info: return "info.circle.fill"
        }
    }

    var tint: Color {
        switch self {
        case .ok: return Color(red: 0.45, green: 0.89, blue: 0.54)
        case .warning: return Color(red: 1, green: 0.65, blue: 0.18)
        case .error: return Color(red: 1, green: 0.38, blue: 0.35)
        case .info: return .white
        }
    }
}

/// Where the crosshair sits on the page right now (status only, captures read the live camera).
struct CalibrationCrosshairState {
    var onSheet: Bool
    var zoomHint: Bool

    static func compute(display: CalibrationSession.Display?, target: CalibrationTarget?,
                        centre: CLLocationCoordinate2D, metresPerPoint: Double) -> CalibrationCrosshairState {
        guard let display, let target else { return .init(onSheet: true, zoomHint: false) }
        let zoom = WebMercator.zoom(latitude: centre.latitude, groundResolution: max(metresPerPoint, 1e-9))
        let camera = MapCamera(center: centre, zoom: zoom, headingDegrees: 0, viewportSize: .zero)
        guard let c = CalibrationCapture.capture(georef: display.georef, pageBox: target.pageBox, camera: camera,
                                                 generation: display.generation) else {
            return .init(onSheet: false, zoomHint: false)
        }
        return .init(onSheet: c.onSheet, zoomHint: c.zoomHint)
    }
}

/// Bottom calibration panel (s10): status, then Undo / datum / Grid / Points,
/// then Add point + Finish. Inline, the map stays live behind it. Every
/// control is at least 56 pt for gloves.
struct CalibrationPanel: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @ObservedObject var session: CalibrationSession
    @ObservedObject var mapVM: MapViewModel
    var onFinish: (ManualCalibration) -> Void

    private let amber = Color(red: 1, green: 0.65, blue: 0.18)
    private static let target: CGFloat = 56

    private var crosshair: CalibrationCrosshairState {
        CalibrationCrosshairState.compute(display: session.display, target: session.target,
                                          centre: mapVM.cameraCentre, metresPerPoint: mapVM.currentMetresPerPoint)
    }

    private var status: (glyph: CalibrationStatusGlyph, text: String) {
        let r = session.report
        if let id = session.movingID, let p = session.state.point(id) {
            return (.info, Messages.calibrationMoving(DisplayFormat.number(Double(p.number), decimals: 0)))
        }
        if !crosshair.onSheet { return (.error, Messages.calibrationOffSheet()) }
        if session.state.points.count >= CalibrationLimits.maxCalibrationPoints, r.n == CalibrationLimits.maxCalibrationPoints,
           case .ready = r.finishability {
            return (.ok, Messages.calibrationMaxPoints(DisplayFormat.number(Double(CalibrationLimits.maxCalibrationPoints), decimals: 0)))
        }
        let msg = r.primaryStatus
        let glyph: CalibrationStatusGlyph
        switch (r.blocked, r.issue, r.finishability) {
        case (.some, _, _): glyph = .error
        case (_, .some, _): glyph = .warning
        case (_, _, .ready): glyph = r.grade == .fair ? .warning : .ok
        case (_, _, .confirm): glyph = .warning
        default: glyph = .info
        }
        return (glyph, msg.text)
    }

    private var secondary: String? {
        // B2: no zoom hint while off the sheet, the next corner shows from n = 0
        let c = crosshair
        return CalibrationCapture.secondaryLine(report: session.report, onSheet: c.onSheet, zoomHint: c.zoomHint)?.text
    }

    private var finishBlocked: Bool {
        if case .blocked = session.report.finishability { return true }
        return false
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if session.draftUnsaved {
                Label(Messages.calibrationDraftUnsaved(), systemImage: "lock.trianglebadge.exclamationmark")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.black)
                    .padding(.horizontal, 10).padding(.vertical, 5)
                    .background(amber, in: Capsule())
                    .accessibilityIdentifier("calibration.draftUnsaved")
            }
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: status.glyph.symbol)
                    .font(.body.weight(.bold))
                    .foregroundStyle(status.glyph.tint)
                VStack(alignment: .leading, spacing: 2) {
                    Text(status.text)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white)
                        // OD-F4: s10 says two lines at most, the copy is sized for it
                        .lineLimit(2)
                        .minimumScaleFactor(0.9)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("calibration.status")
                    if let secondary {
                        Text(secondary)
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.75))
                            .lineLimit(2)
                            .accessibilityIdentifier("calibration.hint")
                    }
                }
                Spacer(minLength: 0)
            }
            if session.movingID != nil {
                movingRow
            } else if let id = session.selectedID, session.state.point(id) != nil {
                toolsRow
                selectedRow(id)
            } else {
                toolsRow
                mainRow
            }
        }
        .padding(12)
        .background(.black.opacity(0.88), in: RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(.white.opacity(0.12)))
    }

    // line 2
    private var toolsRow: some View {
        HStack(spacing: 8) {
            Button { session.undo() } label: {
                Label(L10n.text("Undo"), systemImage: "arrow.uturn.backward")
                    .labelStyle(.iconOnly)
                    .frame(width: Self.target, height: Self.target)
            }
            .disabled(!session.canUndo)
            .opacity(session.canUndo ? 1 : 0.4)
            .accessibilityLabel(L10n.text("Undo"))
            .accessibilityIdentifier("calibration.undo")

            Button { session.phase = .datumSheet } label: {
                Text(CalibrationDatumChoice.displayName(session.state.datumID ?? "WGS84"))
                    .font(.caption.weight(.semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .padding(.horizontal, 10)
                    .frame(minWidth: Self.target, minHeight: Self.target)
            }
            .accessibilityLabel(Messages.calibrationDatumTitle())
            .accessibilityIdentifier("calibration.datum")

            Button { session.gridOn.toggle() } label: {
                Label(Messages.calibrationGridToggle(), systemImage: session.gridOn ? "grid.circle.fill" : "grid.circle")
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 8)
                    .frame(minHeight: Self.target)
            }
            .disabled(session.display?.isProvisional ?? true)
            .opacity(session.display?.isProvisional ?? true ? 0.4 : 1)
            .accessibilityIdentifier("calibration.grid")

            Spacer(minLength: 0)

            Button { session.phase = .pointsSheet } label: {
                Text("\(Messages.calibrationPointsButton()) (\(session.state.points.count))")
                    .font(.caption.weight(.bold))
                    .padding(.horizontal, 10)
                    .frame(minWidth: Self.target, minHeight: Self.target)
            }
            .accessibilityIdentifier("calibration.points")
        }
        .buttonStyle(CalibrationChipStyle())
    }

    // line 3
    private var mainRow: some View {
        HStack(spacing: 8) {
            Button {
                _ = session.beginAdd(capture: mapVM.calibrationCapture?())
            } label: {
                Label(Messages.calibrationAddPoint(), systemImage: "plus")
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: Self.target)
            }
            .buttonStyle(CalibrationPrimaryStyle(tint: amber))
            .disabled(!crosshair.onSheet || session.state.points.count >= CalibrationLimits.maxCalibrationPoints)
            .accessibilityIdentifier("calibration.add")

            Button {
                if let manual = session.finishTapped() { onFinish(manual) }
            } label: {
                Text(L10n.text("Finish"))
                    .font(.headline)
                    .padding(.horizontal, 14)
                    .frame(minWidth: 88, minHeight: Self.target)
            }
            .buttonStyle(CalibrationPrimaryStyle(tint: finishBlocked ? .gray : Color(red: 0.45, green: 0.89, blue: 0.54), expand: false))
            .disabled(finishBlocked)
            .accessibilityIdentifier("calibration.finish")
        }
    }

    private func selectedRow(_ id: UUID) -> some View {
        HStack(spacing: 8) {
            Button(Messages.calibrationActionMove()) { session.beginMove(id) }
                .accessibilityIdentifier("calibration.move")
            Button(Messages.calibrationActionEdit()) { session.beginEdit(id) }
                .accessibilityIdentifier("calibration.edit")
            Button(L10n.text("Delete"), role: .destructive) { session.delete(id) }
                .accessibilityIdentifier("calibration.delete")
            Button(Messages.calibrationActionDone()) { session.select(nil) }
                .accessibilityIdentifier("calibration.deselect")
        }
        .font(.subheadline.weight(.semibold))
        .buttonStyle(CalibrationChipStyle(minHeight: Self.target, fill: true))
    }

    private var movingRow: some View {
        HStack(spacing: 8) {
            Button(L10n.text("Cancel")) { session.cancelMove() }
                .buttonStyle(CalibrationChipStyle(minHeight: Self.target, fill: true))
                .accessibilityIdentifier("calibration.moveCancel")
            Button(Messages.calibrationSetHere()) {
                session.confirmMove(capture: mapVM.calibrationCapture?())
            }
            .buttonStyle(CalibrationPrimaryStyle(tint: amber))
            .disabled(!crosshair.onSheet)
            .accessibilityIdentifier("calibration.setHere")
        }
        .font(.headline)
    }
}

struct CalibrationChipStyle: ButtonStyle {
    var minHeight: CGFloat = 56
    var fill = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(.white)
            .frame(maxWidth: fill ? .infinity : nil, minHeight: minHeight)
            .padding(.horizontal, fill ? 4 : 0)
            .background(.white.opacity(configuration.isPressed ? 0.22 : 0.10), in: RoundedRectangle(cornerRadius: 12))
            .lineLimit(2)
            .minimumScaleFactor(0.75)
    }
}

struct CalibrationPrimaryStyle: ButtonStyle {
    var tint: Color
    var expand = true

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(.black)
            .frame(maxWidth: expand ? .infinity : nil, minHeight: 56)
            .background(tint.opacity(configuration.isPressed ? 0.75 : 1), in: RoundedRectangle(cornerRadius: 14))
            .lineLimit(1)
            .minimumScaleFactor(0.75)
    }
}
