import SwiftUI

/// Layers -> Imported maps (s10): every imported map stays until deleted (D5-03),
/// switch with a tap, Calibrate / Choose page / Generate tiles / own georef /
/// Delete from the row menu. Sizes come from the library, no hashing here (D5-08).
struct ImportedMapsSection: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @ObservedObject var mapVM: MapViewModel
    /// WP2 bake: a running one shows progress + cancel on its row
    @ObservedObject var bake: PDFBakeController
    var onCalibrate: (UUID) -> Void
    var onChoosePage: (ImportedMapEntry) -> Void
    var onGenerateTiles: (ImportedMapEntry) -> Void

    @State private var confirmDelete: ImportedMapEntry?
    @State private var confirmEmbedded: ImportedMapEntry?

    private var entries: [ImportedMapEntry] { mapVM.library?.entries ?? [] }

    /// every entry's file plus its bake (OD-F14)
    static func footerBytes(_ entries: [ImportedMapEntry]) -> Int64 {
        entries.reduce(0) { $0 + max(0, $1.byteCount) + ($1.pdf?.validBake?.bytes ?? 0) }
    }

    /// parents first, each with its derived tiles right under it
    private var ordered: [(entry: ImportedMapEntry, indent: Bool)] {
        let all = entries
        let ids = Set(all.map(\.id))
        var out: [(ImportedMapEntry, Bool)] = []
        for e in all where e.derivedFromId == nil || !ids.contains(e.derivedFromId!) {
            out.append((e, false))
            for d in all where d.derivedFromId == e.id { out.append((d, true)) }
        }
        return out
    }

    /// the estimate / bake is about this entry (crash guard token, same as isFor)
    private func bakeIsFor(_ e: ImportedMapEntry) -> Bool {
        e.kind == .pdf && bake.subjectToken == e.renderGuardToken
    }

    private func draftPoints(_ e: ImportedMapEntry) -> Int? {
        _ = mapVM.draftsEpoch
        guard e.kind == .pdf, let key = e.contentKey, let page = e.pdf?.pageIndex else { return nil }
        return mapVM.drafts.summaries()[CalibrationDraft.key(contentKey: key, pageIndex: page)]
    }

    private func presentation(_ e: ImportedMapEntry) -> ImportedMapPresentation {
        let parent = e.derivedFromId.flatMap { mapVM.entry($0)?.displayName }
        return ImportedMapStates.present(e, file: mapVM.fileStatus(e), draftPoints: draftPoints(e), parentName: parent)
    }

    var body: some View {
        Section {
            switch mapVM.libraryStatus {
            case .locked, .corrupt:
                Label(Messages.mapLibraryLocked(), systemImage: "lock.fill")
                    .foregroundStyle(.secondary)
            default:
                if entries.isEmpty {
                    Text(Messages.mapLibraryEmpty())
                        .font(.callout)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(ordered, id: \.entry.id) { item in
                        row(item.entry, indent: item.indent)
                    }
                }
            }
        } header: {
            Text(Messages.mapLibrarySection())
        } footer: {
            if !entries.isEmpty {
                // OD-F14: what's really on the device, bake files included
                Text(Messages.mapLibraryFooter(Messages.importedMapCount(entries.count),
                                               MapImportError.bytes(Self.footerBytes(entries))))
                    .font(.caption2)
            }
        }
        .alert(Messages.mapDeleteTitle(confirmDelete?.displayName ?? ""),
               isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } }),
               presenting: confirmDelete) { e in
            Button(L10n.text("Delete"), role: .destructive) { mapVM.deleteLibraryEntry(e.id) }
                .accessibilityIdentifier("maps.delete.confirm")
            Button(L10n.text("Cancel"), role: .cancel) {}
        } message: { _ in
            Text(Messages.mapDeleteMessage())
        }
        .alert(Messages.mapActionUseEmbedded(),
               isPresented: Binding(get: { confirmEmbedded != nil }, set: { if !$0 { confirmEmbedded = nil } }),
               presenting: confirmEmbedded) { e in
            Button(Messages.mapActionUseEmbedded(), role: .destructive) { mapVM.revertToEmbedded(e.id) }
            Button(L10n.text("Cancel"), role: .cancel) {}
        } message: { _ in
            Text(Messages.mapUseEmbeddedConfirm())
        }
    }

    @ViewBuilder
    private func row(_ e: ImportedMapEntry, indent: Bool) -> some View {
        let p = presentation(e)
        let active = mapVM.activeEntryID == e.id
        HStack(spacing: 10) {
            Button {
                switch p.rowTap {
                case .activate: mapVM.activateLibraryEntry(e.id)
                case .calibrate: onCalibrate(e.id)
                case .none: break
                }
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: active ? "largecircle.fill.circle" : "circle")
                        .foregroundStyle(active ? Color.accentColor : .secondary)
                        .accessibilityHidden(true)
                    Image(systemName: e.kind == .mbtiles ? "square.stack.3d.up" : "doc.viewfinder")
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(e.displayName)
                            .font(.callout)
                            .foregroundStyle(.primary)
                            .lineLimit(2)
                        Text(p.subtitle.text)
                            .font(.caption2)
                            .foregroundStyle(p.state == .unavailable || p.state == .rejected || p.state == .needsCalibration
                                             ? Color.orange : Color.secondary)
                            .lineLimit(2)
                        if bakeIsFor(e), bake.isRunning {
                            ProgressView(value: bake.progressFraction)
                        }
                    }
                    Spacer(minLength: 4)
                    Text(MapImportError.bytes(e.byteCount))
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("maps.row.\(e.displayName)")
            .accessibilityAddTraits(active ? .isSelected : [])

            Menu {
                ForEach(p.menu, id: \.rawValue) { action in
                    menuItem(action, e)
                }
            } label: {
                Image(systemName: "ellipsis.circle")
                    .font(.title3)
                    .frame(width: 48, height: 48)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel(e.displayName)
            .accessibilityIdentifier("maps.menu.\(e.displayName)")
        }
        .padding(.leading, indent ? 22 : 0)
    }

    @ViewBuilder
    private func menuItem(_ action: ImportedMapMenuAction, _ e: ImportedMapEntry) -> some View {
        switch action {
        case .calibrate:
            Button { onCalibrate(e.id) } label: { Label(Messages.mapActionCalibrate(), systemImage: "scope") }
        case .choosePage:
            Button { onChoosePage(e) } label: { Label(Messages.mapActionChoosePage(), systemImage: "doc.on.doc") }
        case .generateOfflineTiles:
            if bakeIsFor(e), bake.isRunning {
                Button(role: .cancel) { bake.cancel() } label: { Label(L10n.text("Cancel"), systemImage: "xmark.circle") }
            } else if e.pdf?.bake == nil {
                // J1: once a map has its tiles it's Remove in the section above, not Generate
                Button { onGenerateTiles(e) } label: {
                    Label(Messages.pdfBakeGenerateButton(), systemImage: "square.stack.3d.down.right")
                }
                // OD-F9: not while the map on screen is failed, Try Again first
                .disabled(bake.isRunning || bake.state == .estimating
                          || (mapVM.activeEntryID == e.id && mapVM.pdfRuntime.status.failure != nil))
            }
        case .useEmbedded:
            Button { confirmEmbedded = e } label: { Label(Messages.mapActionUseEmbedded(), systemImage: "arrow.uturn.backward") }
        case .delete:
            Button(role: .destructive) { confirmDelete = e } label: { Label(Messages.mapActionDelete(), systemImage: "trash") }
        }
    }
}
