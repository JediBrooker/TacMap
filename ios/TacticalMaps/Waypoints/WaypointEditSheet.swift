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
    @State private var scaleX:      Double                 = 1.0
    @State private var scaleY:      Double                 = 1.0
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
            _scaleX        = State(initialValue: wp.scaleX)
            _scaleY        = State(initialValue: wp.scaleY)
            switch wp.kind {
            case .generic:
                _category = State(initialValue: .military)
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
            // scale on BOTH axes so the symbol enters square at roughly
            // 10% of screen height at the current zoom level.
            _scaleX = State(initialValue: defaultScale)
            _scaleY = State(initialValue: defaultScale)
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(currentKind.displayName, text: $name)
                        .autocorrectionDisabled()
                } header: { Text("Name") } footer: {
                    Text("Optional — leave blank to use the symbol's name automatically.")
                        .font(.caption2)
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
                    // Custom segmented control. SwiftUI's
                    // `.pickerStyle(.segmented)` on iOS 26 ignores
                    // short taps (< ~200ms), which makes the bug
                    // "I can't click Tasks" — finger taps mostly
                    // work on device but it's flaky, and mouse
                    // clicks in the simulator straight up don't
                    // register. Buttons fire on touchUpInside with
                    // no minimum-press threshold.
                    KindCategorySegmentedPicker(selection: $category)
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }

                switch category {
                case .military:
                    Section("Military Unit (APP-6C)") {
                        // Affiliation is only 4 options — popup menu
                        // (default style) fits comfortably with no
                        // scrolling needed.
                        Picker("Affiliation", selection: $affiliation) {
                            ForEach(SymbolAffiliation.allCases, id: \.self) { a in
                                Text(a.displayName).tag(a)
                            }
                        }
                        // Echelon (7) and Function (~30) push to a
                        // dedicated scrollable List instead of using
                        // the default popup menu — popup menu scroll
                        // is unreliable in iOS 26 simulator and
                        // fiddly on device for long lists.
                        Picker("Echelon", selection: $echelon) {
                            ForEach(SymbolEchelon.allCases, id: \.self) { e in
                                Text(e.displayName).tag(e)
                            }
                        }
                        .pickerStyle(.navigationLink)
                        Picker("Function / Branch", selection: $function) {
                            ForEach(SymbolFunction.allCases, id: \.self) { f in
                                Text(f.displayName).tag(f)
                            }
                        }
                        .pickerStyle(.navigationLink)
                        Toggle("Headquarters", isOn: $isHeadquarters)
                    }

                case .controlMeasure:
                    Section("Tactical Task / Control Measure") {
                        // .navigationLink pushes a real scrollable List
                        // onto the navigation stack. The default
                        // popup-menu style has too many items to fit
                        // on screen and the in-menu scroll doesn't
                        // work reliably (silently swallows mouse-wheel
                        // events in iOS 26 simulator, and is fiddly to
                        // flick on device).
                        Picker("Measure", selection: $control) {
                            ForEach(TacticalControlMeasure.pickerEntries, id: \.self) { m in
                                Text(m.displayName).tag(m)
                            }
                        }
                        .pickerStyle(.navigationLink)
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
                        VStack(alignment: .leading, spacing: 12) {
                            // Width
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Label("Width", systemImage: "arrow.left.and.right")
                                    Spacer()
                                    Text(String(format: "%.2f×", scaleX))
                                        .font(.subheadline.monospacedDigit())
                                        .foregroundStyle(.secondary)
                                    Button("Reset") { scaleX = 1.0 }
                                        .buttonStyle(.bordered)
                                        .controlSize(.small)
                                }
                                Slider(value: $scaleX, in: 0.1...20.0, step: 0.1)
                            }
                            // Height
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Label("Height", systemImage: "arrow.up.and.down")
                                    Spacer()
                                    Text(String(format: "%.2f×", scaleY))
                                        .font(.subheadline.monospacedDigit())
                                        .foregroundStyle(.secondary)
                                    Button("Reset") { scaleY = 1.0 }
                                        .buttonStyle(.bordered)
                                        .controlSize(.small)
                                }
                                Slider(value: $scaleY, in: 0.1...20.0, step: 0.1)
                            }
                            // Quick uniform-scale presets — applied to BOTH axes
                            // (and any prior aspect-ratio stretch is lost). Lets
                            // the user say "make the whole thing 2× bigger"
                            // without dragging both sliders.
                            HStack {
                                Text("Both:")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                Spacer()
                                ForEach([0.5, 1.0, 2.0, 5.0, 10.0], id: \.self) { s in
                                    Button(String(format: "%g×", s)) {
                                        scaleX = s; scaleY = s
                                    }
                                    .buttonStyle(.bordered)
                                    .controlSize(.small)
                                }
                            }
                        }
                    } header: { Text("Size") } footer: {
                        Text("Independent width and height multipliers — stretch the symbol wider/thinner or longer/shorter. The geographic footprint scales with the map zoom.")
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
                let label = name.trimmingCharacters(in: .whitespaces).isEmpty
                    ? (original?.name ?? currentKind.displayName)
                    : name
                Text("This will permanently remove “\(label)”.")
            }
        }
        // The default iPad form-sheet is too small — the APP-6C unit options
        // (affiliation / echelon / function / HQ) fall below the fold. On
        // iOS 18+ present it as a large "page" sheet on iPad so the whole
        // builder shows without scrolling (no-op on iPhone / older iOS).
        .padSheetSizing()
    }

    /// Current kind derived from the live editor state.
    private var currentKind: WaypointKind {
        switch category {
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

    /// Scale applied to the live preview. Uses the geometric mean of
    /// scaleX/scaleY so a stretched symbol still shows at a sensible
    /// size in the fixed-size preview cell. Clamped to a reasonable
    /// display range (0.6×–1.4×); the persisted values can still span
    /// 0.1×–20×.
    private var previewScale: CGFloat {
        guard category == .controlMeasure else { return 1.0 }
        let mean = (scaleX * scaleY).squareRoot()
        return CGFloat(min(max(mean, 0.6), 1.4))
    }

    private var locationCoordinate: CLLocationCoordinate2D {
        original?.coordinate ?? defaultCoordinate
    }

    private func save() {
        let trimmedName  = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedNotes = notes.trimmingCharacters(in: .whitespacesAndNewlines)
        let parsedElevation = Double(elevationText.trimmingCharacters(in: .whitespaces))

        // Auto-fill the name with the kind's display name when blank
        // so the user can drop a waypoint without thinking about a
        // label. e.g. a tactical control measure becomes "Form-Up
        // Point"; a friendly infantry platoon becomes "Friendly
        // Infantry Platoon"; a generic waypoint becomes "Waypoint".
        let resolvedName = trimmedName.isEmpty ? currentKind.displayName : trimmedName

        // Persist rotation + scale only for control measures; reset to
        // defaults otherwise so a user who flips category doesn't carry
        // over stale values.
        let persistedRotation = category == .controlMeasure ? rotation : 0
        let persistedScaleX   = category == .controlMeasure ? scaleX   : 1.0
        let persistedScaleY   = category == .controlMeasure ? scaleY   : 1.0

        if let existing = original {
            var updated = existing
            updated.name      = resolvedName
            updated.kind      = currentKind
            updated.notes     = trimmedNotes.isEmpty ? nil : trimmedNotes
            updated.elevation = parsedElevation
            updated.rotation  = persistedRotation
            updated.scaleX    = persistedScaleX
            updated.scaleY    = persistedScaleY
            waypointStore.update(updated)
        } else {
            let new = Waypoint(
                name:      resolvedName,
                notes:     trimmedNotes.isEmpty ? nil : trimmedNotes,
                coordinate: defaultCoordinate,
                elevation: parsedElevation,
                kind:      currentKind,
                rotation:  persistedRotation,
                scaleX:    persistedScaleX,
                scaleY:    persistedScaleY
            )
            waypointStore.add(new)
        }
        dismiss()
    }
}

/// Top-level category in the edit sheet picker.
private enum KindCategory: String, CaseIterable, Hashable {
    case military, controlMeasure

    var displayName: String {
        switch self {
        case .military:       return "Military"
        case .controlMeasure: return "Tasks"
        }
    }
}

/// Drop-in replacement for `Picker(...).pickerStyle(.segmented)` that
/// works with fast taps and mouse clicks. Apple's segmented picker on
/// iOS 26 has a regression where it ignores touches shorter than
/// ~200ms — a deal-breaker for simulator testing with a mouse and
/// flaky on real devices for users with quick fingers. SwiftUI
/// `Button` doesn't have that issue.
private struct KindCategorySegmentedPicker: View {
    @Binding var selection: KindCategory

    var body: some View {
        HStack(spacing: 4) {
            ForEach(KindCategory.allCases, id: \.self) { kind in
                Button {
                    selection = kind
                } label: {
                    Text(kind.displayName)
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .background(
                            RoundedRectangle(cornerRadius: 8, style: .continuous)
                                .fill(selection == kind
                                      ? Color.accentColor.opacity(0.85)
                                      : Color.clear)
                        )
                        .foregroundStyle(selection == kind
                                         ? Color.white
                                         : Color.primary)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(4)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(Color(.tertiarySystemFill))
        )
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }
}
