import SwiftUI
import CoreLocation

struct SymbolEditDraft: Equatable {
    static let fieldOrder = [
        "name", "kind", "notes", "elevationMetres", "layerId", "taskColor",
        "higherFormation", "uniqueIdentifier", "reinforcementStatus", "mgrs",
        "rotationDegrees", "scaleX", "scaleY", "delete",
    ]
    static var elevationValidationError: String { L10n.text("Enter a valid elevation in metres.") }

    var name: String
    var kind: WaypointKind
    var notes: String
    var elevationText: String
    var layerID: UUID
    var taskColor: TaskColor
    var rotationDegrees: Double
    var scaleX: Double
    var scaleY: Double
    var higherFormation: String
    var uniqueIdentifier: String
    var reinforcementStatus: ReinforcementStatus
    private(set) var latitude: Double
    private(set) var longitude: Double
    private(set) var isMoveStaged: Bool

    init(waypoint: Waypoint) {
        name = waypoint.name
        kind = waypoint.kind
        notes = waypoint.notes ?? ""
        elevationText = waypoint.elevation.map { String($0) } ?? ""
        layerID = waypoint.layerID
        taskColor = waypoint.taskColor
        rotationDegrees = waypoint.rotation
        scaleX = waypoint.scaleX
        scaleY = waypoint.scaleY
        higherFormation = waypoint.higherFormation ?? ""
        uniqueIdentifier = waypoint.uniqueIdentifier ?? ""
        reinforcementStatus = waypoint.reinforcementStatus
        latitude = waypoint.latitude
        longitude = waypoint.longitude
        isMoveStaged = false
    }

    enum ValidationError: LocalizedError, Equatable {
        case invalidElevation
        var errorDescription: String? { SymbolEditDraft.elevationValidationError }
    }

    func applying(to waypoint: Waypoint,
                  availableLayerIDs: Set<UUID>,
                  fallbackLayerID: UUID) throws -> Waypoint {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedNotes = notes.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedElevation = elevationText.trimmingCharacters(in: .whitespacesAndNewlines)
        let elevation: Double?
        if trimmedElevation.isEmpty {
            elevation = nil
        } else if let parsed = DecimalInput.parse(trimmedElevation) {
            elevation = parsed
        } else {
            throw ValidationError.invalidElevation
        }

        var updated = waypoint
        updated.name = trimmedName.isEmpty ? kind.displayName : trimmedName
        updated.kind = kind
        updated.notes = trimmedNotes.isEmpty ? nil : trimmedNotes
        updated.elevation = elevation
        updated.layerID = availableLayerIDs.contains(layerID) ? layerID : fallbackLayerID
        if isMoveStaged {
            updated.latitude = latitude
            updated.longitude = longitude
        }
        if kind.controlMeasure != nil {
            let finiteRotation = rotationDegrees.isFinite ? rotationDegrees : 0
            let remainder = finiteRotation.truncatingRemainder(dividingBy: 360)
            updated.rotation = remainder < 0 ? remainder + 360 : remainder
            updated.scaleX = Self.clampedScale(scaleX)
            updated.scaleY = Self.clampedScale(scaleY)
            updated.taskColor = taskColor
        } else {
            updated.rotation = 0
            updated.scaleX = 1
            updated.scaleY = 1
            updated.taskColor = .black
        }
        if kind.militarySpec != nil {
            updated.higherFormation = UnitAmplifierText.normalized(
                higherFormation,
                maximumLength: UnitAmplifierText.higherFormationMaxLength)
            updated.uniqueIdentifier = UnitAmplifierText.normalized(
                uniqueIdentifier,
                maximumLength: UnitAmplifierText.uniqueIdentifierMaxLength)
            updated.reinforcementStatus = reinforcementStatus
        } else {
            updated.higherFormation = nil
            updated.uniqueIdentifier = nil
            updated.reinforcementStatus = .none
        }
        return updated
    }

    mutating func stageMove(to coordinate: CLLocationCoordinate2D) {
        latitude = coordinate.latitude
        longitude = coordinate.longitude
        isMoveStaged = true
    }

    mutating func discardStagedMove() {
        isMoveStaged = false
    }

    var stagedCoordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    mutating func resetRotation() { rotationDegrees = 0 }
    mutating func resetWidth() { scaleX = 1 }
    mutating func resetHeight() { scaleY = 1 }

    func hasStagedMove(from waypoint: Waypoint) -> Bool {
        isMoveStaged && (latitude != waypoint.latitude || longitude != waypoint.longitude)
    }

    private static func clampedScale(_ value: Double) -> Double {
        guard value.isFinite else { return 1 }
        return min(max(value, 0.1), 20)
    }
}

/// Shared labels and target-size contract used by the production SwiftUI
/// controls and by fast unit tests when a hosted accessibility tree is absent.
enum SymbolEditorAccessibility {
    static let minimumTargetPoints: CGFloat = 44
    static var closeLabel: String { L10n.text("Close symbol editor") }
    static var moveLabel: String { L10n.text("Move symbol to crosshair") }
    static var deleteLabel: String { L10n.text("Delete symbol") }
    static var rotationLabel: String { L10n.text("Rotation") }
    static var widthLabel: String { L10n.text("Width scale") }
    static var heightLabel: String { L10n.text("Height scale") }
    static var resetRotationLabel: String { L10n.text("Reset rotation") }
    static var resetWidthLabel: String { L10n.text("Reset width scale") }
    static var resetHeightLabel: String { L10n.text("Reset height scale") }
    static var requiredLabels: [String] { [closeLabel, moveLabel, deleteLabel,
                                 rotationLabel, widthLabel, heightLabel] }

    static var markerSwatches: [(name: String, hex: String)] {
        MarkerCatalog.teamColors + [(name: L10n.text("Slate"), hex: "#8A93A6"),
                                    (name: L10n.text("Black"), hex: "#111417")]
    }

    static func markerLabel(for hex: String) -> String {
        let name = markerSwatches.first {
            $0.hex.caseInsensitiveCompare(hex) == .orderedSame
        }?.name ?? hex
        return L10n.text("Marker colour, %1$@", name)
    }
}

/// Immutable option sources and stable enum IDs keep long selection lists from
/// being replaced when an observed store publishes while the user is scrolling.
enum SymbolPickerOptions {
    static let echelons = SymbolEchelon.allCases
    static let functions = SymbolFunction.allCases
    static let controlMeasures = TacticalControlMeasure.pickerEntries
}

/// Builder for a brand-new symbol. Existing symbols always use the
/// transactional `SelectedSymbolEditSheet` below.
struct WaypointCreationSheet: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @ObservedObject var waypointStore: WaypointStore
    let defaultCoordinate: CLLocationCoordinate2D
    let defaultLayerID: UUID
    @Environment(\.dismiss) private var dismiss

    @State private var name: String = ""
    @State private var category: KindCategory = .military
    // Military
    @State private var affiliation: SymbolAffiliation = .friend
    @State private var echelon:     SymbolEchelon     = .platoon
    @State private var function:    SymbolFunction    = .infantry
    @State private var isHeadquarters: Bool            = false
    @State private var higherFormation: String          = ""
    @State private var uniqueIdentifier: String         = ""
    @State private var reinforcementStatus: ReinforcementStatus = .none
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
    @State private var errorMessage: String?

    init(waypointStore: WaypointStore,
         defaultCoordinate: CLLocationCoordinate2D = .init(latitude: 0, longitude: 0),
         defaultScale: Double = 1.0,
         defaultLayerID: UUID = DrawingLayer.legacyFallbackID) {
        self.waypointStore = waypointStore
        self.defaultCoordinate = defaultCoordinate
        self.defaultLayerID = defaultLayerID
        // New control measure: start at zoom-appropriate scale on both axes
        // so the symbol enters square at roughly 10% of screen height.
        _scaleX = State(initialValue: defaultScale)
        _scaleY = State(initialValue: defaultScale)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(currentKind.displayName, text: $name)
                        .autocorrectionDisabled()
                } header: { Text(L10n.text("Name")) } footer: {
                    Text(L10n.text("Optional — leave blank to use the symbol's name automatically."))
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
                } header: { Text(L10n.text("Preview")) }

                Section(L10n.text("Type")) {
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
                    Section(L10n.text("Military Unit (APP-6C)")) {
                        // Only 4 options so popup menu (default style)
                        // fits fine without scrolling.
                        Picker(L10n.text("Affiliation"), selection: $affiliation) {
                            ForEach(SymbolAffiliation.allCases, id: \.self) { a in
                                Text(a.displayName).tag(a)
                            }
                        }
                        StableNavigationSelection(
                            title: L10n.text("Echelon"),
                            stableID: "create-unit-echelon",
                            options: SymbolPickerOptions.echelons,
                            selection: $echelon,
                            optionTitle: { $0.displayName }
                        )
                        StableNavigationSelection(
                            title: L10n.text("Function / Branch"),
                            stableID: "create-unit-function",
                            options: SymbolPickerOptions.functions,
                            selection: $function,
                            optionTitle: { $0.displayName }
                        )
                        Toggle(L10n.text("Headquarters"), isOn: $isHeadquarters)
                    }
                    Section {
                        UnitAmplifierFields(
                            higherFormation: $higherFormation,
                            uniqueIdentifier: $uniqueIdentifier,
                            reinforcementStatus: $reinforcementStatus
                        )
                    } header: {
                        Text(L10n.text("Unit Amplifiers"))
                    } footer: {
                        Text(L10n.text("M: higher formation (bottom-right). T: unit callsign / unique identifier (bottom-left). F: reinforced or reduced (upper-right)."))
                            .font(.caption2)
                    }

                case .marker:
                    Section(L10n.text("Symbol Set")) {
                        Picker(L10n.text("Set"), selection: $markerSet) {
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
                    Section(L10n.text("Symbol")) {
                        Picker(L10n.text("Symbol"), selection: $markerSymbolID) {
                            ForEach(MarkerCatalog.entries(for: markerSet), id: \.id) { e in
                                Label(e.name, systemImage: e.sfSymbol).tag(e.id)
                            }
                        }
                        .pickerStyle(.navigationLink)
                        .onChange(of: markerSymbolID) { newID in
                            markerColorHex = MarkerCatalog.entry(set: markerSet, id: newID).defaultColorHex
                        }
                    }
                    Section(L10n.text("Colour")) {
                        MarkerColorSwatches(selection: $markerColorHex)
                            .listRowInsets(EdgeInsets())
                            .listRowBackground(Color.clear)
                    }

                case .controlMeasure:
                    Section(L10n.text("Tactical Task / Control Measure")) {
                        StableNavigationSelection(
                            title: L10n.text("Measure"),
                            stableID: "create-task-measure",
                            options: SymbolPickerOptions.controlMeasures,
                            selection: $control,
                            optionTitle: { $0.displayName }
                        )
                    }
                    Section {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(L10n.text("Rotation"))
                                Spacer()
                                Text("\(Int(rotation.rounded()))°")
                                    .font(.subheadline.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                            Slider(value: $rotation, in: 0...360, step: 1)
                                .accessibilityLabel(SymbolEditorAccessibility.rotationLabel)
                                .accessibilityValue("\(Int(rotation.rounded()))°")
                                .frame(minHeight: SymbolEditorAccessibility.minimumTargetPoints)
                            HStack {
                                Button(L10n.text("Reset")) { rotation = 0 }
                                    .buttonStyle(.bordered)
                                    .frame(minWidth: SymbolEditorAccessibility.minimumTargetPoints,
                                           minHeight: SymbolEditorAccessibility.minimumTargetPoints)
                                    .accessibilityLabel(SymbolEditorAccessibility.resetRotationLabel)
                                Spacer()
                                ForEach([0, 90, 180, 270], id: \.self) { deg in
                                    Button("\(deg)°") { rotation = Double(deg) }
                                        .buttonStyle(.bordered)
                                        .frame(minWidth: SymbolEditorAccessibility.minimumTargetPoints,
                                               minHeight: SymbolEditorAccessibility.minimumTargetPoints)
                                        .accessibilityLabel(L10n.text("Set rotation to %1$@ degrees", deg))
                                }
                            }
                        }
                    } header: { Text(L10n.text("Orientation")) } footer: {
                        Text(L10n.text("Rotate the symbol to indicate direction (e.g. axis of advance, ambush facing)."))
                            .font(.caption2)
                    }
                    Section {
                        VStack(alignment: .leading, spacing: 12) {
                            // Width
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Label(L10n.text("Width"), systemImage: "arrow.left.and.right")
                                    Spacer()
                                    Text(DisplayFormat.number(scaleX, decimals: 2) + "×")
                                        .font(.subheadline.monospacedDigit())
                                        .foregroundStyle(.secondary)
                                    Button(L10n.text("Reset")) { scaleX = 1.0 }
                                        .buttonStyle(.bordered)
                                        .frame(minWidth: SymbolEditorAccessibility.minimumTargetPoints,
                                               minHeight: SymbolEditorAccessibility.minimumTargetPoints)
                                        .accessibilityLabel(SymbolEditorAccessibility.resetWidthLabel)
                                }
                                Slider(value: $scaleX, in: 0.1...20.0, step: 0.1)
                                    .accessibilityLabel(SymbolEditorAccessibility.widthLabel)
                                    .accessibilityValue(DisplayFormat.number(scaleX, decimals: 1) + "×")
                                    .frame(minHeight: SymbolEditorAccessibility.minimumTargetPoints)
                            }
                            // Height
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Label(L10n.text("Height"), systemImage: "arrow.up.and.down")
                                    Spacer()
                                    Text(DisplayFormat.number(scaleY, decimals: 2) + "×")
                                        .font(.subheadline.monospacedDigit())
                                        .foregroundStyle(.secondary)
                                    Button(L10n.text("Reset")) { scaleY = 1.0 }
                                        .buttonStyle(.bordered)
                                        .frame(minWidth: SymbolEditorAccessibility.minimumTargetPoints,
                                               minHeight: SymbolEditorAccessibility.minimumTargetPoints)
                                        .accessibilityLabel(SymbolEditorAccessibility.resetHeightLabel)
                                }
                                Slider(value: $scaleY, in: 0.1...20.0, step: 0.1)
                                    .accessibilityLabel(SymbolEditorAccessibility.heightLabel)
                                    .accessibilityValue(DisplayFormat.number(scaleY, decimals: 1) + "×")
                                    .frame(minHeight: SymbolEditorAccessibility.minimumTargetPoints)
                            }
                            // Quick uniform-scale presets, applied to both
                            // axes (clobbers any aspect-ratio stretch).
                            // Just lets the user go "make it 2x bigger"
                            // without dragging both sliders.
                            HStack {
                                Text(L10n.text("Both:"))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                Spacer()
                                ForEach([0.5, 1.0, 2.0, 5.0, 10.0], id: \.self) { s in
                                    Button(DisplayFormat.number(s, decimals: s < 1 ? 1 : 0) + "×") {
                                        scaleX = s; scaleY = s
                                    }
                                    .buttonStyle(.bordered)
                                    .frame(minWidth: SymbolEditorAccessibility.minimumTargetPoints,
                                           minHeight: SymbolEditorAccessibility.minimumTargetPoints)
                                    .accessibilityLabel(L10n.text("Set width and height to %1$@ times", DisplayFormat.number(s, decimals: s < 1 ? 1 : 0)))
                                }
                            }
                        }
                    } header: { Text(L10n.text("Size")) } footer: {
                        Text(L10n.text("Independent width and height multipliers — stretch the symbol wider/thinner or longer/shorter. The geographic footprint scales with the map zoom."))
                            .font(.caption2)
                    }
                }

                Section(L10n.text("Notes")) {
                    TextField(L10n.text("Optional"), text: $notes, axis: .vertical)
                        .lineLimit(3...6)
                }

                Section(L10n.text("Elevation (metres)")) {
                    TextField(L10n.text("Optional — leave blank for none"), text: $elevationText)
                        .keyboardType(.numbersAndPunctuation)
                    Text(Messages.decimalInputHint()).font(.caption).foregroundStyle(.secondary)
                }

                Section {
                    HStack {
                        Text(L10n.text("Location"))
                        Spacer()
                        Text(MGRSFormatter.string(from: locationCoordinate))
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                    }
                } header: { Text(L10n.text("Position")) } footer: {
                    Text(L10n.text("The new symbol will be placed at the current map crosshair."))
                        .font(.caption2)
                }

            }
            .navigationTitle(L10n.text("New Symbol"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(L10n.text("Cancel")) { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(L10n.text("Save")) { save() }
                        .bold()
                }
            }
            .alert(L10n.text("Could Not Save Symbol"),
                   isPresented: Binding(get: { errorMessage != nil },
                                        set: { if !$0 { errorMessage = nil } }),
                   presenting: errorMessage) { _ in
                Button(Messages.acknowledge(), role: .cancel) { errorMessage = nil }
            } message: { Text($0) }
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
        defaultCoordinate
    }

    private func save() {
        let trimmedName  = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedNotes = notes.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedElevation = elevationText.trimmingCharacters(in: .whitespacesAndNewlines)
        let parsedElevation: Double?
        if trimmedElevation.isEmpty {
            parsedElevation = nil
        } else if let parsed = DecimalInput.parse(trimmedElevation) {
            parsedElevation = parsed
        } else {
            errorMessage = SymbolEditDraft.elevationValidationError
            return
        }

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

        let new = Waypoint(
            name:      resolvedName,
            notes:     trimmedNotes.isEmpty ? nil : trimmedNotes,
            coordinate: defaultCoordinate,
            elevation: parsedElevation,
            kind:      currentKind,
            rotation:  persistedRotation,
            scaleX:    persistedScaleX,
            scaleY:    persistedScaleY,
            higherFormation: category == .military ? higherFormation : nil,
            uniqueIdentifier: category == .military ? uniqueIdentifier : nil,
            reinforcementStatus: category == .military ? reinforcementStatus : .none,
            layerID:   defaultLayerID
        )
        do {
            _ = try waypointStore.addDurably(new)
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

/// Transactional editor used by every existing-symbol entry point. Map moves
/// live on the selected-symbol quick-action card; Save and confirmed Delete
/// each perform one durable-before-publish store mutation.
struct SelectedSymbolEditSheet: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @ObservedObject var waypointStore: WaypointStore
    @ObservedObject var drawingStore: DrawingStore
    let waypoint: Waypoint
    let onDeleted: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var draft: SymbolEditDraft
    @State private var category: KindCategory
    @State private var errorMessage: String?
    @State private var showDeleteConfirmation = false
    @State private var mgrsInput: String
    @State private var lastAppliedMGRSInput: String
    @State private var mgrsError: String?
    @State private var mgrsMoveExplicitlyApplied = false
    private let initialMGRSInput: String

    init(waypointStore: WaypointStore,
         drawingStore: DrawingStore,
         waypoint: Waypoint,
         onDeleted: @escaping () -> Void = {}) {
        self.waypointStore = waypointStore
        self.drawingStore = drawingStore
        self.waypoint = waypoint
        self.onDeleted = onDeleted
        let grid = MGRSFormatter.string(from: waypoint.coordinate)
        initialMGRSInput = grid
        _draft = State(initialValue: SymbolEditDraft(waypoint: waypoint))
        _category = State(initialValue: Self.category(for: waypoint.kind))
        _mgrsInput = State(initialValue: grid)
        _lastAppliedMGRSInput = State(initialValue: grid)
        _mgrsError = State(initialValue: nil)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section(L10n.text("Name")) {
                    TextField(draft.kind.displayName, text: $draft.name)
                        .autocorrectionDisabled()
                        .frame(minHeight: 44)
                }

                kindSections

                if draft.kind.militarySpec != nil {
                    Section {
                        UnitAmplifierFields(
                            higherFormation: $draft.higherFormation,
                            uniqueIdentifier: $draft.uniqueIdentifier,
                            reinforcementStatus: $draft.reinforcementStatus
                        )
                    } header: {
                        Text(L10n.text("Unit Amplifiers"))
                    } footer: {
                        Text(L10n.text("Separate from Unit Labels. M is bottom-right, T is bottom-left, and F is upper-right."))
                            .font(.caption2)
                    }
                }

                mgrsLocationSection

                Section(L10n.text("Notes")) {
                    TextField(L10n.text("Optional"), text: $draft.notes, axis: .vertical)
                        .lineLimit(3...6)
                        .frame(minHeight: 44)
                }

                Section(L10n.text("Elevation (metres)")) {
                    TextField(L10n.text("Optional — leave blank for none"), text: $draft.elevationText)
                        .keyboardType(.numbersAndPunctuation)
                        .frame(minHeight: 44)
                    Text(Messages.decimalInputHint()).font(.caption).foregroundStyle(.secondary)
                }

                Section(L10n.text("Layer")) {
                    Picker(L10n.text("Layer"), selection: $draft.layerID) {
                        ForEach(drawingStore.layers) { layer in
                            Text(layer.name).tag(layer.id)
                        }
                    }
                    .pickerStyle(.navigationLink)
                    .frame(minHeight: 44)
                }

                if draft.kind.controlMeasure != nil {
                    taskColourSection
                    controlValueSection(
                        title: L10n.text("Rotation"),
                        value: $draft.rotationDegrees,
                        range: 0...359,
                        step: 1,
                        valueText: "\(Int(draft.rotationDegrees.rounded()))°",
                        accessibilityLabel: SymbolEditorAccessibility.rotationLabel,
                        resetLabel: SymbolEditorAccessibility.resetRotationLabel,
                        reset: { draft.resetRotation() }
                    )
                    controlValueSection(
                        title: L10n.text("Width Scale"),
                        value: $draft.scaleX,
                        range: 0.1...20,
                        step: 0.1,
                        valueText: DisplayFormat.number(draft.scaleX, decimals: 1) + "×",
                        accessibilityLabel: SymbolEditorAccessibility.widthLabel,
                        resetLabel: SymbolEditorAccessibility.resetWidthLabel,
                        reset: { draft.resetWidth() }
                    )
                    controlValueSection(
                        title: L10n.text("Height Scale"),
                        value: $draft.scaleY,
                        range: 0.1...20,
                        step: 0.1,
                        valueText: DisplayFormat.number(draft.scaleY, decimals: 1) + "×",
                        accessibilityLabel: SymbolEditorAccessibility.heightLabel,
                        resetLabel: SymbolEditorAccessibility.resetHeightLabel,
                        reset: { draft.resetHeight() }
                    )
                }

                Section {
                    Button(role: .destructive) {
                        showDeleteConfirmation = true
                    } label: {
                        Label(L10n.text("Delete symbol"), systemImage: "trash")
                            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    }
                    .accessibilityLabel(SymbolEditorAccessibility.deleteLabel)
                }
            }
            .navigationTitle(L10n.text("Edit Symbol"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(L10n.text("Cancel")) { dismiss() }
                        .fixedSize(horizontal: true, vertical: false)
                        .frame(minWidth: 64, minHeight: 44)
                        .accessibilityLabel(SymbolEditorAccessibility.closeLabel)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(L10n.text("Save"), action: save)
                        .bold()
                        .frame(minWidth: 44, minHeight: 44)
                }
            }
            .alert(L10n.text("Symbol Change Failed"),
                   isPresented: Binding(get: { errorMessage != nil },
                                        set: { if !$0 { errorMessage = nil } }),
                   presenting: errorMessage) { _ in
                Button(Messages.acknowledge(), role: .cancel) { errorMessage = nil }
            } message: { Text($0) }
            .confirmationDialog(
                L10n.text("Delete “%1$@”?", waypoint.name),
                isPresented: $showDeleteConfirmation,
                titleVisibility: .visible
            ) {
                Button(L10n.text("Delete symbol"), role: .destructive, action: deleteSymbol)
                Button(L10n.text("Cancel"), role: .cancel) { }
            }
        }
        .interactiveDismissDisabled()
        .padSheetSizing()
    }

    @ViewBuilder
    private var kindSections: some View {
        Section(L10n.text("Kind")) {
            KindCategorySegmentedPicker(selection: Binding(
                get: { category },
                set: { selectCategory($0) }
            ))
            .listRowInsets(EdgeInsets())
            .listRowBackground(Color.clear)

            HStack {
                Spacer()
                WaypointKindIcon(kind: draft.kind,
                                 size: 64,
                                 rotation: draft.kind.controlMeasure == nil ? 0 : draft.rotationDegrees,
                                 taskColor: draft.taskColor)
                    .frame(width: 100, height: 100)
                Spacer()
            }
            .listRowBackground(Color.white)

            switch draft.kind {
            case .generic:
                EmptyView()
            case .military(let spec):
                militaryPickers(spec)
            case .controlMeasure(let measure):
                StableNavigationSelection(
                    title: L10n.text("Measure"),
                    stableID: "edit-task-measure",
                    options: SymbolPickerOptions.controlMeasures,
                    selection: Binding(
                        get: { draft.kind.controlMeasure ?? measure },
                        set: { draft.kind = .controlMeasure($0) }
                    ),
                    optionTitle: { $0.displayName }
                )
            case .marker(let marker):
                markerPickers(marker)
            }
        }
    }

    @ViewBuilder
    private func militaryPickers(_ spec: MilitarySymbolSpec) -> some View {
        Picker(L10n.text("Affiliation"), selection: Binding(
            get: { spec.affiliation },
            set: { draft.kind = .military(.init(affiliation: $0,
                                                 echelon: spec.echelon,
                                                 function: spec.function,
                                                 isHeadquarters: spec.isHeadquarters)) }
        )) {
            ForEach(SymbolAffiliation.allCases, id: \.self) { Text($0.displayName).tag($0) }
        }
        StableNavigationSelection(
            title: L10n.text("Echelon"),
            stableID: "edit-unit-echelon",
            options: SymbolPickerOptions.echelons,
            selection: Binding(
                get: { draft.kind.militarySpec?.echelon ?? spec.echelon },
                set: { newEchelon in
                    guard let current = draft.kind.militarySpec else { return }
                    draft.kind = .military(.init(
                        affiliation: current.affiliation,
                        echelon: newEchelon,
                        function: current.function,
                        isHeadquarters: current.isHeadquarters))
                }
            ),
            optionTitle: { $0.displayName }
        )
        StableNavigationSelection(
            title: L10n.text("Function / Branch"),
            stableID: "edit-unit-function",
            options: SymbolPickerOptions.functions,
            selection: Binding(
                get: { draft.kind.militarySpec?.function ?? spec.function },
                set: { newFunction in
                    guard let current = draft.kind.militarySpec else { return }
                    draft.kind = .military(.init(
                        affiliation: current.affiliation,
                        echelon: current.echelon,
                        function: newFunction,
                        isHeadquarters: current.isHeadquarters))
                }
            ),
            optionTitle: { $0.displayName }
        )
        Toggle(L10n.text("Headquarters"), isOn: Binding(
            get: { spec.isHeadquarters },
            set: { draft.kind = .military(.init(affiliation: spec.affiliation,
                                                 echelon: spec.echelon,
                                                 function: spec.function,
                                                 isHeadquarters: $0)) }
        ))
    }

    @ViewBuilder
    private func markerPickers(_ marker: MarkerSymbol) -> some View {
        Picker(L10n.text("Set"), selection: Binding(
            get: { marker.set },
            set: { newSet in
                let first = MarkerCatalog.entries(for: newSet)[0]
                draft.kind = .marker(.init(set: newSet,
                                           symbolID: first.id,
                                           colorHex: first.defaultColorHex))
            }
        )) {
            ForEach(MarkerSet.allCases, id: \.self) { Text($0.displayName).tag($0) }
        }
        Picker(L10n.text("Symbol"), selection: Binding(
            get: { marker.symbolID },
            set: { draft.kind = .marker(.init(set: marker.set,
                                               symbolID: $0,
                                               colorHex: MarkerCatalog.entry(set: marker.set, id: $0).defaultColorHex)) }
        )) {
            ForEach(MarkerCatalog.entries(for: marker.set), id: \.id) {
                Label($0.name, systemImage: $0.sfSymbol).tag($0.id)
            }
        }
        .pickerStyle(.navigationLink)
        MarkerColorSwatches(selection: Binding(
            get: { marker.colorHex },
            set: { draft.kind = .marker(.init(set: marker.set,
                                               symbolID: marker.symbolID,
                                               colorHex: $0)) }
        ))
    }

    private var taskColourSection: some View {
        Section(L10n.text("Task Colour")) {
            HStack(spacing: 8) {
                ForEach(TaskColor.allCases, id: \.self) { colour in
                    Button {
                        draft.taskColor = colour
                    } label: {
                        Circle()
                            .fill(colour.color)
                            .frame(width: 32, height: 32)
                            .overlay(Circle().stroke(.primary,
                                                     lineWidth: draft.taskColor == colour ? 3 : 0))
                            .frame(minWidth: 44, minHeight: 44)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(colour.label)
                    .accessibilityValue(draft.taskColor == colour ? L10n.text("Selected") : L10n.text("Not selected"))
                    .accessibilityAddTraits(draft.taskColor == colour ? .isSelected : [])
                }
            }
        }
    }

    private var mgrsLocationSection: some View {
        Section {
            TextField(L10n.text("1234 or 56HLH 1234 5678"), text: $mgrsInput)
                .keyboardType(.asciiCapable)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .font(.body.monospaced())
                .frame(minHeight: SymbolEditorAccessibility.minimumTargetPoints)
                .onChange(of: mgrsInput) { _ in mgrsError = nil }
                .onSubmit { _ = applyMGRSMove() }

            Button {
                _ = applyMGRSMove()
            } label: {
                Label(L10n.text("Set MGRS Location"), systemImage: "scope")
                    .frame(maxWidth: .infinity,
                           minHeight: SymbolEditorAccessibility.minimumTargetPoints,
                           alignment: .leading)
            }

            if draft.hasStagedMove(from: waypoint) {
                Text(L10n.text("Will move to %1$@ when saved.", MGRSFormatter.string(from: draft.stagedCoordinate)))
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
            }
            if let mgrsError {
                Text(mgrsError)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        } header: {
            Text(L10n.text("Location (MGRS)"))
        } footer: {
            Text(L10n.text("Enter a full MGRS reference or a local 4, 6, 8, or 10-figure grid. TacMap uses this symbol's current grid square for numeric shorthand and centres the selected square offline."))
                .font(.caption2)
        }
    }

    private func controlValueSection(title: String,
                                     value: Binding<Double>,
                                     range: ClosedRange<Double>,
                                     step: Double,
                                     valueText: String,
                                     accessibilityLabel: String,
                                     resetLabel: String,
                                     reset: @escaping () -> Void) -> some View {
        Section(title) {
            VStack(alignment: .leading) {
                HStack {
                    Text(accessibilityLabel)
                    Spacer()
                    Text(valueText).monospacedDigit().foregroundStyle(.secondary)
                    Button(action: reset) {
                        Label(L10n.text("Reset"), systemImage: "arrow.counterclockwise")
                            .frame(minWidth: SymbolEditorAccessibility.minimumTargetPoints,
                                   minHeight: SymbolEditorAccessibility.minimumTargetPoints)
                    }
                    .buttonStyle(.bordered)
                    .accessibilityLabel(resetLabel)
                }
                Slider(value: value, in: range, step: step)
                    .accessibilityLabel(accessibilityLabel)
                    .accessibilityValue(valueText)
                    .frame(minHeight: 44)
            }
        }
    }

    private func selectCategory(_ newCategory: KindCategory) {
        category = newCategory
        switch newCategory {
        case .generic:
            draft.kind = .generic
        case .military:
            draft.kind = .military(.init(affiliation: .friend,
                                          echelon: .platoon,
                                          function: .infantry,
                                          isHeadquarters: false))
        case .controlMeasure:
            draft.kind = .controlMeasure(.assemblyArea)
        case .marker:
            let first = MarkerCatalog.entries(for: .airsoft)[0]
            draft.kind = .marker(.init(set: .airsoft,
                                       symbolID: first.id,
                                       colorHex: first.defaultColorHex))
        }
    }

    private func save() {
        do {
            let entered = normalizedMGRSInput(mgrsInput)
            let initiallyShown = normalizedMGRSInput(initialMGRSInput)
            if entered == initiallyShown && !mgrsMoveExplicitlyApplied {
                draft.discardStagedMove()
            } else if entered != normalizedMGRSInput(lastAppliedMGRSInput),
                      !applyMGRSMove() {
                return
            }
            guard let current = waypointStore.waypoints.first(where: { $0.id == waypoint.id }) else {
                throw WaypointMutationError.missing
            }
            let updated = try draft.applying(
                to: current,
                availableLayerIDs: Set(drawingStore.layers.map(\.id)),
                fallbackLayerID: fallbackLayerID
            )
            _ = try waypointStore.commitEdit(updated)
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    @discardableResult
    private func applyMGRSMove() -> Bool {
        do {
            let resolved = try MGRSFormatter.resolveGridReference(
                mgrsInput, relativeTo: draft.stagedCoordinate)
            draft.stageMove(to: resolved.coordinate)
            mgrsInput = resolved.formattedReference
            lastAppliedMGRSInput = resolved.formattedReference
            mgrsMoveExplicitlyApplied = true
            mgrsError = nil
            return true
        } catch {
            mgrsError = error.localizedDescription
            return false
        }
    }

    private func normalizedMGRSInput(_ value: String) -> String {
        value.uppercased().filter { !$0.isWhitespace }
    }

    private func deleteSymbol() {
        do {
            _ = try waypointStore.deleteDurably(waypoint)
            dismiss()
            onDeleted()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private var fallbackLayerID: UUID {
        drawingStore.layers.first(where: { $0.id == DrawingLayer.legacyFallbackID })?.id
            ?? drawingStore.layers.first?.id
            ?? DrawingLayer.legacyFallbackID
    }

    private static func category(for kind: WaypointKind) -> KindCategory {
        switch kind {
        case .generic: return .generic
        case .military: return .military
        case .controlMeasure: return .controlMeasure
        case .marker: return .marker
        }
    }
}

/// Top-level category in the edit sheet picker.
private enum KindCategory: String, CaseIterable, Hashable {
    case generic, military, controlMeasure, marker

    var displayName: String {
        switch self {
        case .generic:        return L10n.text("Point")
        case .military:       return L10n.text("Military")
        case .controlMeasure: return L10n.text("Tasks")
        case .marker:         return L10n.text("Markers")
        }
    }
}

/// A stable replacement for long `Picker(.menu/.navigationLink)` controls.
/// SwiftUI's internal picker destination is recreated when an observed parent
/// publishes, which resets its List scroll offset on iOS. This link and its
/// immutable option IDs remain the same across those redraws.
private struct StableNavigationSelection<Option: Hashable>: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    let title: String
    let stableID: String
    let options: [Option]
    @Binding var selection: Option
    let optionTitle: (Option) -> String

    var body: some View {
        NavigationLink {
            StableOptionList(
                title: title,
                options: options,
                selection: $selection,
                optionTitle: optionTitle
            )
            .id(stableID)
        } label: {
            HStack {
                Text(title)
                Spacer()
                Text(optionTitle(selection))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .id(stableID)
    }
}

private struct StableOptionList<Option: Hashable>: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    let title: String
    let options: [Option]
    @Binding var selection: Option
    let optionTitle: (Option) -> String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            ForEach(options, id: \.self) { option in
                Button {
                    selection = option
                    dismiss()
                } label: {
                    HStack {
                        Text(optionTitle(option))
                            .foregroundStyle(.primary)
                        Spacer()
                        if option == selection {
                            Image(systemName: "checkmark")
                                .font(.body.weight(.semibold))
                                .foregroundStyle(.tint)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .id(option)
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct UnitAmplifierFields: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @Binding var higherFormation: String
    @Binding var uniqueIdentifier: String
    @Binding var reinforcementStatus: ReinforcementStatus

    var body: some View {
        TextField(L10n.text("Higher formation (M)"), text: bounded(
            $higherFormation, maximumLength: UnitAmplifierText.higherFormationMaxLength))
            .autocorrectionDisabled()
            .textInputAutocapitalization(.characters)
            .frame(minHeight: SymbolEditorAccessibility.minimumTargetPoints)

        TextField(L10n.text("Unique identifier / callsign (T)"), text: bounded(
            $uniqueIdentifier, maximumLength: UnitAmplifierText.uniqueIdentifierMaxLength))
            .autocorrectionDisabled()
            .textInputAutocapitalization(.characters)
            .frame(minHeight: SymbolEditorAccessibility.minimumTargetPoints)

        Picker(L10n.text("Reinforcement (F)"), selection: $reinforcementStatus) {
            ForEach(ReinforcementStatus.allCases, id: \.self) { status in
                Text(status.displayName).tag(status)
            }
        }
        .pickerStyle(.menu)
        .frame(minHeight: SymbolEditorAccessibility.minimumTargetPoints)
    }

    private func bounded(_ binding: Binding<String>, maximumLength: Int) -> Binding<String> {
        Binding(
            get: { binding.wrappedValue },
            set: {
                binding.wrappedValue = String(
                    $0.unicodeScalars.prefix(maximumLength))
            }
        )
    }
}

/// Preset colour swatches for markers (the airsoft team colours plus a couple
/// of neutrals), with the current pick ringed.
private struct MarkerColorSwatches: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @Binding var selection: String

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(SymbolEditorAccessibility.markerSwatches, id: \.hex) { swatch in
                    let isSelected = selection.caseInsensitiveCompare(swatch.hex) == .orderedSame
                    Button {
                        selection = swatch.hex
                    } label: {
                        Circle()
                            .fill(Color(UIColor(hex: swatch.hex)))
                            .frame(width: 32, height: 32)
                            .overlay(Circle().stroke(Color.primary,
                                                     lineWidth: isSelected ? 3 : 0))
                            .overlay(Circle().stroke(Color.white.opacity(0.4), lineWidth: 1))
                            .frame(width: SymbolEditorAccessibility.minimumTargetPoints,
                                   height: SymbolEditorAccessibility.minimumTargetPoints)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(SymbolEditorAccessibility.markerLabel(for: swatch.hex))
                    .accessibilityValue(isSelected ? L10n.text("Selected") : L10n.text("Not selected"))
                    .accessibilityAddTraits(isSelected ? .isSelected : [])
                }
            }
            .padding(.horizontal, 16)
        }
        .frame(minHeight: SymbolEditorAccessibility.minimumTargetPoints)
        .padding(.vertical, 4)
    }
}

/// Drop-in replacement for Picker(.segmented) that works with fast taps
/// and mouse clicks. Apple's segmented picker on iOS 26 has a regression
/// where it ignores touches shorter than ~200ms, which is a dealbreaker
/// for simulator testing and flaky on device too. Plain SwiftUI Buttons
/// don't have that issue.
private struct KindCategorySegmentedPicker: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @Binding var selection: KindCategory

    var body: some View {
        HStack(spacing: 4) {
            ForEach(KindCategory.allCases, id: \.self) { kind in
                Button {
                    selection = kind
                } label: {
                    Text(kind.displayName)
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity,
                               minHeight: SymbolEditorAccessibility.minimumTargetPoints)
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
                .accessibilityLabel(L10n.text("%1$@ symbol kind", kind.displayName))
                .accessibilityValue(selection == kind ? L10n.text("Selected") : L10n.text("Not selected"))
                .accessibilityAddTraits(selection == kind ? .isSelected : [])
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
