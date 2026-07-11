import SwiftUI
import CoreLocation

/// Edit (or create) a waypoint. Tapping a row in WaypointListSheet opens
/// this. Rename, change category + APP-6C symbol, edit notes/elevation,
/// or delete.
struct WaypointEditSheet: View {
    @ObservedObject var waypointStore: WaypointStore
    /// nil = creating a new waypoint at defaultCoordinate.
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
    // Marker (airsoft / SAR / POI)
    @State private var markerSet:      MarkerSet = .airsoft
    @State private var markerSymbolID: String    = "team"
    @State private var markerColorHex: String    = "#3B7BE0"
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
            case .marker(let mk):
                _category       = State(initialValue: .marker)
                _markerSet      = State(initialValue: mk.set)
                _markerSymbolID = State(initialValue: mk.symbolID)
                _markerColorHex = State(initialValue: mk.colorHex)
            }
        } else {
            // New control measure: start at zoom-appropriate scale on
            // both axes so symbol enters square at roughly 10% of
            // screen height at current zoom.
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
                    // Custom segmented control b/c SwiftUI's
                    // .pickerStyle(.segmented) on iOS 26 ignores
                    // short taps (< ~200ms). The culprit is some
                    // internal gesture recognizer. Finger taps
                    // mostly work on device but its flaky, and
                    // mouse clicks in simulator just don't register.
                    // Buttons fire on touchUpInside with no minimum
                    // press threshold so this works fine.
                    KindCategorySegmentedPicker(selection: $category)
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }

                switch category {
                case .generic:
                    EmptyView() // plain point, no symbol config

                case .military:
                    Section("Military Unit (APP-6C)") {
                        // Only 4 options so popup menu (default style)
                        // fits fine without scrolling.
                        Picker("Affiliation", selection: $affiliation) {
                            ForEach(SymbolAffiliation.allCases, id: \.self) { a in
                                Text(a.displayName).tag(a)
                            }
                        }
                        // Echelon (7) and Function (~30) use
                        // navigationLink to push a scrollable list.
                        // Popup menu scroll is wonky in iOS 26
                        // simulator and fiddly on device for long
                        // lists.
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

                case .marker:
                    Section("Symbol Set") {
                        Picker("Set", selection: $markerSet) {
                            ForEach(MarkerSet.allCases, id: \.self) { s in
                                Text(s.displayName).tag(s)
                            }
                        }
                        .onChange(of: markerSet) { newSet in
                            // Snap to the new set's first symbol + its colour.
                            let first = MarkerCatalog.entries(for: newSet)[0]
                            markerSymbolID = first.id
                            markerColorHex = first.defaultColorHex
                        }
                    }
                    Section("Symbol") {
                        Picker("Symbol", selection: $markerSymbolID) {
                            ForEach(MarkerCatalog.entries(for: markerSet), id: \.id) { e in
                                Label(e.name, systemImage: e.sfSymbol).tag(e.id)
                            }
                        }
                        .pickerStyle(.navigationLink)
                        .onChange(of: markerSymbolID) { newID in
                            markerColorHex = MarkerCatalog.entry(set: markerSet, id: newID).defaultColorHex
                        }
                    }
                    Section("Colour") {
                        MarkerColorSwatches(selection: $markerColorHex)
                            .listRowInsets(EdgeInsets())
                            .listRowBackground(Color.clear)
                    }

                case .controlMeasure:
                    Section("Tactical Task / Control Measure") {
                        // navigationLink pushes a scrollable list.
                        // Default popup has too many items and the
                        // in-menu scroll silently swallows mouse
                        // events in iOS 26 sim, kinda fiddly to
                        // flick on device too.
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
                            // Quick uniform-scale presets, applied to both
                            // axes (clobbers any aspect-ratio stretch).
                            // Just lets the user go "make it 2x bigger"
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
        // Block swipe-to-dismiss. This is an edit form, accidental
        // swipe shouldn't silently nuke the user's changes.
        .interactiveDismissDisabled()
        // Default iPad form-sheet is too small, APP-6C unit options
        // fall below the fold. Present as large "page" sheet on iPad
        // so the whole builder shows without scrolling (no-op on
        // iPhone / older iOS).
        .padSheetSizing()
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
        case .marker:         return .marker(MarkerSymbol(set: markerSet,
                                                          symbolID: markerSymbolID,
                                                          colorHex: markerColorHex))
        }
    }

    /// Rotation for the live preview. Only meaningful for control
    /// measures, other categories just ignore it.
    private var previewRotation: Double {
        category == .controlMeasure ? rotation : 0
    }

    /// Scale for the live preview. Geometric mean of scaleX/scaleY so
    /// a stretched symbol still looks sensible in the fixed-size preview
    /// cell. Clamped to 0.6x-1.4x for display, persisted values can
    /// still span 0.1x-20x.
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

        // Auto-fill name from kind's displayName when blank so user
        // can just drop a waypoint without typing a label. e.g.
        // control measure -> "Form-Up Point", friendly inf pl ->
        // "Friendly Infantry Platoon", generic -> "Waypoint".
        let resolvedName = trimmedName.isEmpty ? currentKind.displayName : trimmedName

        // Only persist rotation + scale for control measures. Reset to
        // defaults otherwise so flipping category doesn't carry over
        // stale values.
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
    case generic, military, controlMeasure, marker

    var displayName: String {
        switch self {
        case .generic:        return "Point"
        case .military:       return "Military"
        case .controlMeasure: return "Tasks"
        case .marker:         return "Markers"
        }
    }
}

/// Preset colour swatches for markers (the airsoft team colours plus a couple
/// of neutrals), with the current pick ringed.
private struct MarkerColorSwatches: View {
    @Binding var selection: String

    private let swatches: [String] = MarkerCatalog.teamColors.map(\.hex) + ["#8A93A6", "#111417"]

    var body: some View {
        HStack(spacing: 12) {
            ForEach(swatches, id: \.self) { hex in
                Circle()
                    .fill(Color(UIColor(hex: hex)))
                    .frame(width: 30, height: 30)
                    .overlay(
                        Circle().stroke(Color.primary,
                                        lineWidth: selection.caseInsensitiveCompare(hex) == .orderedSame ? 3 : 0)
                    )
                    .overlay(Circle().stroke(Color.white.opacity(0.4), lineWidth: 1))
                    .onTapGesture { selection = hex }
            }
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }
}

/// Drop-in replacement for Picker(.segmented) that works with fast taps
/// and mouse clicks. Apple's segmented picker on iOS 26 has a regression
/// where it ignores touches shorter than ~200ms, which is a dealbreaker
/// for simulator testing and flaky on device too. Plain SwiftUI Buttons
/// don't have that issue.
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
