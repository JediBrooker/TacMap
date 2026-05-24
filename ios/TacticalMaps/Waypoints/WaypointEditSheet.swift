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
    // Control measure
    @State private var control:     TacticalControlMeasure = .formUpPoint
    @State private var notes: String = ""
    @State private var elevationText: String = ""
    @State private var showDeleteConfirm = false

    init(waypointStore: WaypointStore,
         original: Waypoint? = nil,
         defaultCoordinate: CLLocationCoordinate2D = .init(latitude: 0, longitude: 0)) {
        self.waypointStore = waypointStore
        self.original = original
        self.defaultCoordinate = defaultCoordinate
        if let wp = original {
            _name          = State(initialValue: wp.name)
            _notes         = State(initialValue: wp.notes ?? "")
            _elevationText = State(initialValue: wp.elevation.map { String(Int($0)) } ?? "")
            switch wp.kind {
            case .generic:
                _category = State(initialValue: .generic)
            case .military(let spec):
                _category    = State(initialValue: .military)
                _affiliation = State(initialValue: spec.affiliation)
                _echelon     = State(initialValue: spec.echelon)
                _function    = State(initialValue: spec.function)
            case .controlMeasure(let m):
                _category = State(initialValue: .controlMeasure)
                _control  = State(initialValue: m)
            }
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
                        WaypointKindIcon(kind: currentKind, size: 64)
                            .frame(width: 80, height: 80)
                        Spacer()
                    }
                    .listRowBackground(Color(.systemGroupedBackground))
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
                    }

                case .controlMeasure:
                    Section("Tactical Control Measure") {
                        Picker("Measure", selection: $control) {
                            ForEach(TacticalControlMeasure.allCases, id: \.self) { m in
                                Text(m.displayName).tag(m)
                            }
                        }
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
                            Label("Delete waypoint", systemImage: "trash")
                        }
                    }
                }
            }
            .navigationTitle(original == nil ? "New Waypoint" : "Edit Waypoint")
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
            .alert("Delete waypoint?",
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
                                                     function: function))
        case .controlMeasure: return .controlMeasure(control)
        }
    }

    private var locationCoordinate: CLLocationCoordinate2D {
        original?.coordinate ?? defaultCoordinate
    }

    private func save() {
        let trimmedName  = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedNotes = notes.trimmingCharacters(in: .whitespacesAndNewlines)
        let parsedElevation = Double(elevationText.trimmingCharacters(in: .whitespaces))

        if let existing = original {
            var updated = existing
            updated.name      = trimmedName
            updated.kind      = currentKind
            updated.notes     = trimmedNotes.isEmpty ? nil : trimmedNotes
            updated.elevation = parsedElevation
            waypointStore.update(updated)
        } else {
            let new = Waypoint(
                name:      trimmedName,
                notes:     trimmedNotes.isEmpty ? nil : trimmedNotes,
                coordinate: defaultCoordinate,
                elevation: parsedElevation,
                kind:      currentKind
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
        case .controlMeasure: return "Control"
        }
    }
}
