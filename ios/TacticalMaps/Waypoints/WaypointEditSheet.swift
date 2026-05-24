import SwiftUI
import CoreLocation

/// Edit (or create) a single waypoint. Reachable by tapping any row in
/// `WaypointListSheet`. Lets the user rename, change icon, edit notes /
/// elevation, and delete.
struct WaypointEditSheet: View {
    @ObservedObject var waypointStore: WaypointStore
    /// nil = creating a brand-new waypoint at `defaultCoordinate`.
    let original: Waypoint?
    let defaultCoordinate: CLLocationCoordinate2D
    @Environment(\.dismiss) private var dismiss

    @State private var name: String = ""
    @State private var kind: WaypointKind = .generic
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
            _kind          = State(initialValue: wp.kind)
            _notes         = State(initialValue: wp.notes ?? "")
            _elevationText = State(initialValue: wp.elevation.map { String(Int($0)) } ?? "")
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("e.g. Camp Alpha", text: $name)
                        .autocorrectionDisabled()
                }

                Section {
                    NavigationLink {
                        WaypointKindPicker(selection: $kind)
                    } label: {
                        HStack {
                            Image(systemName: kind.sfSymbol)
                                .foregroundStyle(kind.tint)
                                .frame(width: 26)
                            Text(kind.displayName)
                            Spacer()
                            Text(kind.category.displayName)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }
                } header: { Text("Symbol") }

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

    private var locationCoordinate: CLLocationCoordinate2D {
        original?.coordinate ?? defaultCoordinate
    }

    private func save() {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedNotes = notes.trimmingCharacters(in: .whitespacesAndNewlines)
        let parsedElevation = Double(elevationText.trimmingCharacters(in: .whitespaces))

        if let existing = original {
            var updated = existing
            updated.name      = trimmedName
            updated.kind      = kind
            updated.notes     = trimmedNotes.isEmpty ? nil : trimmedNotes
            updated.elevation = parsedElevation
            waypointStore.update(updated)
        } else {
            let new = Waypoint(
                name:      trimmedName,
                notes:     trimmedNotes.isEmpty ? nil : trimmedNotes,
                coordinate: defaultCoordinate,
                elevation: parsedElevation,
                kind:      kind
            )
            waypointStore.add(new)
        }
        dismiss()
    }
}

/// Grouped picker for `WaypointKind`. Pushed from `WaypointEditSheet`.
private struct WaypointKindPicker: View {
    @Binding var selection: WaypointKind
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            ForEach(WaypointCategory.allCases, id: \.self) { category in
                Section(category.displayName) {
                    ForEach(category.kinds, id: \.self) { kind in
                        Button {
                            selection = kind
                            dismiss()
                        } label: {
                            HStack {
                                Image(systemName: kind.sfSymbol)
                                    .foregroundStyle(kind.tint)
                                    .frame(width: 28)
                                Text(kind.displayName)
                                    .foregroundStyle(.primary)
                                Spacer()
                                if kind == selection {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(.tint)
                                }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Symbol")
        .navigationBarTitleDisplayMode(.inline)
    }
}
