import SwiftUI

/// Unit for the ring spacing field. Raw values are persisted in UserDefaults.
enum RangeRingUnit: String, CaseIterable, Identifiable {
    case metres
    case kilometres

    var id: String { rawValue }

    var metresPerUnit: Double {
        switch self {
        case .metres: 1
        case .kilometres: 1000
        }
    }

    var label: String {
        switch self {
        case .metres: Messages.ringsUnitMetres()
        case .kilometres: Messages.ringsUnitKilometres()
        }
    }
}

/// Adds range rings around the selected symbol as ordinary line drawings on
/// its layer, committed as one undo step.
struct RangeRingsSheet: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @ObservedObject var drawingStore: DrawingStore
    let waypoint: Waypoint

    @Environment(\.dismiss) private var dismiss
    @AppStorage("rangeRings.count") private var count = 3
    @AppStorage("rangeRings.spacing") private var spacingText = "500"
    @AppStorage("rangeRings.unit") private var unitRaw = RangeRingUnit.metres.rawValue
    @State private var errorMessage: LocalizedMessage?

    private var unit: RangeRingUnit { RangeRingUnit(rawValue: unitRaw) ?? .metres }

    private var radii: [Double]? {
        guard let spacing = DecimalInput.parse(spacingText) else { return nil }
        return RangeRings.radii(intervalMetres: spacing * unit.metresPerUnit,
                                count: min(max(count, 1), RangeRings.maxRings))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Stepper(value: $count, in: 1...RangeRings.maxRings) {
                        Text(Messages.ringsCountValue(DisplayFormat.number(Double(count), decimals: 0)))
                    }
                    .frame(minHeight: 44)
                    TextField(Messages.ringsSpacing(), text: $spacingText)
                        .keyboardType(.decimalPad)
                        .frame(minHeight: 44)
                        .accessibilityIdentifier("rangeRings.spacing")
                    Picker(Messages.ringsUnit(), selection: $unitRaw) {
                        ForEach(RangeRingUnit.allCases) { option in
                            Text(option.label).tag(option.rawValue)
                        }
                    }
                    .pickerStyle(.segmented)
                } header: {
                    Text(Messages.ringsSpacing())
                } footer: {
                    Text(Messages.decimalInputHint())
                }

                Section {
                    if let radii {
                        Text(radii.map { DisplayFormat.distance($0) }.joined(separator: ", "))
                    } else {
                        Text(Messages.ringsInvalid())
                            .foregroundStyle(.red)
                    }
                } header: {
                    Text(Messages.ringsPreview())
                } footer: {
                    Text(Messages.ringsHelp())
                }
            }
            .navigationTitle(Messages.ringsTitle())
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(L10n.text("Cancel")) { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(Messages.ringsCreate()) { create() }
                        .disabled(radii == nil)
                }
            }
            .alert(Messages.ringsNotCreated(),
                   isPresented: Binding(get: { errorMessage != nil },
                                        set: { if !$0 { errorMessage = nil } }),
                   presenting: errorMessage) { _ in
                Button(Messages.acknowledge(), role: .cancel) { errorMessage = nil }
            } message: { Text($0.text) }
        }
    }

    private func create() {
        guard let radii else { return }
        let shapes = RangeRings.shapes(
            around: waypoint,
            radii: radii,
            layerColorHex: drawingStore.layer(id: waypoint.layerID)?.defaultColorHex
        )
        do {
            _ = try drawingStore.addBatchDurably(shapes, actionName: Messages.ringsUndoAction())
            dismiss()
        } catch {
            errorMessage = error.displayMessage
        }
    }
}
