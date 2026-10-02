import SwiftUI

/// Half height list of the calibration points (s10): fit summary, then a row
/// per point with its reference, kind, residual and a status glyph. Tap a row
/// to select the point and fly to it.
struct CalibrationPointsSheet: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @ObservedObject var session: CalibrationSession
    @Environment(\.dismiss) private var dismiss

    private func glyph(_ p: CalibrationPoint) -> CalibrationStatusGlyph? {
        guard let rows = session.report.rowStatus else { return nil }
        switch rows[p.number] {
        case .ok?: return .ok
        case .warn?: return .warning
        case .error?: return .error
        case nil: return nil
        }
    }

    private func residual(_ p: CalibrationPoint) -> String {
        guard session.report.n >= 4, let r = session.report.residualsM[p.number] else { return "—" }
        return Messages.calibrationRowResidual(DisplayFormat.distance(r))
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(session.report.primaryStatus.text)
                        .font(.subheadline)
                        .accessibilityIdentifier("calibration.points.summary")
                }
                Section {
                    ForEach(session.state.points) { p in
                        Button {
                            session.select(p.id)
                            dismiss()
                        } label: {
                            HStack(spacing: 10) {
                                Text("#\(p.number)")
                                    .font(.subheadline.monospacedDigit().weight(.bold))
                                    .frame(minWidth: 34, alignment: .leading)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(p.canonicalReference)
                                        .font(.system(.subheadline, design: .monospaced))
                                    if let label = p.label {
                                        Text(label).font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                                Image(systemName: p.kind == .feature ? "smallcircle.filled.circle" : "plus")
                                    .foregroundStyle(.secondary)
                                    .accessibilityLabel(p.kind == .feature ? Messages.calibrationKindFeature()
                                                        : Messages.calibrationKindIntersection())
                                Spacer(minLength: 4)
                                Text(residual(p))
                                    .font(.caption.monospacedDigit())
                                    .foregroundStyle(.secondary)
                                if let g = glyph(p) {
                                    Image(systemName: g.symbol).foregroundStyle(g.tint)
                                }
                            }
                            .frame(minHeight: 44)
                        }
                        .accessibilityIdentifier("calibration.points.row.\(p.number)")
                    }
                }
            }
            .navigationTitle(Messages.calibrationPointsTitle())
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(L10n.text("Done")) { dismiss() }
                }
            }
        }
    }
}

/// The datum picker (s10): fixed order, "not sure" picks WGS84, national grid note.
struct CalibrationDatumSheet: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @ObservedObject var session: CalibrationSession
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(Messages.calibrationDatumHelp())
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Section {
                    ForEach(CalibrationDatumChoice.order, id: \.self) { id in
                        Button {
                            session.chooseDatum(id)
                            dismiss()
                        } label: {
                            HStack {
                                Text(CalibrationDatumChoice.displayName(id)).foregroundStyle(.primary)
                                Spacer()
                                if session.state.datumID == id {
                                    Image(systemName: "checkmark").foregroundStyle(.tint)
                                }
                            }
                            .frame(minHeight: 44)
                        }
                        .accessibilityIdentifier("calibration.datum.\(id)")
                    }
                    Button {
                        session.chooseDatum("WGS84")
                        dismiss()
                    } label: {
                        Text(Messages.calibrationDatumUnsure()).frame(minHeight: 44)
                    }
                    .accessibilityIdentifier("calibration.datum.unsure")
                } footer: {
                    Text(Messages.calibrationDatumNationalGridNote())
                }
            }
            .navigationTitle(Messages.calibrationDatumTitle())
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
