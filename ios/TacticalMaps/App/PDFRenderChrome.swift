import SwiftUI

extension PDFRenderFailure: LocalizedMessageError {
    /// catalogue key of the reason line, the import probe error reports it too
    var messageKey: String {
        switch self {
        case .cannotOpen: return "pdf_render_reason_cannot_open"
        case .passwordProtected: return "pdf_render_reason_password_protected"
        case .pageMissing: return "pdf_render_reason_page_missing"
        case .pageGeometry: return "pdf_render_reason_page_geometry"
        case .blank: return "pdf_render_reason_blank"
        case .outOfMemory: return "pdf_render_reason_out_of_memory"
        case .renderError: return "pdf_render_reason_render_error"
        }
    }

    /// shared reason copy, resolved at display time so a language switch follows
    var localizedMessage: LocalizedMessage {
        switch self {
        case .cannotOpen: return .init(id: "id.pdf_render_reason_cannot_open", fallback: Messages.pdfRenderReasonCannotOpen(), arguments: [])
        case .passwordProtected: return .init(id: "id.pdf_render_reason_password_protected", fallback: Messages.pdfRenderReasonPasswordProtected(), arguments: [])
        case .pageMissing: return .init(id: "id.pdf_render_reason_page_missing", fallback: Messages.pdfRenderReasonPageMissing(), arguments: [])
        case .pageGeometry: return .init(id: "id.pdf_render_reason_page_geometry", fallback: Messages.pdfRenderReasonPageGeometry(), arguments: [])
        case .blank: return .init(id: "id.pdf_render_reason_blank", fallback: Messages.pdfRenderReasonBlank(), arguments: [])
        case .outOfMemory: return .init(id: "id.pdf_render_reason_out_of_memory", fallback: Messages.pdfRenderReasonOutOfMemory(), arguments: [])
        case .renderError: return .init(id: "id.pdf_render_reason_render_error", fallback: Messages.pdfRenderReasonRenderError(), arguments: [])
        }
    }
}

extension PDFBakeError {
    var text: String {
        switch self {
        case .notCalibrated: return Messages.pdfBakeDisabledCaption()
        case .tooLarge: return Messages.pdfBakeErrorTooLarge()
        case .noSpace(let need): return Messages.pdfBakeErrorNoSpace(PDFBakeFormat.size(need))
        case .writeFailed: return Messages.pdfBakeErrorWriteFailed()
        case .renderFailed: return Messages.pdfBakeErrorRenderFailed()
        case .sourceChanged: return Messages.pdfBakeErrorSourceChanged()
        case .interrupted: return Messages.pdfBakeInterruptedMessage()
        }
    }
}

/// OD-F7: the one pure formatter for bake counts and sizes, used by the option
/// rows, the Layers info row and the noSpace message. Same integer rules as
/// Android, pinned by fixture bakeFormat: MB = 10^6 bytes, half up, one
/// decimal under 100 MB, whole MB from there, GB with one decimal from 1000 MB
enum PDFBakeFormat {
    struct Separators: Equatable {
        let grouping: String
        let decimal: String
    }

    static let en = Separators(grouping: ",", decimal: ".")
    static let de = Separators(grouping: ".", decimal: ",")

    static func separators(languageCode: String?) -> Separators {
        languageCode?.lowercased().hasPrefix("de") == true ? de : en
    }

    /// whatever language the UI is showing right now
    static var current: Separators {
        switch AppLanguage.shared.selection {
        case .en: return en
        case .de: return de
        case .system: return separators(languageCode: Bundle.main.preferredLocalizations.first)
        }
    }

    static func grouped(_ n: Int64, _ s: Separators) -> String {
        let digits = Array(String(n.magnitude))
        var out = ""
        for (i, d) in digits.enumerated() {
            if i > 0, (digits.count - i) % 3 == 0 { out += s.grouping }
            out.append(d)
        }
        return n < 0 ? "-" + out : out
    }

    static func tiles(_ n: Int, _ s: Separators = current) -> String {
        grouped(Int64(n), s)
    }

    static func size(_ bytes: Int64, _ s: Separators = current) -> String {
        let b = max(0, bytes)
        var tenthsMB = (b + 50_000) / 100_000
        if b > 0 { tenthsMB = max(1, tenthsMB) }
        if tenthsMB < 1000 {
            return grouped(tenthsMB / 10, s) + s.decimal + String(tenthsMB % 10) + " MB"
        }
        let wholeMB = (b + 500_000) / 1_000_000
        if wholeMB < 1000 {
            return grouped(wholeMB, s) + " MB"
        }
        let tenthsGB = (b + 50_000_000) / 100_000_000
        return grouped(tenthsGB / 10, s) + s.decimal + String(tenthsGB % 10) + " GB"
    }

    /// "N min", the estimator already rounded up and floored it at 1
    static func minutes(_ minutes: Int) -> String {
        Messages.pdfBakeMinutes(String(max(1, minutes)))
    }

    static func optionLabel(_ o: PDFBakeOptionEstimate) -> String {
        let row = Messages.pdfBakeOptionRow(String(o.maxZoom), tiles(o.tiles), size(o.bytes))
        return o.enoughSpace ? row : row + Messages.pdfBakeOptionNoSpace()
    }

    static func info(_ record: PDFBakeRecord) -> String {
        Messages.pdfBakeInfo(String(record.minZoom), String(record.maxZoom), size(record.bytes))
    }
}

/// When the G1 alert goes up and when it comes down. Pure so a test can drive it.
/// Once per source + reason. OD2-R2-2: anything that isnt a failure (a new
/// source preparing, a recovery, no PDF at all) takes a stale alert down, the
/// key is the runtime's source key so a fresh source can alert again
enum PDFFailureAlertGate {
    enum Change: Equatable {
        case keep
        case show(PDFRenderFailure)
        case dismiss
    }

    struct Next: Equatable {
        let change: Change
        let shownFor: String?

        func alert(current: PDFRenderFailure?) -> PDFRenderFailure? {
            switch change {
            case .keep: return current
            case .show(let f): return f
            case .dismiss: return nil
            }
        }
    }

    static func next(status: PDFRenderStatus, sourceKey: String?, hasPDF: Bool, shownFor: String?) -> Next {
        guard let f = status.failure, hasPDF else { return Next(change: .dismiss, shownFor: shownFor) }
        let key = (sourceKey ?? "") + "#" + f.rawValue
        guard shownFor != key else { return Next(change: .keep, shownFor: shownFor) }
        return Next(change: .show(f), shownFor: key)
    }
}

/// Alerts, notices and the bake chip for the imported PDF, hung off the
/// main map so ContentView doesnt blow the type checker budget.
struct PDFRenderChrome: ViewModifier {
    @ObservedObject var mapVM: MapViewModel
    @ObservedObject var runtime: PDFMapRuntime
    @ObservedObject var bake: PDFBakeController
    @ObservedObject var visibility: LayerVisibility
    @ObservedObject var calibration: CalibrationSession
    /// Layers shows its own bake alert while its up
    var layersSheetShowing: Bool

    @State private var failureShownFor: String?
    @State private var failureAlert: PDFRenderFailure?
    @State private var confirmingSuspectDelete = false

    private var activePDF: PDFMapSource? { mapVM.mapSource as? PDFMapSource }

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .top) { notices }
            .onChange(of: runtime.status) { status in
                let next = PDFFailureAlertGate.next(status: status, sourceKey: runtime.activeSourceKey,
                                                    hasPDF: activePDF != nil, shownFor: failureShownFor)
                failureShownFor = next.shownFor
                failureAlert = next.alert(current: failureAlert)
            }
            .onChange(of: bake.finishedMessage) { done in
                guard done else { return }
                DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { bake.finishedMessage = false }
            }
            .alert(Messages.pdfRenderFailedTitle(activePDF?.displayName ?? ""),
                   isPresented: Binding(get: { failureAlert != nil }, set: { if !$0 { failureAlert = nil } }),
                   presenting: failureAlert) { _ in
                Button(Messages.pdfRenderTryAgain()) {
                    failureShownFor = nil
                    runtime.retry()
                }
                Button(Messages.pdfRenderUseOnlineMap()) {
                    // H1: the user's own online style, not the built in default.
                    // a real selection like Android's restoreOnlineBasemap
                    _ = mapVM.useOnlineMapAfterFailure()
                }
                Button(L10n.text("Not Now"), role: .cancel) {}
            } message: { f in
                Text(f.localizedMessage.text)
            }
            // PDF or MBTiles suspect (3.0.2 M1), same alert and copy
            .alert(Messages.pdfGuardCrashTitle(mapVM.crashSuspectDisplayName),
                   isPresented: Binding(get: { mapVM.crashSuspectAlertShowing && !confirmingSuspectDelete },
                                        set: { _ in })) {
                Button(Messages.pdfGuardOpenAnyway()) { mapVM.openCrashSuspectAnyway() }
                Button(Messages.pdfGuardDeleteMap(), role: .destructive) { confirmingSuspectDelete = true }
                Button(L10n.text("Not Now"), role: .cancel) { mapVM.dismissCrashSuspect() }
            } message: {
                Text(Messages.pdfGuardCrashMessage())
            }
            // the library's own delete confirmation (s8.2), same as the Layers row
            .alert(Messages.mapDeleteTitle(mapVM.crashSuspectDisplayName), isPresented: $confirmingSuspectDelete) {
                Button(L10n.text("Delete"), role: .destructive) {
                    _ = mapVM.deleteCrashSuspect()
                }
                Button(L10n.text("Cancel"), role: .cancel) {}
            } message: {
                Text(Messages.mapDeleteMessage())
            }
            .alert(launchNoticeTitle, isPresented: Binding(get: { mapVM.pdfLaunchNotice != nil && !mapVM.crashSuspectAlertShowing },
                                                           set: { if !$0 { mapVM.dismissLaunchNotice() } })) {
                Button(Messages.acknowledge(), role: .cancel) { mapVM.dismissLaunchNotice() }
            } message: {
                Text(launchNoticeMessage)
            }
            .alert(Messages.pdfBakeErrorTitle(),
                   isPresented: Binding(get: { bakeError != nil && !layersSheetShowing },
                                        set: { if !$0 { bake.dismiss() } })) {
                Button(Messages.acknowledge(), role: .cancel) { bake.dismiss() }
            } message: {
                Text(bakeError?.text ?? "")
            }
    }

    private var bakeError: PDFBakeError? {
        if case .failed(let e) = bake.state { return e }
        return nil
    }

    private var launchNoticeTitle: String {
        switch mapVM.pdfLaunchNotice {
        case .importInterrupted?: return Messages.pdfGuardImportInterruptedTitle()
        case .bakeInterrupted?: return Messages.pdfBakeInterruptedTitle()
        case nil: return ""
        }
    }

    private var launchNoticeMessage: String {
        switch mapVM.pdfLaunchNotice {
        case .importInterrupted?: return Messages.pdfGuardImportInterruptedMessage()
        case .bakeInterrupted?: return Messages.pdfBakeInterruptedMessage()
        case nil: return ""
        }
    }

    @ViewBuilder
    private var notices: some View {
        VStack(spacing: 8) {
            if activePDF != nil, !visibility.importedMapVisible, !calibration.isCalibrating {
                capsule(Messages.importedMapHiddenNotice())
                    .allowsHitTesting(false)
            }
            if case .running = bake.state {
                HStack(spacing: 10) {
                    ProgressView(value: bake.progressFraction)
                        .progressViewStyle(.circular)
                        .tint(.white)
                        .scaleEffect(0.7)
                    Text(Messages.pdfBakeChip(String(Int((bake.progressFraction * 100).rounded(.down)))))
                        .font(.caption.weight(.semibold).monospacedDigit())
                        .foregroundStyle(.white)
                    Button(L10n.text("Cancel")) { bake.cancel() }
                        .font(.caption.weight(.bold))
                        .foregroundStyle(PDFRenderStatusColors.preparing)
                }
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(.black.opacity(0.82), in: Capsule())
            } else if bake.finishedMessage {
                capsule(Messages.pdfBakeDone())
                    .allowsHitTesting(false)
            }
        }
        .padding(.top, 118)
    }

    private func capsule(_ text: String) -> some View {
        Text(text)
            .font(.caption.weight(.semibold))
            .foregroundStyle(.white)
            .padding(.horizontal, 12).padding(.vertical, 7)
            .background(.black.opacity(0.82), in: Capsule())
    }
}
