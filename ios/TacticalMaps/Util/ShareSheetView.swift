import SwiftUI
import UIKit

/// Thin SwiftUI wrapper around `UIActivityViewController` for presenting the
/// system share sheet. Used by the "Export All Data" flow to hand off a file URL.
struct ShareSheetView: UIViewControllerRepresentable {
    let activityItems: [Any]
    var applicationActivities: [UIActivity]? = nil

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems,
                                 applicationActivities: applicationActivities)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
