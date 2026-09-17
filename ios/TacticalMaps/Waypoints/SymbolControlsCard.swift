import SwiftUI

/// Compact entry point for the transactional selected-symbol editor. Editing
/// controls live in a scroll-safe sheet so draft gestures never publish model
/// changes or trigger persistence writes.
struct SymbolControlsCard: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @ObservedObject var waypointStore: WaypointStore
    @ObservedObject var drawingStore: DrawingStore
    @ObservedObject var mapVM: MapViewModel
    let waypointID: UUID
    let onDismiss: () -> Void

    @State private var showingEdit = false
    @State private var mutationError: String?

    var body: some View {
        if let waypoint = waypointStore.waypoints.first(where: { $0.id == waypointID }) {
            VStack(spacing: 8) {
                header(for: waypoint)
                HStack(spacing: 8) {
                    Button {
                        showingEdit = true
                    } label: {
                        Label(L10n.text("Edit Symbol"), systemImage: "slider.horizontal.3")
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.borderedProminent)
                    .accessibilityHint(L10n.text("Opens all symbol fields and actions."))

                    Button {
                        moveToCrosshair(waypoint)
                    } label: {
                        Label(L10n.text("Move to Crosshair"), systemImage: "scope")
                            .lineLimit(1)
                            .minimumScaleFactor(0.72)
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.bordered)
                    .accessibilityLabel(L10n.text("Move symbol to crosshair"))
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(.regularMaterial,
                        in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(.white.opacity(0.15), lineWidth: 0.5)
            )
            .shadow(color: .black.opacity(0.25), radius: 10, y: 4)
            .sheet(isPresented: $showingEdit) {
                if let current = waypointStore.waypoints.first(where: { $0.id == waypointID }) {
                    SelectedSymbolEditSheet(
                        waypointStore: waypointStore,
                        drawingStore: drawingStore,
                        waypoint: current,
                        onDeleted: onDismiss
                    )
                }
            }
            .alert(L10n.text("Symbol Not Moved"),
                   isPresented: Binding(get: { mutationError != nil },
                                        set: { if !$0 { mutationError = nil } })) {
                Button(L10n.text("OK"), role: .cancel) { mutationError = nil }
            } message: {
                Text(mutationError ?? L10n.text("The symbol is still at its previous position."))
            }
        }
    }

    private func moveToCrosshair(_ waypoint: Waypoint) {
        var updated = waypoint
        updated.latitude = mapVM.cameraCentre.latitude
        updated.longitude = mapVM.cameraCentre.longitude
        do {
            _ = try waypointStore.commitEdit(updated, actionName: L10n.text("Move Symbol to Crosshair"))
        } catch {
            mutationError = L10n.text("%1$@ The symbol is still at its previous position.", error.localizedDescription)
        }
    }

    private func header(for waypoint: Waypoint) -> some View {
        let kindLabel = waypoint.kind.displayName
        let showsKind = waypoint.name != kindLabel
        let isUnit = waypoint.kind.militarySpec != nil
        let tile: CGFloat = isUnit ? 44 : 28
        let inner: CGFloat = isUnit ? 38 : 22
        return HStack(spacing: 10) {
            WaypointKindIcon(
                kind: waypoint.kind,
                size: inner,
                rotation: waypoint.kind.controlMeasure == nil ? 0 : waypoint.rotation,
                taskColor: waypoint.taskColor
            )
            .frame(width: tile, height: tile)
            .background(
                RoundedRectangle(cornerRadius: 6, style: .continuous).fill(.white)
            )

            Button {
                showingEdit = true
            } label: {
                HStack(spacing: 6) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(waypoint.name)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                        if showsKind {
                            Text(kindLabel)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                    Image(systemName: "pencil")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L10n.text("Edit %1$@", waypoint.name))
            .accessibilityHint(L10n.text("Opens all symbol fields and actions."))

            Spacer(minLength: 4)
            Button(action: onDismiss) {
                Image(systemName: "xmark.circle.fill")
                    .font(.body)
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .frame(width: 44, height: 44)
            .accessibilityLabel(L10n.text("Close symbol editor"))
        }
    }
}
