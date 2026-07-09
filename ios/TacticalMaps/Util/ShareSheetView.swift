import SwiftUI
import UIKit

/// SwiftUI wrapper for `UIActivityViewController`. Just bridges the system share
/// sheet so "Export All Data" can hand off a file URL.
struct ShareSheetView: UIViewControllerRepresentable {
    let activityItems: [Any]
    var applicationActivities: [UIActivity]? = nil

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems,
                                 applicationActivities: applicationActivities)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
