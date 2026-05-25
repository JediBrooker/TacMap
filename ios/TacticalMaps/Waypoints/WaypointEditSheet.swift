import SwiftUI
import CoreLocation

/// Edit (or create) a single waypoint. Reachable by tapping any row in
/// `WaypointListSheet`. Lets the user rename, change category & APP-6C
/// symbol selection, edit notes / elevation, and delete.
struct WaypointEditSheet: View {
    @ObservedObject var waypointStore: WaypointStore
    /// nil = creating a brand-new waypoint at `defaultCoordinate`.
    let original: Waypoint?
    let defaultCoordinate: CLLocationCoordinate2D
    @Environment(\.dismiss) private var dismiss

    @State private var name: String = ""
    @State private var category: KindCategory = .military
    // Military
    @State private var affiliation: SymbolAffiliation = .friend
    @State private var echelon:     SymbolEchelon     = .platoon
    @State private var function:    SymbolFunction    = .infantry
    @State private var isHeadquarters: Bool            = false
    // Control measure
    @State private var control:     TacticalControlMeasure = .assemblyArea
    @State private var rotation:    Double                 = 0
    @State private var scale:       Double                 = 1.0
    @State private var notes: String = ""
    @State private var elevationText: String = ""
    @State private var showDeleteConfirm = false

    init(waypointStore: WaypointStore,
         original: Waypoint? = nil,
         defaultCoordinate: CLLocationCoordinate2D = .init(latitude: 0, longitude: 0),
         defaultScale: Double = 1.0) {
        self.waypointStore = waypointStore
        self.original = original
        self.defaultCoordinate = defaultCoordinate
        if let wp = original {
            _name          = State(initialValue: wp.name)
            _notes         = State(initialValue: wp.notes ?? "")
            _elevationText = State(initialValue: wp.elevation.map { String(Int($0)) } ?? "")
            _rotation      = State(initialValue: wp.rotation)
            _scale         = State(initialValue: wp.scale)
            switch wp.kind {
            case .generic:
                _category = State(initialValue: .generic)
            case .military(let spec):
                _category       = State(initialValue: .military)
                _affiliation    = State(initialValue: spec.affiliation)
                _echelon        = State(initialValue: spec.echelon)
                _function       = State(initialValue: spec.function)
                _isHeadquarters = State(initialValue: spec.isHeadquarters)
            case .controlMeasure(let m):
                _category = State(initialValue: .controlMeasure)
                _control  = State(initialValue: m)
            }
        } else {
            // New tactical control measure: start at the zoom-appropriate
            // scale so the symbol enters at ~10% of screen height at the
            // current zoom level.
            _scale = State(initialValue: defaultScale)
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("e.g. 1 Pl, A Coy", text: $name)
                        .autocorrectionDisabled()
                }

                // Live preview of the current symbol selection
                Section {
                    HStack {
                        Spacer()
                        WaypointKindIcon(kind: currentKind,
                                         size: 64 * previewScale,
                                         rotation: previewRotation)
                            .frame(width: 100, height: 100)
                            .padding(.vertical, 8)
                        Spacer()
                    }
                    .listRowBackground(Color.white)
                } header: { Text("Preview") }

                Section("Type") {
                    Picker("Category", selection: $category) {
                        ForEach(KindCategory.allCases, id: \.self) { c in
                            Text(c.displayName).tag(c)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                switch category {
                case .generic:
                    EmptyView()

                case .military:
                    Section("Military Unit (APP-6C)") {
                        Picker("Affiliation", selection: $affiliation) {
                            ForEach(SymbolAffiliation.allCases, id: \.self) { a in
                                Text(a.displayName).tag(a)
                            }
                        }
                        Picker("Echelon", selection: $echelon) {
                            ForEach(SymbolEchelon.allCases, id: \.self) { e in
                                Text(e.displayName).tag(e)
                            }
                        }
                        Picker("Function / Branch", selection: $function) {
                            ForEach(SymbolFunction.allCases, id: \.self) { f in
                                Text(f.displayName).tag(f)
                            }
                        }
                        Toggle("Headquarters", isOn: $isHeadquarters)
                    }

                case .controlMeasure:
                    Section("Tactical Task / Control Measure") {
                        Picker("Measure", selection: $control) {
                            ForEach(TacticalControlMeasure.allCases, id: \.self) { m in
                                Text(m.displayName).tag(m)
                            }
                        }
                    }
                    Section {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text("Rotation")
                                Spacer()
                                Text("\(Int(rotation.rounded()))°")
                                    .font(.subheadline.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                            Slider(value: $rotation, in: 0...360, step: 1)
                            HStack {
                                Button("Reset") { rotation = 0 }
                                    .buttonStyle(.bordered)
                                    .controlSize(.small)
                                Spacer()
                                ForEach([0, 90, 180, 270], id: \.self) { deg in
                                    Button("\(deg)°") { rotation = Double(deg) }
                                        .buttonStyle(.bordered)
                                        .controlSize(.small)
                                }
                            }
                        }
                    } header: { Text("Orientation") } footer: {
                        Text("Rotate the symbol to indicate direction (e.g. axis of advance, ambush facing).")
                            .font(.caption2)
                    }
                    Section {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text("Size")
                                Spacer()
                                Text(String(format: "%.2f×", scale))
                                    .font(.subheadline.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                            Slider(value: $scale, in: 0.1...20.0, step: 0.1)
                            HStack {
                                Button("Reset") { scale = 1.0 }
                                    .buttonStyle(.bordered)
                                    .controlSize(.small)
                                Spacer()
                                ForEach([0.5, 1.0, 2.0, 5.0, 10.0], id: \.self) { s in
                                    Button(String(format: "%g×", s)) { scale = s }
                                        .buttonStyle(.bordered)
                                        .controlSize(.small)
                                }
                            }
                        }
                    } header: { Text("Size") } footer: {
                        Text("Scale multiplier on the symbol's geographic footprint. The symbol shrinks when the map zooms out and grows when it zooms in.")
                            .font(.caption2)
                    }
                }

                Section("Notes") {
                    TextField("Optional", text: $notes, axis: .vertical)
                        .lineLimit(3...6)
                }

                Section("Elevation (metres)") {
                    TextField("Optional — leave blank for none", text: $elevationText)
                        .keyboardType(.numbersAndPunctuation)
                }

                Section {
                    HStack {
                        Text("Location")
                        Spacer()
                        Text(MGRSFormatter.string(from: locationCoordinate))
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                    }
                } header: { Text("Position") } footer: {
                    Text("Editing a waypoint's coordinate is not supported in v1.0 — delete and re-add at the new location.")
                        .font(.caption2)
                }

                if original != nil {
                    Section {
                        Button(role: .destructive) {
                            showDeleteConfirm = true
                        } label: {
                            Label("Delete symbol", systemImage: "trash")
                        }
                    }
                }
            }
            .navigationTitle(original == nil ? "New Symbol" : "Edit Symbol")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") { save() }
                        .bold()
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .alert("Delete symbol?",
                   isPresented: $showDeleteConfirm) {
                Button("Delete", role: .destructive) {
                    if let wp = original { waypointStore.remove(wp) }
                    dismiss()
                }
                Button("Cancel", role: .cancel) { }
            } message: {
                Text("This will permanently remove “\(name)”.")
            }
        }
    }

    /// Current kind derived from the live editor state.
    private var currentKind: WaypointKind {
        switch category {
        case .generic:        return .generic
        case .military:       return .military(.init(affiliation: affiliation,
                                                     echelon: echelon,
                                                     function: function,
                                                     isHeadquarters: isHeadquarters))
        case .controlMeasure: return .controlMeasure(control)
        }
    }

    /// Rotation applied to the live preview. Only meaningful for tactical
    /// control measures — other categories ignore the value.
    private var previewRotation: Double {
        category == .controlMeasure ? rotation : 0
    }

    /// Scale applied to the live preview. Clamped to a reasonable
    /// display range (0.6×–1.4×) so the preview never overflows
    /// the picker cell — the persisted value can still be 0.5×–2.5×.
    private var previewScale: CGFloat {
        guard category == .controlMeasure else { return 1.0 }
        return CGFloat(min(max(scale, 0.6), 1.4))
    }

    private var locationCoordinate: CLLocationCoordinate2D {
        original?.coordinate ?? defaultCoordinate
    }

    private func save() {
        let trimmedName  = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedNotes = notes.trimmingCharacters(in: .whitespacesAndNewlines)
        let parsedElevation = Double(elevationText.trimmingCharacters(in: .whitespaces))

        // Persist rotation + scale only for control measures; reset to
        // defaults otherwise so a user who flips category doesn't carry
        // over stale values.
        let persistedRotation = category == .controlMeasure ? rotation : 0
        let persistedScale    = category == .controlMeasure ? scale    : 1.0

        if let existing = original {
            var updated = existing
            updated.name      = trimmedName
            updated.kind      = currentKind
            updated.notes     = trimmedNotes.isEmpty ? nil : trimmedNotes
            updated.elevation = parsedElevation
            updated.rotation  = persistedRotation
            updated.scale     = persistedScale
            waypointStore.update(updated)
        } else {
            let new = Waypoint(
                name:      trimmedName,
                notes:     trimmedNotes.isEmpty ? nil : trimmedNotes,
                coordinate: defaultCoordinate,
                elevation: parsedElevation,
                kind:      currentKind,
                rotation:  persistedRotation,
                scale:     persistedScale
            )
            waypointStore.add(new)
        }
        dismiss()
    }
}

/// Top-level category in the edit sheet picker.
private enum KindCategory: String, CaseIterable, Hashable {
    case generic, military, controlMeasure

    var displayName: String {
        switch self {
        case .generic:        return "Generic"
        case .military:       return "Military"
        case .controlMeasure: return "Tasks"
        }
    }
}
