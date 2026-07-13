import SwiftUI
import UIKit

/// SwiftUI wrapper for `UIActivityViewController`. Just bridges the system share
/// sheet so "Export All Data" can hand off a file URL.
struct ShareSheetView: UIViewControllerRepresentable {
    let activityItems: [Any]
    var applicationActivities: [UIActivity]? = nil

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: activityItems,
                                                  applicationActivities: applicationActivities)
        let urls = activityItems.compactMap { $0 as? URL }.filter(\.isFileURL)
        controller.completionWithItemsHandler = { _, _, _, _ in
            urls.forEach { ExportFileSecurity.remove($0) }
        }
        return controller
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
