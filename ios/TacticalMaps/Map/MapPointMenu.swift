import SwiftUI
import CoreLocation
import UIKit
import UniformTypeIdentifiers

/// A long-pressed point on the map and its coordinate in the user's chosen
/// readout format. Drives the point menu (place, measure, rings, sun and
/// moon, copy).
struct MapPressPoint: Identifiable {
    let id = UUID()
    let coordinate: CLLocationCoordinate2D
    let coordinateText: CoordinateDisplayFormat.Resolved

    init(coordinate: CLLocationCoordinate2D, format: CoordinateDisplayFormat) {
        self.coordinate = coordinate
        self.coordinateText = format.resolve(
            mgrs: MGRSFormatter.string(from: coordinate),
            wgs84: MapViewModel.wgs84Text(for: coordinate),
            utm: MGRSFormatter.utm(from: coordinate)
        )
    }

    /// Copies the coordinate for two minutes, on this device only.
    func copyToPasteboard() {
        UIPasteboard.general.setItems(
            [[UTType.plainText.identifier: coordinateText.text]],
            options: [
                .expirationDate: Date().addingTimeInterval(120),
                .localOnly: true
            ]
        )
    }
}

/// Offline sun and moon times for a long-pressed point.
struct PointSunMoonSheet: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    let point: MapPressPoint
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(point.coordinateText.text)
                        .font(.subheadline.monospaced())
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                    SunMoonSection(coordinate: point.coordinate)
                }
                .padding()
            }
            .navigationTitle(Messages.sunMoonTitle())
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.text("Done")) { dismiss() }
                }
            }
        }
    }
}

/// The long-press point menu and the sheets it opens. Actions that create
/// mission objects are hidden while graphics are locked. The menu is drawn in
/// the app's own view tree rather than as a system action sheet: iOS draws
/// those outside the app's views, where night mode cannot turn them red.
struct MapPointMenuModifier: ViewModifier {
    @Binding var point: MapPressPoint?
    @ObservedObject var drawingStore: DrawingStore
    let canEdit: Bool
    let onPlaceSymbol: (CLLocationCoordinate2D) -> Void
    let onMeasure: (CLLocationCoordinate2D) -> Void
    let onCopied: (CoordinateDisplayFormat) -> Void
    /// free viewer: place / measure / rings still show in the menu (so the
    /// user learns they exist) but hand their title to onLocked instead of
    /// running. sun & moon and copy stay free
    var isFullVersion: Bool = true
    var onLocked: (String) -> Void = { _ in }

    @State private var sunMoonPoint: MapPressPoint?
    @State private var ringCentre: Waypoint?

    func body(content: Content) -> some View {
        content
            .overlay {
                ZStack(alignment: .bottom) {
                    if let pressed = point {
                        Color.black.opacity(0.4)
                            .ignoresSafeArea()
                            .onTapGesture { point = nil }
                            .accessibilityHidden(true)
                            .transition(.opacity)
                        MapPointMenuCard(point: pressed, actions: actions(for: pressed)) { point = nil }
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }
                .animation(.easeOut(duration: 0.2), value: point?.id)
            }
            .nightSheet(item: $sunMoonPoint) { pressed in
                PointSunMoonSheet(point: pressed)
                    .presentationDetents([.medium, .large])
                    .nightDragIndicator()
            }
            .nightSheet(item: $ringCentre) { centre in
                RangeRingsSheet(drawingStore: drawingStore, waypoint: centre, anchored: false)
            }
    }

    /// runs `run` in the full version, otherwise the paywall with this title
    private func gated(_ title: String, _ run: @escaping () -> Void) -> () -> Void {
        isFullVersion ? run : { onLocked(title) }
    }

    private func actions(for pressed: MapPressPoint) -> [MapPointMenuCard.Action] {
        var actions: [MapPointMenuCard.Action] = []
        if canEdit {
            let title = Messages.mapPointPlaceSymbol()
            actions.append(.init(title: title, systemImage: "mappin.and.ellipse", locked: !isFullVersion,
                                 run: gated(title) { onPlaceSymbol(pressed.coordinate) }))
        }
        let measure = Messages.mapPointMeasure()
        actions.append(.init(title: measure, systemImage: "ruler", locked: !isFullVersion,
                             run: gated(measure) { onMeasure(pressed.coordinate) }))
        if canEdit {
            // rings are a drawing, so they ride the drawings lock
            let rings = Messages.mapPointRangeRings()
            actions.append(.init(title: rings, systemImage: "scope", locked: !isFullVersion,
                                 run: gated(rings) { ringCentre = centre(for: pressed) }))
        }
        actions.append(.init(title: Messages.mapPointSunMoon(), systemImage: "sunrise") {
            sunMoonPoint = pressed
        })
        actions.append(.init(title: Messages.mapPointCopy(), systemImage: "doc.on.doc") {
            pressed.copyToPasteboard()
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            onCopied(pressed.coordinateText.format)
        })
        return actions
    }

    /// A stand-in symbol that only supplies the rings' centre, name and layer.
    private func centre(for pressed: MapPressPoint) -> Waypoint {
        let layerID = drawingStore.activeLayerID
            ?? drawingStore.layers.first?.id
            ?? DrawingLayer.legacyFallbackID
        return Waypoint(name: pressed.coordinateText.text,
                        latitude: pressed.coordinate.latitude,
                        longitude: pressed.coordinate.longitude,
                        layerID: layerID)
    }
}

/// The point menu itself: the coordinate, one row per action, then Cancel,
/// in a card at the bottom of the screen like a system action sheet.
private struct MapPointMenuCard: View {
    struct Action: Identifiable {
        let title: String
        let systemImage: String
        /// viewer row, draws a padlock. the tap still runs `run` (which is
        /// the paywall in that case)
        var locked: Bool = false
        let run: () -> Void
        var id: String { title }
    }

    let point: MapPressPoint
    let actions: [Action]
    let dismiss: () -> Void

    var body: some View {
        // Scrolls only when the rows do not fit, e.g. landscape with large text.
        ViewThatFits(in: .vertical) {
            card
            ScrollView { card }
        }
        .frame(maxWidth: 440)
        .padding(.horizontal, 8)
        .padding(.bottom, 8)
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
        .accessibilityAction(.escape, dismiss)
        .accessibilityIdentifier("map.pointMenu")
    }

    private var card: some View {
        VStack(spacing: 8) {
            VStack(spacing: 0) {
                Text(point.coordinateText.text)
                    .font(.footnote.monospaced())
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                ForEach(actions) { action in
                    Divider()
                    Button {
                        dismiss()
                        action.run()
                    } label: {
                        HStack {
                            Label(action.title, systemImage: action.systemImage)
                            if action.locked {
                                Spacer()
                                Image(systemName: "lock.fill")
                                    .font(.footnote.weight(.semibold))
                                    .foregroundStyle(.secondary)
                                    .accessibilityHidden(true)
                            }
                        }
                        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                        .padding(.horizontal, 16)
                        .contentShape(Rectangle())
                    }
                    .accessibilityLabel(action.locked ? Messages.viewerLockedRowHint(action.title) : action.title)
                }
            }
            .background(Color(uiColor: .secondarySystemBackground),
                        in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            Button(action: dismiss) {
                Text(L10n.text("Cancel"))
                    .fontWeight(.semibold)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .contentShape(Rectangle())
            }
            .background(Color(uiColor: .secondarySystemBackground),
                        in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
    }
}
